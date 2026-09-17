package com.alianga.jkit;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * 脱敏工具的回归：各类型的保留位数、边界输入、通用 mask 的兜底策略。
 */
public class DesensitizeUtilsTest {

    @Test
    public void phone() {
        assertEquals("138****8000", DesensitizeUtils.phone("13800138000"));
        assertEquals("138****8000", DesensitizeUtils.desensitize("13800138000", DesensitizeUtils.Type.PHONE));
    }

    @Test
    public void idCard() {
        assertEquals("110101********1234", DesensitizeUtils.idCard("110101199003071234"));
        // 15 位老身份证同样适用（尾部取最后 4 位）
        assertEquals("110101*****7123", DesensitizeUtils.idCard("110101900307123"));
    }

    @Test
    public void bankCard() {
        assertEquals("6222********1234", DesensitizeUtils.bankCard("6222020200011234"));
        // 带空格的卡号先去掉空格再脱敏
        assertEquals("6222********1234", DesensitizeUtils.bankCard("6222 0202 0001 1234"));
    }

    @Test
    public void name() {
        assertEquals("张*", DesensitizeUtils.name("张三"));
        assertEquals("欧**", DesensitizeUtils.name("欧阳修"));
        assertEquals("张*", DesensitizeUtils.name(" 张三 "));
        // 英文名不是纯中文，走兜底（保留首尾）
        assertEquals("J**n", DesensitizeUtils.name("John"));
    }

    @Test
    public void email() {
        assertEquals("z****@example.com", DesensitizeUtils.email("zheng@example.com"));
        assertEquals("*@example.com", DesensitizeUtils.email("z@example.com"));
        // 无 @ 时按兜底处理，不抛异常
        assertEquals("z***g", DesensitizeUtils.email("zheng"));
    }

    @Test
    public void address() {
        assertEquals("北京市海淀区*******", DesensitizeUtils.address("北京市海淀区中关村大街1号"));
        // 短地址：保留位数覆盖全文时只留首字符
        assertEquals("北*", DesensitizeUtils.address("北京"));
    }

    @Test
    public void carNo() {
        assertEquals("京A***45", DesensitizeUtils.carNo("京A12345"));
    }

    @Test
    public void ip() {
        assertEquals("192.168.*.*", DesensitizeUtils.ip("192.168.1.100"));
        // 非 IPv4 走兜底
        assertEquals("2*0", DesensitizeUtils.ip("200"));
    }

    @Test
    public void password() {
        assertEquals("******", DesensitizeUtils.password("123456"));
        // 不泄漏长度：超长密码也是 6 个星
        assertEquals("******", DesensitizeUtils.password("a-very-long-password"));
        assertEquals("******", DesensitizeUtils.password(""));
        assertNull(DesensitizeUtils.password(null));
    }

    @Test
    public void nullAndEmpty() {
        assertNull(DesensitizeUtils.phone(null));
        assertNull(DesensitizeUtils.idCard(null));
        assertNull(DesensitizeUtils.bankCard(null));
        assertNull(DesensitizeUtils.name(null));
        assertNull(DesensitizeUtils.email(null));
        assertNull(DesensitizeUtils.ip(null));
        assertNull(DesensitizeUtils.mask(null, 1, 1));
        assertNull(DesensitizeUtils.desensitize(null, DesensitizeUtils.Type.PHONE));
        assertEquals("", DesensitizeUtils.phone(""));
        assertEquals("", DesensitizeUtils.mask("", 1, 1));
    }

    @Test
    public void maskKeepsHeadAndTail() {
        assertEquals("ab****yz", DesensitizeUtils.mask("abcdefyz", 2, 2));
        assertEquals("ab####yz", DesensitizeUtils.mask("abcdefyz", 2, 2, '#'));
        assertEquals("******", DesensitizeUtils.mask("abcdef", 0, 0));
    }

    @Test
    public void maskNeverLeaksWhenKeepCoversWholeValue() {
        // 保留位数之和 >= 长度时不原样返回，只留首字符
        assertEquals("a***", DesensitizeUtils.mask("abcd", 2, 2));
        assertEquals("a**", DesensitizeUtils.mask("abc", 5, 5));
        assertEquals("1**", DesensitizeUtils.mask("123", 1, 2));
    }

    @Test
    public void maskShortInputKeepsOnlyFirstChar() {
        // 长度不足保留位数：宁可多打码
        assertEquals("1***", DesensitizeUtils.mask("1380", 3, 4));
        assertEquals(4, DesensitizeUtils.mask("1380", 3, 4).length());
        // 单字符无从打码，原样返回（不抛异常、不补位）
        assertEquals("1", DesensitizeUtils.mask("1", 1, 4));
    }

    @Test
    public void maskNegativeKeepIsTreatedAsZero() {
        assertEquals("****", DesensitizeUtils.mask("abcd", -1, -1));
        assertEquals("ab**", DesensitizeUtils.mask("abcd", 2, -5));
    }

    @Test
    public void maskAllKeepsLength() {
        assertEquals("*****", DesensitizeUtils.maskAll("abcde"));
        assertEquals("#####", DesensitizeUtils.maskAll("abcde", '#'));
        assertEquals("", DesensitizeUtils.maskAll(""));
        assertNull(DesensitizeUtils.maskAll(null));
    }

    @Test
    public void typeDispatchCoversAllTypes() {
        for (DesensitizeUtils.Type type : DesensitizeUtils.Type.values()) {
            String out = DesensitizeUtils.desensitize("13800138000", type);
            assertTrue("脱敏结果不应为空: " + type, out != null && !out.isEmpty());
        }
        // type 为 null 时按 DEFAULT 处理
        assertEquals("1*********0", DesensitizeUtils.desensitize("13800138000", null));
    }

    @Test
    public void defaultValueKeepsFirstAndLast() {
        assertEquals("1*********0", DesensitizeUtils.defaultMask("13800138000"));
    }
}
