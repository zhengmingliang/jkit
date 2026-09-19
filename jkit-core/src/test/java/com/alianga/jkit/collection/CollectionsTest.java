package com.alianga.jkit.collection;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.function.Function;
import java.util.function.Predicate;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class CollectionsTest {

    private static Predicate<String> startsWith(String prefix) {
        return new Predicate<String>() {
            @Override
            public boolean test(String value) {
                return value != null && value.startsWith(prefix);
            }
        };
    }

    // ==================== 空判断 ====================

    @Test
    public void testIsEmpty() {
        assertTrue(Collections.isEmpty((Collection<?>) null));
        assertTrue(Collections.isEmpty(new ArrayList<String>()));
        assertFalse(Collections.isEmpty(Arrays.asList("a")));

        Map<String, String> filled = new HashMap<String, String>();
        filled.put("k", "v");
        assertTrue(Collections.isEmpty((Map<?, ?>) null));
        assertTrue(Collections.isEmpty(new HashMap<String, String>()));
        assertFalse(Collections.isEmpty(filled));

        assertTrue(Collections.isNotEmpty(Arrays.asList("a")));
        assertFalse(Collections.isNotEmpty(new ArrayList<String>()));
    }

    // ==================== getFirst / getLast ====================

    @Test
    public void testGetFirst() {
        assertEquals("a", Collections.getFirst(Arrays.asList("a", "b", "c")));
        assertNull(Collections.getFirst(new ArrayList<String>()));
        assertNull(Collections.getFirst((List<String>) null));
        assertEquals("DEF", Collections.getFirst(new ArrayList<String>(), "DEF"));
        assertEquals("DEF", Collections.getFirst((List<String>) null, "DEF"));

        // SortedSet 走 first()
        TreeSet<String> sorted = new TreeSet<String>(Arrays.asList("c", "a", "b"));
        assertEquals("a", Collections.getFirst(sorted));
    }

    @Test
    public void testGetLast() {
        assertEquals("c", Collections.getLast(Arrays.asList("a", "b", "c")));
        assertNull(Collections.getLast(new ArrayList<String>()));
        assertNull(Collections.getLast((List<String>) null));
        assertEquals("DEF", Collections.getLast(new ArrayList<String>(), "DEF"));
        assertEquals("DEF", Collections.getLast((List<String>) null, "DEF"));

        TreeSet<String> sorted = new TreeSet<String>(Arrays.asList("c", "a", "b"));
        assertEquals("c", Collections.getLast(sorted));

        // 既不是 List 也不是 SortedSet：走全量迭代，取迭代器最后一个元素
        HashSet<String> set = new HashSet<String>();
        set.add("only-one");
        assertEquals("only-one", Collections.getLast(set, "DEF"));
    }

    // ==================== removeOne / removeAll ====================

    @Test
    public void testRemoveOne() {
        List<String> list = new ArrayList<String>(Arrays.asList("a", "b", "a"));
        Collections.removeOne(list, "a");
        assertEquals(Arrays.asList("b", "a"), list);

        Collections.removeOne(list, startsWith("b"));
        assertEquals(Arrays.asList("a"), list);

        // 元素不存在或集合为 null 时静默返回
        Collections.removeOne(list, "not-exists");
        assertEquals(Arrays.asList("a"), list);
        Collections.removeOne(null, "a");
    }

    @Test
    public void testRemoveAll() {
        List<String> list = new ArrayList<String>(Arrays.asList("a", "b", "a", "c"));
        Collections.removeAll(list, "a");
        assertEquals(Arrays.asList("b", "c"), list);

        Collections.removeAll(list, startsWith("c"));
        assertEquals(Arrays.asList("b"), list);

        Collections.removeAll(list, "not-exists");
        assertEquals(Arrays.asList("b"), list);
        Collections.removeAll(null, "a");
    }

    // ==================== 查找 ====================

    @Test
    public void testFindFirstAndFindAll() {
        List<String> list = Arrays.asList("ab", "cd", "ae");
        assertEquals("ab", Collections.findFirst(list, startsWith("a")));
        assertNull(Collections.findFirst(list, startsWith("z")));
        assertNull(Collections.findFirst(null, startsWith("a")));
        assertNull(Collections.findFirst(list, null));

        assertEquals(Arrays.asList("ab", "ae"), Collections.findAll(list, startsWith("a")));
        assertTrue(Collections.findAll(null, startsWith("a")).isEmpty());
        assertTrue(Collections.findAll(list, null).isEmpty());
    }

    @Test
    public void testCountMatchesAndExists() {
        List<String> list = Arrays.asList("ab", "cd", "ae");
        assertEquals(2, Collections.countMatches(list, startsWith("a")));
        assertEquals(0, Collections.countMatches(null, startsWith("a")));
        assertEquals(0, Collections.countMatches(list, null));

        assertTrue(Collections.exists(list, startsWith("a")));
        assertFalse(Collections.exists(list, startsWith("z")));
        assertFalse(Collections.exists(null, startsWith("a")));
    }

    @Test
    public void testConvert() {
        Function<String, Integer> toInt = new Function<String, Integer>() {
            @Override
            public Integer apply(String value) {
                return Integer.valueOf(value);
            }
        };

        Collection<Integer> out = Collections.convert(Arrays.asList("1", "2").iterator(), toInt);
        assertEquals(Arrays.asList(1, 2), new ArrayList<Integer>(out));

        List<Integer> target = new ArrayList<Integer>();
        Collections.convert(Arrays.asList("3"), toInt, target);
        assertEquals(Arrays.asList(3), target);

        // null 输入不改动输出集合
        List<Integer> untouched = new ArrayList<Integer>();
        Collections.convert((Collection<String>) null, toInt, untouched);
        assertTrue(untouched.isEmpty());
    }

    // ==================== size ====================

    @Test
    public void testSizeOfCollection() {
        assertEquals(0, Collections.size((Collection<?>) null));
        assertEquals(3, Collections.size(Arrays.asList("a", "b", "c")));
    }

    @Test
    public void testSizeOfObject() {
        assertEquals(0, Collections.size(null));
        assertEquals(3, Collections.size(Arrays.asList("a", "b", "c")));

        Map<String, String> map = new HashMap<String, String>();
        map.put("a", "1");
        map.put("b", "2");
        assertEquals(2, Collections.size(map));

        assertEquals(3, Collections.size(new String[]{"a", "b", "c"}));
        assertEquals(3, Collections.size(Arrays.asList("a", "b", "c").iterator()));

        Enumeration<String> en = java.util.Collections.enumeration(Arrays.asList("a", "b"));
        assertEquals(2, Collections.size(en));

        // 原始类型数组走 Array.getLength
        assertEquals(4, Collections.size(new int[]{1, 2, 3, 4}));
    }

    @Test
    public void testSizeOfUnsupportedObject() {
        try {
            Collections.size("not a collection");
            fail("预期抛出 IllegalArgumentException");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("Unsupported object type"));
        }
    }

    // ==================== contains / findValueOfType ====================

    @Test
    public void testContainsInIterator() {
        Iterator<String> it = Arrays.asList("a", "b").iterator();
        assertTrue(Collections.contains(it, "b"));
        assertFalse(Collections.contains((Iterator<?>) null, "b"));
    }

    @Test
    public void testFindValueOfType() {
        List<Object> list = Arrays.asList((Object) "str", (Object) Integer.valueOf(1));
        assertEquals("str", Collections.findValueOfType(list, String.class));
        assertEquals(Integer.valueOf(1), Collections.findValueOfType(list, Integer.class));
        assertNull(Collections.findValueOfType(list, Double.class));
    }
}
