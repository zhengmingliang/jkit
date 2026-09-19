package com.alianga.jkit.collection;

import org.junit.Test;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class MultiKeyHashMapTest {

    @Test
    public void testPutThenGetIgnoreCase() {
        MultiKeyHashMap<String, String> map = new MultiKeyHashMap<String, String>();
        map.put("UserName", "tom");
        assertEquals("tom", map.get("UserName"));
        assertEquals("tom", map.get("username"));
        assertEquals("tom", map.get("USERNAME"));
        assertEquals("tom", map.getOrDefault("username", "fallback"));
    }

    @Test
    public void testPutAllRegistersIgnoreCase() {
        Map<String, String> src = new HashMap<String, String>();
        src.put("UserName", "tom");
        src.put("orderId", "1001");

        MultiKeyHashMap<String, String> map = new MultiKeyHashMap<String, String>();
        map.putAll(src);

        assertEquals(2, map.size());
        assertEquals("tom", map.get("UserName"));
        assertEquals("tom", map.get("username"));
        assertEquals("1001", map.get("ORDERID"));
    }

    @Test
    public void testPutAllEmptyMapKeepsBehavior() {
        MultiKeyHashMap<String, String> map = new MultiKeyHashMap<String, String>();
        map.putAll(new HashMap<String, String>());
        assertEquals(0, map.size());
        assertTrue(map.keyMap.isEmpty());
    }

    @Test
    public void testRemoveWithDifferentCase() {
        MultiKeyHashMap<String, String> map = new MultiKeyHashMap<String, String>();
        map.put("UserName", "tom");

        assertEquals("tom", map.remove("username"));
        assertEquals(0, map.size());
        assertNull(map.get("username"));
        assertNull(map.get("UserName"));
    }

    @Test
    public void testRemoveCleansKeyMap() {
        MultiKeyHashMap<String, String> map = new MultiKeyHashMap<String, String>();
        map.put("UserName", "tom");
        assertEquals(1, map.keyMap.size());

        map.remove("UserName");
        assertTrue("remove 后 keyMap 不应残留已删除 key", map.keyMap.isEmpty());
    }

    @Test
    public void testClearCleansKeyMap() {
        MultiKeyHashMap<String, String> map = new MultiKeyHashMap<String, String>();
        map.put("UserName", "tom");
        map.put("orderId", "1001");
        assertEquals(2, map.keyMap.size());

        map.clear();
        assertEquals(0, map.size());
        assertTrue("clear 后 keyMap 不应残留 key 引用", map.keyMap.isEmpty());
    }

    @Test
    public void testRemoveMissingKeyReturnsNull() {
        MultiKeyHashMap<String, String> map = new MultiKeyHashMap<String, String>();
        map.put("UserName", "tom");
        assertNull(map.remove("notExists"));
        assertEquals(1, map.size());
        // 删除不存在的 key 不应误伤已登记的 "username"
        assertEquals("tom", map.get("username"));
    }

    @Test
    public void testContainsKeyIgnoreCase() {
        MultiKeyHashMap<String, String> map = new MultiKeyHashMap<String, String>();
        map.put("UserName", "tom");
        assertTrue(map.containsKey("UserName"));
        assertTrue(map.containsKey("username"));
        assertTrue(map.containsKey("USERNAME"));
        assertFalse(map.containsKey("other"));
    }

    @Test
    public void testIgnoreCaseLookupUnderTurkishLocale() {
        Locale origin = Locale.getDefault();
        try {
            Locale.setDefault(new Locale("tr", "TR"));
            MultiKeyHashMap<String, String> map = new MultiKeyHashMap<String, String>();
            map.put("ID", "identifier");
            map.put("TITLE", "title-value");

            assertEquals("identifier", map.get("ID"));
            assertEquals("土耳其语环境下应仍能忽略大小写命中", "identifier", map.get("id"));
            assertEquals("title-value", map.get("title"));
            assertTrue(map.containsKey("id"));
        } finally {
            Locale.setDefault(origin);
        }
    }

    @Test
    public void testMultiKeyWriteAndRead() {
        MultiKeyHashMap<String, String> map = new MultiKeyHashMap<String, String>();
        map.put("name", "nick", "tom");
        assertEquals("tom", map.get("name"));
        assertEquals("tom", map.get("nick"));
        assertEquals("tom", map.get("NAME"));

        map.putValue("jerry", "user", "uid", "account");
        assertEquals("jerry", map.get("USER"));
        assertEquals("jerry", map.get("Uid"));
        assertEquals("jerry", map.get("account"));
    }

    @Test
    public void testGetWithFallbackKeys() {
        MultiKeyHashMap<String, String> map = new MultiKeyHashMap<String, String>();
        map.put("userId", "1001");
        assertEquals("1001", map.get("userid", "uid"));
        assertEquals("1001", map.get("username", "userId", "uid"));
        assertNull(map.get("username", "uid"));
    }

    @Test
    public void testOverwriteSameLowerKey() {
        MultiKeyHashMap<String, String> map = new MultiKeyHashMap<String, String>();
        map.put("UserName", "tom");
        map.put("username", "jerry");
        // 精确匹配优先
        assertEquals("tom", map.get("UserName"));
        assertEquals("jerry", map.get("username"));
    }

    // ------------------------------------------------------------------
    // 修复回归：remove 不再清掉幸存同形条目的别名；compute 族入口补齐 keyMap 登记；
    // null key 不再因 String.valueOf 塌缩误删字面量 "null" 条目
    // ------------------------------------------------------------------

    @Test
    public void removeKeepsAliasOfSurvivingSameShapeKey() {
        MultiKeyHashMap<String, Integer> map = new MultiKeyHashMap<String, Integer>();
        map.put("A", 1);
        map.put("a", 2);
        assertEquals(Integer.valueOf(1), map.remove("A"));
        // 删除 "A" 后，幸存的 "a" 必须仍能忽略大小写命中（此前 get("A") 返回 null）
        assertEquals(Integer.valueOf(2), map.get("a"));
        assertEquals(Integer.valueOf(2), map.get("A"));
        assertTrue(map.containsKey("A"));
        // 继续删除幸存条目后，别名应彻底清理
        assertEquals(Integer.valueOf(2), map.remove("a"));
        assertNull(map.get("A"));
        assertNull(map.get("a"));
        assertFalse(map.containsKey("A"));
    }

    @Test
    public void putIfAbsentRegistersAlias() {
        MultiKeyHashMap<String, Integer> map = new MultiKeyHashMap<String, Integer>();
        assertNull(map.putIfAbsent("Name", 10));
        assertEquals(Integer.valueOf(10), map.get("name"));
        assertTrue(map.containsKey("NAME"));
        // 已存在时不覆盖
        assertEquals(Integer.valueOf(10), map.putIfAbsent("Name", 99));
        assertEquals(Integer.valueOf(10), map.get("Name"));
        assertEquals(Integer.valueOf(10), map.get("NaMe"));
    }

    @Test
    public void computeFamilyRegistersAlias() {
        MultiKeyHashMap<String, Integer> map = new MultiKeyHashMap<String, Integer>();
        map.computeIfAbsent("Key", k -> 7);
        assertEquals(Integer.valueOf(7), map.get("KEY"));

        map.computeIfPresent("Key", (k, v) -> v + 1);
        assertEquals(Integer.valueOf(8), map.get("KEY"));

        map.compute("Key", (k, v) -> v == null ? 1 : v * 2);
        assertEquals(Integer.valueOf(16), map.get("KEY"));

        map.merge("Key", 4, Integer::sum);
        assertEquals(Integer.valueOf(20), map.get("key"));

        // compute 返回 null 删除条目后，别名同步清理
        map.compute("Key", (k, v) -> null);
        assertNull(map.get("KEY"));
        assertFalse(map.containsKey("key"));
    }

    @Test
    public void removeNullDoesNotDeleteLiteralNullKey() {
        MultiKeyHashMap<String, Integer> map = new MultiKeyHashMap<String, Integer>();
        map.put("null", 42);
        assertNull(map.remove(null));
        assertEquals(Integer.valueOf(42), map.get("null"));
        // null key 不参与忽略大小写匹配，避免误命中字面量 "null" 条目
        assertNull(map.get(null));
        assertFalse(map.containsKey(null));
        map.put(null, 7);
        assertEquals(Integer.valueOf(7), map.get(null));
        assertEquals(Integer.valueOf(7), map.remove(null));
        assertNull(map.get(null));
        // null key 的写入/删除不影响 "null" 条目
        assertEquals(Integer.valueOf(42), map.get("null"));
    }
}
