package com.alianga.jkit.entity;

import com.alianga.jkit.common.entity.ValidList;
import org.junit.Test;

import java.util.Arrays;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class ValidListTest {
    @Test
    public void testListBehavior() {
        ValidList<String> list = new ValidList<String>();
        assertTrue(list.isEmpty());
        list.add("a");
        list.add("b");
        assertEquals(2, list.size());
        assertEquals("a", list.get(0));
        assertEquals("b", list.get(1));
        assertEquals(Arrays.asList("a", "b"), list);
    }

    @Test
    public void testGetSetList() {
        ValidList<String> list = new ValidList<String>();
        list.setList(Arrays.asList("x", "y"));
        assertEquals(2, list.size());
        assertEquals("y", list.getList().get(1));
    }

    @Test
    public void testEqualsHashCode() {
        ValidList<String> a = new ValidList<String>();
        a.setList(Arrays.asList("1", "2"));
        ValidList<String> b = new ValidList<String>();
        b.setList(Arrays.asList("1", "2"));
        assertEquals(a, b);
        assertEquals(a.hashCode(), b.hashCode());
    }
}
