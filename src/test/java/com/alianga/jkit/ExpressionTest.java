package com.alianga.jkit;

import com.alianga.jkit.expression.Expression;
import org.junit.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.Assert.assertEquals;

public class ExpressionTest {
    @Test
    public void testArithmeticPrecedence() {
        assertEquals(7L, Expression.eval("1 + 2 * 3"));
    }

    @Test
    public void testEvaluateMapContext() {
        Map<String, Object> context = new HashMap<String, Object>();
        context.put("price", 12);
        context.put("count", 3);
        assertEquals(36L, Expression.eval("price * count", context));
    }

    @Test
    public void testBuiltInFunction() {
        assertEquals(9L, Expression.eval("@max(3, 9, 5)"));
    }

    @Test
    public void testRenderTemplate() {
        Map<String, Object> context = new HashMap<String, Object>();
        context.put("name", "Tom");
        context.put("age", 18);
        assertEquals("Tom is 18", Expression.renderTemplate("${name} is ${age}", context));
    }
}
