package com.alianga.jkit.notify.channel;

import com.alianga.jkit.notify.ChannelConfig;
import com.alianga.jkit.notify.FailureType;
import com.alianga.jkit.notify.Message;
import com.alianga.jkit.notify.NotifyUtils;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Map;
import java.util.SimpleTimeZone;

/**
 * 腾讯云短信渠道（参考 WePush 的 TxYun3MsgSender，API 版本 2021-01-11，TC3-HMAC-SHA256 签名）。
 *
 * <p>配置：{@link ChannelConfig#ofToken(String)} 创建，token 填 SecretId，
 * {@link ChannelConfig#secret(String)} 填 SecretKey；{@code ChannelConfig.extra(CFG_APP_ID, ...)}
 * 填 SdkAppId（如 {@code 1400006666}）；{@link ChannelConfig#name(String)} 填短信签名内容；
 * {@code extra(CFG_TEMPLATE, ...)} 填模板 ID；{@link ChannelConfig#to(String...)} 填手机号
 * （国内号码直接填，如 {@code 13711112222}）。
 * {@code extra(CFG_REGION, ...)} 覆盖地域（默认 {@code ap-guangzhou}）。
 *
 * <p>消息：TEXT。模板参数用 {@link AbstractSmsChannel#EXTRA_SMS_PARAMS}，有序参数风格：
 * {@code .extra(AbstractSmsChannel.EXTRA_SMS_PARAMS, "1235,10")} 按模板
 * {@code 您的验证码为{1}，{2}分钟内有效} 顺序填入。
 *
 * <p>响应判定：HTTP 200 且 JSON {@code SendStatusSet[0].Code == "Ok"}。
 *
 * @author 郑明亮
 * @since 2.0.1
 */
public class TencentSmsChannel extends AbstractSmsChannel {
    /**
     * 渠道 id。
     */
    public static final String ID = "sms-tencent";

    private static final String HOST = "sms.tencentcloudapi.com";
    private static final String SERVICE = "sms";
    private static final String API_VERSION = "2021-01-11";
    private static final String DEFAULT_REGION = "ap-guangzhou";
    private static final String CONTENT_TYPE = "application/json; charset=utf-8";

    @Override
    public String id() {
        return ID;
    }

    @Override
    public String name() {
        return "腾讯云短信";
    }

    @Override
    protected String[] usedConfigKeys() {
        return new String[]{"token", "secret", "appId", "name", "template", "region", "to", "timeoutMs"};
    }

    @Override
    protected String buildUrl(Message message, ChannelConfig config) {
        requireCredentials(config);
        String override = config.webhook();
        if (override != null && !override.trim().isEmpty()) {
            return override.trim();
        }
        return "https://" + HOST + "/";
    }

    @Override
    protected String buildPayload(Message message, ChannelConfig config) {
        requireCredentials(config);
        String sdkAppId = smsAppId(config);
        if (sdkAppId == null || sdkAppId.isEmpty()) {
            throw new IllegalArgumentException(
                    "tencent sms SdkAppId is required (ChannelConfig.extra(" + CFG_APP_ID + "))");
        }
        Map<String, Object> payload = NotifyUtils.map();
        payload.put("PhoneNumberSet", new String[]{e164(currentReceiver(message))});
        payload.put("SmsSdkAppId", sdkAppId);
        payload.put("SignName", config.name());
        payload.put("TemplateId", requiredTemplate(config, id()));
        payload.put("TemplateParamSet", positionalParams(message).toArray(new String[0]));
        return NotifyUtils.toJson(payload);
    }

    @Override
    protected void applyHeaders(com.alianga.jkit.http.HttpRequest request, ChannelConfig config,
                                String payload) {
        super.applyHeaders(request, config, payload);
        String timestamp = String.valueOf(System.currentTimeMillis() / 1000L);
        String date = utcDate(timestamp);
        String secretKey = config.secret();

        // ---- TC3-HMAC-SHA256 ----
        // host 取实际请求地址（webhookUrl 可覆盖官方域名，签名必须与之一致）
        String host = hostOf(request.getUrl());
        String canonicalRequest = "POST\n/\n\n" + "content-type:" + CONTENT_TYPE + "\n"
                + "host:" + host + "\n" + "x-tc-action:sendsms\n" + "\n"
                + "content-type;host;x-tc-action\n" + sha256Hex(payload);
        String credentialScope = date + "/" + regionOf(config) + "/" + SERVICE + "/tc3_request";
        String stringToSign = "TC3-HMAC-SHA256\n" + timestamp + "\n" + credentialScope + "\n"
                + sha256Hex(canonicalRequest);
        byte[] kDate = hmacSha256(("TC3" + secretKey).getBytes(StandardCharsets.UTF_8), date);
        byte[] kService = hmacSha256(kDate, SERVICE);
        byte[] kSigning = hmacSha256(kService, "tc3_request");
        String signature = toHex(hmacSha256(kSigning, stringToSign));

        request.header("Content-Type", CONTENT_TYPE);
        request.header("X-TC-Action", "SendSms");
        request.header("X-TC-Version", API_VERSION);
        request.header("X-TC-Timestamp", timestamp);
        request.header("X-TC-Region", regionOf(config));
        request.header("Authorization", "TC3-HMAC-SHA256 Credential=" + config.token() + "/"
                + credentialScope + ", SignedHeaders=content-type;host;x-tc-action, Signature=" + signature);
    }

