package com.alianga.jkit.storage.conn;

import com.alianga.jkit.storage.FileStorage;
import com.alianga.jkit.storage.FileStorages;
import com.alianga.jkit.storage.StorageErrorCode;
import com.alianga.jkit.storage.StorageException;
import com.alianga.jkit.storage.engine.QqMailAttachFileStorage;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiConsumer;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * 连接 → 存储实例的打开入口：统一在这里创建 {@link FileStorage}，
 * 并把配置错误翻译成用户可读的提示。
 *
 * <p>另提供 {@link #callPooled} 的轻量操作复用池：FTP / SFTP 每次新建实例都要
 * 重做一遍「TCP 连接 + 登录 + 能力探测」握手，编辑保存、读文件这类小操作
 * 每次都多花一次握手时间；池化后同一条控制连接反复用。长时间传输（上传 /
 * 下载 / 同步）仍走 {@link #call} 的独立实例，不和轻量操作抢同一条连接。</p>
 *
 * @author 郑明亮
 */
public final class Sessions {
    /** 轻量操作复用池：连接身份（id + 平台 + 配置哈希）→ 常驻实例。 */
    private static final Map<String, Pooled> POOL = new ConcurrentHashMap<String, Pooled>();

    /**
     * 常驻实例 + 是否已成功用过（只有用通过的实例断线才值得重试，首次就连不上直接报错）。
     *
     * <p>引用计数：{@code inFlight} 记正在用它的操作数；{@code released} 置位后实例不再被借出、
     * 不再重试，等最后一个在途操作结束才真正关闭（不打断正在保存 / 建目录的操作）。</p>
     */
    static final class Pooled {
        final FileStorage storage;
        volatile boolean proven;
        private int inFlight;
        private boolean released;
        private boolean closed;

        Pooled(FileStorage storage) {
            this.storage = storage;
        }

        /** 借出：已释放的实例返回 false，调用方应重新取池。 */
        synchronized boolean acquire() {
            if (released) {
                return false;
            }
            inFlight++;
            return true;
        }

        /** 归还；已释放且无人在用时安排关闭。 */
        void returned() {
            boolean close;
            synchronized (this) {
                inFlight--;
                close = released && inFlight == 0 && !closed;
                if (close) {
                    closed = true;
                }
            }
            if (close) {
                scheduleClose(storage);
            }
        }

        /** 标记释放；无人在用时立即安排关闭，否则等最后一个操作归还。 */
        void markReleased() {
            boolean close;
            synchronized (this) {
                released = true;
                close = inFlight == 0 && !closed;
                if (close) {
                    closed = true;
                }
            }
            if (close) {
                scheduleClose(storage);
            }
        }

        synchronized boolean isReleased() {
            return released;
        }
    }

    /**
     * 测试钩子：关闭执行器。默认后台守护线程——FTP 的 close() 要等引擎锁（可能正被
     * 长列目录占着），绝不能在 UI 线程上同步关闭。
     */
    static volatile java.util.concurrent.Executor closer = new java.util.concurrent.Executor() {
        @Override
        public void execute(Runnable command) {
            Thread thread = new Thread(command, "storage-session-close");
            thread.setDaemon(true);
            thread.start();
        }
    };

    private static void scheduleClose(FileStorage storage) {
        try {
            closer.execute(new Runnable() {
                @Override
                public void run() {
                    closeQuietly(storage);
                }
            });
        } catch (RuntimeException e) {
            closeQuietly(storage);
        }
    }

    /** QQ 邮箱附件：连接 id → 引擎回推的最新 Cookie（仅内存，落盘由 {@link #cookieSink} 加密写回）。 */
    private static final Map<String, String> LATEST_COOKIES = new ConcurrentHashMap<String, String>();
    private static final Map<String, String> LATEST_SIDS = new ConcurrentHashMap<String, String>();
    /** Cookie 更新落盘回调（宿主注册：加密写回连接配置）；值属于密钥，任何地方都不打日志。 */
    private static volatile BiConsumer<Connection, String> cookieSink;
    /** sid 更新落盘回调（续期后新 sid）；值属于密钥，不打日志。 */
    private static volatile BiConsumer<Connection, String> sidSink;

    static {
        // 应用退出时把常驻 FTP / SFTP 会话礼貌地关掉（QUIT / disconnect），不留半开连接
        try {
            Runtime.getRuntime().addShutdownHook(new Thread(new Runnable() {
                @Override
                public void run() {
                    closePoolNow();
                }
            }, "storage-sessions-close"));
        } catch (IllegalStateException | SecurityException ignored) {
            // 已在关闭中或不允许注册钩子：交给进程退出回收
        }
    }

    private Sessions() {
    }

    /**
     * 注册 QQ 邮箱附件 Cookie 更新的落盘回调。
     *
     * @param sink (连接, 新 Cookie) → 加密写回；null 取消
     */
    public static void setCookieSink(BiConsumer<Connection, String> sink) {
        cookieSink = sink;
    }

    /**
     * 注册 QQ 邮箱附件 sid 更新的落盘回调。
     *
     * @param sink (连接, 新 sid) → 加密写回；null 取消
     */
    public static void setSidSink(BiConsumer<Connection, String> sink) {
        sidSink = sink;
    }

    /** 叠加内存里的最新 Cookie：界面持有的连接对象可能还是旧值。 */
    static Connection withLatestCookie(Connection connection) {
        String latest = LATEST_COOKIES.get(connection.id());
        String latestSid = LATEST_SIDS.get(connection.id());
        boolean cookieSame = latest == null || latest.equals(connection.config().get("cookie"));
        boolean sidSame = latestSid == null || latestSid.equals(connection.config().get("sid"));
        if (cookieSame && sidSame) {
            return connection;
        }
        Map<String, String> config = new LinkedHashMap<String, String>(connection.config());
        if (!cookieSame) {
            config.put("cookie", latest);
        }
        if (!sidSame) {
            config.put("sid", latestSid);
        }
        return connection.with(connection.name(), connection.platform(), config);
    }

    /** 引擎回推 Cookie 时：记最新值并交给宿主加密落盘。 */
    static void onCookieUpdated(Connection connection, String cookie) {
        onSessionUpdated(connection, cookie, null);
    }

    /** Cookie / sid 回推：写回连接配置（值不进日志）。 */
    static void onSessionUpdated(Connection connection, String cookie, String sid) {
        if (cookie != null && !cookie.trim().isEmpty()) {
            String previous = LATEST_COOKIES.put(connection.id(), cookie);
            boolean cookieChanged = !cookie.equals(previous)
                    && !(previous == null && cookie.equals(connection.config().get("cookie")));
            if (cookieChanged) {
                BiConsumer<Connection, String> sink = cookieSink;
                if (sink != null) {
                    try {
                        sink.accept(connection, cookie);
                    } catch (RuntimeException ignored) {
                        // 落盘失败不阻断浏览；内存里已是最新值
                    }
                }
            }
        }
        if (sid != null && !sid.trim().isEmpty()) {
            String prevSid = connection.config().get("sid");
            if (!sid.equals(prevSid)) {
                LATEST_SIDS.put(connection.id(), sid);
                BiConsumer<Connection, String> sink = sidSink;
                if (sink != null) {
                    try {
                        sink.accept(connection, sid);
                    } catch (RuntimeException ignored) {
                        // ignore
                    }
                }
            }
        }
    }

    /**
     * 打开连接对应的存储实例（每次新实例，引擎均线程安全；FTP/SFTP 引擎自带连接管理）。
     *
     * @param connection 连接
     * @return 存储实例
     * @throws StorageException 平台未知或配置非法
     */
    public static FileStorage open(Connection connection) {
        try {
            Connection effective = withLatestCookie(connection);
            FileStorage storage = FileStorages.of(effective.platform(), effective.config());
            if (storage instanceof QqMailAttachFileStorage) {
                ((QqMailAttachFileStorage) storage).setSessionUpdateListener(
                        new QqMailAttachFileStorage.SessionUpdateListener() {
                            @Override
                            public void onSessionUpdated(String cookie, String sid) {
                                Sessions.onSessionUpdated(effective, cookie, sid);
                            }
                        });
            }
            return storage;
        } catch (StorageException e) {
            throw new StorageException(e.code(),
                    "连接「" + connection.name() + "」无法创建：" + e.getMessage(), e.path(), e);
        }
    }

    /**
     * 打开连接、执行一次操作并关闭。FTP/SFTP 会在关闭时释放控制连接。
     *
     * @param connection 连接
     * @param action     操作
     * @param <T>        结果类型
     * @return 操作结果
     * @throws Exception 打开失败或操作失败
     */
    public static <T> T call(Connection connection, StorageAction<T> action) throws Exception {
        FileStorage storage = open(connection);
        try {
            return action.apply(storage);
        } finally {
            try {
                storage.close();
            } catch (Exception ignored) {
                // 关闭失败不影响操作结果
            }
        }
    }

    /**
     * 用常驻实例执行一次轻量操作（列目录 / 读文件 / 编辑保存 / 建目录等小往返）。
     * 只有握手成本高的平台（FTP：TCP + 登录 + 能力探测；SFTP：TCP + SSH 握手 + 认证）
     * 才池化，HTTP 类平台实例本来就轻，仍走 {@link #call} 用完即关。
     *
     * <p>实例按「id + 平台 + 配置哈希」缓存，同一连接的多个浏览标签共用一个实例；
     * 连接编辑后旧实例随 {@link #closePool()} 释放，标签关闭时由 {@link #release} 释放。
     * 并发：FTP 引擎内部一把锁串行化同一条控制连接（并发预览 / 下载会排队，不会交错读写），
     * SFTP 引擎在一个 Session 上维护 ChannelSftp 池，可并发。</p>
     *
     * <p>断线自愈：常驻实例若因连接类错误（CONNECT_FAILED / TIMEOUT / IO_ERROR，
     * 例如服务端踢掉空闲连接、SSH Session 已断）失败，立即作废并用新实例重试一次；
     * 鉴权失败、目标不存在等业务错误不重试。</p>
     *
     * @param connection 连接
     * @param action     操作
     * @param <T>        结果类型
     * @return 操作结果
     * @throws Exception 打开失败或操作失败
     */
    public static <T> T callPooled(Connection connection, StorageAction<T> action) throws Exception {
        return callPooled(connection, true, action);
    }

    /**
     * 在独立「通道」上的常驻实例执行只读操作（如 FTP 缩略图），不与浏览列目录抢同一把引擎锁。
     *
     * @param connection 连接
     * @param lane       通道名（null 表示主通道）
     * @param action     操作
     * @param <T>        结果类型
     * @return 操作结果
     * @throws Exception 打开失败或操作失败
     */
    public static <T> T callPooledLane(Connection connection, String lane, StorageAction<T> action)
            throws Exception {
        return callPooled(connection, lane, true, action);
    }

    /**
     * 写操作（上传保存 / 建目录 / 重命名 / 删除等）：复用常驻实例，但断线后<b>不自动重试</b>——
     * 服务端可能已执行成功只是回包时断了，重试会导致重复执行（rename 报不存在、mkdir 报已存在、
     * 上传再覆盖一次）。断掉的实例照样作废，下次操作重建。
     *
     * @param connection 连接
     * @param action     操作
     * @param <T>        结果类型
     * @return 操作结果
     * @throws Exception 打开失败或操作失败
     */
    public static <T> T callPooledWrite(Connection connection, StorageAction<T> action) throws Exception {
        return callPooled(connection, false, action);
    }

    /**
     * 用常驻实例执行操作。
     *
     * @param connection 连接
     * @param retryable  断线后是否允许用新实例重试一次（仅幂等的读操作传 true）
     * @param action     操作
     * @param <T>        结果类型
     * @return 操作结果
     * @throws Exception 打开失败或操作失败
     */
    public static <T> T callPooled(Connection connection, boolean retryable, StorageAction<T> action)
            throws Exception {
        return callPooled(connection, null, retryable, action);
    }

    /**
     * 主实现。<b>不要用于大文件 / 长时间传输</b>：常驻实例被所有轻量操作共用（FTP 还串行于同一把
     * 引擎锁），长传输会把列目录、编辑保存等全部堵住；上传 / 下载 / 同步请走 {@link #call} 的独立实例。
     */
    private static <T> T callPooled(Connection connection, String lane, boolean retryable,
                                    StorageAction<T> action) throws Exception {
        if (!isPooledPlatform(connection.platform())) {
            return call(connection, action);
        }
        String base = poolKey(connection);
        String live = LIVE_KEYS.get(connection.id());
        if (liveTracked && !base.equals(live)) {
            // 连接已删除或配置已改：持有旧 Connection 的后台任务不得在旧 key 下重建实例
            throw new StorageException(StorageErrorCode.CONFLICT,
                    "连接「" + connection.name() + "」已被修改或删除，请刷新后重试", "");
        }
        String key = lane == null ? base : base + "#" + lane;
        Pooled pooled = borrow(key, connection);
        try {
            T result = action.apply(pooled.storage);
            pooled.proven = true;
            return result;
        } catch (StorageException e) {
            boolean dropped = isConnectionFailure(e);
            // 已被释放（关标签 / 断开 / 连接改动）的实例：报错原样抛出，不重试也不再进池，免得重登一个孤儿实例
            boolean wasReleased = pooled.isReleased();
            if (dropped && POOL.remove(key, pooled)) {
                // 旧实例作废（只摘掉自己那个，别误关别的线程刚建好的新实例）
                pooled.markReleased();
            }
            if (!retryable || wasReleased || !dropped || !pooled.proven) {
                throw e;
            }
            Pooled fresh = borrow(key, connection);
            try {
                T result = action.apply(fresh.storage);
                fresh.proven = true;
                return result;
            } catch (Exception again) {
                // 重试建的新实例也失败：断线或从未连通过就立刻摘出池，别让后续操作再撞上它
                if ((!(again instanceof StorageException) || isConnectionFailure((StorageException) again)
                        || !fresh.proven) && POOL.remove(key, fresh)) {
                    fresh.markReleased();
                }
                throw again;
            } finally {
                fresh.returned();
            }
        } finally {
            pooled.returned();
        }
    }

    private static Pooled borrow(String key, Connection connection) {
        while (true) {
            // 网络握手（TCP + 登录 + 能力探测，可能数秒）必须在 CHM 的 bin 锁外做：
            // computeIfAbsent 的映射函数持锁执行，握手期间哈希到同一 bin 的其它 key
            // 全部被卡。锁外开好再用 putIfAbsent 竞争入池，输者关闭自己多开的实例
            Pooled pooled = POOL.get(key);
            if (pooled == null) {
                Pooled fresh = new Pooled(opener.apply(connection));
                Pooled existing = POOL.putIfAbsent(key, fresh);
                if (existing != null) {
                    scheduleClose(fresh.storage);
                    pooled = existing;
                } else {
                    pooled = fresh;
                }
            }
            if (pooled.acquire()) {
                return pooled;
            }
            POOL.remove(key, pooled);
        }
    }

    /** 测试钩子：实例工厂（默认 {@link #open}），单测替换为计数桩。 */
    static volatile Function<Connection, FileStorage> opener = new Function<Connection, FileStorage>() {
        @Override
        public FileStorage apply(Connection connection) {
            return open(connection);
        }
    };

    /**
     * 是否池化常驻实例（握手贵的协议）。
     *
     * @param platform 平台 id
     * @return true 表示 {@link #callPooled} 复用实例
     */
    public static boolean isPooledPlatform(String platform) {
        return "ftp".equals(platform) || "sftp".equals(platform);
    }

    /** 断线特征（JSch / socket 的异常消息）：SFTP 引擎会把断掉的通道报成 CONFLICT（SftpException id=4），只看错误码认不出来。 */
    private static final String[] DROPPED_MARKERS = {
            "pipe closed", "session is down", "session is not connected", "channel is not opened",
            "broken pipe", "connection reset", "socket closed", "connection closed", "end of io stream"};

    static boolean isConnectionFailure(StorageException e) {
        if (e.code() == StorageErrorCode.CONNECT_FAILED
                || e.code() == StorageErrorCode.TIMEOUT
                || e.code() == StorageErrorCode.IO_ERROR) {
            return true;
        }
        for (Throwable t = e; t != null; t = t.getCause() == t ? null : t.getCause()) {
            String message = t.getMessage();
            if (message == null) {
                continue;
            }
            String lower = message.toLowerCase(Locale.ROOT);
            for (String marker : DROPPED_MARKERS) {
                if (lower.contains(marker)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static String poolKey(Connection connection) {
        return connection.id() + "|" + connection.platform() + "|" + connection.config().hashCode();
    }

    /** 连接库里现存的连接 id → 当前 key（{@link #retainOnly} 维护）；用于拒绝旧 Connection 重建实例。 */
    private static final Map<String, String> LIVE_KEYS = new ConcurrentHashMap<String, String>();
    /** 是否已由宿主同步过连接库（未同步时不做过期校验，例如单测 / 独立调用）。 */
    private static volatile boolean liveTracked;

    /** 当前常驻实例数（诊断 / 测试用）。 */
    static int pooledCount() {
        return POOL.size();
    }

    /**
     * 释放某个连接的常驻实例（浏览标签关闭时调用；之后再用会按需重建）。
     *
     * @param connection 连接
     */
    public static void release(Connection connection) {
        if (connection == null) {
            return;
        }
        String prefix = connection.id() + "|";
        POOL.entrySet().removeIf(new java.util.function.Predicate<Map.Entry<String, Pooled>>() {
            @Override
            public boolean test(Map.Entry<String, Pooled> entry) {
                if (entry.getKey().startsWith(prefix)) {
                    entry.getValue().markReleased();
                    return true;
                }
                return false;
            }
        });
    }

    /**
     * 连接库重载后只释放失效的常驻实例：连接已删除或配置已变（key 不再匹配），没变的保留。
     *
     * <p>同时作废「消失 / 配置已变」连接的引擎回推缓存（LATEST_COOKIES / LATEST_SIDS）：
     * 用户手工粘贴新 cookie 保存后，旧的引擎回推值若还留着，下次 open 时
     * withLatestCookie 会把用户刚保存的值静默覆盖回旧登录态，且无任何提示。
     * 配置未变时保留缓存——sink 落盘失败的场景仍靠内存值兜底。</p>
     *
     * @param current 当前全部连接
     */
    public static void retainOnly(java.util.Collection<Connection> current) {
        Set<String> live = new HashSet<String>();
        Map<String, String> ids = new java.util.HashMap<String, String>();
        if (current != null) {
            for (Connection connection : current) {
                live.add(poolKey(connection));
                ids.put(connection.id(), poolKey(connection));
            }
        }
        Map<String, String> previousKeys = new java.util.HashMap<String, String>(LIVE_KEYS);
        // 差量更新（retainAll + putAll）而非 clear + putAll：clear 与 putAll 之间
        // 并发的 callPooled 会读到空表，对完全有效的连接误报「已被修改或删除」
        LIVE_KEYS.keySet().retainAll(ids.keySet());
        LIVE_KEYS.putAll(ids);
        liveTracked = true;
        // 引擎回推缓存只对「配置没变的现存连接」有效
        Predicate<String> stale = new Predicate<String>() {
            @Override
            public boolean test(String id) {
                return !ids.containsKey(id) || !ids.get(id).equals(previousKeys.get(id));
            }
        };
        LATEST_COOKIES.keySet().removeIf(stale);
        LATEST_SIDS.keySet().removeIf(stale);
        POOL.entrySet().removeIf(new java.util.function.Predicate<Map.Entry<String, Pooled>>() {
            @Override
            public boolean test(Map.Entry<String, Pooled> entry) {
                String key = entry.getKey();
                int lane = key.indexOf('#');
                if (!live.contains(lane < 0 ? key : key.substring(0, lane))) {
                    entry.getValue().markReleased();
                    return true;
                }
                return false;
            }
        });
    }

    private static void closeQuietly(FileStorage storage) {
        try {
            storage.close();
        } catch (Exception ignored) {
            // 关闭失败无所谓，实例即刻废弃
        }
    }

    /**
     * 释放全部常驻实例（视图销毁时调用）；在途操作结束后才真正关闭。
     */
    public static void closePool() {
        liveTracked = false;
        LIVE_KEYS.clear();
        POOL.entrySet().removeIf(new java.util.function.Predicate<Map.Entry<String, Pooled>>() {
            @Override
            public boolean test(Map.Entry<String, Pooled> entry) {
                entry.getValue().markReleased();
                return true;
            }
        });
    }

    /** 应用退出：同步关闭全部常驻实例（钩子线程，不在 UI 线程）。 */
    private static void closePoolNow() {
        for (Pooled pooled : POOL.values()) {
            closeQuietly(pooled.storage);
        }
        POOL.clear();
    }

    /**
     * 一次存储操作。
     *
     * @param <T> 结果类型
     */
    @FunctionalInterface
    public interface StorageAction<T> {
        /**
         * 在已打开的存储上执行。
         *
         * @param storage 存储实例
         * @return 结果
         * @throws Exception 操作失败
         */
        T apply(FileStorage storage) throws Exception;
    }
}
