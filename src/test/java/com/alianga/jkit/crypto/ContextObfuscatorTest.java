package com.alianga.jkit.crypto;

import com.alianga.jkit.Base64Utils;
import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * {@link ContextObfuscator} 的行为与互操作性测试。
 *
 * <p>其中「黄金向量」用例锁定密文的字节格式：这些密文由迁入本库之前的实现产出，
 * 且存在与之互操作的前端实现，因此格式一旦被改动这些用例就会失败。</p>
 */
public class ContextObfuscatorTest {

    /**
     * 黄金向量：迁入本库前的实现产出的密文必须仍能被正确还原，否则说明字节格式被改动。
     */
    @Test
    public void deobfuscate_acceptsCipherTextFromLegacyImplementation() {
        assertEquals("1", text(ContextObfuscator.deobfuscate("Y9B0NWm4/3oia4aycw==", "zml", "123456")));
        assertEquals("1中文；",
                text(ContextObfuscator.deobfuscate("MHkmeJajg5tURZpes/98KXi0CqWD9w==", "zml", "123456")));
        assertEquals("1中文；",
                text(ContextObfuscator.deobfuscate("7sTFgZcEI17Yf5ZSkIWrE+ZeCqWD9w==", "zml", "123456")));
    }

    /**
     * 黄金向量：较长明文（跨多段密钥流）的历史密文同样必须能还原。
     */
    @Test
    public void deobfuscate_acceptsLongCipherTextFromLegacyImplementation() {
        byte[] plain = ContextObfuscator.deobfuscate(
                "UcFlkF+ieb8xzEr3nzfpNW+KXHIin2I7spZmQC90HAZiI45nypUdH6XKWeY=",
                "admin", "1786010668595");
        assertEquals("6g1pucjm5ki6lpi1b+UfKaNeFzZBaUZtnr8MYXdfEnE=", Base64Utils.encodeToString(plain));
    }

    /**
     * 各种长度的明文都应能原样往返，重点覆盖 32 字节密钥流分段边界。
     */
    @Test
    public void obfuscate_roundTripsAcrossKeyStreamSegmentBoundaries() {
        int[] lengths = {1, 5, 31, 32, 33, 63, 64, 65, 100, 256};
        for (int length : lengths) {
            byte[] plain = new byte[length];
            for (int i = 0; i < length; i++) {
                plain[i] = (byte) (i * 7 + 1);
            }
            String obfuscated = ContextObfuscator.obfuscate(plain, "zml", "123456");
            assertArrayEquals("长度 " + length + " 的明文应能原样还原",
                    plain, ContextObfuscator.deobfuscate(obfuscated, "zml", "123456"));
        }
    }

    /**
     * 密文长度应恒为 salt(8) + 明文长度 + 校验和(4)。
     */
    @Test
    public void obfuscate_producesExpectedCipherTextLayout() {
        byte[] plain = "hello".getBytes(StandardCharsets.UTF_8);
        byte[] raw = Base64Utils.decode(ContextObfuscator.obfuscate(plain, "zml", "123456"));
        assertEquals(8 + plain.length + 4, raw.length);
    }

    /**
     * 每次调用都掺入新的随机 salt，相同入参不应产生相同密文。
     */
    @Test
    public void obfuscate_producesDifferentCipherTextForSameInput() {
        byte[] plain = "same-plain-text".getBytes(StandardCharsets.UTF_8);
        String first = ContextObfuscator.obfuscate(plain, "zml", "123456");
        String second = ContextObfuscator.obfuscate(plain, "zml", "123456");
        assertFalse("随机 salt 应使两次密文不同", first.equals(second));
        // 但都必须能还原成同一明文
        assertArrayEquals(plain, ContextObfuscator.deobfuscate(first, "zml", "123456"));
        assertArrayEquals(plain, ContextObfuscator.deobfuscate(second, "zml", "123456"));
    }

    /**
     * 上下文不匹配时必须报错，而不是返回一段错误的明文。
     */
    @Test
    public void deobfuscate_rejectsMismatchedContext() {
        String obfuscated = ContextObfuscator.obfuscate("secret".getBytes(StandardCharsets.UTF_8), "zml", "123456");
        assertMismatch(obfuscated, "zml", "999999");
        assertMismatch(obfuscated, "other", "123456");
    }

