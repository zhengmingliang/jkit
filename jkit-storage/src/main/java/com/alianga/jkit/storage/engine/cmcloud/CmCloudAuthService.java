package com.alianga.jkit.storage.engine.cmcloud;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BiConsumer;

/**
 * 移动云盘扫码授权服务（纯 Java，无子进程 / 无 Python / 无 UI 依赖）：驱动「取票 →
 * 出授权入口 → 轮询 → 拿到业务票据」的完整 IDaaS 流程，供扫码向导直接消费。
 *
 * <p>状态与结果经 {@link Value} 通知：监听器在<b>工作线程</b>上同步触发，
 * UI 消费方自行切线程（如 JavaFX 的 {@code Platform.runLater}）——引擎层不依赖任何 UI 栈。</p>
 *
 * <p>状态机（{@link State}）：</p>
 * <ol>
 *   <li>{@code RUNNING}：后台线程循环调用 {@link CmCloudIdaasClient#getToken}。
 *   返回 103000 即授权完成，票据回调并落盘缓存；返回 103001 说明需要授权，
 *   把登录短链（含二维码矩阵）回调展示；返回 130088 表示会话待授权，
 *   继续用同一个 uuid 轮询（重置本地入口同理，不重发会话）；其他码按失败回调。</li>
 *   <li>{@code SUCCEEDED} / {@code FAILED}：终态，线程退出。</li>
 * </ol>
 *
 * <p>票据拿到后同时写入本地凭据缓存（{@link CmCloudIdaasStore#saveTicketCache}），
 * 连接配置里 creditToken / clawUid 留空的场景由引擎的 CmCloudConfig 回落到缓存，
 * 用户扫一次码即可长期使用（creditToken 过期后重新扫码刷新）。</p>
 *
 * @author 郑明亮
 */
public final class CmCloudAuthService {
    /** 授权状态。 */
    public enum State {IDLE, RUNNING, SUCCEEDED, FAILED}

    /** 轮询间隔与总超时。 */
    private static final long POLL_INTERVAL_MS = 3_000L;
    private static final long TIMEOUT_MS = 300_000L;

    private final Value<State> state = new Value<State>(State.IDLE);
    private final Value<Ticket> ticket = new Value<Ticket>(null);
    private final Value<AuthEntry> entry = new Value<AuthEntry>(null);
    private final Value<String> error = new Value<String>(null);

    private Thread worker;

    /** @return 状态值（向导监听展示） */
    public Value<State> state() {
        return state;
    }

    /** @return 最新授权入口（null 表示还没有） */
    public Value<AuthEntry> entry() {
        return entry;
    }

    /** @return 授权成功的票据（null 表示还没有） */
    public Value<Ticket> ticket() {
        return ticket;
    }

    /** @return 失败原因（仅 FAILED 态有效） */
    public Value<String> error() {
        return error;
    }

    /** 启动授权流程（重复调用忽略；用完请 {@link #stop()}）。 */
    public synchronized void start() {
        if (worker != null && worker.isAlive()) {
            return;
        }
        state.set(State.RUNNING);
        error.set(null);
        entry.set(null);
        worker = new Thread(new Runnable() {
            @Override
            public void run() {
                runLoop();
            }
        }, "cmcloud-auth");
        worker.setDaemon(true);
        worker.start();
    }

    /** 停止后台轮询（关窗口 / 取消时调用；幂等）。 */
    public synchronized void stop() {
        if (worker != null && worker.isAlive()) {
            worker.interrupt();
        }
        worker = null;
        if (state.get() == State.RUNNING) {
            state.set(State.IDLE);
        }
    }

    private void runLoop() {
        CmCloudIdaasClient client = new CmCloudIdaasClient();
        long deadline = System.currentTimeMillis() + TIMEOUT_MS;
        // 会话 uuid：130088 轮询期间保持同一次会话，只有确实没有入口可重放才作废重取
        String sessionUuid = null;
        try {
            while (System.currentTimeMillis() < deadline) {
                CmCloudIdaasClient.TokenResult result = client.getToken(sessionUuid, null);
                if (result.code() == CmCloudIdaasProfile.CODE_SUCCESS) {
                    // 成功：票据落缓存并回调（skillId 等来自内置登记，clawUid 来自本地设备身份）
                    CmCloudIdaasStore.saveTicketCache(result.creditToken(),
                            clawUidOf(result), result.expireTime());
                    Ticket done = new Ticket(result.creditToken(),
                            CmCloudIdaasProfile.SKILL_ID, CmCloudIdaasProfile.CLIENT_ID,
                            CmCloudIdaasProfile.STRATEGY_ID, clawUidOf(result));
                    ticket.set(done);
                    state.set(State.SUCCEEDED);
                    return;
                }
                if (result.code() == CmCloudIdaasProfile.CODE_AUTH_REQUIRED) {
                    // 需要授权：展示入口（103001 现取现发）
                    if (result.authPageUrl() != null && result.uuid() != null) {
                        sessionUuid = result.uuid();
                        entry.set(new AuthEntry(result.authPageUrl(),
                                QrEncoder.encode(result.authPageUrl()
                                        .getBytes(java.nio.charset.StandardCharsets.UTF_8))));
                    }
                } else if (result.code() == CmCloudIdaasProfile.CODE_PENDING) {
                    // 待授权会话未完成：有入口展示就保持同一次会话继续轮询；
                    // 没有入口可展示（历史遗留会话）就作废 uuid 重新下发入口。
                    // entry 只被工作线程读写（volatile），直接读无竞态
                    if (entry.get() == null) {
                        CmCloudIdaasStore.clearAuthUuid();
                        sessionUuid = null;
                    }
                } else {
                    fail("取票失败（code=" + result.code() + "）：" + result.desc());
                    return;
                }
                Thread.sleep(POLL_INTERVAL_MS);
            }
            fail("等待授权超时（5 分钟），请重试");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            if (state.get() == State.RUNNING) {
                state.set(State.IDLE);
            }
        } catch (RuntimeException e) {
            fail("授权流程异常：" + e.getMessage());
        }
    }

