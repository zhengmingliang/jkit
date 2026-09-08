package com.alianga.jkit.notify;

import com.alianga.jkit.notify.channel.BarkChannel;
import com.alianga.jkit.notify.channel.DingTalkChannel;
import com.alianga.jkit.notify.channel.FeishuChannel;
import com.alianga.jkit.notify.channel.ServerChanChannel;
import com.alianga.jkit.notify.channel.SmtpChannel;
import com.alianga.jkit.notify.channel.WebhookChannel;
import com.alianga.jkit.notify.channel.WecomChannel;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.ServiceLoader;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 通知门面。静态方法覆盖绝大多数用法：
 * <pre>{@code
 * SendResult result = NotificationManager.send("dingtalk",
 *         Message.text("告警", "CPU 使用率 95%"),
 *         ChannelConfig.webhook("https://oapi.dingtalk.com/robot/send?access_token=xxx")
 *                 .secret("SEC..."));
 * List&lt;SendResult&gt; all = NotificationManager.sendAll(message, targets);
 * }</pre>
 *
 * <p>渠道注册模型（单一路径，避免双重注册）：
 * <ul>
 * <li>{@link #defaults()} 代码注册核心内置渠道（钉钉/企微/飞书/Server酱/Bark/Webhook/SMTP）；</li>
 * <li>{@link #loadSpi()} 只加载 classpath 上可选/扩展渠道（如 {@code jkit-notify-extra} 的
 * Slack/Telegram/ntfy/短信）——核心 jar 不再把内置渠道写进 {@code META-INF/services}；</li>
 * <li>代码 {@link #register(NotificationChannel)} 可覆盖同 id。</li>
 * </ul>
 *
 * <p>同步 {@link #send(String, Message, ChannelConfig)} 不会因网络失败抛异常；
 * 渠道 id 不存在、消息或配置为 {@code null}、类型不被渠道支持属于编程错误，直接抛
 * {@link IllegalArgumentException}。
 *
 * <p>{@link #sendAsync} 使用本模块独立的守护线程池（默认 8 线程），不和 jkit HTTP / SSE
 * 共用，避免 SMTP 阻塞把下载饿死。可用 {@link #setAsyncExecutor(ExecutorService)} 替换。
 *
 * @author 郑明亮
 * @since 2.0.1
 */
public final class NotificationManager {
    private static final NotificationManager INSTANCE = new NotificationManager().defaults().loadSpi();

    private static final ExecutorService DEFAULT_ASYNC = Executors.newFixedThreadPool(8, new ThreadFactory() {
        private final AtomicInteger seq = new AtomicInteger();

        @Override
        public Thread newThread(Runnable runnable) {
            Thread thread = new Thread(runnable, "jkit-notify-" + seq.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        }
    });

    private static volatile ExecutorService asyncExecutor = DEFAULT_ASYNC;

    private final Map<String, NotificationChannel> channels = new LinkedHashMap<String, NotificationChannel>();

    private NotificationManager() {
    }

    /**
     * @return 全局注册表
     */
    public static NotificationManager get() {
        return INSTANCE;
    }

    /**
     * 替换异步发送使用的线程池。传入 {@code null} 恢复默认池。
     *
     * <p>不会关闭被替换掉的池，调用方自行管理生命周期。
     *
     * @param executor 线程池
     */
    public static void setAsyncExecutor(ExecutorService executor) {
        asyncExecutor = executor == null ? DEFAULT_ASYNC : executor;
    }

    /**
     * @return 当前异步线程池
     */
    public static ExecutorService asyncExecutor() {
        return asyncExecutor;
    }

    /**
     * 注册（或按 id 覆盖）一个渠道。
     *
     * @param channel 渠道实现
     * @return this
     */
    public NotificationManager register(NotificationChannel channel) {
        if (channel == null || channel.id() == null) {
            throw new IllegalArgumentException("channel id is required");
        }
        channels.put(channel.id(), channel);
        return this;
    }

    /**
     * 按 id 移除渠道。移除内置渠道后可用 {@link #register(NotificationChannel)} 重新挂上。
     *
     * @param id 渠道 id
     * @return 被移除的渠道；不存在时为 {@code null}
     */
    public NotificationChannel unregister(String id) {
        if (id == null) {
            return null;
        }
        return channels.remove(id);
    }

    /**
     * @param id 渠道 id
     * @return 渠道，不存在时为 {@code null}
     */
    public NotificationChannel get(String id) {
        return channels.get(id);
    }

    /**
     * @return 全部已注册渠道（只读）
     */
    public List<NotificationChannel> list() {
        return Collections.unmodifiableList(new ArrayList<NotificationChannel>(channels.values()));
    }

    /**
     * 同步发送一条消息。
     *
     * @param channelId 渠道 id
     * @param message 消息
     * @param config 渠道配置
     * @return 发送结果
     * @throws IllegalArgumentException 渠道不存在 / 参数为空 / 类型不被支持
     */
    public static SendResult send(String channelId, Message message, ChannelConfig config) {
        return send(channelId, message, config, null);
    }

    /**
     * 同步发送，应用 {@link NotifyPolicy}（静默时段 / 去重 / 本地限流）。
     *
     * @param channelId 渠道 id
     * @param message 消息
     * @param config 渠道配置
     * @param policy 策略，{@code null} 表示不应用
     * @return 发送结果
     */
    public static SendResult send(String channelId, Message message, ChannelConfig config, NotifyPolicy policy) {
        Message rendered = message == null ? null : message.rendered();
        NotificationChannel channel = resolve(channelId, rendered, config);
        if (policy != null) {
            SendResult blocked = policy.beforeSend(channelId, rendered);
            if (blocked != null) {
                return blocked;
            }
        }
        SendResult result = channel.send(rendered, config);
        if (policy != null) {
            policy.afterAttempt(channelId, rendered);
        }
        return result;
    }

    /**
     * 同一渠道多套账号按顺序试，直到成功。可重试失败（网络 / 限流）才切下一个；
     * 配置错误也切下一个（密钥错了换号有意义）。
     *
     * @param channelId 渠道 id
     * @param message 消息
     * @param accounts 账号配置，至少 1 个
     * @return 最后一次结果；全部失败则聚合
     */
    public static SendResult sendFailover(String channelId, Message message, List<ChannelConfig> accounts) {
        return sendFailover(channelId, message, accounts, null);
    }

    /**
     * 带策略的故障转移。策略在第一次真正发送前检查；抑制则整组不发。
     *
     * @param channelId 渠道 id
     * @param message 消息
     * @param accounts 账号配置
     * @param policy 策略
     * @return 发送结果
     */
    public static SendResult sendFailover(String channelId, Message message, List<ChannelConfig> accounts,
                                          NotifyPolicy policy) {
        if (accounts == null || accounts.isEmpty()) {
            throw new IllegalArgumentException("accounts is required");
        }
        Message rendered = message == null ? null : message.rendered();
        NotificationChannel channel = resolve(channelId, rendered, accounts.get(0));
        if (policy != null) {
            SendResult blocked = policy.beforeSend(channelId, rendered);
            if (blocked != null) {
                return blocked;
            }
        }
        List<SendResult> attempts = new ArrayList<SendResult>(accounts.size());
        for (int i = 0; i < accounts.size(); i++) {
            ChannelConfig config = accounts.get(i);
            if (config == null) {
                throw new IllegalArgumentException("config is required");
            }
            SendResult result = channel.send(rendered, config);
            attempts.add(result);
            if (policy != null) {
                policy.afterAttempt(channelId, rendered);
            }
            if (result.isSuccess()) {
                if (attempts.size() == 1) {
                    return result;
                }
                return SendResult.aggregate(channelId, attempts, true);
            }
        }
        return SendResult.aggregate(channelId, attempts, true);
    }

    /**
     * 同一条消息发到多个渠道。某个渠道失败不影响其它渠道；返回列表与 {@code targets} 迭代顺序一致。
     *
     * <p>编程错误（未知渠道、类型不支持、配置为 null）仍立即抛出，不会部分发送。
     *
     * @param message 消息
     * @param targets 渠道 id → 配置，用 {@link LinkedHashMap} 可保持顺序
     * @return 各渠道发送结果
     */
    public static List<SendResult> sendAll(Message message, Map<String, ChannelConfig> targets) {
        if (targets == null || targets.isEmpty()) {
            throw new IllegalArgumentException("targets is required");
        }
        Message rendered = message == null ? null : message.rendered();
        List<Resolved> resolved = new ArrayList<Resolved>(targets.size());
        for (Map.Entry<String, ChannelConfig> entry : targets.entrySet()) {
            resolved.add(new Resolved(resolve(entry.getKey(), rendered, entry.getValue()), entry.getValue()));
        }
        List<SendResult> results = new ArrayList<SendResult>(resolved.size());
        for (Resolved item : resolved) {
            results.add(item.channel.send(rendered, item.config));
        }
        return results;
    }

    /**
     * {@link #sendAll(Message, Map)} 的聚合视图：全部成功才 {@link SendResult#isSuccess()}。
     *
     * @param message 消息
     * @param targets 渠道 id → 配置
     * @return 聚合结果，子结果见 {@link SendResult#parts()}
     */
    public static SendResult sendAllAggregated(Message message, Map<String, ChannelConfig> targets) {
        return SendResult.aggregate("all", sendAll(message, targets));
    }

    /**
     * 异步发送一条消息（本模块独立线程池，不和 HTTP / SSE 共用）。
     *
     * <p>校验（渠道存在、类型支持）在提交前同步完成，非法参数立刻抛出；
     * 网络失败会作为 {@link SendResult} 从 {@link Future#get()} 返回。
     *
     * @param channelId 渠道 id
     * @param message 消息
     * @param config 渠道配置
     * @return 结果 Future
     * @throws IllegalArgumentException 渠道不存在 / 参数为空 / 类型不被支持
     */
    public static Future<SendResult> sendAsync(String channelId, Message message, ChannelConfig config) {
        return sendAsync(channelId, message, config, null);
    }

    /**
     * 异步发送，应用策略。校验与策略检查在提交前同步完成。
     *
     * @param channelId 渠道 id
     * @param message 消息
     * @param config 配置
     * @param policy 策略
     * @return Future
     */
    public static Future<SendResult> sendAsync(String channelId, Message message, ChannelConfig config,
                                               NotifyPolicy policy) {
        final Message rendered = message == null ? null : message.rendered();
        final NotificationChannel channel = resolve(channelId, rendered, config);
        if (policy != null) {
            SendResult blocked = policy.beforeSend(channelId, rendered);
            if (blocked != null) {
                return java.util.concurrent.CompletableFuture.completedFuture(blocked);
            }
        }
        final NotifyPolicy applied = policy;
        return asyncExecutor().submit(() -> {
            SendResult result = channel.send(rendered, config);
            if (applied != null) {
                applied.afterAttempt(channelId, rendered);
            }
            return result;
        });
    }

    /**
     * 异步 fan-out，校验同步完成。
     *
     * @param message 消息
     * @param targets 渠道 id → 配置
     * @return 各渠道结果列表的 Future
     */
    public static Future<List<SendResult>> sendAllAsync(Message message, Map<String, ChannelConfig> targets) {
        if (targets == null || targets.isEmpty()) {
            throw new IllegalArgumentException("targets is required");
        }
        final Message rendered = message == null ? null : message.rendered();
        final List<Resolved> resolved = new ArrayList<Resolved>(targets.size());
        for (Map.Entry<String, ChannelConfig> entry : targets.entrySet()) {
            resolved.add(new Resolved(resolve(entry.getKey(), rendered, entry.getValue()), entry.getValue()));
        }
        return asyncExecutor().submit(() -> {
            List<SendResult> results = new ArrayList<SendResult>(resolved.size());
            for (Resolved item : resolved) {
                results.add(item.channel.send(rendered, item.config));
            }
            return results;
        });
    }

    private static NotificationChannel resolve(String channelId, Message message, ChannelConfig config) {
        if (message == null || message.content() == null || message.content().trim().isEmpty()) {
            throw new IllegalArgumentException("message with content is required");
        }
        if (config == null) {
            throw new IllegalArgumentException("config is required");
        }
        NotificationChannel channel = INSTANCE.channels.get(channelId);
        if (channel == null) {
            throw new IllegalArgumentException("unknown channel: " + channelId);
        }
        if (!channel.supports(message.type())) {
            throw new IllegalArgumentException(
                    channelId + " does not support message type: " + message.type());
        }
        return channel;
    }

    /** 注册核心内置渠道（不经 SPI，避免与 META-INF/services 双重注册）。 */
    private NotificationManager defaults() {
        register(new DingTalkChannel());
        register(new WecomChannel());
        register(new FeishuChannel());
        register(new ServerChanChannel());
        register(new BarkChannel());
        register(new WebhookChannel());
        register(new SmtpChannel());
        return this;
    }

    /**
     * 加载 SPI 扩展渠道。核心内置已由 {@link #defaults()} 注册，核心模块的
     * services 文件应为空或不列核心渠道；extra 模块的 SPI 在此加载。
     */
    private NotificationManager loadSpi() {
        for (NotificationChannel channel : ServiceLoader.load(NotificationChannel.class)) {
            register(channel);
        }
        return this;
    }

    private static final class Resolved {
        private final NotificationChannel channel;
        private final ChannelConfig config;

        private Resolved(NotificationChannel channel, ChannelConfig config) {
            this.channel = channel;
            this.config = config;
        }
    }
}
