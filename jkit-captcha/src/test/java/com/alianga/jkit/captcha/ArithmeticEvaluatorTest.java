package com.alianga.jkit.captcha;

import org.junit.Assert;
import org.junit.Test;

/**
 * 算术表达式求值。
 */
public class ArithmeticEvaluatorTest {

    @Test
    public void testAdditionAndSubtraction() {
        Assert.assertEquals(22, ArithmeticEvaluator.evaluate("14+8=?").result);
        Assert.assertEquals("14+8", ArithmeticEvaluator.evaluate("14+8=?").expression);
        Assert.assertEquals(4, ArithmeticEvaluator.evaluate("27-23=?").result);
        Assert.assertEquals(45, ArithmeticEvaluator.evaluate("40 + 5 = ?").result);
    }

    @Test
    public void testMultiplyDivideAndAliases() {
        Assert.assertEquals(12, ArithmeticEvaluator.evaluate("3*4").result);
        Assert.assertEquals(12, ArithmeticEvaluator.evaluate("3x4").result);
        Assert.assertEquals(12, ArithmeticEvaluator.evaluate("3X4").result);
        Assert.assertEquals(12, ArithmeticEvaluator.evaluate("3×4").result);
        Assert.assertEquals(4, ArithmeticEvaluator.evaluate("8/2=?").result);
        Assert.assertEquals(4, ArithmeticEvaluator.evaluate("9/2").result);
        Assert.assertNull(ArithmeticEvaluator.evaluate("8/0"));
    }

    @Test
    public void testChainsMultiplyBeforeAdd() {
        Assert.assertEquals(13, ArithmeticEvaluator.evaluate("8+6-1=?").result);
        Assert.assertEquals("8+6-1", ArithmeticEvaluator.evaluate("8+6-1=?").expression);
        Assert.assertEquals(14, ArithmeticEvaluator.evaluate("2+3x4").result);
        Assert.assertEquals("2+3x4", ArithmeticEvaluator.evaluate("2+3x4").expression);
        Assert.assertEquals(2, ArithmeticEvaluator.evaluate("8-2x3").result);
        Assert.assertNull(ArithmeticEvaluator.evaluate("29t187"));
        Assert.assertNull(ArithmeticEvaluator.evaluate("30t47"));
        Assert.assertNull(ArithmeticEvaluator.evaluate("5t4e2"));
    }

    @Test
    public void testRejectsIncompleteExpressions() {
        Assert.assertNull(ArithmeticEvaluator.evaluate(null));
        Assert.assertNull(ArithmeticEvaluator.evaluate(""));
        Assert.assertNull(ArithmeticEvaluator.evaluate("12"));
        Assert.assertNull(ArithmeticEvaluator.evaluate("12+"));
        Assert.assertNull(ArithmeticEvaluator.evaluate("+12"));
        Assert.assertNull(ArithmeticEvaluator.evaluate("a+1"));
        Assert.assertNull(ArithmeticEvaluator.evaluate("=?"));
    }
}
