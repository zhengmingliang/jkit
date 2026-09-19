package com.alianga.jkit.expression;

import org.junit.Assert;
import org.junit.Test;

import java.util.HashMap;
import java.util.Map;

/**
 * 表达式求值器回归测试：锁定运算符优先级、短路求值、关系比较、类型强制、内置函数、变量与三元等既有语义，
 * 并固化本次修复的两个正确性问题——{@code &&}/{@code ||} 的短路求值，以及字符串的关系比较。
 */
public class ExpressionRegressionTest {

    private static Object e(String expr) {
        return Expression.eval(expr);
    }

    private static Object e(String expr, Map<String, Object> ctx) {
        return Expression.eval(expr, ctx);
    }

    private static boolean b(String expr) {
        return (Boolean) e(expr);
    }

    private static Number num(String expr) {
        return (Number) e(expr);
    }

    private static Number num(String expr, Map<String, Object> ctx) {
        return (Number) e(expr, ctx);
    }

    private static String str(String expr) {
        return (String) e(expr);
    }

    private static String str(String expr, Map<String, Object> ctx) {
        return (String) e(expr, ctx);
    }

    private static boolean b(String expr, Map<String, Object> ctx) {
        return (Boolean) e(expr, ctx);
    }

    // ------------------------------------------------------------------
    // 本次修复一：&& / || 短路求值（右操作数按需求值，不应触发其副作用/异常）
    // ------------------------------------------------------------------

    @Test
    public void logicalOrShortCircuitsWhenLeftTrue() {
        // 左为真，右操作数 (1/0) 不被求值，不应抛除零异常
        Assert.assertTrue(b("true || (1/0)"));
    }

    @Test
    public void logicalAndShortCircuitsWhenLeftFalse() {
        // 左为假，右操作数 (1/0) 不被求值
        Assert.assertFalse(b("false && (1/0)"));
    }

    @Test
    public void logicalOrEvaluatesRightWhenLeftFalse() {
        // 左为假时必须求右操作数，除零异常照常抛出（证明不是“永远不求值”）
        Assert.assertThrows(ExpressionException.class, () -> e("false || (1/0)"));
    }

    @Test
    public void logicalAndEvaluatesRightWhenLeftTrue() {
        // 左为真时必须求右操作数，除零异常照常抛出
        Assert.assertThrows(ExpressionException.class, () -> e("true && (1/0)"));
    }

    @Test
    public void logicalShortCircuitNested() {
        // 嵌套：内层 false && (1/0) 短路为假，外层 || true 短路为真
        Assert.assertTrue(b("(false && (1/0)) || true"));
        // 内层 true || (1/0) 短路为真，外层 && false 不短路、但左已为真无需看右
        Assert.assertFalse(b("true || (1/0) && false"));
    }

    @Test
    public void logicalAndOrBasicValues() {
        Assert.assertTrue(b("true && true"));
        Assert.assertFalse(b("true && false"));
        Assert.assertTrue(b("false || true"));
        Assert.assertFalse(b("false || false"));
    }

    // ------------------------------------------------------------------
    // 本次修复二：关系运算符支持字符串（按字典序），数字仍按数值
    // ------------------------------------------------------------------

    @Test
    public void stringRelationalComparison() {
        Assert.assertTrue(b("'abc' < 'abd'"));
        Assert.assertFalse(b("'abc' > 'abd'"));
        Assert.assertTrue(b("'abc' <= 'abc'"));
        Assert.assertTrue(b("'abc' >= 'abc'"));
        Assert.assertFalse(b("'abc' >= 'abd'"));
        // 前缀：'ab' 是 'abc' 的前缀，字典序更小
        Assert.assertTrue(b("'ab' < 'abc'"));
    }

    @Test
    public void numericRelationalComparisonUnchanged() {
        Assert.assertTrue(b("3 > 2"));
        Assert.assertTrue(b("2 >= 2"));
        Assert.assertTrue(b("3.5 < 4"));
        Assert.assertFalse(b("5 <= 4"));
    }

    @Test
    public void relationalIncomparableTypesThrowClearError() {
        Assert.assertThrows(ExpressionException.class, () -> e("'a' > 1"));
        Assert.assertThrows(ExpressionException.class, () -> e("1 < 'a'"));
    }

    // ------------------------------------------------------------------
    // 既有语义回归：算术与运算符优先级
    // ------------------------------------------------------------------

    @Test
    public void arithmeticPrecedence() {
        Assert.assertEquals(7L, num("1+2*3").longValue());   // 先乘后加
        Assert.assertEquals(9L, num("(1+2)*3").longValue()); // 括号优先
        Assert.assertEquals(-2L, num("-5+3").longValue());
        Assert.assertEquals(-5L, num("-(5)").longValue());
        Assert.assertEquals(1L, num("10%3").longValue());
    }

    @Test
    public void divisionTruncatesForIntegersKeepsDoubleForDecimals() {
        Assert.assertEquals(3L, num("7/2").longValue());
        Assert.assertEquals(3.5, num("7.0/2").doubleValue(), 0.0);
        Assert.assertEquals(3.5, num("7/2.0").doubleValue(), 0.0);
    }

    @Test
    public void powerAndXorAreDistinct() {
        Assert.assertEquals(1024.0, num("2**10").doubleValue(), 0.0); // ** 是幂
        Assert.assertEquals(8L, num("2^10").longValue());            // ^ 是按位异或
        Assert.assertEquals(2.0, num("4**0.5").doubleValue(), 1e-9);  // 幂支持小数指数（平方根）
        Assert.assertEquals(512.0, num("2**9").doubleValue(), 0.0);
    }

