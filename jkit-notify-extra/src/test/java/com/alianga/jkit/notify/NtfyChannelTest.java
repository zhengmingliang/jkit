package com.alianga.jkit.notify;

import com.alianga.jkit.notify.channel.NtfyChannel;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * ntfy 渠道黑盒测试。
 *
 * @author 郑明亮
 * @since 2.0.1
 */
public class NtfyChannelTest extends AbstractHttpChannelTest {

    /**
     * webhook 完整地址（路径即主题）：topic 进 URL 与 payload，extras 映射 tags/priority/click。
     */
    @Test
    public void ntfyPublishViaWebhookPath() {
        respond(200, "{\"id\":\"abc123\",\"time\":1700000000,\"topic\":\"mytopic\"}");
        SendResult result = NotificationManager.send(NtfyChannel.ID,
                Message.text("磁盘告警", "使用率 95%")
                        .extra(NtfyChannel.EXTRA_TAGS, "warning,rotating_light")
                        .extra(NtfyChannel.EXTRA_PRIORITY, "high")
                        .extra(Message.EXTRA_URL, "https://grafana.example.com/dash"),
                ChannelConfig.webhook(baseUrl() + "/mytopic"));
        assertTrue(result.isSuccess());
        Captured request = take();
        assertEquals("/mytopic", request.path());
        assertJsonEquals("{\"topic\":\"mytopic\",\"title\":\"磁盘告警\",\"message\":\"使用率 95%\","
                + "\"tags\":[\"warning\",\"rotating_light\"],\"priority\":\"high\","
                + "\"click\":\"https://grafana.example.com/dash\"}", request.body());
        assertTrue(isEmpty());
    }

    /**
     * to() 指定主题 + token 走 Bearer 鉴权；自建服务地址用 webhookUrl 覆盖。
     */
    @Test
    public void ntfyTopicFromConfigWithBearerToken() {
        respond(200, "{\"id\":\"m1\",\"topic\":\"deploys\"}");
        NotificationManager.send(NtfyChannel.ID,
                Message.text("部署完成"),
                ChannelConfig.ofToken("tk_token-1").to("deploys").webhookUrl(baseUrl()));
        Captured request = take();
        assertEquals("/deploys", request.path());
        assertJsonEquals("{\"topic\":\"deploys\",\"message\":\"部署完成\"}", request.body());
        assertEquals("Bearer tk_token-1", request.headers().get("authorization"));
    }

    /**
     * EXTRA_GROUP 按条覆盖主题（webhook 自带路径被剥掉）；to() 不再参与。
     */
    @Test
    public void ntfyPerMessageTopicOverride() {
        respond(200, "{\"id\":\"m2\"}");
        NotificationManager.send(NtfyChannel.ID,
                Message.text("hello").extra(Message.EXTRA_GROUP, "alerts"),
                ChannelConfig.webhook(baseUrl() + "/ignored").to("deploys"));
        Captured request = take();
        assertEquals("/alerts", request.path());
        assertJsonEquals("{\"topic\":\"alerts\",\"message\":\"hello\"}", request.body());
    }

    /**
     * MARKDOWN 带 markdown:true；数字优先级转 int；Basic 认证头。
     */
    @Test
    public void ntfyMarkdownNumericPriorityAndBasicAuth() {
        respond(200, "{\"id\":\"m3\"}");
        NotificationManager.send(NtfyChannel.ID,
                Message.markdown("发布", "**v1.2.3** 已上线").extra(NtfyChannel.EXTRA_PRIORITY, 5),
                ChannelConfig.webhook(baseUrl()).to("rel")
                        .username("phil").password("s3cret"));
        Captured request = take();
        assertJsonEquals("{\"topic\":\"rel\",\"title\":\"发布\",\"message\":\"**v1.2.3** 已上线\","
                + "\"priority\":5,\"markdown\":true}", request.body());
        assertEquals("Basic " + Base64.getEncoder().encodeToString("phil:s3cret"
                .getBytes(StandardCharsets.UTF_8)), request.headers().get("authorization"));
    }

    /**
     * 无标题消息不发 title 字段。
     */
    @Test
    public void ntfyTitleOmittedWhenAbsent() {
        respond(200, "{\"id\":\"m4\"}");
        NotificationManager.send(NtfyChannel.ID, Message.text("only content"),
                ChannelConfig.webhook(baseUrl() + "/ops"));
        Captured request = take();
        assertJsonEquals("{\"topic\":\"ops\",\"message\":\"only content\"}", request.body());
    }

    /**
     * 错误响应映射：40301 → CONFIG_ERROR，错误信息取 error 字段。
     */
    @Test
    public void ntfyFailureMapped() {
        respond(403, "{\"code\":40301,\"http\":403,\"error\":\"forbidden\"}");
        SendResult result = NotificationManager.send(NtfyChannel.ID,
                Message.text("hi"),
                ChannelConfig.ofToken("tk_bad").to("private").webhookUrl(baseUrl()));
        assertFalse(result.isSuccess());
        assertEquals("ntfy code 40301: forbidden", result.error());
        assertEquals(FailureType.CONFIG_ERROR, result.failureType());
        assertFalse(result.isRetryable());
    }

    /**
     * 429 归为限流。
     */
    @Test
    public void ntfyThrottled() {
        respond(429, "{\"code\":42901,\"http\":429,\"error\":\"limit reached\"}");
        SendResult result = NotificationManager.send(NtfyChannel.ID,
                Message.text("hi"), ChannelConfig.webhook(baseUrl() + "/ops"));
        assertFalse(result.isSuccess());
        assertEquals(FailureType.THROTTLED, result.failureType());
        assertTrue(result.isRetryable());
    }

    /**
     * 主题缺失抛编程错误。
     */
    @Test(expected = IllegalArgumentException.class)
    public void ntfyMissingTopicRejected() {
        NotificationManager.send(NtfyChannel.ID, Message.text("hi"),
                ChannelConfig.webhook(baseUrl()));
    }
}
