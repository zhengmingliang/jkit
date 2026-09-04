package com.alianga.jkit.notify;

import com.alianga.jkit.notify.channel.DingTalkChannel;

import org.junit.Test;

import java.net.URLEncoder;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * 钉钉渠道黑盒测试：加签 URL、text/markdown payload、@能力、errcode 判定。
 *
 * @author 郑明亮
 * @since 2.0.1
 */
public class DingTalkChannelTest extends AbstractHttpChannelTest {

    /**
     * 签名算法独立复算（测试内直接用 Mac，不依赖被测代码）。
     */
    private static String expectedSign(String secret, long timestamp) throws Exception {
        String stringToSign = timestamp + "\n" + secret;
        javax.crypto.Mac mac = javax.crypto.Mac.getInstance("HmacSHA256");
        mac.init(new javax.crypto.spec.SecretKeySpec(
                secret.getBytes("UTF-8"), "HmacSHA256"));
        byte[] signData = mac.doFinal(stringToSign.getBytes("UTF-8"));
        return java.util.Base64.getEncoder().encodeToString(signData);
    }

    /**
     * text 消息：payload 结构 + 成功判定。
     */
    @Test
    public void textMessagePayloadAndSuccess() {
        respond(200, "{\"errcode\":0,\"errmsg\":\"ok\"}");
        SendResult result = NotificationManager.send(DingTalkChannel.ID,
                Message.text("告警", "CPU 使用率 95%"),
                ChannelConfig.webhook(baseUrl() + "/robot/send?access_token=token123"));
        assertTrue(result.toString(), result.isSuccess());
        Captured request = take();
        assertEquals("access_token=token123", request.query());
        assertJsonEquals("{\"msgtype\":\"text\",\"text\":{\"content\":\"CPU 使用率 95%\"}}",
                request.body());
        assertTrue(isEmpty());
    }

    /**
     * markdown 消息 + @手机号 + @所有人。
     *
     * <p>正文里原本没有手机号，渠道会自动追加 {@code @手机号}——钉钉光有 at 数组不会触发提醒。
     */
    @Test
    public void markdownMessageWithAt() {
        respond(200, "{\"errcode\":0}");
        SendResult result = NotificationManager.send(DingTalkChannel.ID,
                Message.markdown("部署通知", "## 发布成功")
                        .extra(Message.EXTRA_AT_MOBILES, "13800000000,13900000000")
                        .extra(Message.EXTRA_AT_ALL, true),
                ChannelConfig.webhook(baseUrl() + "/robot/send?access_token=t"));
        assertTrue(result.isSuccess());
        Captured request = take();
        assertJsonEquals("{\"msgtype\":\"markdown\",\"markdown\":{\"title\":\"部署通知\","
                        + "\"text\":\"## 发布成功 @13800000000 @13900000000\"},"
                        + "\"at\":{\"atMobiles\":[\"13800000000\",\"13900000000\"],"
                        + "\"isAtAll\":true}}",
                request.body());
    }

    /**
     * 加签：URL 上带 timestamp 与 sign，sign 与测试内独立计算值一致。
     */
    @Test
    public void signedUrlMatchesIndependentComputation() throws Exception {
        respond(200, "{\"errcode\":0}");
        String secret = "SECtestsecret";
        long before = System.currentTimeMillis();
        SendResult result = NotificationManager.send(DingTalkChannel.ID,
                Message.text("hi"),
                ChannelConfig.webhook(baseUrl() + "/robot/send?access_token=t").secret(secret));
        long after = System.currentTimeMillis();
        assertTrue(result.isSuccess());
        Captured request = take();

        int tsIndex = request.query().indexOf("timestamp=");
        assertTrue(tsIndex >= 0);
        long timestamp = Long.parseLong(
                request.query().substring(tsIndex + "timestamp=".length()).split("&")[0]);
        assertTrue(timestamp >= before && timestamp <= after);

        int signIndex = request.query().indexOf("sign=");
        assertTrue(signIndex >= 0);
        String signParam = request.query().substring(signIndex + "sign=".length());
        assertEquals(URLEncoder.encode(expectedSign(secret, timestamp), "UTF-8"), signParam);
    }

