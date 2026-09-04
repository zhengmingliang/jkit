package com.alianga.jkit.notify;

import com.alianga.jkit.notify.channel.FeishuChannel;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * 飞书渠道黑盒测试：text/interactive payload、签名、code 判定。
 *
 * @author 郑明亮
 * @since 2.0.1
 */
public class FeishuChannelTest extends AbstractHttpChannelTest {

    /**
     * text 消息走 msg_type=text。
     */
    @Test
    public void textMessage() {
        respond(200, "{\"code\":0,\"msg\":\"success\"}");
        SendResult result = NotificationManager.send(FeishuChannel.ID,
                Message.text("告警", "内存不足"),
                ChannelConfig.webhook(baseUrl() + "/hook"));
        assertTrue(result.isSuccess());
        Captured request = take();
        assertJsonEquals("{\"msg_type\":\"text\",\"content\":{\"text\":\"内存不足\"}}", request.body());
        assertTrue(isEmpty());
    }

    /**
     * markdown 消息转 interactive 卡片。
     */
    @Test
    public void markdownBecomesInteractiveCard() {
        respond(200, "{\"code\":0}");
        SendResult result = NotificationManager.send(FeishuChannel.ID,
                Message.markdown("周报", "## 本周完成\n- 模块 A"),
                ChannelConfig.webhook(baseUrl()));
        assertTrue(result.isSuccess());
        Captured request = take();
        assertJsonEquals("{\"msg_type\":\"interactive\",\"card\":{\"header\":"
                + "{\"title\":{\"tag\":\"plain_text\",\"content\":\"周报\"}},"
                + "\"elements\":[{\"tag\":\"div\",\"text\":{\"tag\":\"lark_md\","
                + "\"content\":\"## 本周完成\\n- 模块 A\"}}]}}", request.body());
    }

    /**
     * 配置 secret 时 URL 带 timestamp 与 sign。
     */
    @Test
    public void signedUrlHasTimestampAndSign() {
        respond(200, "{\"code\":0}");
        SendResult result = NotificationManager.send(FeishuChannel.ID,
                Message.text("hi"),
                ChannelConfig.webhook(baseUrl()).secret("feishusecret"));
        assertTrue(result.isSuccess());
        Captured request = take();
        assertTrue(request.query(), request.query().contains("timestamp="));
        assertTrue(request.query(), request.query().contains("sign="));
    }

    /**
     * 兼容 StatusCode 字段名 + 失败路径。
     */
    @Test
    public void statusCodeFieldAndFailure() {
        respond(200, "{\"StatusCode\":19021,\"StatusMessage\":\"sign match fail\"}");
        SendResult result = NotificationManager.send(FeishuChannel.ID,
                Message.text("hi"), ChannelConfig.webhook(baseUrl()));
        assertFalse(result.isSuccess());
        assertEquals("feishu code 19021: sign match fail", result.error());
    }
}
