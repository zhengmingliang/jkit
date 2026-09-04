package com.alianga.jkit.notify;

import com.alianga.jkit.notify.channel.WebhookChannel;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * 通用 Webhook 渠道黑盒测试：默认模板、自定义模板、转义、自定义请求头。
 *
 * @author 郑明亮
 * @since 2.0.1
 */
public class WebhookChannelTest extends AbstractHttpChannelTest {

    /**
     * 默认模板：{"text":"title\ncontent"}。
     */
    @Test
    public void defaultTemplate() {
        respond(200, "ok");
        SendResult result = NotificationManager.send(WebhookChannel.ID,
                Message.text("告警", "CPU 95%"),
                ChannelConfig.webhook(baseUrl() + "/hook"));
        assertTrue(result.isSuccess());
        Captured request = take();
        assertJsonEquals("{\"text\":\"告警\\nCPU 95%\"}", request.body());
        assertTrue(isEmpty());
    }

    /**
     * Slack 风格模板：自定义 payload 结构。
     */
    @Test
    public void slackStyleTemplate() {
        respond(200, "ok");
        SendResult result = NotificationManager.send(WebhookChannel.ID,
                Message.text("构建失败", "job #42"),
                ChannelConfig.webhook(baseUrl() + "/slack")
                        .payloadTemplate("{\"channel\":\"#ci\",\"text\":\"${title}: ${content}\"}"));
        assertTrue(result.isSuccess());
        Captured request = take();
        assertJsonEquals("{\"channel\":\"#ci\",\"text\":\"构建失败: job #42\"}", request.body());
    }

    /**
     * 正文包含引号/换行时 JSON 转义防注入。
     */
    @Test
    public void contentEscapingProtectsJson() {
        respond(200, "ok");
        NotificationManager.send(WebhookChannel.ID,
                Message.text("a\"b\nc"),
                ChannelConfig.webhook(baseUrl()).payloadTemplate("{\"text\":\"${content}\"}"));
        Captured request = take();
        assertEquals("{\"text\":\"a\\\"b\\nc\"}", request.body());
        assertJsonEquals("{\"text\":\"a\\\"b\\nc\"}", request.body());
    }

    /**
     * 未知占位符原样保留。
     */
    @Test
    public void unknownPlaceholderKept() {
        respond(200, "ok");
        NotificationManager.send(WebhookChannel.ID,
                Message.text("hi"),
                ChannelConfig.webhook(baseUrl()).payloadTemplate("{\"k\":\"${unknown}\"}"));
        Captured request = take();
        assertJsonEquals("{\"k\":\"${unknown}\"}", request.body());
    }

    /**
     * HTML 类型也支持（正文原样嵌入）。
     */
    @Test
    public void htmlMessageSupported() {
        respond(200, "ok");
        SendResult result = NotificationManager.send(WebhookChannel.ID,
                Message.html("报表", "<b>营收</b>"),
                ChannelConfig.webhook(baseUrl()));
        assertTrue(result.isSuccess());
        Captured request = take();
        assertJsonEquals("{\"text\":\"报表\\n<b>营收</b>\"}", request.body());
    }

    /**
     * 非 2xx 判失败（自定义 header 不影响判定）。
     */
    @Test
    public void non2xxFails() {
        respond(403, "forbidden");
        SendResult result = NotificationManager.send(WebhookChannel.ID,
                Message.text("hi"),
                ChannelConfig.webhook(baseUrl()).header("Authorization", "Bearer t"));
        assertFalse(result.isSuccess());
        assertEquals(403, result.status());
        take();
    }
}