    private void fail(String message) {
        error.set(message);
        state.set(State.FAILED);
    }

    /** clawUid 优先用设备身份文件的持久化值（getToken 成功后已写入），兜底重新收敛。 */
    private static String clawUidOf(CmCloudIdaasClient.TokenResult ignored) {
        String persisted = CmCloudIdaasStore.loadClawUid();
        return persisted != null ? persisted
                : CmCloudIdaasStore.resolveFallbackClawUid();
    }

    /**
     * 可监听的值容器（引擎层对 UI 属性的最小替代）：volatile 值 + 监听器列表，
     * {@link #set} 在调用线程上同步触发监听器——消费方需要切线程时自行处理。
     *
     * @param <T> 值类型
     * @author 郑明亮
     */
    public static final class Value<T> {
        private volatile T value;
        private final List<BiConsumer<T, T>> listeners = new CopyOnWriteArrayList<BiConsumer<T, T>>();

        /** @param initial 初始值 */
        public Value(T initial) {
            this.value = initial;
        }

        /** @return 当前值 */
        public T get() {
            return value;
        }

        /**
         * 更新值并在调用线程上同步触发监听器。
         *
         * @param newValue 新值
         */
        public void set(T newValue) {
            T old = this.value;
            this.value = newValue;
            for (BiConsumer<T, T> listener : listeners) {
                listener.accept(old, newValue);
            }
        }

        /**
         * 注册监听器（旧值, 新值）。
         *
         * @param listener 监听器
         */
        public void addListener(BiConsumer<T, T> listener) {
            listeners.add(listener);
        }

        /**
         * 移除监听器。
         *
         * @param listener 监听器
         */
        public void removeListener(BiConsumer<T, T> listener) {
            listeners.remove(listener);
        }
    }

    /** 授权入口（登录短链 + 二维码矩阵）。 */
    public static final class AuthEntry {
        private final String loginUrl;
        private final boolean[][] qrMatrix;

        /**
         * @param loginUrl  登录短链
         * @param qrMatrix  二维码矩阵
         */
        public AuthEntry(String loginUrl, boolean[][] qrMatrix) {
            this.loginUrl = loginUrl;
            this.qrMatrix = qrMatrix;
        }

        /** @return 登录短链 */
        public String loginUrl() {
            return loginUrl;
        }

        /** @return 二维码矩阵 */
        public boolean[][] qrMatrix() {
            return qrMatrix;
        }
    }

    /** 授权成功的五项业务票据。 */
    public static final class Ticket {
        private final String creditToken;
        private final String skillId;
        private final String clientId;
        private final String strategyId;
        private final String clawUid;

        /**
         * @param creditToken 业务票据
         * @param skillId     技能 id
         * @param clientId    客户端 id
         * @param strategyId  策略 id
         * @param clawUid     设备身份
         */
        public Ticket(String creditToken, String skillId, String clientId,
                      String strategyId, String clawUid) {
            this.creditToken = creditToken;
            this.skillId = skillId;
            this.clientId = clientId;
            this.strategyId = strategyId;
            this.clawUid = clawUid;
        }

        /** @return 业务票据 */
        public String creditToken() {
            return creditToken;
        }

        /** @return 技能 id */
        public String skillId() {
            return skillId;
        }

        /** @return 客户端 id */
        public String clientId() {
            return clientId;
        }

        /** @return 策略 id */
        public String strategyId() {
            return strategyId;
        }

        /** @return 设备身份 */
        public String clawUid() {
            return clawUid;
        }

        @Override
        public boolean equals(Object other) {
            if (this == other) {
                return true;
            }
            if (!(other instanceof Ticket)) {
                return false;
            }
            Ticket that = (Ticket) other;
            return java.util.Objects.equals(creditToken, that.creditToken)
                    && java.util.Objects.equals(skillId, that.skillId)
                    && java.util.Objects.equals(clientId, that.clientId)
                    && java.util.Objects.equals(strategyId, that.strategyId)
                    && java.util.Objects.equals(clawUid, that.clawUid);
        }

        @Override
        public int hashCode() {
            return java.util.Objects.hash(creditToken, skillId, clientId, strategyId, clawUid);
        }

        @Override
        public String toString() {
            return "Ticket[" + skillId + " clawUid=" + clawUid + "]";
        }
    }
}
