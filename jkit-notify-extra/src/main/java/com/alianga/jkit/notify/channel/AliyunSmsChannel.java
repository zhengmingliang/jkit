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
import java.util.TimeZone;
import java.util.TreeMap;

/**
 * 阿里云短信渠道（参考 WePush 的 AliYunMsgSender，官方 API 版本 2017-05-25）。
 *
 * <p>配置：{@link ChannelConfig#ofToken(String)} 创建，token 填 AccessKeyId，
 * {@link ChannelConfig#secret(String)} 填 AccessKeySecret；{@link ChannelConfig#name(String)}
 * 填短信签名（如 {@code 阿里云}）；{@link ChannelConfig#template(String)} 填模板 CODE
 * （如 {@code SMS_123456789}）；{@link ChannelConfig#to(String...)} 填手机号
 * （逗号 / 分号 / 空白分隔多个）。
 *
 * <p>消息：TEXT。模板参数用 {@link AbstractSmsChannel#EXTRA_SMS_PARAMS}，命名参数风格：
 * {@code .extra(AbstractSmsChannel.EXTRA_SMS_PARAMS, "code=9527,min=5")}。
 *
 * <p>响应判定：HTTP 200 且 JSON {@code Code == "OK"}。
 *
 * @author 郑明亮
 * @since 2.0.1
 */
public class AliyunSmsChannel extends AbstractSmsChannel {
    /**
     * 渠道 id。
     */
    public static final String ID = "sms-aliyun";

    private static final String HOST_SUFFIX = ".aliyuncs.com";
    private static final String API_VERSION = "2017-05-25";
    private static final String SIGN_METHOD = "HMAC-SHA1";

    @Override
    public String id() {
        return ID;
    }

    @Override
    public String name() {
        return "阿里云短信";
    }

    @Override
    protected String[] usedConfigKeys() {
        return new String[]{"token", "secret", "name", "template", "region", "to", "timeoutMs"};
    }

    @Override
    protected String buildUrl(Message message, ChannelConfig config) {
        requireCredentials(config);
        String endpoint = resolveHost(config.region(), config.webhook());
        Map<String, String> params = commonParams(config, message);
        String canonicalQuery = buildCanonicalQuery(params);
        String stringToSign = "POST&%2F&" + NotifyUtils.urlEncode(canonicalQuery);
        String signature = NotifyUtils.base64(
                hmacSha1(config.secret() + "&", stringToSign));
        return endpoint + "?" + canonicalQuery
                + "&Signature=" + NotifyUtils.urlEncode(signature);
    }

    @Override
    protected String buildPayload(Message message, ChannelConfig config) {
        // 参数都在 URL 上（RPC 签名要求），请求体恒为空
        return "";
    }

    @Override
    protected String contentType(Message message, ChannelConfig config) {
        return "application/x-www-form-urlencoded";
    }

    private void requireCredentials(ChannelConfig config) {
        if (config.token() == null || config.token().isEmpty()) {
            throw new IllegalArgumentException(
                    "aliyun sms accessKeyId is required (ChannelConfig.token)");
        }
        if (config.secret() == null || config.secret().isEmpty()) {
            throw new IllegalArgumentException(
                    "aliyun sms accessKeySecret is required (ChannelConfig.secret)");
        }
        if (config.name() == null || config.name().isEmpty()) {
            throw new IllegalArgumentException("aliyun sms signName is required (ChannelConfig.name)");
        }
        requiredTemplate(config, id());
    }

    private static String resolveHost(String region, String endpointOverride) {
        // webhookUrl 覆盖完整接口地址（自建网关 / 测试用）；地域形态 cn-hangzhou /
        // dysmsapi.cn-hangzhou.aliyuncs.com 均可
        if (endpointOverride != null && !endpointOverride.trim().isEmpty()) {
            return endpointOverride.trim();
        }
        if (region == null || region.isEmpty()) {
            return "https://dysmsapi" + HOST_SUFFIX + "/";
        }
        if (region.contains(".")) {
            return "https://" + region + "/";
        }
        return "https://dysmsapi." + region + HOST_SUFFIX + "/";
    }

