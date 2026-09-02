package com.alianga.jkit;

import org.junit.Test;

import java.util.Arrays;
import java.util.Base64;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

public class Base64CompareTest {
    // ==================== Base64Utils 基础测试 ====================

    @Test
    public void testEncode_byteArray() {
        assertEncodeEquals(new byte[0]);
        assertEncodeEquals(new byte[]{1});
        assertEncodeEquals(new byte[]{1, 2});
        assertEncodeEquals(new byte[]{1, 2, 3});
        assertEncodeEquals(new byte[]{1, 2, 3, 4});
        assertEncodeEquals(new byte[]{(byte) 0xFF, (byte) 0x00, (byte) 0x80});
        assertEncodeEquals("Hello, Base64!".getBytes());
        assertEncodeEquals("hello world 中文测试".getBytes());
    }

    private void assertEncodeEquals(byte[] src) {
        byte[] enc = Base64Utils.encode(src);
        char[] chars = new char[((src.length + 2) / 3) << 2];
        Base64Utils.encode(src, chars, 0);
        assertArrayEquals(new String(enc).toCharArray(), chars);
        assertArrayEquals(src, Base64Utils.decode(new String(enc)));
        assertArrayEquals(src, Base64Utils.decode(enc));
    }

    @Test
    public void testEncodeToString() {
        assertEquals("aGVsbG8=", Base64Utils.encodeToString("hello".getBytes()));
    }

    @Test
    public void testEncodeIntoByteArray() {
        byte[] src = "Hello".getBytes();
        byte[] dst = new byte[((src.length + 2) / 3) << 2];
        int n = Base64Utils.encode(src, dst, 0);
        assertEquals(8, n);
        assertArrayEquals("SGVsbG8=".getBytes(), Arrays.copyOf(dst, n));
    }

    @Test
    public void testEncodeIntoCharArray() {
        byte[] src = "Hello".getBytes();
        char[] dst = new char[((src.length + 2) / 3) << 2];
        int n = Base64Utils.encode(src, dst, 0);
        assertEquals(8, n);
        assertArrayEquals("SGVsbG8=".toCharArray(), Arrays.copyOf(dst, n));
    }

    @Test
    public void testEncodeWithOffset() {
        byte[] src = "Hello".getBytes();
        byte[] dst = new byte[((src.length + 2) / 3) << 2 + 4];
        int n = Base64Utils.encode(src, dst, 4);
        assertEquals(8, n);
        assertArrayEquals("SGVsbG8=".getBytes(), Arrays.copyOfRange(dst, 4, 12));
    }

    @Test
    public void testDecode_string() {
        assertDecodeEquals("SGVsbG8=");
        assertDecodeEquals("SGVsbG8gV29ybGQh");
        assertDecodeEquals("AQIDBAUGBwg=");
        assertDecodeEquals("AQID");
        assertDecodeEquals("");
    }

    private void assertDecodeEquals(String encoded) {
        byte[] r1 = Base64Utils.decode(encoded);
        byte[] r2 = Base64Utils.decode(encoded.toCharArray());
        assertArrayEquals(r1, r2);
        assertEquals(encoded, Base64Utils.encodeToString(r1));
    }

    @Test
    public void testDecode_byteArray() {
        byte[] encoded = "SGVsbG8=".getBytes();
        assertArrayEquals("Hello".getBytes(), Base64Utils.decode(encoded));
        assertArrayEquals("Hello".getBytes(), Base64Utils.decode(encoded, 0, encoded.length));
    }

    @Test
    public void testDecode_charArray() {
        char[] encoded = "SGVsbG8=".toCharArray();
        assertArrayEquals("Hello".getBytes(), Base64Utils.decode(encoded));
        assertArrayEquals("Hello".getBytes(), Base64Utils.decode(encoded, 0, encoded.length));
    }

    @Test
    public void testDecode_invalidLength() {
        assertThrows(IllegalArgumentException.class, () -> Base64Utils.decode("abc"));
        assertThrows(IllegalArgumentException.class, () -> Base64Utils.decode("abcde"));
        assertThrows(IllegalArgumentException.class, () -> Base64Utils.decode("abc".getBytes()));
    }

    @Test
    public void testDecode_invalidChar() {
        assertThrows(IllegalArgumentException.class, () -> Base64Utils.decode("!!!="));
        assertThrows(IllegalArgumentException.class, () -> Base64Utils.decode("!!!=".getBytes()));
    }

    @Test
    public void testDecode_paddingCases() {
        assertArrayEquals("a".getBytes(), Base64Utils.decode("YQ=="));
        assertArrayEquals("A".getBytes(), Base64Utils.decode("QQ=="));
        byte[] r = Base64Utils.decode("QQQ=");
        assertEquals(2, r.length);
        assertEquals('A', (char) r[0]);
    }

    @Test
    public void testEncodeString_andDecodeString() {
        String original = "hello world 中文测试";
        String encoded = Base64Utils.encodeString(original);
        String decoded = Base64Utils.decodeString(encoded);
        assertEquals(original, decoded);
    }