    /**
     * 业务失败：errcode 非 0 时 result 失败且错误信息可读。
     */
    @Test
    public void errcodeFailure() {
        respond(200, "{\"errcode\":310000,\"errmsg\":\"sign not match\"}");
        SendResult result = NotificationManager.send(DingTalkChannel.ID,
                Message.text("hi"), ChannelConfig.webhook(baseUrl()));
        assertFalse(result.isSuccess());
        assertEquals("dingtalk errcode 310000: sign not match", result.error());
    }

    /**
     * HTTP 层失败：5xx 时不成功。
     */
    @Test
    public void httpFailure() {
        respond(500, "server error");
        SendResult result = NotificationManager.send(DingTalkChannel.ID,
                Message.text("hi"), ChannelConfig.webhook(baseUrl()));
        assertFalse(result.isSuccess());
    }

    /**
     * 网络不可达：返回 fail 而不是抛异常。
     */
    @Test
    public void networkErrorReturnsFail() {
        SendResult result = NotificationManager.send(DingTalkChannel.ID,
                Message.text("hi"),
                ChannelConfig.webhook("http://127.0.0.1:1/robot/send").timeoutMs(2000));
        assertFalse(result.isSuccess());
        assertNull(result.response());
    }

    /**
     * 缺 webhook 配置属于编程错误，直接抛出。
     */
    @Test(expected = IllegalArgumentException.class)
    public void missingWebhookRejected() {
        NotificationManager.send(DingTalkChannel.ID, Message.text("hi"), ChannelConfig.webhook(null));
    }

    /**
     * 正文中的引号与换行被正确转义，不破坏 JSON 结构。
     */
    @Test
    public void contentEscaping() {
        respond(200, "{\"errcode\":0}");
        NotificationManager.send(DingTalkChannel.ID,
                Message.text("a\"b\nc\\d"), ChannelConfig.webhook(baseUrl()));
        Captured request = take();
        assertJsonEquals("{\"msgtype\":\"text\",\"text\":{\"content\":\"a\\\"b\\nc\\\\d\"}}",
                request.body());
    }

    /**
     * actionCard 单按钮卡片。
     */
    @Test
    public void actionCardPayload() {
        respond(200, "{\"errcode\":0}");
        SendResult result = NotificationManager.send(DingTalkChannel.ID,
                Message.actionCard("发布", "v1.2.3 已上线", "查看详情", "https://ci.example.com/42"),
                ChannelConfig.webhook(baseUrl()));
        assertTrue(result.isSuccess());
        Captured request = take();
        assertJsonEquals("{\"msgtype\":\"actionCard\",\"actionCard\":{\"title\":\"发布\","
                + "\"text\":\"v1.2.3 已上线\",\"btnOrientation\":\"1\","
                + "\"singleTitle\":\"查看详情\",\"singleURL\":\"https://ci.example.com/42\"}}",
                request.body());
    }

    /**
     * 图文卡片走 feedCard；图片走 markdown 内嵌公网 picUrl。
     */
    @Test
    public void newsAndImagePayload() {
        respond(200, "{\"errcode\":0}");
        NotificationManager.send(DingTalkChannel.ID,
                Message.news("发布", "v1.2.3", "https://ci.example.com/42", "https://example.com/cover.png"),
                ChannelConfig.webhook(baseUrl()));
        assertJsonEquals("{\"msgtype\":\"feedCard\",\"feedCard\":{\"links\":[{\"title\":\"发布\","
                + "\"messageURL\":\"https://ci.example.com/42\",\"picURL\":\"https://example.com/cover.png\"}]}}",
                take().body());

        respond(200, "{\"errcode\":0}");
        NotificationManager.send(DingTalkChannel.ID,
                Message.image("截图", new byte[]{1, 2, 3})
                        .extra(Message.EXTRA_PIC_URL, "https://example.com/shot.png"),
                ChannelConfig.webhook(baseUrl()));
        assertJsonEquals("{\"msgtype\":\"markdown\",\"markdown\":{\"title\":\"截图\","
                + "\"text\":\"![截图](https://example.com/shot.png)\\nhttps://example.com/shot.png\"}}",
                take().body());
    }
}
