package com.alianga.jkit.notify;

/**
 * 通知渠道 SPI。实现一个新渠道只需要实现本接口，然后任选一种方式注册：
 *
 * <ol>
 * <li>代码注册：{@code NotificationManager.get().register(new MyChannel());}</li>
 * <li>SPI 注册：在 jar 的 {@code META-INF/services/com.alianga.jkit.notify.NotificationChannel}
 * 文件里写上实现类全限定名。</li>
 * </ol>
 *
 * <p>实现约定：
 * <ul>
 * <li>{@link #send(Message, ChannelConfig)} 不抛异常，所有失败都封装为
 * {@link SendResult#fail(String, String)} 返回；</li>
 * <li>只从 {@link ChannelConfig} 读取配置、只从 {@link Message#extras()} 读取渠道参数，
 * 两者互不越界，保证渠道间可自由组合。</li>
 * </ul>
 *
 * @author 郑明亮
 * @since 2.0.1
 */
public interface NotificationChannel {
    /**
     * @return 渠道唯一 id，例如 {@code dingtalk}
     */
    String id();

    /**
     * @return 展示名，例如 {@code 钉钉机器人}
     */
    String name();

    /**
     * 渠道是否支持该消息类型。不支持的类型会在发送前被
     * {@link NotificationManager} 直接拒绝。
     *
     * @param type 消息类型
     * @return 是否支持
     */
    boolean supports(MessageType type);

    /**
     * 发送一条消息。
     *
     * @param message 消息
     * @param config 渠道配置
     * @return 发送结果，永不为 {@code null}
     */
    SendResult send(Message message, ChannelConfig config);
}
