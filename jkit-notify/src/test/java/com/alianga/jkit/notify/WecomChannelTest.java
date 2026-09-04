package com.alianga.jkit.notify;

import com.alianga.jkit.notify.channel.WecomChannel;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * 企微渠道黑盒测试：payload 结构、@能力、errcode 判定。
 *
 * @author 郑明亮
 * @since 2.0.1
 */
public class WecomChannelTest extends AbstractHttpChannelTest {

    /**
     * text 消息带 @手机号。
     */
    @Test
    public void textMessageWithAtMobiles() {
        respond(200, "{\"errcode\":0,\"errmsg\":\"ok\"}");
        SendResult result = NotificationManager.send(WecomChannel.ID,
                Message.text("告警", "磁盘 90%")
                        .extra(Message.EXTRA_AT_MOBILES, "13800000000"),
                ChannelConfig.webhook(baseUrl() + "/webhook/send?key=k"));
        assertTrue(result.isSuccess());
        Captured request = take();
        assertEquals("key=k", request.query());
        assertJsonEquals("{\"msgtype\":\"text\",\"text\":{\"content\":\"磁盘 90%\","
                + "\"mentioned_mobile_list\":[\"13800000000\"]}}", request.body());
        assertTrue(isEmpty());
    }

    /**
     * @所有人（无手机号列表时只有 mentioned_list）。
     */
    @Test
    public void textMessageWithAtAll() {
        respond(200, "{\"errcode\":0}");
        SendResult result = NotificationManager.send(WecomChannel.ID,
                Message.text("hi").extra(Message.EXTRA_AT_ALL, true),
                ChannelConfig.webhook(baseUrl()));
        assertTrue(result.isSuccess());
        Captured request = take();
        assertJsonEquals("{\"msgtype\":\"text\",\"text\":{\"content\":\"hi\","
                + "\"mentioned_list\":[\"@all\"]}}", request.body());
    }

    /**
     * markdown 消息。
     */
    @Test
    public void markdownMessage() {
        respond(200, "{\"errcode\":0}");
        SendResult result = NotificationManager.send(WecomChannel.ID,
                Message.markdown("标题", "## 实时数据\n> 90%"),
                ChannelConfig.webhook(baseUrl()));
        assertTrue(result.isSuccess());
        Captured request = take();
        assertJsonEquals("{\"msgtype\":\"markdown\",\"markdown\":{\"content\":\"## 实时数据\\n> 90%\"}}",
                request.body());
    }

    /**
     * errcode 非 0 判失败。
     */
    @Test
    public void errcodeFailure() {
        respond(200, "{\"errcode\":93000,\"errmsg\":\"invalid webhook url\"}");
        SendResult result = NotificationManager.send(WecomChannel.ID,
                Message.text("hi"), ChannelConfig.webhook(baseUrl()));
        assertFalse(result.isSuccess());
        assertEquals("wecom errcode 93000: invalid webhook url", result.error());
    }
}
