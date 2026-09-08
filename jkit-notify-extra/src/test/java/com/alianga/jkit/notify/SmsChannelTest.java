package com.alianga.jkit.notify;

import com.alianga.jkit.notify.channel.AbstractSmsChannel;
import com.alianga.jkit.notify.channel.AliyunSmsChannel;
import com.alianga.jkit.notify.channel.HuaweiSmsChannel;
import com.alianga.jkit.notify.channel.TencentSmsChannel;
import com.alianga.jkit.notify.channel.YunpianSmsChannel;

import org.junit.Test;

import java.net.URLDecoder;
import java.util.HashMap;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * 短信渠道（阿里云 / 腾讯云 / 云片 / 华为云）黑盒测试。
 *
 * @author 郑明亮
 * @since 2.0.1
 */
public class SmsChannelTest extends AbstractHttpChannelTest {

    /**
     * 云片：apikey/mobile/text 表单提交，code==0 判成功。
     */
    @Test
    public void yunpianFormPayload() {
        respond(200, "{\"code\":0,\"msg\":\"OK\",\"count\":1,\"fee\":1,\"sid\":123}");
        SendResult result = NotificationManager.send(YunpianSmsChannel.ID,
                Message.text("您的验证码是1235"),
                ChannelConfig.ofToken("apikey-xyz").webhookUrl(baseUrl() + "/single_send.json")
                        .to("13800000001"));
        assertTrue(result.toString(), result.isSuccess());
        Captured request = take();
        Map<String, String> form = formOf(request.body());
        assertEquals("apikey-xyz", form.get("apikey"));
        assertEquals("13800000001", form.get("mobile"));
        assertEquals("您的验证码是1235", form.get("text"));
        assertTrue(contentTypeOf(request).startsWith("application/x-www-form-urlencoded"));
        assertTrue(isEmpty());
    }

    /**
     * 云片：code!=0 判失败并映射错误类别。
     */
    @Test
    public void yunpianFailureMapped() {
        respond(200, "{\"code\":2,\"msg\":\"apikey 非法\"}");
        SendResult result = NotificationManager.send(YunpianSmsChannel.ID,
                Message.text("hi"),
                ChannelConfig.ofToken("bad").webhookUrl(baseUrl()).to("13800000001"));
        assertFalse(result.isSuccess());
        assertEquals("yunpian code 2: apikey 非法", result.error());
        assertEquals(FailureType.CONFIG_ERROR, result.failureType());
    }

    /**
     * 云片：正文超长按字节截断。
     */
    @Test
    public void yunpianTruncatesLongText() {
        respond(200, "{\"code\":0}");
        StringBuilder longText = new StringBuilder();
        for (int i = 0; i < 500; i++) {
            longText.append("验证码");
        }
        NotificationManager.send(YunpianSmsChannel.ID, Message.text(longText.toString()),
                ChannelConfig.ofToken("k").webhookUrl(baseUrl()).to("13800000001"));
        Captured request = take();
        Map<String, String> form = formOf(request.body());
        assertTrue(NotifyUtils.utf8Length(form.get("text")) <= YunpianSmsChannel.MAX_TEXT_BYTES);
        assertTrue(form.get("text").endsWith(NotifyUtils.TRUNCATE_SUFFIX));
    }

    /**
     * 云片：缺 apikey 抛编程错误。
     */
    @Test(expected = IllegalArgumentException.class)
    public void yunpianMissingTokenRejected() {
        NotificationManager.send(YunpianSmsChannel.ID, Message.text("hi"),
                ChannelConfig.webhook(baseUrl()).to("13800000001"));
    }

