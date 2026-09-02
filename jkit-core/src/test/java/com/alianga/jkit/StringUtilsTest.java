package com.alianga.jkit;

import org.junit.Test;

import java.util.Arrays;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class StringUtilsTest {
    @Test
    public void testBlank() {
        assertTrue(StringUtils.isBlank(null));
        assertTrue(StringUtils.isBlank(""));
        assertTrue(StringUtils.isBlank(" \t\n"));
        assertFalse(StringUtils.isBlank(" text "));
        assertTrue(StringUtils.isNotBlank(" text "));
    }

    @Test
    public void testEmpty() {
        assertTrue(StringUtils.isEmpty((String) null));
        assertTrue(StringUtils.isEmpty(""));
        assertFalse(StringUtils.isEmpty("null"));
        assertFalse(StringUtils.isEmpty("value"));
    }

    /**
     * isEmpty 只按长度判断，不去除首尾空白，与 isBlank 分工明确。
     */
    @Test
    public void isEmpty_doesNotTrim() {
        assertFalse("全空白字符串不算 empty", StringUtils.isEmpty("   "));
        assertTrue("全空白字符串算 blank", StringUtils.isBlank("   "));
    }

    /**
     * 修复前 isEmpty(Object) 用 "".equals(s) 判断，空的 StringBuilder 等非 String
     * 字符序列被误判为非空，isNotEmpty 因而返回 true。
     */
    @Test
    public void isEmpty_handlesNonStringCharSequence() {
        assertTrue("空 StringBuilder 应算空", StringUtils.isEmpty(new StringBuilder()));
        assertFalse(StringUtils.isNotEmpty(new StringBuilder()));
        assertTrue(StringUtils.isNotEmpty(new StringBuilder("x")));
        assertTrue(StringUtils.isEmpty(new StringBuffer()));
        // 非字符序列的对象一律视为非空
        assertFalse(StringUtils.isEmpty(new Object()));
        assertFalse(StringUtils.isEmpty(Integer.valueOf(0)));
    }

    /**
     * 修复前 equalsIgnoreCase(null, null) 返回 false，与常见实现语义相反。
     */
    @Test
    public void equalsIgnoreCase_treatsBothNullAsEqual() {
        assertTrue(StringUtils.equalsIgnoreCase(null, null));
        assertFalse(StringUtils.equalsIgnoreCase(null, "a"));
        assertFalse(StringUtils.equalsIgnoreCase("a", null));
        assertTrue(StringUtils.equalsIgnoreCase("AbC", "aBc"));
        assertFalse(StringUtils.equalsIgnoreCase("ab", "abc"));
    }

    @Test
    public void emptyFamily_matchesBlankFamilyShape() {
        assertTrue(StringUtils.isAnyEmpty("", "bar"));
        assertFalse(StringUtils.isAnyEmpty(" ", "bar"));
        assertFalse(StringUtils.isAnyEmpty("foo", "bar"));
        assertTrue(StringUtils.isAnyEmpty((CharSequence) null));
        // 数组本身为 null 或空时与 blank 家族保持一致的约定
        assertFalse(StringUtils.isAnyEmpty((CharSequence[]) null));
        assertFalse(StringUtils.isAnyEmpty(new CharSequence[]{}));

        assertTrue(StringUtils.isNoneEmpty("foo", "bar"));
        assertFalse(StringUtils.isNoneEmpty("foo", ""));

        assertTrue(StringUtils.isAllEmpty(null, ""));
        assertFalse(StringUtils.isAllEmpty("", "bar"));
        assertTrue(StringUtils.isAllEmpty(new CharSequence[]{}));
    }

    @Test
    public void contains_worksOnCharSequence() {
        assertTrue(StringUtils.contains("abcdef", "cd"));
        assertFalse(StringUtils.contains("abcdef", "cf"));
        assertTrue(StringUtils.contains(new StringBuilder("abcdef"), "ef"));
        assertTrue("空子串视为命中", StringUtils.contains("abc", ""));
        assertFalse(StringUtils.contains((CharSequence) null, "a"));
        assertFalse(StringUtils.contains("abc", null));
    }

    /**
     * 数组成员判断与子串判断是两件事，方法名已区分开，避免重载歧义。
     */
    @Test
    public void containsElementIgnoreCase_checksArrayMembership() {
        String[] arr = {"Foo", "BAR"};
        assertTrue(StringUtils.containsElementIgnoreCase(arr, "foo"));
        assertTrue(StringUtils.containsElementIgnoreCase(arr, "bar"));
        assertFalse("只做整体相等，不做子串匹配", StringUtils.containsElementIgnoreCase(arr, "fo"));
        assertFalse(StringUtils.containsElementIgnoreCase(arr, "baz"));
        assertFalse(StringUtils.containsElementIgnoreCase(null, "foo"));
        assertFalse(StringUtils.containsElementIgnoreCase(arr, null));
    }

    @Test
    public void containsAny_matchesCharsAndSequences() {
        assertTrue(StringUtils.containsAny("abc", 'x', 'b'));
        assertFalse(StringUtils.containsAny("abc", 'x', 'y'));
        assertFalse(StringUtils.containsAny("abc", new char[0]));
        assertFalse(StringUtils.containsAny(null, 'a'));
        assertFalse(StringUtils.containsAny("", 'a'));

        assertTrue(StringUtils.containsAny("abc", "zz", "bc"));
        assertFalse(StringUtils.containsAny("abc", "zz", "yy"));
        assertFalse(StringUtils.containsAny("abc", (CharSequence[]) null));
    }

    /**
     * 增补字符由一对代理项组成，只匹配到其中一半不应算命中。
     */
    @Test
    public void containsAny_handlesSurrogatePair() {
        // U+2070E 由高代理 \uD841 与低代理 \uDF0E 组成
        String supplementary = "\uD841\uDF0E";
        assertTrue(StringUtils.containsAny(supplementary, '\uD841', '\uDF0E'));
        // 被搜索串里的低代理项与待查的低代理项不配对，不应命中
        assertFalse(StringUtils.containsAny(supplementary, '\uD841', '\uDF0F'));
    }

    @Test
    public void indexOfIgnoreCase_findsCaseInsensitively() {
        assertEquals(2, StringUtils.indexOfIgnoreCase("abCDef", "cd"));
        assertEquals(2, StringUtils.indexOfIgnoreCase("abcdef", "CD"));
        assertEquals(-1, StringUtils.indexOfIgnoreCase("abcdef", "zz"));
        assertEquals(-1, StringUtils.indexOfIgnoreCase(null, "a"));
        assertEquals(-1, StringUtils.indexOfIgnoreCase("abc", null));
        // 起始位置生效，负数按 0 处理
        assertEquals(-1, StringUtils.indexOfIgnoreCase("abcdef", "AB", 1));
        assertEquals(0, StringUtils.indexOfIgnoreCase("abcdef", "AB", -5));
        assertEquals(3, StringUtils.indexOfIgnoreCase("abcabc", "A", 1));
        // 非 String 的字符序列同样可用
        assertEquals(1, StringUtils.indexOfIgnoreCase(new StringBuilder("aBc"), "b"));
    }

    @Test
    public void trimToNull_returnsNullWhenBlank() {
        assertNull(StringUtils.trimToNull(null));
        assertNull(StringUtils.trimToNull(""));
        assertNull(StringUtils.trimToNull("  \t "));
        assertEquals("a", StringUtils.trimToNull("  a  "));
    }

    @Test
    public void defaultIfEmptyAndBlank_differOnWhitespace() {
        assertEquals("d", StringUtils.defaultIfEmpty(null, "d"));
        assertEquals("d", StringUtils.defaultIfEmpty("", "d"));
        assertEquals("  ", StringUtils.defaultIfEmpty("  ", "d"));
        assertEquals("v", StringUtils.defaultIfEmpty("v", "d"));

        assertEquals("d", StringUtils.defaultIfBlank(null, "d"));
        assertEquals("d", StringUtils.defaultIfBlank("", "d"));
        assertEquals("d", StringUtils.defaultIfBlank("  ", "d"));
        assertEquals("v", StringUtils.defaultIfBlank("v", "d"));
    }

    @Test
    public void replaceOnce_onlyReplacesFirstOccurrence() {
        assertEquals("xbcabc", StringUtils.replaceOnce("abcabc", "a", "x"));
        assertEquals("abcabc", StringUtils.replaceOnce("abcabc", "z", "x"));
        assertEquals("bcabc", StringUtils.replaceOnce("abcabc", "a", ""));
        assertNull(StringUtils.replaceOnce(null, "a", "x"));
        assertEquals("abc", StringUtils.replaceOnce("abc", null, "x"));
        assertEquals("abc", StringUtils.replaceOnce("abc", "a", null));
    }

    @Test
    public void testSubstring() {
        assertEquals("ef", StringUtils.substring("abcdef", -2));
        assertEquals("bcd", StringUtils.substring("abcdef", 1, 4));
        assertEquals("a", StringUtils.substringBefore("a/b/c", "/"));
        assertEquals("c", StringUtils.substringAfterLast("a/b/c", "/"));
        assertEquals("body", StringUtils.substringBetween("a[body]b", "[", "]"));
    }

    @Test
    public void testCaseAndReverse() {
        assertEquals("Hello", StringUtils.upperFirstLetter("hello"));
        assertEquals("hello", StringUtils.lowerFirstLetter("Hello"));
        assertEquals("cba", StringUtils.reverse("abc"));
    }

    @Test
    public void testPathAndList() {
        assertEquals("b/c", StringUtils.cleanPath("a/../b/./c"));
        assertTrue(StringUtils.pathEquals("a/./b", "a/b"));
        assertEquals("a,b,c",
                StringUtils.collectionToDelimitedString(Arrays.asList("a", "b", "c"), ","));
        assertEquals(3, StringUtils.delimitedListToStringArray("a,b,c", ",").length);
    }
}
