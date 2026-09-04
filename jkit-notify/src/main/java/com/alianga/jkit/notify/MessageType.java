package com.alianga.jkit.notify;

/**
 * 消息内容类型。
 *
 * <p>渠道通过 {@link NotificationChannel#supports(MessageType)} 声明支持的范围，
 * 不支持的类型在发送前即被拒绝；部分渠道对相近类型有明确的转换规则（例如
 * SMTP 渠道收到 {@link #MARKDOWN} 时转成 HTML 发送），以各渠道 javadoc 为准。
 *
 * @author 郑明亮
 * @since 2.0.1
 */
public enum MessageType {
    /**
     * 纯文本。
     */
    TEXT,

    /**
     * Markdown 文本。
     */
    MARKDOWN,

    /**
     * HTML 富文本。
     */
    HTML,

    /**
     * 钉钉独立跳转 ActionCard（带按钮的卡片）。
     */
    ACTION_CARD,

    /**
     * 企业微信 markdown_v2（比 markdown 支持更多语法）。
     */
    MARKDOWN_V2,

    /**
     * 图片消息（钉钉 / 企微群机器人：需先拿到平台 media_id 或图的 md5+base64）。
     */
    IMAGE,

    /**
     * 企业微信图文 / 钉钉 FeedCard 风格的链接卡片。
     */
    NEWS
}