    @Test
    public void testEncodeToString_withChinese() {
        String original = "hello world 中文测试";
        String encoded = Base64Utils.encodeToString(original.getBytes());
        String decoded = new String(Base64Utils.decode(encoded));
        assertEquals(original, decoded);
    }

    // ==================== JDK Base64 对比测试 ====================

    @Test
    public void testCompareEncode_withJDK() {
        assertJdkEncodeEquals(new byte[0]);
        assertJdkEncodeEquals(new byte[]{1});
        assertJdkEncodeEquals(new byte[]{1, 2});
        assertJdkEncodeEquals(new byte[]{1, 2, 3});
        assertJdkEncodeEquals(new byte[]{1, 2, 3, 4});
        assertJdkEncodeEquals(new byte[]{(byte) 0xFF, (byte) 0x00, (byte) 0x80});
        assertJdkEncodeEquals("Hello, Base64!".getBytes());
        assertJdkEncodeEquals("hello world 中文测试".getBytes());
        assertJdkEncodeEquals(new byte[]{(byte) 0xFF, (byte) 0xFF, (byte) 0xFF});
    }

    private void assertJdkEncodeEquals(byte[] src) {
        assertEquals(Base64.getEncoder().encodeToString(src), Base64Utils.encodeToString(src));
    }

    @Test
    public void testCompareDecode_withJDK() {
        assertJdkDecodeEquals("SGVsbG8=");
        assertJdkDecodeEquals("SGVsbG8gV29ybGQh");
        assertJdkDecodeEquals("AQIDBAUGBwg=");
        assertJdkDecodeEquals("AQID");
        assertJdkDecodeEquals("AA==");
        assertJdkDecodeEquals("////");
        assertJdkDecodeEquals("////AAAA");
        assertJdkDecodeEquals("");
    }

    private void assertJdkDecodeEquals(String encoded) {
        assertArrayEquals(Base64.getDecoder().decode(encoded), Base64Utils.decode(encoded));
    }

    @Test
    public void testCompareRoundTrip_withJDK() {
        byte[][] testCases = {
                new byte[0], new byte[]{0}, new byte[]{1, 2}, new byte[]{1, 2, 3},
                new byte[]{1, 2, 3, 4, 5}, "Hello, Base64!".getBytes(),
                "hello world 中文测试".getBytes(),
                new byte[]{(byte) 0xFF, (byte) 0x00, (byte) 0x80, (byte) 0x7F},
        };
        for (byte[] src : testCases) {
            String jdkEnc = Base64.getEncoder().encodeToString(src);
            String coderEnc = Base64Utils.encodeToString(src);
            assertEquals(jdkEnc, coderEnc);
            assertArrayEquals(src, Base64.getDecoder().decode(jdkEnc));
            assertArrayEquals(src, Base64Utils.decode(coderEnc));
            // 交叉验证
            assertArrayEquals(src, Base64.getDecoder().decode(coderEnc));
            assertArrayEquals(src, Base64Utils.decode(jdkEnc));
        }
    }

    @Test
    public void testCompareEmptyInput_withJDK() {
        assertEquals("", Base64Utils.encodeToString(new byte[0]));
        assertEquals("", Base64.getEncoder().encodeToString(new byte[0]));
        assertArrayEquals(new byte[0], Base64Utils.decode(""));
        assertArrayEquals(new byte[0], Base64.getDecoder().decode(""));
    }

    @Test
    public void testCompareInvalidLength_withJDK() {
        assertThrows(IllegalArgumentException.class, () -> Base64Utils.decode("abc"));
        assertThrows(IllegalArgumentException.class, () -> Base64Utils.decode("abcde"));
        assertThrows(IllegalArgumentException.class, () -> Base64.getDecoder().decode("abcde"));
    }

    @Test
    public void testCompareInvalidChar_withJDK() {
        assertThrows(IllegalArgumentException.class, () -> Base64Utils.decode("!!!="));
        assertThrows(IllegalArgumentException.class, () -> Base64.getDecoder().decode("!!!="));
    }

    // ==================== 宽容解码对比测试 ====================

    @Test
    public void testDecodeLenient_withJDK() {
        assertJdkLenientEquals(""); // "" -> []
        assertJdkLenientEquals("QQ"); // 2 -> [65]
        assertJdkLenientEquals("QQQ"); // 3 -> [65, 4]
        assertJdkLenientEquals("QQQQ"); // 4 -> [65, 4, 16]
        assertJdkLenientEquals("QQQQQ"); // 5 -> THROW
        assertJdkLenientEquals("QQQQQQ"); // 6 -> [65, 4, 16, 65]
        assertJdkLenientEquals("QQQQQQQ"); // 7 -> [65, 4, 16, 65, 4]
        assertJdkLenientEquals("QQQQQQQQ"); // 8 -> [65, 4, 16, 65, 4, 16]
        assertJdkLenientEquals("YQ"); // 2 -> [97]
        assertJdkLenientEquals(""); // 合法 padding
        assertJdkLenientEquals(".equals(YQ)YQ="); // 3 + padding -> THROW
        assertJdkLenientEquals("ab"); // 2 -> [105]
        assertJdkLenientEquals("abc"); // 3 -> [105, -73]
        assertJdkLenientEquals("abcd"); // 4 -> [105, -73, 29]
        assertJdkLenientEquals("abcde"); // 5 -> THROW
        assertJdkLenientEquals("===="); // 全 padding -> THROW
        assertJdkLenientEquals("a"); // 1 -> THROW
        assertJdkLenientEquals("a="); // 1 + padding -> THROW
        assertJdkLenientEquals("ab="); // 2 + padding -> THROW
        assertJdkLenientEquals("abc="); // 3 + padding -> [105, -73]
        assertJdkLenientEquals("abcd="); // 4 + padding -> THROW
        assertJdkLenientEquals("QQ="); // 2 + padding -> THROW
        assertJdkLenientEquals(""); // 1 + padding -> THROW
    }

