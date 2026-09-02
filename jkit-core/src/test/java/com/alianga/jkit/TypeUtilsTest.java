package com.alianga.jkit;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class TypeUtilsTest {
    @Test
    public void testIsBoxed() {
        assertTrue(TypeUtils.isBoxed(Integer.class));
        assertTrue(TypeUtils.isBoxed(Boolean.class));
        assertFalse(TypeUtils.isBoxed(int.class));
        assertFalse(TypeUtils.isBoxed(String.class));
    }

    @Test
    public void testGetBoxedType() {
        assertEquals(Integer.class, TypeUtils.getBoxedType(int.class));
        assertEquals(Long.class, TypeUtils.getBoxedType(long.class));
        assertEquals(Boolean.class, TypeUtils.getBoxedType(boolean.class));
        // 非基本类型原样返回
        assertEquals(String.class, TypeUtils.getBoxedType(String.class));
    }
}
