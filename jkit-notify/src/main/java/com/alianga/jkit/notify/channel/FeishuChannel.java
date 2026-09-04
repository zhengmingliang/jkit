package com.alianga.jkit.notify.channel;

import com.alianga.jkit.notify.ChannelConfig;
import com.alianga.jkit.notify.FailureType;
import com.alianga.jkit.notify.Message;
import com.alianga.jkit.notify.MessageType;
import com.alianga.jkit.notify.NotifyUtils;

/**
 * 飞书自定义机器人渠道。
 *
 * <p>配置：{@link ChannelConfig#webhook(String)} 填群机器人 webhook 地址；
 * 安全设置选"签名校验"时再 {@link ChannelConfig#secret(String)} 填密钥。
 *
 * <p>消息：TEXT / MARKDOWN。TEXT 走 {@code msg_type=text}；MARKDOWN 转
 * {@code msg_type=interactive} 卡片（lark_md），标题取消息标题。
 *
 * <p><b>签名与钉钉恰好相反</b>：飞书用**秒**级时间戳，HMAC-SHA256 的**密钥**是
 * {@code timestamp + "\n" + secret} 而**待签数据为空串**；钉钉是密钥为 secret 本身、
 * 待签数据为 {@code timestamp + "\n" + secret}，且用毫秒。两者写法互换必然签名失败。
 *
 * <p>响应判定：HTTP 200 且业务码为 0（兼容 {@code code} 与 {@code StatusCode} 两种字段名）。
 *
 * @author 郑明亮
 * @since 2.0.1
 */
public class FeishuChannel extends AbstractHttpChannel {
    /**
     * 渠道 id。
     */
    public static final String ID = "feishu";

    @Override
    public String id() {
        return ID;
    }

    @Override
    public String name() {
        return "飞书机器人";
    }

    @Override
    public boolean supports(MessageType type) {
        return type == MessageType.TEXT || type == MessageType.MARKDOWN;
    }

    @Override
    protected String buildUrl(Message message, ChannelConfig config) {
        String webhook = config.webhook();
        if (webhook == null || webhook.isEmpty()) {
            throw new IllegalArgumentException("feishu webhook is required (ChannelConfig.webhook)");
        }
        String secret = config.secret();
        if (secret != null && !secret.isEmpty()) {
            long timestamp = System.currentTimeMillis() / 1000L;
            String sign = NotifyUtils.base64(
                    NotifyUtils.hmacSha256(timestamp + "\n" + secret, ""));
            webhook = NotifyUtils.appendQuery(webhook, "timestamp", String.valueOf(timestamp));
            webhook = NotifyUtils.appendQuery(webhook, "sign", NotifyUtils.urlEncode(sign));
        }
        return webhook;
    }

    @Override
    protected String buildPayload(Message message, ChannelConfig config) {
        String escaped = NotifyUtils.jsonEscape(message.content());
        if (message.type() == MessageType.MARKDOWN) {
            return "{\"msg_type\":\"interactive\",\"card\":{\"header\":{\"title\":{\"tag\":\"plain_text\","
                    + "\"content\":\"" + NotifyUtils.jsonEscape(message.title("通知")) + "\"}},"
                    + "\"elements\":[{\"tag\":\"div\",\"text\":{\"tag\":\"lark_md\",\"content\":\""
                    + escaped + "\"}}]}}";
        }
        return "{\"msg_type\":\"text\",\"content\":{\"text\":\"" + escaped + "\"}}";
    }

    @Override
    protected boolean isAccepted(int httpStatus, String responseBody) {
        Integer code = NotifyUtils.jsonInt(responseBody, "code");
        if (code == null) {
            code = NotifyUtils.jsonInt(responseBody, "StatusCode");
        }
        return code != null && code == 0;
    }

    @Override
    protected FailureType classify(int httpStatus, String responseBody) {
        Integer code = NotifyUtils.jsonInt(responseBody, "code");
        if (code == null) {
            code = NotifyUtils.jsonInt(responseBody, "StatusCode");
        }
        if (code != null) {
            switch (code) {
                case 9499:
                    // too many request
                    return FailureType.THROTTLED;
                case 19021:
                    // sign match fail
                    return FailureType.CONFIG_ERROR;
                case 19001:
                case 19003:
                    // webhook 地址 / 参数非法
                    return FailureType.CONFIG_ERROR;
                default:
                    break;
            }
        }
        return super.classify(httpStatus, responseBody);
    }

    @Override
    protected String errorMessage(int httpStatus, String responseBody) {
        Integer code = NotifyUtils.jsonInt(responseBody, "code");
        String msg = NotifyUtils.jsonString(responseBody, "msg");
        if (code == null) {
            code = NotifyUtils.jsonInt(responseBody, "StatusCode");
        }
        if (msg == null) {
            msg = NotifyUtils.jsonString(responseBody, "StatusMessage");
        }
        if (code != null && msg != null) {
            return "feishu code " + code + ": " + msg;
        }
        return super.errorMessage(httpStatus, responseBody);
    }
}
