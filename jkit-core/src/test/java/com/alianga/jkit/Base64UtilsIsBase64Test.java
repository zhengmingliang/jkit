package com.alianga.jkit;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Random;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * {@link Base64Utils#isBase64(String)} 与 {@link Base64Utils#isBase64Format(String)} 的测试。
 *
 * <p>{@code isBase64} 是从 common-model 的 {@code com.dtsz.cm.utils.Base64Utils} 迁移过来的启发式判断，
 * 行为刻意与原实现保持一致（解码 → 要求全为可打印 ASCII → 重新编码后与原串相同）。
 * 它有几处反直觉的地方（尤其是二进制内容返回 {@code false}），本类把这些行为逐条钉住，
 * 免得以后有人当成「格式校验」误用，或者「顺手修正」掉。
 */
public class Base64UtilsIsBase64Test {

    // ==================== isBase64：正例 ====================

    @Test
    public void isBase64_trueForEncodedPrintableAsciiText() {
        assertTrue(Base64Utils.isBase64(encode("hello")));
        assertTrue(Base64Utils.isBase64(encode("hello world")));
        assertTrue(Base64Utils.isBase64(encode("a")));
        assertTrue(Base64Utils.isBase64(encode("user:password")));
        assertTrue(Base64Utils.isBase64(encode("{\"k\":1}")));
        // 可打印 ASCII 的边界：空格(32) 与 ~(126)
        assertTrue(Base64Utils.isBase64(encode(" ")));
        assertTrue(Base64Utils.isBase64(encode("~")));
        assertTrue(Base64Utils.isBase64(encode(" !\"#$%&'()*+,-./0123456789:;<=>?@~")));
    }

    // ==================== isBase64：反例（含反直觉的） ====================

    /**
     * 最容易踩的坑：二进制内容的 base64 会被判为 false。
     * 这不是 bug，是这个启发式判断的定义使然——它只认「可打印文本」。
     */
    @Test
    public void isBase64_falseForEncodedBinaryData() {
        // PNG 文件头
        byte[] png = {(byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A};
        String encoded = Base64.getEncoder().encodeToString(png);
        assertTrue("前提：这确实是合法 base64", Base64Utils.isBase64Format(encoded));
        assertFalse("二进制内容按定义返回 false", Base64Utils.isBase64(encoded));

        // 随机二进制，绝大多数会含不可打印字节
        Random rnd = new Random(42);
        int falseCount = 0;
        for (int i = 0; i < 50; i++) {
            byte[] data = new byte[32];
            rnd.nextBytes(data);
            if (!Base64Utils.isBase64(Base64.getEncoder().encodeToString(data))) {
                falseCount++;
            }
        }
        assertTrue("随机二进制应基本都被判为 false，实际 " + falseCount + "/50", falseCount >= 45);
    }

    /**
     * 含中文的文本也会返回 false：UTF-8 解码后是非 ASCII 字符。
     */
    @Test
    public void isBase64_falseForNonAsciiText() {
        assertFalse(Base64Utils.isBase64(encode("中文")));
        assertFalse(Base64Utils.isBase64(encode("hello 中文")));
        assertFalse(Base64Utils.isBase64(encode("emoji 😀")));
    }

    /**
     * 含控制字符（如换行、制表符）的文本也返回 false，因为 &lt;32。
     */
    @Test
    public void isBase64_falseForTextWithControlChars() {
        assertFalse(Base64Utils.isBase64(encode("line1\nline2")));
        assertFalse(Base64Utils.isBase64(encode("a\tb")));
    }

    @Test
    public void isBase64_falseForNullAndEmpty() {
        assertFalse(Base64Utils.isBase64(null));
        assertFalse(Base64Utils.isBase64(""));
    }

    @Test
    public void isBase64_falseForMalformedInput() {
        assertFalse(Base64Utils.isBase64("not base64!!"));
        assertFalse(Base64Utils.isBase64("abc"));        // 长度非 4 的倍数
        assertFalse(Base64Utils.isBase64("ab$d"));       // 非法字符
        assertFalse(Base64Utils.isBase64("===="));
        assertFalse(Base64Utils.isBase64("-_-_"));       // URL 安全字母表
    }

    /**
     * 非规范形式（同一份数据的非标准编码）会返回 false，因为重新编码后与原串不等。
     */
    @Test
    public void isBase64_falseForNonCanonicalEncoding() {
        // MIME 折行形式
        assertFalse(Base64Utils.isBase64("aGVsbG8g\r\nd29ybGQ="));
        // 末组不补位（JDK 能解，但重新编码会补上 '='，于是不相等）
        assertFalse(Base64Utils.isBase64("aGVsbG8"));
    }

    /**
     * 原实现的 javadoc 就承认的局限：本身形似 base64 的普通文本无法区分。
     * 这里把它钉住，说明这是已知行为而不是缺陷。
     */
    @Test
    public void isBase64_cannotDistinguishTextThatItselfLooksLikeBase64() {
        // "MTIzNDU2" 解码为 "123456"，全可打印，且重新编码后一致
        assertTrue("已知局限：普通字符串也可能被判为 true", Base64Utils.isBase64("MTIzNDU2"));
        assertTrue("hello 的 base64 的 base64 同样如此", Base64Utils.isBase64(encode(encode("hello"))));
    }

    // ==================== isBase64Format ====================

    @Test
    public void isBase64Format_trueForAnyValidBase64IncludingBinary() {
        assertTrue(Base64Utils.isBase64Format(encode("hello")));
        assertTrue(Base64Utils.isBase64Format(encode("中文")));
        assertTrue(Base64Utils.isBase64Format(encode("line1\nline2")));

        Random rnd = new Random(7);
        for (int len = 1; len <= 64; len++) {
            byte[] data = new byte[len];
            rnd.nextBytes(data);
            String encoded = Base64.getEncoder().encodeToString(data);
            assertTrue("二进制的 base64 也应判为格式合法, len=" + len,
                    Base64Utils.isBase64Format(encoded));
        }
    }

    @Test
    public void isBase64Format_falseForInvalidInput() {
        assertFalse(Base64Utils.isBase64Format(null));
        assertFalse(Base64Utils.isBase64Format(""));
        assertFalse(Base64Utils.isBase64Format("abc"));      // 长度非 4 的倍数
        assertFalse(Base64Utils.isBase64Format("ab$d"));
        assertFalse(Base64Utils.isBase64Format("-_-_"));
        assertFalse(Base64Utils.isBase64Format("aGVsbG8g\r\nd29ybGQ="));
    }

    /**
     * 两个方法的关系：isBase64 为 true 时 isBase64Format 必然为 true（前者更严格），反之不成立。
     */
    @Test
    public void isBase64ImpliesIsBase64Format() {
        String[] samples = {encode("hello"), encode("中文"), "MTIzNDU2", "abc", "ab$d", "",
                Base64.getEncoder().encodeToString(new byte[]{(byte) 0x89, 0x50, 0x4E, 0x47})};
        for (String s : samples) {
            if (Base64Utils.isBase64(s)) {
                assertTrue("isBase64 为 true 时 isBase64Format 也必须为 true: " + s,
                        Base64Utils.isBase64Format(s));
            }
        }
    }

    /**
     * 与迁移来源（common-model 的实现）逐例对比，确认行为一致。
     * 这里内联一份源实现，避免依赖外部项目。
     */
    @Test
    public void isBase64_matchesOriginalImplementation() {
        String[] samples = {
                null, "", "hello", "abc", "ab$d", "====", "-_-_", "MTIzNDU2",
                encode("hello"), encode("hello world"), encode("中文"), encode("line1\nline2"),
                encode(" "), encode("~"), encode(encode("hello")),
                "aGVsbG8", "aGVsbG8g\r\nd29ybGQ=",
                Base64.getEncoder().encodeToString(new byte[]{(byte) 0x89, 0x50, 0x4E, 0x47}),
        };
        for (String s : samples) {
            assertEqualsBool("与源实现不一致, 输入=" + s,
                    originalIsBase64(s), Base64Utils.isBase64(s));
        }
    }

    private static void assertEqualsBool(String msg, boolean expected, boolean actual) {
        if (expected != actual) {
            throw new AssertionError(msg + " 期望=" + expected + " 实际=" + actual);
        }
    }

    /** common-model 中 Base64Utils.isBase64 的原实现，仅用于对比。 */
    private static boolean originalIsBase64(String encodeString) {
        if (encodeString == null || encodeString.isEmpty()) {
            return false;
        }
        int unPrintCount = 0;
        try {
            byte[] decode = Base64.getDecoder().decode(encodeString);
            String decodeStr = new String(decode, StandardCharsets.UTF_8);
            for (char c : decodeStr.toCharArray()) {
                if (c < 32 || c > 126) {
                    unPrintCount++;
                }
            }
            if (unPrintCount == 0) {
                String encodeToString = Base64.getEncoder()
                        .encodeToString(decodeStr.getBytes(StandardCharsets.UTF_8));
                return encodeToString.equals(encodeString);
            }
        } catch (Exception e) {
            return false;
        }
        return false;
    }

    private static String encode(String text) {
        return Base64.getEncoder().encodeToString(text.getBytes(StandardCharsets.UTF_8));
    }
}
