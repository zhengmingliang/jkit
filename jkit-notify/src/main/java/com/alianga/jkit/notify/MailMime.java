package com.alianga.jkit.notify;

import com.alianga.jkit.notify.channel.SmtpChannel;

import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.List;

/**
 * 将 {@link Message}（含 {@link Attachment}）渲染为 RFC822 MIME 字节。
 *
 * <p>复用 {@link SmtpChannel} 的 MIME 组装规则，供 jkit-storage 邮件盘等非 SMTP 路径使用，
 * 避免各模块手写 multipart / Base64。
 *
 * @author 郑明亮
 */
public final class MailMime {
    private MailMime() {
    }

    /**
     * 按 SmtpChannel 规则渲染完整 MIME。
     *
     * @param message 消息（含附件）
     * @param from   发件人邮箱；为空时回退 {@code config.from()} / {@code config.username()}
     * @param config 渠道配置（至少需设置 {@code to}；建议同时设置 {@code from}）
     * @return RFC822 字节（UTF-8）
     */
    public static byte[] render(Message message, String from, ChannelConfig config) {
        if (message == null) {
            throw new IllegalArgumentException("message is required");
        }
        if (config == null) {
            throw new IllegalArgumentException("config is required");
        }
        String resolvedFrom = from;
        if (resolvedFrom == null || resolvedFrom.trim().isEmpty()) {
            resolvedFrom = config.from() != null ? config.from() : config.username();
        }
        if (resolvedFrom == null || resolvedFrom.trim().isEmpty()) {
            resolvedFrom = "jkit-storage@localhost";
        }
        List<Attachment> attachments = message.attachments();
        if (attachments == null) {
            attachments = Collections.emptyList();
        }
        String mime = new Renderer().exposeBuildMime(message, resolvedFrom.trim(), config, attachments);
        return mime.getBytes(StandardCharsets.UTF_8);
    }

    /**
     * 包内子类：仅用于调用 {@link SmtpChannel#buildMime}，不扩大其 public 面。
     */
    private static final class Renderer extends SmtpChannel {
        String exposeBuildMime(Message message, String from, ChannelConfig config,
                               List<Attachment> attachments) {
            return buildMime(message, from, config, attachments);
        }
    }
}
