package com.alianga.jkit.collection;

import org.junit.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

public class MapsNumberParseTest {

    private Map<String, Object> mapWith(Object value) {
        Map<String, Object> map = new HashMap<String, Object>();
        map.put("v", value);
        return map;
    }

    @Test
    public void testParseWholeNumberString() {
        assertEquals(123, Maps.getNumber(mapWith("123"), "v").intValue());
        assertEquals(Integer.valueOf(123), Maps.getInteger(mapWith("123"), "v"));
        assertEquals(Long.valueOf(123L), Maps.getLong(mapWith("123"), "v"));
    }

    @Test
    public void testParseDecimalString() {
        assertEquals(Double.valueOf(1.5D), Maps.getDouble(mapWith("1.5"), "v"));
        assertEquals(Integer.valueOf(1), Maps.getInteger(mapWith("1.5"), "v"));
    }

    @Test
    public void testParseGroupingSeparator() {
        // 默认 Locale 下 "1,234" 是带千分位的合法数字
        assertEquals(Integer.valueOf(1234), Maps.getInteger(mapWith("1,234"), "v"));
    }

    @Test
    public void testTrimBeforeParse() {
        assertEquals(Integer.valueOf(42), Maps.getInteger(mapWith(" 42 "), "v"));
    }

    @Test
    public void testPartialParseIsRejected() {
        assertNull("前缀可解析但整串不是数字时应返回 null", Maps.getNumber(mapWith("123abc"), "v"));
        assertNull(Maps.getNumber(mapWith("1.2.3"), "v"));
        assertNull("日期串不应被截断成 2024", Maps.getNumber(mapWith("2024-01"), "v"));
        assertNull(Maps.getInteger(mapWith("2024-01"), "v"));
    }

    @Test
    public void testNonParseableStringReturnsNull() {
        assertNull(Maps.getNumber(mapWith("abc"), "v"));
        assertNull(Maps.getNumber(mapWith(""), "v"));
        assertNull(Maps.getNumber(mapWith("   "), "v"));
        assertNull(Maps.getNumber(mapWith(null), "v"));
    }

    @Test
    public void testNumberValuePassesThrough() {
        assertEquals(Integer.valueOf(7), Maps.getNumber(mapWith(Integer.valueOf(7)), "v"));
        assertEquals(Double.valueOf(2.5D), Maps.getDouble(mapWith(Double.valueOf(2.5D)), "v"));
    }

    @Test
    public void testNullMapAndMissingKey() {
        assertNull(Maps.getNumber(null, "v"));
        assertNull(Maps.getNumber(new HashMap<String, Object>(), "v"));
        assertNull(Maps.getInteger(null, "v"));
    }

    @Test
    public void testDefaultValueOverload() {
        assertEquals(Integer.valueOf(99), Maps.getInteger(mapWith("2024-01"), "v", Integer.valueOf(99)));
        assertEquals(Integer.valueOf(123), Maps.getInteger(mapWith("123"), "v", Integer.valueOf(99)));
    }
}