    private void assertJdkLenientEquals(String encoded) {
        byte[] jdkDec;
        try { jdkDec = Base64.getDecoder().decode(encoded); }
        catch (IllegalArgumentException e) { jdkDec = null; }
        byte[] coderDec;
        try { coderDec = Base64Utils.decodeLenient(encoded); }
        catch (IllegalArgumentException e) { coderDec = null; }
        if (jdkDec == null) {
            assertEquals(".equals(Q)lenient mismatch for: " + encoded + " (expected throw)", null, coderDec);
        } else {
            assertArrayEquals("lenient mismatch for: " + encoded, jdkDec, coderDec);
        }
    }

    // ==================== MIME 宽容解码对比测试 ====================

    @Test
    public void testDecodeMime_withJDK() {
        assertJdkMimeEquals("SGVsbG8="); // no whitespace -> Hello
        assertJdkMimeEquals("SGVs bG8="); // space -> Hello
        assertJdkMimeEquals("abc"); // lenient
        assertJdkMimeEquals(""); // lenient
    }

    private void assertJdkMimeEquals(String encoded) {
        byte[] jdkDec = Base64.getMimeDecoder().decode(encoded);
        byte[] coderDec = Base64Utils.decodeMime(encoded);
        assertArrayEquals(".equals(ab)mime mismatch for: " + encoded, jdkDec, coderDec);
    }

    // ==================== URL-safe 对比 ====================

    @Test
    public void testCompareUrlSafe_withJDK() {
        byte[] src = new byte[]{(byte) 0xFF, (byte) 0xFF, (byte) 0xFF};
        assertEquals("////", Base64.getEncoder().encodeToString(src));
        assertEquals("////", Base64Utils.encodeToString(src));
        assertEquals("____", Base64.getUrlEncoder().encodeToString(src));

        byte[] src2 = new byte[]{(byte) 0xFF, (byte) 0x80, (byte) 0xFF};
        String stdEnc = Base64.getEncoder().encodeToString(src2);
        String urlEnc = Base64.getUrlEncoder().encodeToString(src2);
        assertEquals("/4D/", stdEnc);
        assertEquals("_4D_", urlEnc);
        assertEquals(stdEnc, Base64Utils.encodeToString(src2));
    }

    // ==================== 性能测试 ====================

    @Test
    public void testCompareEncodePerformance_withJDK() {
        byte[] src = new byte[1024 * 1024];
        for (int i = 0; i < src.length; i++) {
            src[i] = (byte) i;
        }
        for (int i = 0; i < 10; i++) { Base64Utils.encodeToString(src); Base64.getEncoder().encodeToString(src); }
        long t0 = System.nanoTime();
        for (int i = 0; i < 100; i++) {
            Base64Utils.encodeToString(src);
        }
        long tCoder = System.nanoTime() - t0;
        t0 = System.nanoTime();
        for (int i = 0; i < 100; i++) {
            Base64.getEncoder().encodeToString(src);
        }
        long tJdk = System.nanoTime() - t0;
        System.out.printf("[性能对比 encode] Base64Utils: %d ms, JDK Base64: %d ms%n",
                tCoder / 1_000_000, tJdk / 1_000_000);
    }

    @Test
    public void testCompareDecodePerformance_withJDK() {
        byte[] src = new byte[1024 * 1024];
        for (int i = 0; i < src.length; i++) {
            src[i] = (byte) i;
        }
        String encoded = Base64Utils.encodeToString(src);
        for (int i = 0; i < 10; i++) { Base64Utils.decode(encoded); Base64.getDecoder().decode(encoded); }
        long t0 = System.nanoTime();
        for (int i = 0; i < 100; i++) {
            Base64Utils.decode(encoded);
        }
        long tCoder = System.nanoTime() - t0;
        t0 = System.nanoTime();
        for (int i = 0; i < 100; i++) {
            Base64.getDecoder().decode(encoded);
        }
        long tJdk = System.nanoTime() - t0;
        System.out.printf("[性能对比 decode] Base64Utils: %d ms, JDK Base64: %d ms%n",
                tCoder / 1_000_000, tJdk / 1_000_000);
    }
}
