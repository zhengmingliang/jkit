package com.alianga.jkit;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class DataUtilsTest {
    @Test
    public void testOrEqualsIgnoreCase() {
        assertTrue(DataUtils.orEqualsIgnoreCase("YES", "y", "yes", "true"));
        assertTrue(DataUtils.orEqualsIgnoreCase("YES", "yes"));
        assertFalse(DataUtils.orEqualsIgnoreCase("NO", "y", "yes"));
        assertFalse(DataUtils.orEqualsIgnoreCase(null, "y"));
        assertFalse(DataUtils.orEqualsIgnoreCase("y", (Object[]) null));
    }

    @Test
    public void testGetFirstNumber() {
        assertEquals("12.30", DataUtils.getFirstNumber("abc 12.30 元"));
        assertEquals("", DataUtils.getFirstNumber("无数字"));
    }
}
