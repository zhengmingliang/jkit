package com.alianga.jkit.notify.channel;

import com.alianga.jkit.notify.ChannelConfig;
import com.alianga.jkit.notify.FailureType;
import com.alianga.jkit.notify.Message;
import com.alianga.jkit.notify.MessageType;
import com.alianga.jkit.notify.NotifyUtils;

import java.util.Map;

/**
 * Bark 渠道（iOS 推送）。
 *
 * <p>配置：{@link ChannelConfig#token(String)} 填 device_key，
 * {@link ChannelConfig#webhook(String)} 可覆盖自建服务地址（默认 {@code https://api.day.app}）。
 *
 * <p>消息：TEXT / MARKDOWN（Bark 不区分富文本，均按通知正文发送）。
 * 铃声/分组/时效性/跳转地址走 {@link Message#EXTRA_SOUND}、{@link Message#EXTRA_GROUP}、
 * {@link Message#EXTRA_LEVEL}、{@link Message#EXTRA_URL}。
 * 正文按 UTF-8 {@value #MAX_BODY_BYTES} 字节上限自动截断。
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

    /**
     * 正文 UTF-8 字节上限。
     */
    public static final int MAX_BODY_BYTES = 4096;

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
    protected String[] usedConfigKeys() {
        return new String[]{"token", "webhook", "timeoutMs"};
    }

    @Override
    protected int contentMaxBytes(Message message) {
        return MAX_BODY_BYTES;
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
        Map<String, Object> payload = NotifyUtils.map();
        payload.put("device_key", deviceKey);
        payload.put("title", message.title("通知"));
        payload.put("body", limitedContent(message));
        putIfPresent(payload, "sound", message.extraString(Message.EXTRA_SOUND));
        putIfPresent(payload, "group", message.extraString(Message.EXTRA_GROUP));
        putIfPresent(payload, "level", message.extraString(Message.EXTRA_LEVEL));
        putIfPresent(payload, "url", message.extraString(Message.EXTRA_URL));
        return NotifyUtils.toJson(payload);
    }

    private static void putIfPresent(Map<String, Object> payload, String key, String value) {
        if (value != null && !value.isEmpty()) {
            payload.put(key, value);
        }
    }

    @Override
    protected boolean isAccepted(int httpStatus, String responseBody) {
        return httpStatus == 200 && jsonIntEquals(responseBody, "code", 200);
    }

    @Override
    protected FailureType classify(int httpStatus, String responseBody) {
        if (jsonIntEquals(responseBody, "code", 400)) {
            // device key 不存在
            return FailureType.CONFIG_ERROR;
        }
        return super.classify(httpStatus, responseBody);
    }

    @Override
    protected String errorMessage(int httpStatus, String responseBody) {
        String mapped = jsonCodeMessage("bark code", responseBody, "code", "message");
        return mapped != null ? mapped : super.errorMessage(httpStatus, responseBody);
    }
}
