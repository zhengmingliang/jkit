package com.alianga.jkit.notify.channel;

import com.alianga.jkit.notify.ChannelConfig;
import com.alianga.jkit.notify.FailureType;
import com.alianga.jkit.notify.Message;
import com.alianga.jkit.notify.NotifyUtils;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.text.SimpleDateFormat;
import java.util.Base64;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.SimpleTimeZone;

/**
 * 华为云短信渠道（参考 WePush 的 HwYunMsgSender，X-WSSE 鉴权）。
 *
 * <p>配置：{@link ChannelConfig#ofToken(String)} 创建，token 填 App Key；
 * {@link ChannelConfig#secret(String)} 填 App Secret；{@code ChannelConfig.extra(CFG_APP_ID, ...)}
 * 填短信通道号（sender，国内签名通道号如 {@code 8823120512345}）；
 * {@link ChannelConfig#name(String)} 填签名名称（通用模板时必填）；
 * {@code ChannelConfig.extra(CFG_TEMPLATE, ...)} 填模板 ID；{@link ChannelConfig#to(String...)} 填手机号。
 * {@link ChannelConfig#webhookUrl(String)} 填 APP 接入地址
 * （如 {@code https://smsapi.cn-north-4.myhuaweicloud.com:443/sms/batchSendSmsV1}，必填）。
 *
 * <p>消息：TEXT。模板参数用 {@link AbstractSmsChannel#EXTRA_SMS_PARAMS}，有序参数风格：
 * {@code .extra(AbstractSmsChannel.EXTRA_SMS_PARAMS, "1235,10")}。参数值不含 {@code {"}} 时按
 * 字符串参数发送；模板无参数时可不配。
 *
 * <p>响应判定：HTTP 200 且 JSON {@code code == "000000"}（华为云成功码）。
 *
 * @author 郑明亮
 * @since 2.0.1
 */
public class HuaweiSmsChannel extends AbstractSmsChannel {
    /**
     * 渠道 id。
     */
    public static final String ID = "sms-huawei";

    private static final String WSSE_HEADER_FORMAT =
            "UsernameToken Username=\"%s\",PasswordDigest=\"%s\",Nonce=\"%s\",Created=\"%s\"";
    private static final String AUTH_HEADER_VALUE = "WSSE realm=\"SDP\",profile=\"UsernameToken\",type=\"Appkey\"";

    @Override
    public String id() {
        return ID;
    }

    @Override
    public String name() {
        return "华为云短信";
    }

    @Override
    protected String[] usedConfigKeys() {
        return new String[]{"token", "secret", "appId", "name", "template", "webhook", "to", "timeoutMs"};
    }

    @Override
    protected String buildUrl(Message message, ChannelConfig config) {
        String url = config.webhook();
        if (url == null || url.isEmpty()) {
            throw new IllegalArgumentException(
                    "huawei sms endpoint is required (ChannelConfig.webhook, e.g."
                            + " https://smsapi.cn-north-4.myhuaweicloud.com:443/sms/batchSendSmsV1)");
        }
        if (config.token() == null || config.token().isEmpty()) {
            throw new IllegalArgumentException("huawei sms appKey is required (ChannelConfig.token)");
        }
        if (config.secret() == null || config.secret().isEmpty()) {
            throw new IllegalArgumentException("huawei sms appSecret is required (ChannelConfig.secret)");
        }
        String sender = smsAppId(config);
        if (sender == null || sender.isEmpty()) {
            throw new IllegalArgumentException(
                    "huawei sms sender is required (ChannelConfig.extra(" + CFG_APP_ID + "))");
        }
        requiredTemplate(config, id());
        return url;
    }

    @Override
    protected String buildPayload(Message message, ChannelConfig config) {
        Map<String, String> form = NotifyUtils.strMap();
        form.put("from", smsAppId(config));
        form.put("to", currentReceiver(message));
        form.put("templateId", requiredTemplate(config, id()));
        List<String> paras = positionalParams(message);
        if (!paras.isEmpty()) {
            form.put("templateParas", NotifyUtils.toJson(paras));
        }
        String signature = config.name();
        if (signature != null && !signature.isEmpty()) {
            form.put("signature", signature);
        }
        return NotifyUtils.formEncode(form);
    }

    @Override
    protected String contentType(Message message, ChannelConfig config) {
        return "application/x-www-form-urlencoded";
    }

    @Override
    protected void applyHeaders(com.alianga.jkit.http.HttpRequest request, ChannelConfig config,
                                String payload) {
        super.applyHeaders(request, config, payload);
        request.header("Authorization", AUTH_HEADER_VALUE);
        request.header("X-WSSE", buildWsseHeader(config.token(), config.secret()));
    }

    /**
     * 构造 X-WSSE 鉴权头：Nonce + Created + AppSecret 做 SHA-256，hex 后再 Base64。
     *
     * @param appKey App Key
     * @param appSecret App Secret
     * @return X-WSSE 请求头值
     */
    public static String buildWsseHeader(String appKey, String appSecret) {
        String created = utcTimestamp();
        String nonce = NotifyUtils.uuid();
        String digestInput = nonce + created + appSecret;
        String hexDigest = sha256Hex(digestInput.getBytes(StandardCharsets.UTF_8));
        String passwordDigest = Base64.getEncoder()
                .encodeToString(hexDigest.getBytes(StandardCharsets.UTF_8));
        return String.format(WSSE_HEADER_FORMAT, appKey, passwordDigest, nonce, created);
    }

    private static String utcTimestamp() {
        SimpleDateFormat format = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'");
        format.setTimeZone(new SimpleTimeZone(0, "UTC"));
        return format.format(new Date());
    }

    private static String sha256Hex(byte[] data) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(data);
            StringBuilder out = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                out.append(String.format("%02x", b & 0xff));
            }
            return out.toString();
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    @Override
    protected boolean isAccepted(int httpStatus, String responseBody) {
        if (httpStatus < 200 || httpStatus >= 300) {
            return false;
        }
        String code = NotifyUtils.jsonString(responseBody, "code");
        return "000000".equals(code);
    }

    @Override
    protected FailureType classify(int httpStatus, String responseBody) {
        String code = NotifyUtils.jsonString(responseBody, "code");
        if (code != null && !"000000".equals(code)) {
            if ("E200037".equals(code)) {
                // 发送短信频率过高
                return FailureType.THROTTLED;
            }
            if ("E000102".equals(code) || "E000109".equals(code) || "E000111".equals(code)
                    || "E000620".equals(code) || "E000621".equals(code) || "E000623".equals(code)
                    || "E200029".equals(code) || "E200030".equals(code) || "E200031".equals(code)) {
                // 鉴权、通道号、模板、签名等配置类错误
                return FailureType.CONFIG_ERROR;
            }
        }
        return super.classify(httpStatus, responseBody);
    }

    @Override
    protected String errorMessage(int httpStatus, String responseBody) {
        String code = NotifyUtils.jsonString(responseBody, "code");
        String detail = NotifyUtils.jsonString(responseBody, "description");
        if (code != null && detail != null) {
            return "huawei sms " + code + ": " + detail;
        }
        return super.errorMessage(httpStatus, responseBody);
    }
}