    /**
     * 上下文顺序参与派生，交换顺序应被判定为不匹配。
     */
    @Test
    public void deobfuscate_isSensitiveToContextOrder() {
        String obfuscated = ContextObfuscator.obfuscate("secret".getBytes(StandardCharsets.UTF_8), "a", "b");
        assertMismatch(obfuscated, "b", "a");
    }

    /**
     * 上下文个数不同应被判定为不匹配。
     */
    @Test
    public void deobfuscate_isSensitiveToContextCount() {
        String obfuscated = ContextObfuscator.obfuscate("secret".getBytes(StandardCharsets.UTF_8), "a", "b");
        assertMismatch(obfuscated, "a");
    }

    /**
     * 密文任一字节被篡改都应被校验和发现。
     */
    @Test
    public void deobfuscate_detectsTamperedCipherText() {
        byte[] plain = "tamper-me".getBytes(StandardCharsets.UTF_8);
        byte[] raw = Base64Utils.decode(ContextObfuscator.obfuscate(plain, "zml", "123456"));
        // 翻转异或密文段中的一个 bit
        raw[8] = (byte) (raw[8] ^ 0x01);
        assertMismatch(Base64Utils.encodeToString(raw), "zml", "123456");
    }

    /**
     * 长度不足的密文应给出明确错误，而不是数组越界。
     */
    @Test
    public void deobfuscate_rejectsTooShortCipherText() {
        try {
            // 只有 11 字节，不足 salt(8) + 校验和(4)
            ContextObfuscator.deobfuscate(Base64Utils.encodeToString(new byte[11]), "zml", "123456");
            fail("长度不足的密文应抛出 IllegalArgumentException");
        } catch (IllegalArgumentException e) {
            assertTrue("异常信息应说明长度不足：" + e.getMessage(), e.getMessage().contains("too short"));
        }
    }

    /**
     * 空明文返回空字符串，空密文返回空数组，两端行为都不抛异常。
     */
    @Test
    public void obfuscate_handlesEmptyInput() {
        assertEquals("", ContextObfuscator.obfuscate(null, "zml", "123456"));
        assertEquals("", ContextObfuscator.obfuscate(new byte[0], "zml", "123456"));
        assertEquals(0, ContextObfuscator.deobfuscate(null, "zml", "123456").length);
        assertEquals(0, ContextObfuscator.deobfuscate("", "zml", "123456").length);
    }

    /**
     * 上下文中的 {@code null} 元素按空字符串处理，因此与显式传空串等价。
     */
    @Test
    public void obfuscate_treatsNullContextElementAsEmptyString() {
        byte[] plain = "x".getBytes(StandardCharsets.UTF_8);
        String obfuscated = ContextObfuscator.obfuscate(plain, null, "123456");
        assertArrayEquals(plain, ContextObfuscator.deobfuscate(obfuscated, "", "123456"));
    }

    /**
     * 不传上下文也应能正常往返（此时仅靠内置 pepper 与随机 salt 派生）。
     */
    @Test
    public void obfuscate_worksWithoutAnyContext() {
        byte[] plain = "no-context".getBytes(StandardCharsets.UTF_8);
        String obfuscated = ContextObfuscator.obfuscate(plain);
        assertArrayEquals(plain, ContextObfuscator.deobfuscate(obfuscated));
    }

    /**
     * 明文不应以任何形式直接出现在密文字节里。
     */
    @Test
    public void obfuscate_doesNotLeakPlainBytes() {
        byte[] plain = new byte[32];
        Arrays.fill(plain, (byte) 0x41);
        byte[] raw = Base64Utils.decode(ContextObfuscator.obfuscate(plain, "zml", "123456"));
        byte[] masked = Arrays.copyOfRange(raw, 8, 8 + plain.length);
        assertFalse("异或段不应等于明文", Arrays.equals(plain, masked));
    }

    private static void assertMismatch(String obfuscated, String... contexts) {
        try {
            ContextObfuscator.deobfuscate(obfuscated, contexts);
            fail("上下文不匹配或密文被篡改时应抛出 IllegalArgumentException");
        } catch (IllegalArgumentException e) {
            assertTrue("异常信息应指明校验失配：" + e.getMessage(),
                    e.getMessage().contains("checksum mismatch"));
        }
    }

    private static String text(byte[] bytes) {
        return new String(bytes, StandardCharsets.UTF_8);
    }
}
