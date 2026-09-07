package com.alianga.jkit.notify.channel;

import com.alianga.jkit.notify.ChannelConfig;
import com.alianga.jkit.notify.FailureType;
import com.alianga.jkit.notify.Message;
import com.alianga.jkit.notify.NotifyUtils;

import java.util.Map;

/**
 * 云片短信渠道（参考 WePush 的 YunPianMsgSender，single_send 接口）。
 *
 * <p>配置：{@link ChannelConfig#ofToken(String)} 创建，token 填 APIKEY；
 * {@link ChannelConfig#to(String...)} 填手机号（逗号分隔多个）。
 * {@link ChannelConfig#webhookUrl(String)} 可覆盖接口地址（默认
 * {@code https://sms.yunpian.com/v2/sms/single_send.json}）。
 *
 * <p>消息：TEXT。云片是"全文短信"（不是模板 + 参数），消息正文即短信内容，
 * 签名（如 {@code 【签名】}）由云片后台配置或写在正文里。内容按 UTF-8
 * {@value #MAX_TEXT_BYTES} 字节截断。
 *
 * <p>响应判定：HTTP 200 且 JSON {@code code == 0}。
 *
 * @author 郑明亮
 * @since 2.0.1
 */
public class YunpianSmsChannel extends AbstractSmsChannel {
    /**
     * 渠道 id。
     */
    public static final String ID = "sms-yunpian";

    /**
     * 短信正文 UTF-8 字节上限（单条 67 字符 × 6 条长短信，按 3 字节保守取 1200）。
     */
    public static final int MAX_TEXT_BYTES = 1200;

    private static final String DEFAULT_ENDPOINT =
            "https://sms.yunpian.com/v2/sms/single_send.json";

    @Override
    public String id() {
        return ID;
    }

    @Override
    public String name() {
        return "云片短信";
    }

    @Override
    protected String[] usedConfigKeys() {
        return new String[]{"token", "webhook", "to", "timeoutMs"};
    }

    @Override
    protected int contentMaxBytes(Message message) {
        return MAX_TEXT_BYTES;
    }

    @Override
    protected String buildUrl(Message message, ChannelConfig config) {
        if (config.token() == null || config.token().isEmpty()) {
            throw new IllegalArgumentException("yunpian apikey is required (ChannelConfig.token)");
        }
        String endpoint = config.webhook();
        return endpoint == null || endpoint.isEmpty() ? DEFAULT_ENDPOINT : endpoint;
    }

    @Override
    protected String buildPayload(Message message, ChannelConfig config) {
        Map<String, String> form = NotifyUtils.strMap();
        form.put("apikey", config.token());
        form.put("mobile", currentReceiver(message));
        form.put("text", limitedContent(message));
        return NotifyUtils.formEncode(form);
    }

    @Override
    protected String contentType(Message message, ChannelConfig config) {
        return "application/x-www-form-urlencoded";
    }

    @Override
    protected boolean isAccepted(int httpStatus, String responseBody) {
        if (httpStatus < 200 || httpStatus >= 300) {
            return false;
        }
        Integer code = NotifyUtils.jsonInt(responseBody, "code");
        return code != null && code == 0;
    }

    @Override
    protected FailureType classify(int httpStatus, String responseBody) {
        Integer code = NotifyUtils.jsonInt(responseBody, "code");
        if (code != null) {
            if (code == -4 || code == -5 || code == -52 || code == -53 || code == -54) {
                // 触发频率/数量类限制
                return FailureType.THROTTLED;
            }
            if (code == 1 || code == 2 || code == 3 || code == 4 || code == 7 || code == 8
                    || code == 9 || code == 13 || code == 14 || code == 16 || code == 17
                    || code == 22 || code == 23) {
                // apikey / 模板 / 签名 / 手机号等配置与参数类错误
                return FailureType.CONFIG_ERROR;
            }
        }
        return super.classify(httpStatus, responseBody);
    }

    @Override
    protected String errorMessage(int httpStatus, String responseBody) {
        Integer code = NotifyUtils.jsonInt(responseBody, "code");
        String detail = NotifyUtils.jsonString(responseBody, "msg");
        if (code != null && detail != null) {
            return "yunpian code " + code + ": " + detail;
        }
        return super.errorMessage(httpStatus, responseBody);
    }
}
