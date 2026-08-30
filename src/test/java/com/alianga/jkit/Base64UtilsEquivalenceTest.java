package com.alianga.jkit;

import org.junit.Test;

import java.util.Arrays;
import java.util.Base64;
import java.util.Random;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * {@link Base64Utils} 两条整块实现的等价性测试。
 *
 * <p>{@code encodeToString} / {@code encode(byte[])} / {@code decode(String)} 会按运行时 JDK 版本
 * 在「本类手写实现」和 {@link java.util.Base64} 之间切换（低版本手写更快，高版本 JDK 有 intrinsic）。
 * 这个切换成立的前提是<b>两条实现对任何输入都给出相同结果、相同异常</b>，否则就会变成
 * 「换个 JDK 跑出来的行为不一样」的隐蔽 bug。本类就是守这条不变量的，
 * 因此每个用例都同时调用两条实现并互相比对，而不是只测公开入口。
 */
public class Base64UtilsEquivalenceTest {

    // ==================== 编码等价 ====================

    @Test
    public void encode_bothImplementationsAgreeOnAllLengths() {
        Random rnd = new Random(20260829L);
        // 覆盖 0~200 全部长度，确保三种补位（无 '='、一个 '='、两个 '='）都被覆盖
        for (int len = 0; len <= 200; len++) {
            byte[] data = new byte[len];
            rnd.nextBytes(data);
            assertEncodeAgrees(data);
        }
    }

    @Test
    public void encode_bothImplementationsAgreeOnEdgeBytes() {
        assertEncodeAgrees(new byte[0]);
        assertEncodeAgrees(new byte[]{0});
        assertEncodeAgrees(new byte[]{(byte) 0xFF});
        assertEncodeAgrees(new byte[]{0, 0, 0});
        assertEncodeAgrees(new byte[]{(byte) 0xFF, (byte) 0xFF, (byte) 0xFF});
        assertEncodeAgrees(new byte[]{(byte) 0xFB, (byte) 0xFF, (byte) 0xBF});
        // 所有单字节取值
        for (int i = 0; i < 256; i++) {
            assertEncodeAgrees(new byte[]{(byte) i});
            assertEncodeAgrees(new byte[]{(byte) i, (byte) i});
        }
    }

    @Test
    public void encode_bothImplementationsAgreeOnLargePayload() {
        byte[] data = new byte[256 * 1024 + 7];
        new Random(1L).nextBytes(data);
        assertEncodeAgrees(data);
    }

    private void assertEncodeAgrees(byte[] data) {
        String jdk = Base64.getEncoder().encodeToString(data);
        String internal = Base64Utils.encodeToStringInternal(data);
        String api = Base64Utils.encodeToString(data);

        assertEquals("手写实现与 JDK 编码结果不一致, len=" + data.length, jdk, internal);
        assertEquals("公开入口与 JDK 编码结果不一致, len=" + data.length, jdk, api);

        // byte[] 版本同样要一致
        assertArrayEquals("encodeInternal(byte[]) 与 JDK 不一致, len=" + data.length,
                Base64.getEncoder().encode(data), Base64Utils.encodeInternal(data));
        assertArrayEquals("encode(byte[]) 与 JDK 不一致, len=" + data.length,
                Base64.getEncoder().encode(data), Base64Utils.encode(data));
    }

    // ==================== 解码等价 ====================

    @Test
    public void decode_bothImplementationsAgreeOnAllLengths() {
        Random rnd = new Random(20260830L);
        for (int len = 0; len <= 200; len++) {
            byte[] data = new byte[len];
            rnd.nextBytes(data);
            assertDecodeAgrees(Base64.getEncoder().encodeToString(data), data);
        }
    }

    @Test
    public void decode_bothImplementationsAgreeOnLargePayload() {
        byte[] data = new byte[256 * 1024 + 5];
        new Random(2L).nextBytes(data);
        assertDecodeAgrees(Base64.getEncoder().encodeToString(data), data);
    }

    private void assertDecodeAgrees(String encoded, byte[] expected) {
        byte[] jdk = Base64.getDecoder().decode(encoded);
        byte[] internal = Base64Utils.decodeInternal(encoded);
        byte[] api = Base64Utils.decode(encoded);

        assertArrayEquals("JDK 解码结果不符预期", expected, jdk);
        assertArrayEquals("手写实现与 JDK 解码结果不一致, len=" + encoded.length(), jdk, internal);
        assertArrayEquals("公开入口与 JDK 解码结果不一致, len=" + encoded.length(), jdk, api);
    }

