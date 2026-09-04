package com.alianga.jkit.notify.channel;

import com.alianga.jkit.notify.ChannelConfig;
import com.alianga.jkit.notify.FailureType;
import com.alianga.jkit.notify.Message;
import com.alianga.jkit.notify.MessageType;
import com.alianga.jkit.notify.NotifyUtils;

import java.util.List;

/**
 * 钉钉自定义机器人渠道。
 *
 * <p>配置：{@link ChannelConfig#webhook(String)} 填机器人 webhook 地址（含 access_token），
 * 安全设置选"加签"时再 {@link ChannelConfig#secret(String)} 填 SEC 开头的密钥。
 *
 * <p>消息：TEXT / MARKDOWN。正文按 UTF-8 {@value #MAX_CONTENT_BYTES} 字节上限自动截断。
 *
 * <p><b>@人的关键规则</b>：钉钉光有 {@code at.atMobiles} 数组**不会**高亮提醒，被 @ 的手机号
 * 必须以字面文本出现在正文里，否则静默失效。本渠道会自动把缺失的 {@code @手机号} 追加到正文末尾
 * （已出现的不重复追加）。钉钉按子串匹配，手机号外面套 markdown 装饰（如 {@code **138...**}）
 * 也能生效。@所有人走 {@link Message#EXTRA_AT_ALL}，默认关闭。
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
        return type == MessageType.TEXT || type == MessageType.MARKDOWN;
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
        boolean atAll = message.extraBoolean(Message.EXTRA_AT_ALL, false);
        StringBuilder at = new StringBuilder();
        boolean hasAt = (atMobiles != null && !atMobiles.isEmpty()) || atAll;
        if (hasAt) {
            at.append(",\"at\":{");
            if (atMobiles != null && !atMobiles.isEmpty()) {
                at.append("\"atMobiles\":[");
                for (int i = 0; i < atMobiles.size(); i++) {
                    if (i > 0) {
                        at.append(',');
                    }
                    at.append('"').append(NotifyUtils.jsonEscape(atMobiles.get(i))).append('"');
                }
                at.append(']');
            }
            if (atAll) {
                if (at.charAt(at.length() - 1) != '{') {
                    at.append(',');
                }
                at.append("\"isAtAll\":true");
            }
            at.append('}');
        }
        String escaped = NotifyUtils.jsonEscape(inlineAtMobiles(limitedContent(message), atMobiles));
        if (message.type() == MessageType.MARKDOWN) {
            return "{\"msgtype\":\"markdown\",\"markdown\":{\"title\":\""
                    + NotifyUtils.jsonEscape(message.title("通知")) + "\",\"text\":\"" + escaped + "\"}"
                    + at + "}";
        }
        return "{\"msgtype\":\"text\",\"text\":{\"content\":\"" + escaped + "\"}" + at + "}";
    }

    /**
     * 把 {@code atMobiles} 里尚未出现在正文中的手机号以 {@code @手机号} 形式追加到正文末尾。
     *
     * <p>钉钉的 at 数组只是"谁被 @"的声明，真正触发提醒靠正文里的字面手机号；已出现的号码
     * 不重复追加，避免正文里出现两次 @。
     *
     * @param content 正文
     * @param atMobiles 要 @ 的手机号，可为 {@code null}
     * @return 补齐 @ 之后的正文
     */
    protected String inlineAtMobiles(String content, List<String> atMobiles) {
        if (atMobiles == null || atMobiles.isEmpty()) {
            return content;
        }
        StringBuilder out = new StringBuilder(content == null ? "" : content);
        for (String mobile : atMobiles) {
            if (mobile == null || mobile.isEmpty()) {
                continue;
            }
            if (out.indexOf(mobile) < 0) {
                out.append(" @").append(mobile);
            }
        }
        return out.toString();
    }

    @Override
    protected boolean isAccepted(int httpStatus, String responseBody) {
        Integer errcode = NotifyUtils.jsonInt(responseBody, "errcode");
        return errcode != null && errcode == 0;
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
        Integer errcode = NotifyUtils.jsonInt(responseBody, "errcode");
        String errmsg = NotifyUtils.jsonString(responseBody, "errmsg");
        if (errcode != null && errmsg != null) {
            return "dingtalk errcode " + errcode + ": " + errmsg;
        }
        return super.errorMessage(httpStatus, responseBody);
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
