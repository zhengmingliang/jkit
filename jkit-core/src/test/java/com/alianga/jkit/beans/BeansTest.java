package com.alianga.jkit.beans;

import com.alianga.jkit.exception.TypeNotMatchExecption;
import org.junit.Assert;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class BeansTest {

    // ---- copyProperties ----

    @Test
    public void copyMapToMapWithExcludeWritesTargetNotSource() {
        Map<String, Object> src = new LinkedHashMap<String, Object>();
        src.put("a", 1);
        src.put("b", 2);
        src.put("c", 3);
        Map<String, Object> tgt = new LinkedHashMap<String, Object>();
        tgt.put("x", 99);
        BeanUtils.copyProperties(src, tgt, new String[]{"b"});
        Assert.assertEquals(3, tgt.size());
        Assert.assertEquals(1, tgt.get("a"));
        Assert.assertNull(tgt.get("b"));
        Assert.assertEquals(3, tgt.get("c"));
        Assert.assertEquals(99, tgt.get("x"));
        // 源不应被改动
        Assert.assertEquals(3, src.size());
        Assert.assertEquals(2, src.get("b"));
    }

    @Test
    public void copyMapToMapWithoutExcludePutAll() {
        Map<String, Object> src = new LinkedHashMap<String, Object>();
        src.put("a", 1);
        src.put("b", 2);
        Map<String, Object> tgt = new LinkedHashMap<String, Object>();
        BeanUtils.copyProperties(src, tgt, null);
        Assert.assertEquals(2, tgt.size());
        Assert.assertEquals(1, tgt.get("a"));
        Assert.assertEquals(2, tgt.get("b"));
    }

    @Test
    public void copyMapToMapEmptyExcludeActsLikePutAll() {
        Map<String, Object> src = new LinkedHashMap<String, Object>();
        src.put("a", 1);
        Map<String, Object> tgt = new LinkedHashMap<String, Object>();
        BeanUtils.copyProperties(src, tgt, new String[]{});
        Assert.assertEquals(1, tgt.get("a"));
    }

    @Test
    public void copyBeanToBeanWithExcludeSkipsField() {
        SrcBean src = new SrcBean();
        src.setName("Tom");
        src.setAge(20);
        src.setTag("hi");
        TgtBean tgt = new TgtBean();
        BeanUtils.copyProperties(src, tgt, new String[]{"tag"});
        Assert.assertEquals("Tom", tgt.getName());
        Assert.assertEquals(20, tgt.getAge());
        Assert.assertNull(tgt.getTag());
    }

    @Test
    public void copyBeanToMapWithExclude() {
        SrcBean src = new SrcBean();
        src.setName("Tom");
        src.setAge(20);
        src.setTag("hi");
        Map<String, Object> tgt = new LinkedHashMap<String, Object>();
        BeanUtils.copyProperties(src, tgt, new String[]{"tag"});
        Assert.assertEquals("Tom", tgt.get("name"));
        Assert.assertEquals(20, tgt.get("age"));
        Assert.assertFalse(tgt.containsKey("tag"));
    }

    @Test
    public void copyMapToBeanHonorsSetterTypeConversion() {
        Map<String, Object> src = new LinkedHashMap<String, Object>();
        src.put("name", "Amy");
        src.put("age", 30);
        src.put("tag", "no");
        TgtBean tgt = new TgtBean();
        BeanUtils.copyProperties(src, tgt, new String[]{"tag"});
        Assert.assertEquals("Amy", tgt.getName());
        Assert.assertEquals(30, tgt.getAge());
        Assert.assertNull(tgt.getTag());
    }

    @Test
    public void mergePropertiesOverwritesOnlyNonNull() {
        SrcBean src = new SrcBean();
        src.setName("New");
        src.setAge(0);
        TgtBean tgt = new TgtBean();
        tgt.setName("Old");
        tgt.setAge(5);
        tgt.setTag("keep");
        BeanUtils.mergeProperties(src, tgt);
        Assert.assertEquals("New", tgt.getName());
        Assert.assertEquals(0, tgt.getAge()); // 0 也非空，应覆盖
        Assert.assertEquals("keep", tgt.getTag()); // 源为 null，保留目标原值
    }

    @Test
    public void copyNullsAreNoOps() {
        Map<String, Object> tgt = new LinkedHashMap<String, Object>();
        BeanUtils.copyProperties(null, tgt, null);
        BeanUtils.copyProperties(new LinkedHashMap<String, Object>(), null, null);
        Assert.assertTrue(tgt.isEmpty());
    }

    // ---- ObjectUtils.set ----

    @Test
    public void setListByIndexDoesNotThrow() {
        List<String> list = new ArrayList<String>(Arrays.asList("a", "b", "c"));
        ObjectUtils.set(list, "[1]", "B");
        Assert.assertEquals(Arrays.asList("a", "B", "c"), list);
    }

    @Test
    public void setListInvalidKeyStillThrows() {
        List<String> list = new ArrayList<String>(Arrays.asList("a", "b"));
        try {
            ObjectUtils.set(list, "foo", "x");
            Assert.fail("expected TypeNotMatchExecption");
        } catch (TypeNotMatchExecption expected) {
            // ok
        }
    }

    @Test
    public void setCreatesNestedMapNodes() {
        Map<String, Object> root = new LinkedHashMap<String, Object>();
        ObjectUtils.set(root, "a.b.c", 7, true);
        Assert.assertTrue(root.get("a") instanceof Map);
        @SuppressWarnings("unchecked")
        Map<String, Object> a = (Map<String, Object>) root.get("a");
        @SuppressWarnings("unchecked")
        Map<String, Object> b = (Map<String, Object>) a.get("b");
        Assert.assertEquals(7, b.get("c"));
    }

    // ---- ObjectUtils.get / contains / toMap / getNonEmptyFields ----

    @Test
    public void getMultiLevelPath() {
        Map<String, Object> user = new LinkedHashMap<String, Object>();
        user.put("name", "Z");
        Map<String, Object> root = new LinkedHashMap<String, Object>();
        root.put("user", user);
        Assert.assertEquals("Z", ObjectUtils.get(root, "user.name"));
        Assert.assertNull(ObjectUtils.get(root, "user.missing"));
    }

    @Test
    public void getListSizeAndIndex() {
        List<String> list = new ArrayList<String>(Arrays.asList("a", "b", "c"));
        Assert.assertEquals(3, ObjectUtils.get(list, "size"));
        Assert.assertEquals("b", ObjectUtils.get(list, "[1]"));
    }

    @Test
    public void containsBeanAndMap() {
        SrcBean bean = new SrcBean();
        Assert.assertTrue(ObjectUtils.contains(bean, "name"));
        Assert.assertFalse(ObjectUtils.contains(bean, "missing"));
        Map<String, Object> map = new LinkedHashMap<String, Object>();
        map.put("k", 1);
        Assert.assertTrue(ObjectUtils.contains(map, "k"));
        Assert.assertFalse(ObjectUtils.contains(map, "missing"));
    }

    @Test
    public void toMapFromBean() {
        SrcBean bean = new SrcBean();
        bean.setName("Tom");
        bean.setAge(20);
        Map<String, Object> map = ObjectUtils.toMap(bean);
        Assert.assertEquals("Tom", map.get("name"));
        Assert.assertEquals(20, map.get("age"));
    }

    @Test
    public void getNonEmptyFieldsSkipsEmptyAndExcluded() {
        Map<String, Object> map = new LinkedHashMap<String, Object>();
        map.put("p", 1);
        map.put("q", "");
        map.put("r", null);
        map.put("s", 0);
        List<String> fields = ObjectUtils.getNonEmptyFields(map, new String[]{"p"});
        Assert.assertEquals(Arrays.asList("s"), fields); // 0 视为空被跳过，p 被排除
    }

    @Test
    public void isEmptyVariants() {
        Assert.assertTrue(ObjectUtils.isEmpty(null));
        Assert.assertTrue(ObjectUtils.isEmpty(""));
        Assert.assertTrue(ObjectUtils.isEmpty("   "));
        Assert.assertTrue(ObjectUtils.isEmpty(new ArrayList<Object>()));
        Assert.assertTrue(ObjectUtils.isEmpty(new LinkedHashMap<Object, Object>()));
        Assert.assertFalse(ObjectUtils.isEmpty("x"));
        Assert.assertFalse(ObjectUtils.isEmpty(Arrays.asList(1)));
    }

    public static class SrcBean {
        private String name;
        private int age;
        private String tag;

        public String getName() {
            return name;
        }

        public void setName(String n) {
            name = n;
        }

        public int getAge() {
            return age;
        }

        public void setAge(int a) {
            age = a;
        }

        public String getTag() {
            return tag;
        }

        public void setTag(String t) {
            tag = t;
        }
    }

    public static class TgtBean {
        private String name;
        private int age;
        private String tag;

        public String getName() {
            return name;
        }

        public void setName(String n) {
            name = n;
        }

        public int getAge() {
            return age;
        }

        public void setAge(int a) {
            age = a;
        }

        public String getTag() {
            return tag;
        }

        public void setTag(String t) {
            tag = t;
        }
    }
}