    /**
     * 非法输入上「公开入口」与「手写实现」必须完全一致（都抛或都不抛、同类异常）。
     *
     * <p>注意这里的金标准是<b>手写实现</b>而不是 JDK：{@code Base64Utils.decode} 的既有契约要求
     * 长度必须是 4 的整数倍（必须显式补位），而 JDK 的解码器比这宽松——它接受最后一组不补位，
     * 例如 {@code "abc"} 会被 JDK 解成 2 字节。所以公开入口在委托给 JDK 之前会补一道长度校验，
     * 否则同一份数据会「在 JDK 8 上抛异常、在 JDK 11+ 上却能解开」。
     * 这个用例就是守这条不变量的。
     */
    @Test
    public void decode_publicApiMatchesInternalOnInvalidInput() {
        String[] invalid = {
                "a",                    // 长度 1，非 4 的倍数
                "ab",                   // 长度 2，JDK 接受、本库拒绝
                "abc",                  // 长度 3，JDK 接受、本库拒绝
                "aaaaa",                // 长度 5
                "abcdef",               // 长度 6
                "abcdefa",              // 长度 7
                "a===",                 // 补位过多
                "====",                 // 全补位
                "ab$d",                 // 非法字符
                "ab\u00ffd",            // 非 ASCII
                "YWJj ZGVm",            // 中间有空格，严格解码不接受
                "YWJj\nZGVm",           // 中间有换行，严格解码不接受
                "a=aa",                 // '=' 位置非法
                "aa=a",                 // '=' 位置非法
                "-_-_",                 // URL 安全字母表，标准解码器不接受
        };
        for (String s : invalid) {
            Throwable apiError = catchThrowable(() -> Base64Utils.decode(s));
            Throwable internalError = catchThrowable(() -> Base64Utils.decodeInternal(s));

            String desc = "输入=" + escape(s)
                    + " 公开入口=" + describe(apiError) + " 手写=" + describe(internalError);
            assertEquals("公开入口与手写实现对非法输入的处理必须一致 -> " + desc,
                    internalError != null, apiError != null);

            if (internalError == null) {
                assertArrayEquals("两者结果不一致 -> " + desc,
                        Base64Utils.decodeInternal(s), Base64Utils.decode(s));
            } else {
                assertTrue("手写实现应抛 IllegalArgumentException, 实际 " + describe(internalError),
                        internalError instanceof IllegalArgumentException);
                assertTrue("公开入口应抛 IllegalArgumentException, 实际 " + describe(apiError),
                        apiError instanceof IllegalArgumentException);
            }
        }
    }

    /**
     * 显式记录本库与 JDK 在补位上的差异，以及公开入口已经把差异消掉。
     *
     * <p>如果哪天有人想「简化」掉 {@code decode(String)} 里的长度校验，这个用例会失败。
     */
    @Test
    public void decode_isStricterThanJdkAboutPadding() {
        // JDK 接受不补位的末组
        assertArrayEquals("JDK 应能解开不补位的 \"abc\"",
                new byte[]{(byte) 0x69, (byte) 0xb7}, Base64.getDecoder().decode("abc"));

        // 本库（两条路径）都必须拒绝
        for (String s : new String[]{"ab", "abc", "abcdef", "abcdefa"}) {
            assertTrue("公开入口应拒绝不补位的 " + s,
                    catchThrowable(() -> Base64Utils.decode(s)) instanceof IllegalArgumentException);
            assertTrue("手写实现应拒绝不补位的 " + s,
                    catchThrowable(() -> Base64Utils.decodeInternal(s)) instanceof IllegalArgumentException);
        }

        // 需要宽松语义时用 decodeLenient
        assertArrayEquals("decodeLenient 才是宽松入口",
                new byte[]{(byte) 0x69, (byte) 0xb7}, Base64Utils.decodeLenient("abc"));
    }

    /**
     * 合法输入上公开入口必须与当前 JDK 选中的那条实现结果一致（回归护栏）。
     */
    @Test
    public void publicApiMatchesBothImplementationsOnRoundTrip() {
        Random rnd = new Random(3L);
        for (int i = 0; i < 500; i++) {
            byte[] data = new byte[rnd.nextInt(300)];
            rnd.nextBytes(data);
            String encoded = Base64Utils.encodeToString(data);
            assertArrayEquals("往返失败, len=" + data.length, data, Base64Utils.decode(encoded));
            assertEquals("与 JDK 编码不一致, len=" + data.length,
                    Base64.getEncoder().encodeToString(data), encoded);
        }
    }

