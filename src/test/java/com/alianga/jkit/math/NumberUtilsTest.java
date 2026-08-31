package com.alianga.jkit.math;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * {@link NumberUtils} 的数字判定测试。
 */
public class NumberUtilsTest {

    /**
     * 修复前 isNumber(String) 对 {@code null} 直接 NPE，对空串返回 true。
     */
    @Test
    public void isNumber_rejectsNullAndEmpty() {
        assertFalse(NumberUtils.isNumber((String) null));
        assertFalse(NumberUtils.isNumber(""));
        assertFalse(NumberUtils.isNumber((char[]) null));
        assertFalse(NumberUtils.isNumber(new char[0]));

        assertTrue(NumberUtils.isNotNumber((String) null));
        assertTrue(NumberUtils.isNotNumber(""));
    }

    /**
     * isNumber 是逐字符判定，只认 0-9。
     */
    @Test
    public void isNumber_onlyAcceptsPlainDigits() {
        assertTrue(NumberUtils.isNumber("0"));
        assertTrue(NumberUtils.isNumber("1234567890"));
        assertFalse("负号不属于 0-9", NumberUtils.isNumber("-1"));
        assertFalse("小数点不属于 0-9", NumberUtils.isNumber("1.5"));
        assertFalse(NumberUtils.isNumber("1e10"));
        assertFalse(NumberUtils.isNumber("12a"));
    }

    @Test
    public void isParsableNumber_acceptsJavaNumberLiterals() {
        // 整数与符号
        assertTrue(NumberUtils.isParsableNumber("1"));
        assertTrue(NumberUtils.isParsableNumber("0"));
        assertTrue(NumberUtils.isParsableNumber("-1"));
        assertTrue(NumberUtils.isParsableNumber("+1"));
        assertTrue(NumberUtils.isParsableNumber("007"));
        // 小数
        assertTrue(NumberUtils.isParsableNumber("1.5"));
        assertTrue(NumberUtils.isParsableNumber("-1.5"));
        assertTrue(NumberUtils.isParsableNumber(".5"));
        assertTrue(NumberUtils.isParsableNumber("-.5"));
        assertTrue(NumberUtils.isParsableNumber("1."));
        // 科学计数法
        assertTrue(NumberUtils.isParsableNumber("1e10"));
        assertTrue(NumberUtils.isParsableNumber("1E10"));
        assertTrue(NumberUtils.isParsableNumber("1e-10"));
        assertTrue(NumberUtils.isParsableNumber("1e+10"));
        assertTrue(NumberUtils.isParsableNumber("1.2e3"));
        // 十六进制
        assertTrue(NumberUtils.isParsableNumber("0x1F"));
        assertTrue(NumberUtils.isParsableNumber("0X1f"));
        assertTrue(NumberUtils.isParsableNumber("0xdeadBEEF"));
        // 类型后缀
        assertTrue(NumberUtils.isParsableNumber("123L"));
        assertTrue(NumberUtils.isParsableNumber("123l"));
        assertTrue(NumberUtils.isParsableNumber("1.5f"));
        assertTrue(NumberUtils.isParsableNumber("1.5F"));
        assertTrue(NumberUtils.isParsableNumber("1.5d"));
        assertTrue(NumberUtils.isParsableNumber("1.5D"));
    }

    @Test
    public void isParsableNumber_rejectsMalformedInput() {
        assertFalse(NumberUtils.isParsableNumber(null));
        assertFalse(NumberUtils.isParsableNumber(""));
        assertFalse("只有符号位", NumberUtils.isParsableNumber("-"));
        assertFalse(NumberUtils.isParsableNumber("+"));
        assertFalse(NumberUtils.isParsableNumber("abc"));
        assertFalse("两个小数点", NumberUtils.isParsableNumber("1.2.3"));
        assertFalse(NumberUtils.isParsableNumber("1..2"));
        assertFalse("缺少指数数值", NumberUtils.isParsableNumber("1e"));
        assertFalse(NumberUtils.isParsableNumber("1e+"));
        assertFalse(NumberUtils.isParsableNumber("1e-"));
        assertFalse("指数不能带小数", NumberUtils.isParsableNumber("1e2.5"));
        assertFalse("整型后缀不能与指数共存", NumberUtils.isParsableNumber("1e5L"));
        assertFalse(NumberUtils.isParsableNumber("--1"));
        assertFalse("缺少十六进制位", NumberUtils.isParsableNumber("0x"));
        assertFalse(NumberUtils.isParsableNumber("0X"));
        assertFalse("非法十六进制位", NumberUtils.isParsableNumber("0xG"));
        assertFalse(NumberUtils.isParsableNumber("0x1Fg"));
        assertFalse(NumberUtils.isParsableNumber("e5"));
        assertFalse(NumberUtils.isParsableNumber("."));
        assertFalse(NumberUtils.isParsableNumber("1 2"));
        assertFalse(NumberUtils.isParsableNumber("1,000"));
        assertFalse(NumberUtils.isParsableNumber("null"));
    }

    /**
     * 两个判定方法语义不同，不能互相替代。
     */
    @Test
    public void isNumber_andIsParsableNumber_haveDifferentSemantics() {
        assertFalse(NumberUtils.isNumber("-1.5e3"));
        assertTrue(NumberUtils.isParsableNumber("-1.5e3"));
        // 纯数字串两者都认
        assertTrue(NumberUtils.isNumber("123"));
        assertTrue(NumberUtils.isParsableNumber("123"));
    }
}