    private static String hostOf(String url) {
        String host = url == null ? "" : url.trim();
        int scheme = host.indexOf("://");
        if (scheme >= 0) {
            host = host.substring(scheme + 3);
        }
        int slash = host.indexOf('/');
        return slash < 0 ? host : host.substring(0, slash);
    }

    private String regionOf(ChannelConfig config) {
        String region = smsRegion(config);
        return region == null || region.isEmpty() ? DEFAULT_REGION : region;
    }

    private static void requireCredentials(ChannelConfig config) {
        if (config.token() == null || config.token().isEmpty()) {
            throw new IllegalArgumentException("tencent sms SecretId is required (ChannelConfig.token)");
        }
        if (config.secret() == null || config.secret().isEmpty()) {
            throw new IllegalArgumentException("tencent sms SecretKey is required (ChannelConfig.secret)");
        }
    }

    private static String e164(String receiver) {
        // 国内号码补 +86；已带 + 或双前缀的按 E.164 原样
        if (receiver.startsWith("+")) {
            return receiver;
        }
        if (receiver.startsWith("86") && receiver.length() >= 13) {
            return "+" + receiver;
        }
        return "+86" + receiver;
    }

    private static String utcDate(String epochSeconds) {
        SimpleDateFormat format = new SimpleDateFormat("yyyy-MM-dd");
        format.setTimeZone(new SimpleTimeZone(0, "UTC"));
        return format.format(new Date(Long.parseLong(epochSeconds) * 1000L));
    }

    private static byte[] hmacSha256(byte[] key, String data) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new IllegalStateException("HmacSHA256 unavailable", e);
        }
    }

    private static String sha256Hex(String data) {
        return NotifyUtils.sha256Hex(data.getBytes(StandardCharsets.UTF_8));
    }

    private static String toHex(byte[] bytes) {
        StringBuilder out = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            out.append(String.format("%02x", b & 0xff));
        }
        return out.toString();
    }

    @Override
    protected boolean isAccepted(int httpStatus, String responseBody) {
        if (httpStatus < 200 || httpStatus >= 300) {
            return false;
        }
        String code = sendStatusCode(responseBody);
        return code != null && "Ok".equals(code);
    }

    @Override
    protected FailureType classify(int httpStatus, String responseBody) {
        String code = sendStatusCode(responseBody);
        if (code != null && !"Ok".equals(code)) {
            if ("LimitExceeded.DeliveryFrequency".equals(code)
                    || "LimitExceeded.PhoneNumberOneHourLimit".equals(code)
                    || "LimitExceeded.PhoneNumberDailyLimit".equals(code)) {
                return FailureType.THROTTLED;
            }
            if ("AuthFailure.SignatureFailure".equals(code) || "AuthFailure.SecretIdNotFound".equals(code)
                    || "AuthFailure.SignatureExpire".equals(code)
                    || "InvalidParameterValue.TemplateId".equals(code)
                    || "InvalidParameterValue.SmsAppId".equals(code)
                    || "UnauthorizedOperation.SmsSdkAppIdVerifyFail".equals(code)
                    || "FailedOperation.SignatureIncorrectOrUnapproved".equals(code)
                    || "FailedOperation.TemplateIncorrectOrUnapproved".equals(code)) {
                return FailureType.CONFIG_ERROR;
            }
        }
        return super.classify(httpStatus, responseBody);
    }

    @Override
    protected String errorMessage(int httpStatus, String responseBody) {
        String code = sendStatusCode(responseBody);
        String message = sendStatusMessage(responseBody);
        if (code != null) {
            return "tencent sms " + code + (message == null ? "" : ": " + message);
        }
        return super.errorMessage(httpStatus, responseBody);
    }

    private static String sendStatusCode(String responseBody) {
        return sendStatusField(responseBody, "Code");
    }

    private static String sendStatusMessage(String responseBody) {
        return sendStatusField(responseBody, "Message");
    }

    /**
     * 容错取 {@code Response.SendStatusSet[0].inner}；非 JSON 响应返回 {@code null}。
     */
    private static String sendStatusField(String json, String inner) {
        Object parsed = NotifyUtils.parseJson(json);
        if (!(parsed instanceof Map)) {
            return null;
        }
        Object response = ((Map<?, ?>) parsed).get("Response");
        if (!(response instanceof Map)) {
            return null;
        }
        Object statusSet = ((Map<?, ?>) response).get("SendStatusSet");
        if (!(statusSet instanceof java.util.List) || ((java.util.List<?>) statusSet).isEmpty()) {
            return null;
        }
        Object first = ((java.util.List<?>) statusSet).get(0);
        if (first instanceof Map) {
            Object value = ((Map<?, ?>) first).get(inner);
            return value == null ? null : String.valueOf(value);
        }
        return null;
    }
}
