package com.alianga.jkit.notify;

import com.alianga.jkit.HttpUtils;
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
import java.util.concurrent.Future;

/**
 * 通知门面。静态方法覆盖绝大多数用法：
 * <pre>{@code
 * SendResult result = NotificationManager.send("dingtalk",
 *         Message.text("告警", "CPU 使用率 95%"),
 *         ChannelConfig.webhook("https://oapi.dingtalk.com/robot/send?access_token=xxx")
 *                 .secret("SEC..."));
 * }</pre>
 *
 * <p>渠道来源有三：内置注册（钉钉/企微/飞书/Server酱/Bark/通用 Webhook/SMTP）、
 * classpath 上 {@code META-INF/services} SPI、代码 {@link #register(NotificationChannel)}。
 * 相同 id 后注册的覆盖先注册的，因此调用方可以替换任意内置渠道。
 *
 * <p>同步 {@link #send(String, Message, ChannelConfig)} 不会因网络失败抛异常；
 * 渠道 id 不存在、消息或配置为 {@code null}、类型不被渠道支持属于编程错误，直接抛
 * {@link IllegalArgumentException}。
 *
 * @author 郑明亮
 * @since 2.0.1
 */
public final class NotificationManager {
    private static final NotificationManager INSTANCE = new NotificationManager().defaults().loadSpi();

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
        NotificationChannel channel = resolve(channelId, message, config);
        return channel.send(message, config);
    }

    /**
     * 异步发送一条消息（复用 jkit HTTP 公共线程池）。
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
        final NotificationChannel channel = resolve(channelId, message, config);
        return HttpUtils.asyncExecutor().submit(() -> channel.send(message, config));
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

    private NotificationManager loadSpi() {
        for (NotificationChannel channel : ServiceLoader.load(NotificationChannel.class)) {
            register(channel);
        }
        return this;
    }
}
