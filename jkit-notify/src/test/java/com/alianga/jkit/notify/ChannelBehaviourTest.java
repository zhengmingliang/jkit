package com.alianga.jkit.notify;

import com.alianga.jkit.json.JSON;
import com.alianga.jkit.notify.channel.DingTalkChannel;
import com.alianga.jkit.notify.channel.FeishuChannel;
import com.alianga.jkit.notify.channel.WecomChannel;

import org.junit.Test;

import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * 平台错误码 → {@link FailureType} 映射，以及长度上限、@人内联规则的黑盒测试。
 *
 * @author 郑明亮
 * @since 2.0.1
 */
public class ChannelBehaviourTest extends AbstractHttpChannelTest {

    /**
     * 钉钉：130101 发送过快归 THROTTLED（可重试但要更长退避）。
     */
    @Test
    public void dingtalkThrottled() {
        respond(200, "{\"errcode\":130101,\"errmsg\":\"send too fast\"}");
        SendResult r = NotificationManager.send(DingTalkChannel.ID,
                Message.text("hi"), ChannelConfig.webhook(baseUrl()));
        assertFalse(r.isSuccess());
        assertEquals(FailureType.THROTTLED, r.failureType());
        assertTrue(r.isRetryable());
    }

    /**
     * 钉钉：310000 加签/关键词问题归 CONFIG_ERROR，不该重试。
     */
    @Test
    public void dingtalkConfigError() {
        respond(200, "{\"errcode\":310000,\"errmsg\":\"sign not match\"}");
        SendResult r = NotificationManager.send(DingTalkChannel.ID,
                Message.text("hi"), ChannelConfig.webhook(baseUrl()));
        assertEquals(FailureType.CONFIG_ERROR, r.failureType());
        assertFalse(r.isRetryable());
    }

    /**
     * HTTP 5xx 归 RETRYABLE，429 归 THROTTLED，其它 4xx 归 CONFIG_ERROR。
     */
    @Test
    public void httpStatusClassification() {
        respond(503, "unavailable");
        assertEquals(FailureType.RETRYABLE, NotificationManager.send(DingTalkChannel.ID,
                Message.text("hi"), ChannelConfig.webhook(baseUrl())).failureType());

        respond(429, "too many");
        assertEquals(FailureType.THROTTLED, NotificationManager.send(DingTalkChannel.ID,
                Message.text("hi"), ChannelConfig.webhook(baseUrl())).failureType());

        respond(404, "not found");
        assertEquals(FailureType.CONFIG_ERROR, NotificationManager.send(DingTalkChannel.ID,
                Message.text("hi"), ChannelConfig.webhook(baseUrl())).failureType());
    }

    /**
     * HTTP 200 但业务码未识别时保守归 PERMANENT（重发同内容无意义）。
     */
    @Test
    public void unknownBusinessCodeIsPermanent() {
        respond(200, "{\"errcode\":999999,\"errmsg\":\"unknown\"}");
        SendResult r = NotificationManager.send(DingTalkChannel.ID,
                Message.text("hi"), ChannelConfig.webhook(baseUrl()));
        assertEquals(FailureType.PERMANENT, r.failureType());
        assertFalse(r.isRetryable());
    }

    /**
     * 网络不可达归 RETRYABLE。
     */
    @Test
    public void networkErrorIsRetryable() {
        SendResult r = NotificationManager.send(DingTalkChannel.ID, Message.text("hi"),
                ChannelConfig.webhook("http://127.0.0.1:1/x").timeoutMs(2000));
        assertEquals(FailureType.RETRYABLE, r.failureType());
        assertTrue(r.isRetryable());
    }

    /**
     * 企微：45009 超限归 THROTTLED，-1 系统繁忙归 RETRYABLE。
     */
    @Test
    public void wecomClassification() {
        respond(200, "{\"errcode\":45009,\"errmsg\":\"api freq out of limit\"}");
        assertEquals(FailureType.THROTTLED, NotificationManager.send(WecomChannel.ID,
                Message.text("hi"), ChannelConfig.webhook(baseUrl())).failureType());

        respond(200, "{\"errcode\":-1,\"errmsg\":\"system busy\"}");
        assertEquals(FailureType.RETRYABLE, NotificationManager.send(WecomChannel.ID,
                Message.text("hi"), ChannelConfig.webhook(baseUrl())).failureType());
    }

