package com.alianga.jkit.notify;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

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

        SendResult timed = SendResult.fail("c", "timeout", 123L, FailureType.RETRYABLE);
        assertEquals(123L, timed.elapsedMs());
        assertTrue(timed.isRetryable());
    }

    /**
     * 附件拆包按块切开，SHA-256 稳定。
     */
    @Test
    public void splitBytesAndSha256() {
        byte[] data = "abcdefghijkl".getBytes(StandardCharsets.UTF_8);
        assertEquals(2, NotifyUtils.splitBytes(data, 6).size());
        assertEquals(12, NotifyUtils.splitBytes(data, 6).get(0).length
                + NotifyUtils.splitBytes(data, 6).get(1).length);
        assertEquals(64, NotifyUtils.sha256Hex(data).length());
        assertEquals(NotifyUtils.sha256Hex(data), NotifyUtils.sha256Hex(data));
        assertEquals(32, NotifyUtils.md5Hex(data).length());
    }

    /**
     * 按文件名后缀与 ZIP 文件头识别 MIME，无需调用方手填。
     */
    @Test
    public void detectMimeTypeFromNameAndMagic() {
        assertEquals("image/png", NotifyUtils.detectMimeType("logo.png", new byte[]{0}));
        assertEquals("image/jpeg", NotifyUtils.detectMimeType("photo.JPG", null));
        assertEquals("application/zip", NotifyUtils.detectMimeType("pkg.zip", null));
        assertEquals("text/plain", NotifyUtils.detectMimeType("notes.md", null));
        byte[] zipHeader = new byte[]{0x50, 0x4b, 0x03, 0x04, 0x14, 0x00, 0x00, 0x00, 0x08, 0x00};
        assertEquals("application/zip", NotifyUtils.detectMimeType("unknown.bin", zipHeader));
        assertEquals("application/octet-stream", NotifyUtils.detectMimeType("noext", new byte[]{1, 2, 3}));
        Attachment png = Attachment.of("a.png", new byte[]{1});
        assertEquals("image/png", png.contentType());
    }

    /**
     * 响应式文档壳带 viewport 与媒体查询，非响应式只有 charset。
     */
    @Test
    public void markdownDocumentResponsiveOption() {
        String responsive = NotifyUtils.markdownToDocument("## 标题", true);
        assertTrue(responsive.contains("viewport"));
        assertTrue(responsive.contains("@media"));
        assertTrue(responsive.contains("<h2>标题</h2>"));
        String plain = NotifyUtils.markdownToDocument("## 标题", false);
        assertTrue(plain.contains("<h2>标题</h2>"));
        assertTrue(!plain.contains("@media"));
    }

    /**
     * {@code ${key}} / {@code ${a.b}} 替换；缺键变空串；值内占位符不二次展开。
     */
    @Test
    public void renderTemplateNestedAndMissing() {
        Map<String, Object> nested = new LinkedHashMap<String, Object>();
        nested.put("name", "web-1");
        Map<String, Object> vars = new LinkedHashMap<String, Object>();
        vars.put("host", nested);
        vars.put("value", "95%");
        vars.put("raw", "keep ${value}");
        assertEquals("CPU web-1 = 95%",
                NotifyUtils.renderTemplate("CPU ${host.name} = ${value}", vars));
        assertEquals("x=", NotifyUtils.renderTemplate("x=${missing}", vars));
        assertEquals("keep ${value}", NotifyUtils.renderTemplate("${raw}", vars));
        assertEquals("plain", NotifyUtils.renderTemplate("plain", vars));
        assertNull(NotifyUtils.renderTemplate(null, vars));
    }
}
