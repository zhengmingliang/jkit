package com.alianga.jkit.notify;

import com.alianga.jkit.notify.channel.BarkChannel;
import com.alianga.jkit.notify.channel.ServerChanChannel;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Server酱 / Bark 渠道黑盒测试。
 *
 * @author 郑明亮
 * @since 2.0.1
 */
public class ServerChanBarkChannelTest extends AbstractHttpChannelTest {

    /**
     * Server酱：SendKey 拼到 URL，title/desp 进 payload。
     */
    @Test
    public void serverChanSendKeyAndPayload() {
        respond(200, "{\"code\":0,\"message\":\"\"}");
        SendResult result = NotificationManager.send(ServerChanChannel.ID,
                Message.markdown("晚餐提醒", "今天吃火锅"),
                ChannelConfig.webhook(baseUrl() + "/SCT123.send"));
        assertTrue(result.isSuccess());
        Captured request = take();
        assertJsonEquals("{\"title\":\"晚餐提醒\",\"desp\":\"今天吃火锅\"}", request.body());
        assertTrue(isEmpty());
    }

    /**
     * Server酱：无标题时用正文首行当标题；正文过短自动补空格。
     */
    @Test
    public void serverChanFallsBackTitleAndPadsShortDesp() {
        respond(200, "{\"code\":0}");
        NotificationManager.send(ServerChanChannel.ID,
                Message.text("ok"),
                ChannelConfig.webhook(baseUrl()));
        Captured request = take();
        assertJsonEquals("{\"title\":\"ok\",\"desp\":\"ok     \"}", request.body());
    }

    /**
     * Server酱：code 非 0 判失败。
     */
    @Test
    public void serverChanFailure() {
        respond(200, "{\"code\":40001,\"message\":\"bad sendkey\"}");
        SendResult result = NotificationManager.send(ServerChanChannel.ID,
                Message.text("hi"), ChannelConfig.webhook(baseUrl()));
        assertFalse(result.isSuccess());
        assertEquals("serverchan code 40001: bad sendkey", result.error());
    }

    /**
     * Bark：device_key/token 进 payload，extras 映射 sound/group。
     */
    @Test
    public void barkPayloadWithExtras() {
        respond(200, "{\"code\":200,\"message\":\"success\"}");
        SendResult result = NotificationManager.send(BarkChannel.ID,
                Message.text("带宽告警", "出口流量打满")
                        .extra(Message.EXTRA_SOUND, "minuet")
                        .extra(Message.EXTRA_GROUP, "ops")
                        .extra(Message.EXTRA_URL, "https://example.com/dash"),
                ChannelConfig.webhook(baseUrl() + "/push").token("devicekey123"));
        assertTrue(result.isSuccess());
        Captured request = take();
        assertJsonEquals("{\"device_key\":\"devicekey123\",\"title\":\"带宽告警\","
                + "\"body\":\"出口流量打满\",\"sound\":\"minuet\",\"group\":\"ops\","
                + "\"url\":\"https://example.com/dash\"}", request.body());
        assertTrue(isEmpty());
    }

    /**
     * Bark：code 非 200 判失败。
     */
    @Test
    public void barkFailure() {
        respond(200, "{\"code\":400,\"message\":\"device key not found\"}");
        SendResult result = NotificationManager.send(BarkChannel.ID,
                Message.text("hi"), ChannelConfig.webhook(baseUrl()).token("k"));
        assertFalse(result.isSuccess());
        assertEquals("bark code 400: device key not found", result.error());
    }

    /**
     * Bark：缺 device_key 抛出编程错误。
     */
    @Test(expected = IllegalArgumentException.class)
    public void barkMissingDeviceKeyRejected() {
        NotificationManager.send(BarkChannel.ID, Message.text("hi"),
                ChannelConfig.webhook(baseUrl()));
    }

    /**
     * ofToken 工厂 + webhookUrl 覆盖自建地址：令牌进 payload，地址走覆盖值。
     */
    @Test
    public void ofTokenWithWebhookUrlOverride() {
        respond(200, "{\"code\":200}");
        SendResult result = NotificationManager.send(BarkChannel.ID,
                Message.text("hi"),
                ChannelConfig.ofToken("dk-9").webhookUrl(baseUrl()));
        assertTrue(result.toString(), result.isSuccess());
        Captured request = take();
        assertJsonEquals("{\"device_key\":\"dk-9\",\"title\":\"通知\",\"body\":\"hi\"}", request.body());
    }
}