    /**
     * 飞书：9499 归 THROTTLED，19021 签名失败归 CONFIG_ERROR。
     */
    @Test
    public void feishuClassification() {
        respond(200, "{\"code\":9499,\"msg\":\"too many request\"}");
        assertEquals(FailureType.THROTTLED, NotificationManager.send(FeishuChannel.ID,
                Message.text("hi"), ChannelConfig.webhook(baseUrl())).failureType());

        respond(200, "{\"code\":19021,\"msg\":\"sign match fail\"}");
        assertEquals(FailureType.CONFIG_ERROR, NotificationManager.send(FeishuChannel.ID,
                Message.text("hi"), ChannelConfig.webhook(baseUrl())).failureType());
    }

    /**
     * 钉钉 @人：手机号未出现在正文时自动追加 {@code @手机号}，且 at 数组同时带上。
     */
    @Test
    public void dingtalkInlinesMissingAtMobile() {
        respond(200, "{\"errcode\":0}");
        NotificationManager.send(DingTalkChannel.ID,
                Message.text("服务异常").extra(Message.EXTRA_AT_MOBILES, "13800000000"),
                ChannelConfig.webhook(baseUrl()));
        Captured request = take();
        assertJsonEquals("{\"msgtype\":\"text\",\"text\":{\"content\":\"服务异常 @13800000000\"},"
                + "\"at\":{\"atMobiles\":[\"13800000000\"]}}", request.body());
    }

    /**
     * 钉钉 @人：正文里已含该手机号时不重复追加（含 markdown 装饰的情况）。
     */
    @Test
    public void dingtalkDoesNotDuplicateExistingAtMobile() {
        respond(200, "{\"errcode\":0}");
        NotificationManager.send(DingTalkChannel.ID,
                Message.markdown("告警", "**13800000000** 请处理")
                        .extra(Message.EXTRA_AT_MOBILES, "13800000000"),
                ChannelConfig.webhook(baseUrl()));
        Captured request = take();
        // 正文原样保留，未追加第二个 @手机号
        assertJsonEquals("{\"msgtype\":\"markdown\",\"markdown\":{\"title\":\"告警\","
                + "\"text\":\"**13800000000** 请处理\"},"
                + "\"at\":{\"atMobiles\":[\"13800000000\"]}}", request.body());
    }

    /**
     * 钉钉：超长正文被截断到 20000 字节以内并带标记。
     */
    @Test
    public void dingtalkTruncatesOverlongContent() {
        respond(200, "{\"errcode\":0}");
        StringBuilder longText = new StringBuilder();
        for (int i = 0; i < 10000; i++) {
            longText.append("汉字");
        }
        SendResult r = NotificationManager.send(DingTalkChannel.ID,
                Message.text(longText.toString()), ChannelConfig.webhook(baseUrl()));
        assertTrue(r.isSuccess());
        Captured request = take();
        String content = contentOf(request.body(), "text", "content");
        assertTrue("应被截断: " + content.length(),
                NotifyUtils.utf8Length(content) <= DingTalkChannel.MAX_CONTENT_BYTES);
        assertTrue(content.endsWith(NotifyUtils.TRUNCATE_SUFFIX));
    }

    /**
     * 企微：TEXT 上限 2048 字节，MARKDOWN 上限 4096 字节，两者不同。
     */
    @Test
    public void wecomAppliesDifferentLimitsPerType() {
        StringBuilder longText = new StringBuilder();
        for (int i = 0; i < 3000; i++) {
            longText.append("汉");
        }

        respond(200, "{\"errcode\":0}");
        NotificationManager.send(WecomChannel.ID,
                Message.text(longText.toString()), ChannelConfig.webhook(baseUrl()));
        String textContent = contentOf(take().body(), "text", "content");
        assertTrue(NotifyUtils.utf8Length(textContent) <= WecomChannel.MAX_TEXT_BYTES);

        respond(200, "{\"errcode\":0}");
        NotificationManager.send(WecomChannel.ID,
                Message.markdown("t", longText.toString()), ChannelConfig.webhook(baseUrl()));
        String mdContent = contentOf(take().body(), "markdown", "content");
        assertTrue(NotifyUtils.utf8Length(mdContent) <= WecomChannel.MAX_MARKDOWN_BYTES);

        // markdown 允许的字节数更多，所以保留的内容更长
        assertTrue(mdContent.length() > textContent.length());
    }

    private static String contentOf(String json, String outer, String inner) {
        Map<?, ?> root = JSON.parseObject(json);
        return String.valueOf(((Map<?, ?>) root.get(outer)).get(inner));
    }
}