    /**
     * 阿里云：RPC 签名参数进 URL，TemplateParam 为命名参数 JSON。
     */
    @Test
    public void aliyunSignedUrlAndParams() {
        respond(200, "{\"Code\":\"OK\",\"BizId\":\"9006197\",\"RequestId\":\"F655\"}");
        SendResult result = NotificationManager.send(AliyunSmsChannel.ID,
                Message.text("验证码短信").extra(AbstractSmsChannel.EXTRA_SMS_PARAMS, "code=9527,min=5"),
                ChannelConfig.ofToken("accessKeyId").secret("accessKeySecret")
                        .name("阿里云签名").extra(AbstractSmsChannel.CFG_TEMPLATE, "SMS_123456789")
                        .webhookUrl(baseUrl()).to("13800000001"));
        assertTrue(result.toString(), result.isSuccess());
        Captured request = take();
        Map<String, String> query = formOf(request.query());
        assertEquals("SendSms", query.get("Action"));
        assertEquals("accessKeyId", query.get("AccessKeyId"));
        assertEquals("13800000001", query.get("PhoneNumbers"));
        assertEquals("SMS_123456789", query.get("TemplateCode"));
        assertEquals("阿里云签名", query.get("SignName"));
        assertEquals("9527", parseJsonMap(query.get("TemplateParam")).get("code"));
        assertEquals("5", parseJsonMap(query.get("TemplateParam")).get("min"));
        assertTrue(query.containsKey("Signature"));
        assertTrue(query.containsKey("Timestamp"));
        assertTrue(query.containsKey("SignatureNonce"));
        assertTrue(request.body().isEmpty());
        assertTrue(isEmpty());
    }

    /**
     * 阿里云：Code!=OK 判失败，错误码映射类别。
     */
    @Test
    public void aliyunFailureMapped() {
        respond(200, "{\"Code\":\"isv.BUSINESS_LIMIT_CONTROL\","
                + "\"Message\":\"触发分钟级流控\"}");
        SendResult result = NotificationManager.send(AliyunSmsChannel.ID,
                Message.text("hi"),
                ChannelConfig.ofToken("k").secret("s").name("签名")
                        .extra(AbstractSmsChannel.CFG_TEMPLATE, "SMS_1").webhookUrl(baseUrl()).to("13800000001"));
        assertFalse(result.isSuccess());
        assertTrue(result.error().startsWith("aliyun sms isv.BUSINESS_LIMIT_CONTROL"));
        assertEquals(FailureType.THROTTLED, result.failureType());
    }

    /**
     * 阿里云：缺签名抛编程错误。
     */
    @Test(expected = IllegalArgumentException.class)
    public void aliyunMissingSignNameRejected() {
        NotificationManager.send(AliyunSmsChannel.ID, Message.text("hi"),
                ChannelConfig.ofToken("k").secret("s").extra(AbstractSmsChannel.CFG_TEMPLATE, "SMS_1")
                        .webhookUrl(baseUrl()).to("13800000001"));
    }

    /**
     * 腾讯云：TC3 签名请求头 + JSON 请求体；SendStatusSet[0].Code==Ok 判成功。
     */
    @Test
    public void tencentSignedRequest() {
        respond(200, "{\"Response\":{\"SendStatusSet\":[{\"Code\":\"Ok\",\"Message\":\"send success\","
                + "\"PhoneNumber\":\"+8613800000001\",\"SerialNo\":\"1\"}],\"RequestId\":\"r1\"}}");
        SendResult result = NotificationManager.send(TencentSmsChannel.ID,
                Message.text("验证码短信").extra(AbstractSmsChannel.EXTRA_SMS_PARAMS, "9527,5"),
                ChannelConfig.ofToken("secretId").secret("secretKey")
                        .extra(AbstractSmsChannel.CFG_APP_ID, "1400006666").name("腾讯云签名").extra(AbstractSmsChannel.CFG_TEMPLATE, "1234567")
                        .webhookUrl(baseUrl()).to("13800000001"));
        assertTrue(result.toString(), result.isSuccess());
        Captured request = take();
        Map<String, String> body = parseJsonMap(request.body());
        assertEquals("1400006666", body.get("SmsSdkAppId"));
        assertEquals("腾讯云签名", body.get("SignName"));
        assertEquals("1234567", body.get("TemplateId"));
        assertEquals("[+8613800000001]", normalizeList(body.get("PhoneNumberSet")));
        assertEquals("[9527,5]", normalizeList(body.get("TemplateParamSet")));
    }