    @Test
    public void bitwiseOperators() {
        Assert.assertEquals(1L, num("5&3").longValue());
        Assert.assertEquals(10L, num("2|8").longValue());
        Assert.assertEquals(16L, num("1<<4").longValue());
        Assert.assertEquals(4L, num("16>>2").longValue());
    }

    @Test
    public void equalityAndTypeCoercion() {
        Assert.assertTrue(b("3 == 3.0"));      // 数字按值比较
        Assert.assertFalse(b("1 == '1'"));      // 不同类型不相等
        Assert.assertTrue(b("'a' == 'a'"));
        Assert.assertTrue(b("'a' != 'b'"));
        Assert.assertTrue(b("null == null"));
        Assert.assertFalse(b("null != null"));
    }

    @Test
    public void logicalNegation() {
        Assert.assertFalse(b("!true"));
        Assert.assertTrue(b("!!true"));
        Assert.assertTrue(b("!false"));
    }

    @Test
    public void ternaryPrecedence() {
        Assert.assertEquals("yes", str("1>0 ? 'yes':'no'"));
        Assert.assertEquals(1L, num("1>0 ? 1 : 2 + 10").longValue());   // 三元低于 +，即 1>0 ? 1 : (2+10)
        Assert.assertEquals(12L, num("1>0 ? 2 + 10 : 1").longValue());
    }

    @Test
    public void stringConcatenation() {
        Assert.assertEquals("abcd", str("'ab'+'cd'"));
        Assert.assertEquals("x123", str("'x'+123"));
        Assert.assertEquals("123x", str("123+'x'"));
    }

    @Test
    public void nullInArithmeticTreatedAsZero() {
        Assert.assertEquals(1L, num("null + 1").longValue());
    }

    // ------------------------------------------------------------------
    // 既有语义回归：内置函数
    // ------------------------------------------------------------------

    @Test
    public void builtinMathFunctions() {
        Assert.assertEquals(3L, num("max(1,2,3)").longValue());
        Assert.assertEquals(1L, num("min(1,2,3)").longValue());
        Assert.assertEquals(5.0, num("abs(-5)").doubleValue(), 0.0);
        Assert.assertEquals(4.0, num("sqrt(16)").doubleValue(), 0.0);
        Assert.assertEquals(6L, num("sum(1,2,3)").longValue());
        Assert.assertEquals(3.0, num("avg(2,4)").doubleValue(), 0.0);
    }

    @Test
    public void builtinStringFunctions() {
        Assert.assertEquals(5, num("length('hello')").intValue());
        Assert.assertEquals("ABC", str("upper('abc')"));
        Assert.assertEquals("abc", str("lower('ABC')"));
    }

    @Test
    public void builtinNullAndDateFunctions() {
        Assert.assertEquals("x", str("ifNull(null, 'x')"));
        Assert.assertNotNull(e("now()"));
    }

    // ------------------------------------------------------------------
    // 既有语义回归：变量与成员调用
    // ------------------------------------------------------------------

    @Test
    public void variableAccessAndArithmetic() {
        Map<String, Object> ctx = new HashMap<String, Object>();
        ctx.put("a", 5);
        ctx.put("b", 3);
        ctx.put("name", "zk");
        Assert.assertEquals(8L, num("a+b", ctx).longValue());
        Assert.assertEquals(16L, num("(a+b)*2", ctx).longValue());
        Assert.assertEquals("big", str("a>b ? 'big':'small'", ctx));
        Assert.assertEquals("zk!", str("name + '!'", ctx));
    }

    @Test
    public void methodInvocationOnVariable() {
        Map<String, Object> ctx = new HashMap<String, Object>();
        ctx.put("s", "HELLO");
        Assert.assertEquals("hello", str("s.toLowerCase()", ctx));
        Assert.assertEquals("HELLO", str("s.toUpperCase()", ctx));
    }

    // ------------------------------------------------------------------
    // 修复回归：解析后的表达式复用求值（第 3 次起走压缩后的 Impl 快速路径），
    // 快速路径此前仍是急切求值 + (Number) 强转，短路与字符串比较在复用场景失效
    // ------------------------------------------------------------------

    @Test
    public void shortCircuitSurvivesEvaluatorCompression() {
        Map<String, Object> falseCtx = new HashMap<String, Object>();
        falseCtx.put("flag", Boolean.FALSE);
        Expression andExpr = Expression.parse("flag && (1/0)");
        for (int i = 0; i < 5; i++) {
            Assert.assertEquals("复用求值第 " + (i + 1) + " 次仍应短路", Boolean.FALSE, andExpr.evaluate(falseCtx));
        }
        Map<String, Object> trueCtx = new HashMap<String, Object>();
        trueCtx.put("flag", Boolean.TRUE);
        Expression orExpr = Expression.parse("flag || (1/0)");
        for (int i = 0; i < 5; i++) {
            Assert.assertEquals("复用求值第 " + (i + 1) + " 次仍应短路", Boolean.TRUE, orExpr.evaluate(trueCtx));
        }
    }

    @Test
    public void stringRelationalCompareSurvivesEvaluatorCompression() {
        Map<String, Object> ctx = new HashMap<String, Object>();
        ctx.put("a", "abc");
        ctx.put("b", "abd");
        for (String expr : new String[]{"a < b", "a <= b", "b > a", "b >= a"}) {
            Expression parsed = Expression.parse(expr);
            for (int i = 0; i < 5; i++) {
                Assert.assertEquals("表达式 " + expr + " 复用求值第 " + (i + 1) + " 次",
                        Boolean.TRUE, parsed.evaluate(ctx));
            }
        }
    }
}
