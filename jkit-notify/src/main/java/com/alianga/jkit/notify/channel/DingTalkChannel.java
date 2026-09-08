package com.alianga.jkit.notify.channel;

import com.alianga.jkit.notify.ChannelConfig;
import com.alianga.jkit.notify.FailureType;
import com.alianga.jkit.notify.Message;
import com.alianga.jkit.notify.MessageType;
import com.alianga.jkit.notify.NotifyUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 钉钉自定义机器人渠道。
 *
 * <p>配置：{@link ChannelConfig#webhook(String)} 填机器人 webhook 地址（含 access_token），
 * 安全设置选"加签"时再 {@link ChannelConfig#secret(String)} 填 SEC 开头的密钥。
 *
 * <p>消息：TEXT / MARKDOWN / ACTION_CARD。正文按 UTF-8 {@value #MAX_CONTENT_BYTES} 字节上限自动截断。
 *
 * <p><b>@人的关键规则</b>：钉钉光有 {@code at.atMobiles} 数组**不会**高亮提醒，被 @ 的手机号
 * 必须以字面文本出现在正文里，否则静默失效。本渠道会自动把缺失的 {@code @手机号} 追加到正文末尾
 * （已出现的不重复追加），截断时会为这些后缀预留字节。钉钉按子串匹配，手机号外面套 markdown
 * 装饰（如 {@code **138...**}）也能生效。userid 走 {@link Message#EXTRA_AT_USERIDS}。
 * @所有人走 {@link Message#EXTRA_AT_ALL}，默认关闭。
 *
 * <p><b>其它平台约束</b>：单个机器人 20 条/分钟，超限会被禁言约 10 分钟（本渠道不做本地限流，
 * 高频场景请自行合并消息）；加签的 timestamp 与钉钉服务器相差超过 1 小时即失败——容器时区或
 * NTP 不同步是该错误的头号原因；安全设置选"自定义关键词"时正文必须含关键词，否则 errcode 310000。
 *
 * <p>响应判定：HTTP 200 且 {@code errcode == 0}。
 *
 * @author 郑明亮
 * @since 2.0.1
 */
public class DingTalkChannel extends AbstractHttpChannel {
    /**
     * 渠道 id。
     */
    public static final String ID = "dingtalk";

    /**
     * 正文 UTF-8 字节上限。
     */
    public static final int MAX_CONTENT_BYTES = 20000;

    @Override
    public String id() {
        return ID;
    }

    @Override
    public String name() {
        return "钉钉机器人";
    }

    @Override
    public boolean supports(MessageType type) {
        return type == MessageType.TEXT || type == MessageType.MARKDOWN
                || type == MessageType.ACTION_CARD || type == MessageType.NEWS || type == MessageType.IMAGE;
    }

    @Override
    protected String[] usedConfigKeys() {
        return new String[]{"webhook", "secret", "timeoutMs"};
    }

    @Override
    protected int contentMaxBytes(Message message) {
        return MAX_CONTENT_BYTES;
    }

    @Override
    protected String buildUrl(Message message, ChannelConfig config) {
        String url = requiredWebhook(config);
        String secret = config.secret();
        if (secret != null && !secret.isEmpty()) {
            long timestamp = System.currentTimeMillis();
            String sign = NotifyUtils.base64(NotifyUtils.hmacSha256(secret, timestamp + "\n" + secret));
            url = NotifyUtils.appendQuery(url, "timestamp", String.valueOf(timestamp));
            url = NotifyUtils.appendQuery(url, "sign", NotifyUtils.urlEncode(sign));
        }
        return url;
    }

    @Override
    protected String buildPayload(Message message, ChannelConfig config) {
        List<String> atMobiles = message.extraStrings(Message.EXTRA_AT_MOBILES);
        List<String> atUserIds = message.extraStrings(Message.EXTRA_AT_USERIDS);
        boolean atAll = message.extraBoolean(Message.EXTRA_AT_ALL, false);
        String content = withInlineAt(message.content(), atMobiles);

        Map<String, Object> root = NotifyUtils.map();
        if (message.type() == MessageType.ACTION_CARD) {
            Map<String, Object> card = NotifyUtils.map();
            card.put("title", message.title("通知"));
            card.put("text", content);
            card.put("btnOrientation", message.extraBoolean(Message.EXTRA_BTN_VERTICAL, false) ? "0" : "1");
            String btnTitle = message.extraString(Message.EXTRA_BTN_TITLE);
            String btnUrl = message.extraString(Message.EXTRA_URL);
            if (btnTitle != null && !btnTitle.isEmpty() && btnUrl != null && !btnUrl.isEmpty()) {
                card.put("singleTitle", btnTitle);
                card.put("singleURL", btnUrl);
            }
            root.put("msgtype", "actionCard");
            root.put("actionCard", card);
        } else if (message.type() == MessageType.NEWS) {
            String jump = message.extraString(Message.EXTRA_URL);
            if (jump == null || jump.isEmpty()) {
                throw new IllegalArgumentException("dingtalk news url is required (Message.EXTRA_URL)");
            }
            Map<String, Object> link = NotifyUtils.map();
            link.put("title", message.title("通知"));
            link.put("messageURL", jump);
            String pic = message.extraString(Message.EXTRA_PIC_URL);
            if (pic != null && !pic.isEmpty()) {
                link.put("picURL", pic);
            }
            List<Map<String, Object>> links = new ArrayList<Map<String, Object>>();
            links.add(link);
            Map<String, Object> feed = NotifyUtils.map();
            feed.put("links", links);
            root.put("msgtype", "feedCard");
            root.put("feedCard", feed);
        } else if (message.type() == MessageType.IMAGE) {
            String pic = message.extraString(Message.EXTRA_PIC_URL);
            if (pic == null || pic.isEmpty()) {
                throw new IllegalArgumentException(
                        "dingtalk image requires a public picUrl (group bot cannot upload files)");
            }
            Map<String, Object> markdown = NotifyUtils.map();
            markdown.put("title", message.title("图片"));
            markdown.put("text", "![" + message.title("图片") + "](" + pic + ")\n" + pic);
            root.put("msgtype", "markdown");
            root.put("markdown", markdown);
        } else if (message.type() == MessageType.MARKDOWN) {
            Map<String, Object> markdown = NotifyUtils.map();
            markdown.put("title", message.title("通知"));
            markdown.put("text", content);
            root.put("msgtype", "markdown");
            root.put("markdown", markdown);
        } else {
            Map<String, Object> text = NotifyUtils.map();
            text.put("content", content);
            root.put("msgtype", "text");
            root.put("text", text);
        }
        Map<String, Object> at = NotifyUtils.map();
        if (atMobiles != null && !atMobiles.isEmpty()) {
            at.put("atMobiles", atMobiles);
        }
        if (atUserIds != null && !atUserIds.isEmpty()) {
            at.put("atUserIds", atUserIds);
        }
        if (atAll) {
            at.put("isAtAll", Boolean.TRUE);
        }
        if (!at.isEmpty()) {
            root.put("at", at);
        }
        return NotifyUtils.toJson(root);
    }

    /**
     * 先为尚未出现的 {@code @手机号} 预留字节再截断，避免超长正文把 @ 顶出上限。
     *
     * @param content 原文
     * @param atMobiles 要 @ 的手机号
     * @return 截断并补齐 @ 之后的正文
     */
    protected String withInlineAt(String content, List<String> atMobiles) {
        String suffix = missingAtSuffix(content, atMobiles);
        String truncated = limitedContent(content, MAX_CONTENT_BYTES, NotifyUtils.utf8Length(suffix));
        if (suffix.isEmpty()) {
            return truncated;
        }
        return truncated + suffix;
    }

    /**
     * 把 {@code atMobiles} 里尚未出现在正文中的手机号以 {@code @手机号} 形式追加到正文末尾。
     *
     * @param content 正文
     * @param atMobiles 要 @ 的手机号，可为 {@code null}
     * @return 补齐 @ 之后的正文
     */
    protected String inlineAtMobiles(String content, List<String> atMobiles) {
        String suffix = missingAtSuffix(content, atMobiles);
        if (suffix.isEmpty()) {
            return content;
        }
        return (content == null ? "" : content) + suffix;
    }

    private static String missingAtSuffix(String content, List<String> atMobiles) {
        if (atMobiles == null || atMobiles.isEmpty()) {
            return "";
        }
        String haystack = content == null ? "" : content;
        StringBuilder suffix = new StringBuilder();
        for (String mobile : atMobiles) {
            if (mobile == null || mobile.isEmpty()) {
                continue;
            }
            if (haystack.indexOf(mobile) < 0) {
                suffix.append(" @").append(mobile);
            }
        }
        return suffix.toString();
    }

    @Override
    protected boolean isAccepted(int httpStatus, String responseBody) {
        return jsonIntEquals(responseBody, "errcode", 0);
    }

    @Override
    protected FailureType classify(int httpStatus, String responseBody) {
        Integer errcode = NotifyUtils.jsonInt(responseBody, "errcode");
        if (errcode != null) {
            switch (errcode) {
                case 130101:
                    // 发送过于频繁（20 条/分钟），等更久再试，不要判定凭证失效
                    return FailureType.THROTTLED;
                case 300001:
                    // token 无效
                    return FailureType.CONFIG_ERROR;
                case 310000:
                    // 加签不匹配 / 自定义关键词未命中 / IP 白名单不通过
                    return FailureType.CONFIG_ERROR;
                default:
                    break;
            }
        }
        return super.classify(httpStatus, responseBody);
    }

    @Override
    protected String errorMessage(int httpStatus, String responseBody) {
        String mapped = jsonCodeMessage("dingtalk errcode", responseBody, "errcode", "errmsg");
        return mapped != null ? mapped : super.errorMessage(httpStatus, responseBody);
    }

    /**
     * 校验并返回 webhook 地址。
     *
     * @param config 渠道配置
     * @return webhook 地址
     */
    protected String requiredWebhook(ChannelConfig config) {
        String webhook = config.webhook();
        if (webhook == null || webhook.isEmpty()) {
            throw new IllegalArgumentException("dingtalk webhook is required (ChannelConfig.webhook)");
        }
        return webhook;
    }
}