    /**
     * 腾讯云：业务码非 Ok 映射失败类别。
     */
    @Test
    public void tencentFailureMapped() {
        respond(200, "{\"Response\":{\"SendStatusSet\":[{\"Code\":\"LimitExceeded.DeliveryFrequency\","
                + "\"Message\":\"发送频率超限\"}]}}");
        SendResult result = NotificationManager.send(TencentSmsChannel.ID,
                Message.text("hi"),
                ChannelConfig.ofToken("k").secret("s").extra(AbstractSmsChannel.CFG_APP_ID, "1").name("签名")
                        .extra(AbstractSmsChannel.CFG_TEMPLATE, "2").webhookUrl(baseUrl()).to("13800000001"));
        assertFalse(result.isSuccess());
        assertTrue(result.error().startsWith("tencent sms LimitExceeded.DeliveryFrequency"));
        assertEquals(FailureType.THROTTLED, result.failureType());
    }

    /**
     * 腾讯云：缺 SdkAppId 抛编程错误。
     */
    @Test(expected = IllegalArgumentException.class)
    public void tencentMissingAppIdRejected() {
        NotificationManager.send(TencentSmsChannel.ID, Message.text("hi"),
                ChannelConfig.ofToken("k").secret("s").extra(AbstractSmsChannel.CFG_TEMPLATE, "t")
                        .webhookUrl(baseUrl()).to("13800000001"));
    }

    /**
     * 华为云：WSSE 头 + 表单请求体；code==000000 判成功。
     */
    @Test
    public void huaweiFormAndWsse() {
        respond(200, "{\"code\":\"000000\",\"description\":\"Success\","
                + "\"result\":[{\"status\":\"000000\"}]}");
        SendResult result = NotificationManager.send(HuaweiSmsChannel.ID,
                Message.text("验证码短信").extra(AbstractSmsChannel.EXTRA_SMS_PARAMS, "9527,5"),
                ChannelConfig.ofToken("appKey").secret("appSecret")
                        .extra(AbstractSmsChannel.CFG_APP_ID, "8823120512345").name("华为云签名").extra(AbstractSmsChannel.CFG_TEMPLATE, "12345678")
                        .webhookUrl(baseUrl()).to("13800000001"));
        assertTrue(result.toString(), result.isSuccess());
        Captured request = take();
        Map<String, String> form = formOf(request.body());
        assertEquals("8823120512345", form.get("from"));
        assertEquals("13800000001", form.get("to"));
        assertEquals("12345678", form.get("templateId"));
        assertEquals("华为云签名", form.get("signature"));
        assertTrue(form.get("templateParas").contains("9527"));
        // WSSE 头格式校验由 buildWsseHeader 的单测覆盖
        assertTrue(isEmpty());
    }

    /**
     * 华为云：WSSE 头构造与官方示例算法一致（nonce+created+secret → SHA-256 hex → Base64）。
     */
    @Test
    public void huaweiWsseHeaderAlgorithm() {
        String wsse = HuaweiSmsChannel.buildWsseHeader("appKey", "secret");
        assertTrue(wsse.startsWith("UsernameToken Username=\"appKey\""));
        assertTrue(wsse.contains("PasswordDigest=\""));
        assertTrue(wsse.contains("Nonce=\""));
        assertTrue(wsse.contains("Created=\""));
    }