    private Map<String, String> commonParams(ChannelConfig config, Message message) {
        // TreeMap 保证参数按字典序排列，RPC 签名要求
        Map<String, String> params = new TreeMap<String, String>();
        params.put("AccessKeyId", config.token());
        params.put("Action", "SendSms");
        params.put("Format", "JSON");
        params.put("PhoneNumbers", currentReceiver(message));
        params.put("RegionId", "cn-hangzhou");
        params.put("SignName", config.name());
        params.put("SignatureMethod", SIGN_METHOD);
        params.put("SignatureNonce", NotifyUtils.uuid());
        params.put("SignatureVersion", "1.0");
        params.put("TemplateCode", config.template());
        params.put("Timestamp", iso8601());
        params.put("Version", API_VERSION);
        Map<String, String> named = namedParams(message);
        if (!named.isEmpty()) {
            params.put("TemplateParam", NotifyUtils.toJson(named));
        }
        return params;
    }

    @Override
    protected boolean isAccepted(int httpStatus, String responseBody) {
        if (httpStatus < 200 || httpStatus >= 300) {
            return false;
        }
        return "OK".equals(NotifyUtils.jsonString(responseBody, "Code"));
    }

    @Override
    protected FailureType classify(int httpStatus, String responseBody) {
        String code = NotifyUtils.jsonString(responseBody, "Code");
        if (code != null) {
            if ("isv.BUSINESS_LIMIT_CONTROL".equals(code)) {
                return FailureType.THROTTLED;
            }
            if ("isv.SMS_SIGNATURE_ILLEGAL".equals(code) || "SignatureDoesNotMatch".equals(code)
                    || "InvalidAccessKeyId.NotFound".equals(code) || "isv.ACCOUNT_NOT_EXISTS".equals(code)
                    || "isv.MOBILE_NUMBER_ILLEGAL".equals(code) || "isv.SMS_TEST_NUMBER_LIMIT".equals(code)) {
                return FailureType.CONFIG_ERROR;
            }
        }
        return super.classify(httpStatus, responseBody);
    }

    @Override
    protected String errorMessage(int httpStatus, String responseBody) {
        String code = NotifyUtils.jsonString(responseBody, "Code");
        String message = NotifyUtils.jsonString(responseBody, "Message");
        if (code != null && message != null) {
            return "aliyun sms " + code + ": " + message;
        }
        return super.errorMessage(httpStatus, responseBody);
    }

    private static String buildCanonicalQuery(Map<String, String> params) {
        StringBuilder out = new StringBuilder(256);
        for (Map.Entry<String, String> entry : params.entrySet()) {
            if (out.length() > 0) {
                out.append('&');
            }
            out.append(percentEncode(entry.getKey())).append('=').append(percentEncode(entry.getValue()));
        }
        return out.toString();
    }

    private static String percentEncode(String value) {
        // RPC 签名专用编码：空格编成 %20（URLEncoder 默认给 +），星号编成 %2A，波浪号还原
        String encoded = NotifyUtils.urlEncode(value);
        return encoded.replace("+", "%20").replace("*", "%2A").replace("%7E", "~");
    }

    private static String iso8601() {
        SimpleDateFormat format = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'");
        format.setTimeZone(TimeZone.getTimeZone("UTC"));
        return format.format(new Date());
    }

    private static byte[] hmacSha1(String key, String data) {
        try {
            Mac mac = Mac.getInstance("HmacSHA1");
            mac.init(new SecretKeySpec(key.getBytes(StandardCharsets.UTF_8), "HmacSHA1"));
            return mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new IllegalStateException("HmacSHA1 unavailable", e);
        }
    }
}
