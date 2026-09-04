package com.alianga.jkit.notify;

import org.junit.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

/**
 * UTF-8 字节安全截断与失败分类的单元测试。
 *
 * @author 郑明亮
 * @since 2.0.1
 */
public class NotifyUtilsTruncateTest {

    /**
     * UTF-8 字节长度按字节计：中文 3 字节、emoji 4 字节。
     */
    @Test
    public void utf8LengthCountsBytes() {
        assertEquals(0, NotifyUtils.utf8Length(null));
        assertEquals(3, NotifyUtils.utf8Length("abc"));
        assertEquals(9, NotifyUtils.utf8Length("中文字"));
        assertEquals(4, NotifyUtils.utf8Length("😀"));
    }

    /**
     * 未超限时返回原对象，不做任何拷贝。
     */
    @Test
    public void withinLimitReturnsSameInstance() {
        String text = "短消息";
        assertSame(text, NotifyUtils.truncateUtf8(text, 100));
        assertNull(NotifyUtils.truncateUtf8(null, 100));
        // 非正数上限表示不限制
        assertSame(text, NotifyUtils.truncateUtf8(text, 0));
        assertSame(text, NotifyUtils.truncateUtf8(text, -1));
    }

    /**
     * 恰好等于上限时不截断（边界）。
     */
    @Test
    public void exactLimitNotTruncated() {
        String text = "中文";
        assertSame(text, NotifyUtils.truncateUtf8(text, 6));
    }

    /**
     * 超限时含标记在内不超过上限，且不切断多字节字符。
     */
    @Test
    public void truncatedResultFitsLimitAndKeepsValidUtf8() {
        StringBuilder longText = new StringBuilder();
        for (int i = 0; i < 200; i++) {
            longText.append("汉字");
        }
        int limit = 100;
        String result = NotifyUtils.truncateUtf8(longText.toString(), limit);

        assertTrue("含标记在内应不超上限，实际 " + NotifyUtils.utf8Length(result),
                NotifyUtils.utf8Length(result) <= limit);
        assertTrue("应带截断标记: " + result, result.endsWith(NotifyUtils.TRUNCATE_SUFFIX));

        // 关键断言：字节序列往返不产生替换字符，证明没有把汉字劈成半个
        byte[] bytes = result.getBytes(StandardCharsets.UTF_8);
        assertEquals(result, new String(bytes, StandardCharsets.UTF_8));
        assertEquals(-1, result.indexOf('\uFFFD'));
    }

    /**
     * emoji（代理对）不被劈开。
     */
    @Test
    public void surrogatePairNotSplit() {
        StringBuilder emojis = new StringBuilder();
        for (int i = 0; i < 50; i++) {
            emojis.append("😀");
        }
        String result = NotifyUtils.truncateUtf8(emojis.toString(), 60);
        assertTrue(NotifyUtils.utf8Length(result) <= 60);
        assertEquals(-1, result.indexOf('\uFFFD'));
        // 去掉标记后剩余部分的长度必须是代理对的整数倍（每个 emoji 占 2 个 char）
        String body = result.substring(0, result.length() - NotifyUtils.TRUNCATE_SUFFIX.length());
        assertEquals(0, body.length() % 2);
    }

    /**
     * 上限比截断标记本身还短时退化为纯截断，仍不超上限。
     */
    @Test
    public void limitShorterThanSuffixDegradesGracefully() {
        String result = NotifyUtils.truncateUtf8("很长很长的中文内容", 5);
        assertTrue(NotifyUtils.utf8Length(result) <= 5);
        assertEquals(-1, result.indexOf('\uFFFD'));
    }

    /**
     * FailureType 的可重试语义。
     */
    @Test
    public void failureTypeRetryableSemantics() {
        assertTrue(FailureType.RETRYABLE.isRetryable());
        assertTrue(FailureType.THROTTLED.isRetryable());
        assertTrue(!FailureType.CONFIG_ERROR.isRetryable());
        assertTrue(!FailureType.PERMANENT.isRetryable());
        assertTrue(!FailureType.NONE.isRetryable());
    }

    /**
     * 成功结果永不可重试；失败结果按类别透出。
     */
    @Test
    public void sendResultRetryableFlag() {
        assertTrue(!SendResult.ok("c", 200, "ok", 1L).isRetryable());
        assertEquals(FailureType.NONE, SendResult.ok("c", 200, "ok", 1L).failureType());

        // 无响应的网络失败默认可重试
        SendResult networkFail = SendResult.fail("c", "timeout");
        assertTrue(networkFail.isRetryable());
        assertEquals(FailureType.RETRYABLE, networkFail.failureType());

        SendResult configFail = SendResult.fail("c", 200, "{}", "bad secret", 1L, FailureType.CONFIG_ERROR);
        assertTrue(!configFail.isRetryable());
        assertEquals(FailureType.CONFIG_ERROR, configFail.failureType());
        assertTrue(configFail.toString().contains("CONFIG_ERROR"));
    }
}
