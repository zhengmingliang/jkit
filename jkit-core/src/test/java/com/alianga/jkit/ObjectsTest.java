package com.alianga.jkit;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * {@link Objects} 中空值判定与默认值相关方法的测试。
 */
public class ObjectsTest {

    @Test
    public void allNotNull_requiresEveryValuePresent() {
        assertTrue(Objects.allNotNull("a", "b"));
        assertTrue("空数组视为全部非空", Objects.allNotNull());
        assertFalse(Objects.allNotNull("a", null));
        assertFalse(Objects.allNotNull(null, "b"));
        assertFalse(Objects.allNotNull((Object) null));
        assertFalse("数组本身为 null 时返回 false", Objects.allNotNull((Object[]) null));
    }

    @Test
    public void anyNotNull_requiresAtLeastOneValuePresent() {
        assertTrue(Objects.anyNotNull(null, "b"));
        assertTrue(Objects.anyNotNull("a", null));
        assertFalse(Objects.anyNotNull(null, null));
        assertFalse("空数组视为不存在非空项", Objects.anyNotNull());
        assertFalse(Objects.anyNotNull((Object[]) null));
    }

    /**
     * defaultIfNull 容忍默认值为 null，这是它与 requireNonNullElse 的关键差异。
     */
    @Test
    public void defaultIfNull_toleratesNullDefault() {
        assertEquals("v", Objects.defaultIfNull("v", "d"));
        assertEquals("d", Objects.defaultIfNull(null, "d"));
        assertNull("两者都为 null 时返回 null 而不抛异常", Objects.defaultIfNull(null, null));

        // 返回的是原对象本身而非副本
        StringBuilder value = new StringBuilder("x");
        assertSame(value, Objects.defaultIfNull(value, new StringBuilder("y")));
    }

    /**
     * 对照 requireNonNullElse：默认值为 null 时它会抛异常。
     */
    @Test
    public void requireNonNullElse_stillRejectsNullDefault() {
        assertEquals("v", Objects.requireNonNullElse("v", "d"));
        assertEquals("d", Objects.requireNonNullElse(null, "d"));
        try {
            Objects.requireNonNullElse(null, null);
            fail("两者都为 null 时应抛出 NullPointerException");
        } catch (NullPointerException expected) {
            assertEquals("defaultObj", expected.getMessage());
        }
    }
}
