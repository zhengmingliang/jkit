package com.alianga.jkit.mock;

import org.junit.Assert;
import org.junit.Test;

/**
 * MockRegex 正则反向生成单元测试。
 *
 * @author 郑明亮
 */
public class MockRegexTest {

    @Test
    public void testPhone() {
        for (int i = 0; i < 50; i++) {
            String value = MockRegex.generate("^1[3-9]\\d{9}$");
            Assert.assertTrue("手机号：" + value, value.matches("1[3-9]\\d{9}"));
        }
    }

    @Test
    public void testFixedLength() {
        for (int i = 0; i < 50; i++) {
            String value = MockRegex.generate("[A-Z]{3}\\d{4}");
            Assert.assertTrue("编码：" + value, value.matches("[A-Z]{3}\\d{4}"));
        }
    }

    @Test
    public void testRangeLength() {
        for (int i = 0; i < 50; i++) {
            String value = MockRegex.generate("[a-z]{6,10}");
            int len = value.length();
            Assert.assertTrue("长度：" + len, len >= 6 && len <= 10);
            Assert.assertTrue(value.matches("[a-z]+"));
        }
    }

    @Test
    public void testDashPattern() {
        for (int i = 0; i < 30; i++) {
            String value = MockRegex.generate("\\d{3}-\\d{4}");
            Assert.assertTrue("号码：" + value, value.matches("\\d{3}-\\d{4}"));
        }
    }

    @Test
    public void testAlternation() {
        for (int i = 0; i < 30; i++) {
            String value = MockRegex.generate("(a|b|c)+");
            Assert.assertTrue("选择：" + value, value.matches("[abc]+"));
        }
    }

    @Test
    public void testBackReference() {
        for (int i = 0; i < 30; i++) {
            String value = MockRegex.generate("(\\d)\\1\\1");
            Assert.assertEquals("反向引用：" + value, 3, value.length());
            Assert.assertEquals(value.charAt(0), value.charAt(1));
            Assert.assertEquals(value.charAt(1), value.charAt(2));
        }
    }

    @Test
    public void testCharClass() {
        for (int i = 0; i < 30; i++) {
            String value = MockRegex.generate("[abc]{4}");
            Assert.assertTrue("字符集：" + value, value.matches("[abc]{4}"));
        }
    }

    @Test
    public void testNegatedCharClass() {
        for (int i = 0; i < 30; i++) {
            String value = MockRegex.generate("[^abc]{4}");
            Assert.assertEquals(4, value.length());
            Assert.assertFalse(value.matches("[abc]+"));
        }
    }

    @Test
    public void testQuantifiers() {
        Assert.assertTrue(MockRegex.generate("[ab]*").matches("[ab]*"));
        Assert.assertTrue(MockRegex.generate("[ab]+").matches("[ab]+"));
        Assert.assertEquals(0, MockRegex.generate("[ab]{0}").length());
    }

    @Test
    public void testEscapeAndDot() {
        Assert.assertTrue(MockRegex.generate("\\d\\d").matches("\\d{2}"));
        Assert.assertTrue(MockRegex.generate("\\w+").matches("\\w+"));
        Assert.assertEquals(3, MockRegex.generate("...").length());
    }

    @Test
    public void testLookaheadKept() {
        // 与 Mock.js 一致：前瞻分组照常生成内容，如 /abc(?=\d)/ => "abc8"
        for (int i = 0; i < 20; i++) {
            String value = MockRegex.generate("abc(?=\\d)");
            Assert.assertTrue("前瞻：" + value, value.matches("abc\\d"));
        }
    }

    @Test
    public void testEmailLike() {
        for (int i = 0; i < 20; i++) {
            String value = MockRegex.generate("[a-z]{5}@[a-z]{3}\\.(com|cn)");
            Assert.assertTrue("邮箱：" + value, value.matches("[a-z]{5}@[a-z]{3}\\.(com|cn)"));
        }
    }
}
