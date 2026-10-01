package com.alianga.jkit.mock.server;

import org.junit.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * {@link MockHttpServer} 日志截断：文本按字符截断附长度提示；字节先限字节再解码，不整段进堆。
 *
 * @author 郑明亮
 */
public class MockHttpServerTruncateTest {

    @Test
    public void shortTextKeptAsIs() {
        assertEquals("abc", MockHttpServer.truncateForLog("abc", 10));
        assertEquals("", MockHttpServer.truncateForLog("", 10));
        assertEquals("", MockHttpServer.truncateForLog(null, 10));
        assertEquals("", MockHttpServer.truncateForLog("abc", 0));
    }

    @Test
    public void longTextTruncatedWithLengthHint() {
        String value = repeat("a", 3000);
        String out = MockHttpServer.truncateForLog(value, MockHttpServer.LOG_TEXT_MAX);
        assertTrue(out.startsWith(repeat("a", MockHttpServer.LOG_TEXT_MAX)));
        assertTrue(out.endsWith("(3000 chars)"));
    }

    @Test
    public void bytesTruncatedWithByteHint() {
        // 12000 字节 > maxChars*4=8192，才会走字节截断分支并标原始字节数
        byte[] big = repeat("x", 12000).getBytes(StandardCharsets.UTF_8);
        String out = MockHttpServer.truncateBytesForLog(big, MockHttpServer.LOG_TEXT_MAX);
        assertTrue(out.contains("(12000 bytes)"));
    }

    @Test
    public void multibyteUtf8NotCutMidCharacter() {
        // 1000 个「中」= 3000 字节，未超 2048*4 字节限制，但解码 1000 字符超过 maxChars 时按字符截断
        String text = repeat("中", 1000);
        String out = MockHttpServer.truncateBytesForLog(text.getBytes(StandardCharsets.UTF_8), 100);
        assertEquals(repeat("中", 100) + "…(3000 bytes)", out);
    }

    @Test
    public void decodePathKeepsPlusAndDecodesPercent() {
        assertEquals("/a+b", MockHttpServer.decodePath("/a+b"));
        assertEquals("/a+b", MockHttpServer.decodePath("/a%2Bb"));
        assertEquals("/a b", MockHttpServer.decodePath("/a%20b"));
        assertEquals("/报告.pdf", MockHttpServer.decodePath("/%E6%8A%A5%E5%91%8A.pdf"));
        assertEquals("/plain", MockHttpServer.decodePath("/plain"));
        assertEquals("/bad%zz", MockHttpServer.decodePath("/bad%zz"));
    }

    private static String repeat(String s, int times) {
        StringBuilder sb = new StringBuilder(s.length() * times);
        for (int i = 0; i < times; i++) {
            sb.append(s);
        }
        return sb.toString();
    }
}