    /**
     * 零拷贝 API 是 JSON 模块的热路径，必须与整块编码结果一致（这条路径不参与版本切换）。
     */
    @Test
    public void zeroCopyApiMatchesBulkEncoding() {
        Random rnd = new Random(4L);
        for (int len = 0; len <= 128; len++) {
            byte[] data = new byte[len];
            rnd.nextBytes(data);
            String expected = Base64.getEncoder().encodeToString(data);

            char[] chars = new char[((len + 2) / 3) << 2];
            int written = Base64Utils.encode(data, chars, 0);
            assertEquals("写入长度应等于缓冲区长度, len=" + len, chars.length, written);
            assertEquals("零拷贝 char[] 编码与 JDK 不一致, len=" + len, expected, new String(chars));

            byte[] bytes = new byte[chars.length];
            int written2 = Base64Utils.encode(data, bytes, 0);
            assertEquals(bytes.length, written2);
            assertEquals("零拷贝 byte[] 编码与 JDK 不一致, len=" + len,
                    expected, new String(bytes, java.nio.charset.StandardCharsets.US_ASCII));

            // 带偏移量写入：前面留 3 个字节不许被覆盖
            byte[] withOffset = new byte[chars.length + 3];
            Arrays.fill(withOffset, (byte) '#');
            Base64Utils.encode(data, withOffset, 3);
            assertEquals("偏移量之前的内容被覆盖了", "###", new String(withOffset, 0, 3,
                    java.nio.charset.StandardCharsets.US_ASCII));
            assertEquals("带偏移量写入结果不正确, len=" + len, expected,
                    new String(withOffset, 3, chars.length, java.nio.charset.StandardCharsets.US_ASCII));
        }
    }

    /**
     * 从缓冲区范围解码（JSON 反序列化热路径）应与整块解码一致。
     */
    @Test
    public void rangeDecodeMatchesBulkDecoding() {
        Random rnd = new Random(5L);
        for (int len = 0; len <= 128; len++) {
            byte[] data = new byte[len];
            rnd.nextBytes(data);
            String encoded = Base64.getEncoder().encodeToString(data);

            // 模拟 JSON 场景：base64 内容被引号包在一个更大的缓冲区里
            String wrapped = "\"" + encoded + "\"";
            byte[] buf = wrapped.getBytes(java.nio.charset.StandardCharsets.US_ASCII);
            assertArrayEquals("从 byte[] 范围解码不一致, len=" + len,
                    data, Base64Utils.decode(buf, 1, encoded.length()));

            char[] cbuf = wrapped.toCharArray();
            assertArrayEquals("从 char[] 范围解码不一致, len=" + len,
                    data, Base64Utils.decode(cbuf, 1, encoded.length()));
        }
    }

    // ==================== 版本选择开关自身 ====================

    /**
     * 确认选择开关按当前运行的 JDK 生效，并且选中的实现确实与另一条等价。
     * 这个用例在 JDK 8 与 JDK 17 上都应通过，只是走的分支不同。
     */
    @Test
    public void versionSwitchSelectsAnEquivalentImplementation() {
        byte[] data = "版本切换等价性 version switch".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        String encoded = Base64Utils.encodeToString(data);

        assertEquals("无论走哪条分支，编码结果都应与 JDK 一致",
                Base64.getEncoder().encodeToString(data), encoded);
        assertEquals("无论走哪条分支，编码结果都应与手写实现一致",
                Base64Utils.encodeToStringInternal(data), encoded);

        assertArrayEquals("无论走哪条分支，解码结果都应与 JDK 一致",
                Base64.getDecoder().decode(encoded), Base64Utils.decode(encoded));
        assertArrayEquals("无论走哪条分支，解码结果都应与手写实现一致",
                Base64Utils.decodeInternal(encoded), Base64Utils.decode(encoded));
    }

    /** decodeString / encodeString 走 UTF-8，中文必须能往返。 */
    @Test
    public void stringApiRoundTripsUtf8() {
        String[] texts = {"", "a", "中文", "emoji 😀 混排", "特殊 +/= 字符"};
        for (String text : texts) {
            String encoded = Base64Utils.encodeString(text);
            assertEquals("编码应等于 UTF-8 字节的 base64: " + text,
                    Base64.getEncoder().encodeToString(
                            text.getBytes(java.nio.charset.StandardCharsets.UTF_8)),
                    encoded);
            assertEquals("往返失败: " + text, text, Base64Utils.decodeString(encoded));
        }
    }

    // ==================== 辅助 ====================

    private interface Block {
        void run();
    }

    private static Throwable catchThrowable(Block block) {
        try {
            block.run();
            return null;
        } catch (Throwable t) {
            return t;
        }
    }

    private static String describe(Throwable t) {
        return t == null ? "无异常" : t.getClass().getSimpleName();
    }

    private static String escape(String s) {
        StringBuilder sb = new StringBuilder("\"");
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\n') {
                sb.append("\\n");
            } else if (c == '\r') {
                sb.append("\\r");
            } else if (c < 0x20 || c > 0x7e) {
                sb.append(String.format("\\u%04x", (int) c));
            } else {
                sb.append(c);
            }
        }
        return sb.append('"').toString();
    }
}