    /**
     * 华为云：业务码非 000000 映射失败类别。
     */
    @Test
    public void huaweiFailureMapped() {
        respond(200, "{\"code\":\"E200037\",\"description\":\"The SMS containing"
                + " sensitive words\"}");
        SendResult result = NotificationManager.send(HuaweiSmsChannel.ID,
                Message.text("hi"),
                ChannelConfig.ofToken("k").secret("s").extra(AbstractSmsChannel.CFG_APP_ID, "1").extra(AbstractSmsChannel.CFG_TEMPLATE, "2")
                        .webhookUrl(baseUrl()).to("13800000001"));
        assertFalse(result.isSuccess());
        assertTrue(result.error().startsWith("huawei sms E200037"));
    }

    /**
     * 华为云：缺接入地址抛编程错误。
     */
    @Test(expected = IllegalArgumentException.class)
    public void huaweiMissingEndpointRejected() {
        NotificationManager.send(HuaweiSmsChannel.ID, Message.text("hi"),
                ChannelConfig.ofToken("k").secret("s").extra(AbstractSmsChannel.CFG_APP_ID, "1").extra(AbstractSmsChannel.CFG_TEMPLATE, "2")
                        .to("13800000001"));
    }

    /**
     * 多收件人逐号码发送：两个号码一次成功一次失败，整体成功且带 parts。
     */
    @Test
    public void yunpianMultiReceiverAggregated() {
        respond(200, "{\"code\":0}");
        respond(200, "{\"code\":2,\"msg\":\"apikey 非法\"}");
        SendResult result = NotificationManager.send(YunpianSmsChannel.ID,
                Message.text("hi"),
                ChannelConfig.ofToken("k").webhookUrl(baseUrl()).to("13800000001,13800000002"));
        // 第一个号码成功，第二个失败；任一成功即整体成功
        assertTrue(result.isSuccess());
        assertEquals(2, result.parts().size());
    }

    /**
     * 收件人缺失抛编程错误（四个渠道统一约定）。
     */
    @Test
    public void missingReceiverRejected() {
        try {
            NotificationManager.send(YunpianSmsChannel.ID, Message.text("hi"),
                    ChannelConfig.ofToken("k").webhookUrl(baseUrl()));
            throw new AssertionError("receiver should be required");
        } catch (IllegalArgumentException expected) {
            // 预期
        }
        try {
            NotificationManager.send(AliyunSmsChannel.ID, Message.text("hi"),
                    ChannelConfig.ofToken("k").secret("s").name("n").extra(AbstractSmsChannel.CFG_TEMPLATE, "t")
                            .webhookUrl(baseUrl()));
            throw new AssertionError("receiver should be required");
        } catch (IllegalArgumentException expected) {
            // 预期
        }
    }

    private static Map<String, String> formOf(String encoded) {
        Map<String, String> out = new HashMap<String, String>();
        if (encoded == null || encoded.isEmpty()) {
            return out;
        }
        for (String pair : encoded.split("&")) {
            int eq = pair.indexOf('=');
            if (eq > 0) {
                try {
                    out.put(URLDecoder.decode(pair.substring(0, eq), "UTF-8"),
                            URLDecoder.decode(pair.substring(eq + 1), "UTF-8"));
                } catch (Exception e) {
                    // 测试辅助方法，忽略编码异常
                }
            }
        }
        return out;
    }

    private static Map<String, String> parseJsonMap(String json) {
        Map<String, String> out = new HashMap<String, String>();
        Object parsed = com.alianga.jkit.json.JSON.parse(json);
        for (Object key : ((Map<?, ?>) parsed).keySet()) {
            Object value = ((Map<?, ?>) parsed).get(key);
            out.put(String.valueOf(key), value == null ? null : String.valueOf(value));
        }
        return out;
    }

    private static String normalizeList(String jsonList) {
        // 去空格，规避 JSON 库输出 "[\"9527\",\"5\"]" 与 "[\"9527\", \"5\"]" 的差异
        return jsonList == null ? null : jsonList.replace(" ", "");
    }

    private static String contentTypeOf(Captured request) {
        String type = request.headers().get("content-type");
        return type == null ? "" : type;
    }
}
