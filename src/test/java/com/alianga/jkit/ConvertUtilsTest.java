package com.alianga.jkit;

import com.alianga.jkit.convert.ConvertUtils;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class ConvertUtilsTest {
    @Test
    public void testBoolean() {
        assertTrue(ConvertUtils.toBoolean("true"));
        assertTrue(ConvertUtils.toBoolean("YES"));
        assertTrue(ConvertUtils.toBoolean(1, 1, "yes"));
    }

    @Test
    public void testNumber() {
        assertEquals(Integer.valueOf(12), ConvertUtils.toInteger("12.9", 0));
        assertEquals(1, ConvertUtils.toInt("yes"));
        assertEquals(1L, ConvertUtils.toLongValue("true"));
        assertEquals(3.14D, ConvertUtils.toDouble("3.14", 0D), 1e-9);
    }

    @Test
    public void testString() {
        assertEquals("", ConvertUtils.toNoneNullString(null));
        assertEquals("N/A", ConvertUtils.toNoneNullString("NULL", "N/A"));
        assertEquals("N/A", ConvertUtils.toNoneEmptyString("", "N/A"));
        assertEquals("value", ConvertUtils.toNoneEmptyString("value", "N/A"));
    }
}
