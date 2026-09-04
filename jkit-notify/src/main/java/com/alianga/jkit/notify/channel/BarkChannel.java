package com.alianga.jkit.notify.channel;

import com.alianga.jkit.notify.ChannelConfig;
import com.alianga.jkit.notify.FailureType;
import com.alianga.jkit.notify.Message;
import com.alianga.jkit.notify.MessageType;
import com.alianga.jkit.notify.NotifyUtils;

/**
 * Bark 渠道（iOS 推送）。
 *
 * <p>配置：{@link ChannelConfig#token(String)} 填 device_key，
 * {@link ChannelConfig#webhook(String)} 可覆盖自建服务地址（默认 {@code https://api.day.app}）。
 *
 * <p>消息：TEXT / MARKDOWN（Bark 不区分富文本，均按通知正文发送）。
 * 铃声/分组/时效性/跳转地址走 {@link Message#EXTRA_SOUND}、{@link Message#EXTRA_GROUP}、
 * {@link Message#EXTRA_LEVEL}、{@link Message#EXTRA_URL}。
 *
 * <p>响应判定：HTTP 200 且 JSON {@code code == 200}。
 *
 * @author 郑明亮
 * @since 2.0.1
 */
public class BarkChannel extends AbstractHttpChannel {
    /**
     * 渠道 id。
     */
    public static final String ID = "bark";

    private static final String DEFAULT_ENDPOINT = "https://api.day.app";

    @Override
    public String id() {
        return ID;
    }

    @Override
    public String name() {
        return "Bark";
    }

    @Override
    public boolean supports(MessageType type) {
        return type == MessageType.TEXT || type == MessageType.MARKDOWN;
    }

    @Override
    protected String buildUrl(Message message, ChannelConfig config) {
        String base = config.webhook();
        if (base == null || base.isEmpty()) {
            base = DEFAULT_ENDPOINT;
        }
        if (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        return base + "/push";
    }

    @Override
    protected String buildPayload(Message message, ChannelConfig config) {
        String deviceKey = config.token();
        if (deviceKey == null || deviceKey.isEmpty()) {
            throw new IllegalArgumentException("bark device_key is required (ChannelConfig.token)");
        }
        StringBuilder payload = new StringBuilder();
        payload.append("{\"device_key\":\"").append(NotifyUtils.jsonEscape(deviceKey)).append('"');
        payload.append(",\"title\":\"").append(NotifyUtils.jsonEscape(message.title("通知"))).append('"');
        payload.append(",\"body\":\"").append(NotifyUtils.jsonEscape(limitedContent(message))).append('"');
        appendIfPresent(payload, "sound", message.extraString(Message.EXTRA_SOUND));
        appendIfPresent(payload, "group", message.extraString(Message.EXTRA_GROUP));
        appendIfPresent(payload, "level", message.extraString(Message.EXTRA_LEVEL));
        appendIfPresent(payload, "url", message.extraString(Message.EXTRA_URL));
        payload.append('}');
        return payload.toString();
    }

    private static void appendIfPresent(StringBuilder payload, String key, String value) {
        if (value != null && !value.isEmpty()) {
            payload.append(",\"").append(key).append("\":\"")
                    .append(NotifyUtils.jsonEscape(value)).append('"');
        }
    }

    @Override
    protected boolean isAccepted(int httpStatus, String responseBody) {
        if (httpStatus != 200) {
            return false;
        }
        Integer code = NotifyUtils.jsonInt(responseBody, "code");
        return code != null && code == 200;
    }

    @Override
    protected FailureType classify(int httpStatus, String responseBody) {
        Integer code = NotifyUtils.jsonInt(responseBody, "code");
        if (code != null && code == 400) {
            // device key 不存在
            return FailureType.CONFIG_ERROR;
        }
        return super.classify(httpStatus, responseBody);
    }

    @Override
    protected String errorMessage(int httpStatus, String responseBody) {
        Integer code = NotifyUtils.jsonInt(responseBody, "code");
        String message = NotifyUtils.jsonString(responseBody, "message");
        if (code != null && message != null) {
            return "bark code " + code + ": " + message;
        }
        return super.errorMessage(httpStatus, responseBody);
    }
}
