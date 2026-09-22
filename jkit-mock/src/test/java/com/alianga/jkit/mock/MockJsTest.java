package com.alianga.jkit.mock;

import org.junit.Assert;
import org.junit.Test;

import java.util.List;
import java.util.Map;

/**
 * MockJs 模板引擎单元测试，覆盖 Mock.js 的数据模板定义（DTD）规范。
 *
 * @author 郑明亮
 */
public class MockJsTest {

    @Test
    public void testStringRuleRepeat() {
        MockJs js = new MockJs();
        Object value = js.mock("{\"name|3\": \"ab\"}");
        Map<String, Object> map = asMap(value);
        Assert.assertEquals("ababab", map.get("name"));
    }

    @Test
    public void testStringRuleRange() {
        MockJs js = new MockJs();
        for (int i = 0; i < 20; i++) {
            Map<String, Object> map = asMap(js.mock("{\"name|1-5\": \"x\"}"));
            int len = String.valueOf(map.get("name")).length();
            Assert.assertTrue("长度应在 1-5，实际 " + len, len >= 1 && len <= 5);
        }
    }

    @Test
    public void testNumberRuleRange() {
        MockJs js = new MockJs();
        for (int i = 0; i < 50; i++) {
            Map<String, Object> map = asMap(js.mock("{\"age|18-60\": 1}"));
            int age = ((Number) map.get("age")).intValue();
            Assert.assertTrue("年龄应在 18-60，实际 " + age, age >= 18 && age <= 60);
        }
    }

    @Test
    public void testNumberRuleFixed() {
        MockJs js = new MockJs();
        Map<String, Object> map = asMap(js.mock("{\"total|100\": 1}"));
        Assert.assertEquals(100, ((Number) map.get("total")).intValue());
    }

    @Test
    public void testNumberRuleDecimal() {
        MockJs js = new MockJs();
        for (int i = 0; i < 30; i++) {
            Map<String, Object> map = asMap(js.mock("{\"price|1-100.2\": 1}"));
            String text = String.valueOf(map.get("price"));
            int dot = text.indexOf('.');
            Assert.assertTrue("应含小数位：" + text, dot > 0);
            Assert.assertEquals("小数位应为 2：" + text, 2, text.length() - dot - 1);
        }
    }

    @Test
    public void testNumberRuleDecimalRange() {
        MockJs js = new MockJs();
        for (int i = 0; i < 30; i++) {
            Map<String, Object> map = asMap(js.mock("{\"price|9.9-999.2\": 1}"));
            double price = ((Number) map.get("price")).doubleValue();
            Assert.assertTrue("价格应在 9-999.99，实际 " + price, price >= 9D && price < 1000D);
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    public void testIncrementRule() {
        MockJs js = new MockJs();
        Map<String, Object> map = asMap(js.mock("{\"list|4\": [{\"id|+1\": 1}]}"));
        List<Map<String, Object>> list = (List<Map<String, Object>>) map.get("list");
        Assert.assertEquals(4, list.size());
        for (int i = 0; i < 4; i++) {
            Assert.assertEquals(i + 1, ((Number) list.get(i).get("id")).intValue());
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    public void testOrderRule() {
        MockJs js = new MockJs();
        Map<String, Object> map = asMap(js.mock("{\"list|4\": [{\"lv|+1\": [\"A\", \"B\", \"C\"]}]}"));
        List<Map<String, Object>> list = (List<Map<String, Object>>) map.get("list");
        Assert.assertEquals("A", list.get(0).get("lv"));
        Assert.assertEquals("B", list.get(1).get("lv"));
        Assert.assertEquals("C", list.get(2).get("lv"));
        Assert.assertEquals("A", list.get(3).get("lv"));
    }

    @Test
    @SuppressWarnings("unchecked")
    public void testArrayPickOne() {
        MockJs js = new MockJs();
        for (int i = 0; i < 20; i++) {
            Map<String, Object> map = asMap(js.mock("{\"city|1\": [\"北京\", \"上海\", \"广州\"]}"));
            Assert.assertTrue(String.valueOf(map.get("city")),
                    "北京".equals(map.get("city")) || "上海".equals(map.get("city"))
                            || "广州".equals(map.get("city")));
        }
        // 与 Mock.js 一致：'list|1' 取单个元素，不再是数组
        Map<String, Object> obj = asMap(js.mock("{\"item|1\": [{\"a\": 1}, {\"b\": 2}]}"));
        Assert.assertTrue(obj.get("item") instanceof Map);
    }

    @Test
    @SuppressWarnings("unchecked")
    public void testArrayRepeat() {
        MockJs js = new MockJs();
        Map<String, Object> map = asMap(js.mock("{\"tags|2\": [\"a\", \"b\"]}"));
        List<Object> tags = (List<Object>) map.get("tags");
        Assert.assertEquals(4, tags.size());
        Assert.assertEquals("a", tags.get(0));
        Assert.assertEquals("b", tags.get(3));
    }

    @Test
    public void testObjectPropertyPick() {
        MockJs js = new MockJs();
        for (int i = 0; i < 20; i++) {
            Map<String, Object> map = asMap(js.mock("{\"user|2\": {\"a\": 1, \"b\": 2, \"c\": 3}}"));
            Assert.assertEquals(2, map.get("user") == null ? 0 : asMap(map.get("user")).size());
        }
    }

    @Test
    public void testBooleanProbability() {
        MockJs js = new MockJs();
        int trues = 0;
        for (int i = 0; i < 200; i++) {
            Map<String, Object> map = asMap(js.mock("{\"flag|1-9\": true}"));
            if (Boolean.TRUE.equals(map.get("flag"))) {
                trues++;
            }
        }
        Assert.assertTrue("概率应偏低，实际 true 数 " + trues, trues < 120);
    }

    @Test
    public void testRegexPhone() {
        MockJs js = new MockJs();
        for (int i = 0; i < 30; i++) {
            Map<String, Object> map = asMap(js.mock("{\"phone\": \"/^1[3-9]\\\\d{9}$/\"}"));
            Assert.assertTrue("手机号不合法：" + map.get("phone"),
                    String.valueOf(map.get("phone")).matches("1[3-9]\\d{9}"));
        }
    }

    @Test
    public void testRegexCode() {
        MockJs js = new MockJs();
        for (int i = 0; i < 30; i++) {
            Map<String, Object> map = asMap(js.mock("{\"code\": \"/[A-Z]{3}\\\\d{4}/\"}"));
            Assert.assertTrue("编码不合法：" + map.get("code"),
                    String.valueOf(map.get("code")).matches("[A-Z]{3}\\d{4}"));
        }
    }

    @Test
    public void testRegexLengthRange() {
        MockJs js = new MockJs();
        for (int i = 0; i < 30; i++) {
            Map<String, Object> map = asMap(js.mock("{\"token\": \"/[a-z]{6,10}/\"}"));
            int len = String.valueOf(map.get("token")).length();
            Assert.assertTrue("长度应在 6-10，实际 " + len, len >= 6 && len <= 10);
        }
    }

    @Test
    public void testPlaceholderSingle() {
        MockJs js = new MockJs();
        Map<String, Object> map = asMap(js.mock("{\"name\": \"@cname\"}"));
        String name = String.valueOf(map.get("name"));
        Assert.assertTrue("中文姓名：" + name, name.length() >= 2 && name.length() <= 4);
    }

    @Test
    public void testPlaceholderWithArgs() {
        MockJs js = new MockJs();
        for (int i = 0; i < 30; i++) {
            Map<String, Object> map = asMap(js.mock("{\"age\": \"@integer(10,20)\"}"));
            int age = ((Number) map.get("age")).intValue();
            Assert.assertTrue("年龄应在 10-20，实际 " + age, age >= 10 && age <= 20);
        }
    }

    @Test
    public void testPlaceholderInText() {
        MockJs js = new MockJs();
        Map<String, Object> map = asMap(js.mock("{\"hi\": \"你好 @cname 欢迎\"}"));
        String text = String.valueOf(map.get("hi"));
        Assert.assertTrue(text, text.startsWith("你好 ") && text.endsWith(" 欢迎"));
    }

    @Test
    public void testPlaceholderPick() {
        MockJs js = new MockJs();
        for (int i = 0; i < 20; i++) {
            Map<String, Object> map = asMap(js.mock("{\"v\": \"@pick(['a','b','c'])\"}"));
            String v = String.valueOf(map.get("v"));
            Assert.assertTrue("pick 结果：" + v, "abc".contains(v) && v.length() == 1);
        }
    }

    @Test
    public void testSingleQuoteTemplate() {
        MockJs js = new MockJs();
        Map<String, Object> map = asMap(js.mock("{'id|+1': 1, 'name': '@cname'}"));
        Assert.assertEquals(1, ((Number) map.get("id")).intValue());
        Assert.assertNotNull(map.get("name"));
    }

    @Test
    public void testUnknownPlaceholderKept() {
        MockJs js = new MockJs();
        Map<String, Object> map = asMap(js.mock("{\"x\": \"@notExists\"}"));
        Assert.assertEquals("@notExists", map.get("x"));
    }

    @Test
    public void testMockJson() {
        String json = MockJs.mockJson("{\"list|2\": [{\"id|+1\": 1}]}");
        Assert.assertTrue(json, json.contains("\"list\""));
        Assert.assertTrue(json, json.contains("\"id\":1") || json.contains("\"id\": 1"));
    }

    @Test
    public void testMockRecords() {
        MockJs js = new MockJs();
        List<Map<String, Object>> rows = js.mockRecords("{\"list|3\": [{\"id|+1\": 1}]}");
        Assert.assertEquals(3, rows.size());
        Assert.assertEquals(1, ((Number) rows.get(0).get("id")).intValue());
    }

    @Test
    public void testSamplesAllRunnable() {
        for (Map.Entry<String, String> entry : MockJs.samples().entrySet()) {
            Object data = new MockJs().mock(entry.getValue());
            Assert.assertNotNull("示例 " + entry.getKey() + " 生成失败", data);
            Assert.assertTrue("示例 " + entry.getKey() + " 应生成对象", data instanceof Map);
        }
    }

    @Test
    public void testResetClearsOrder() {
        MockJs js = new MockJs();
        asMap(js.mock("{\"list|2\": [{\"lv|+1\": [\"A\", \"B\"]}]}"));
        js.reset();
        Map<String, Object> again = asMap(js.mock("{\"list|2\": [{\"lv|+1\": [\"A\", \"B\"]}]}"));
        List<Map<String, Object>> list = castList(again.get("list"));
        Assert.assertEquals("A", list.get(0).get("lv"));
        Assert.assertEquals("B", list.get(1).get("lv"));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object value) {
        Assert.assertTrue("应为对象：" + value, value instanceof Map);
        return (Map<String, Object>) value;
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> castList(Object value) {
        Assert.assertTrue("应为数组：" + value, value instanceof List);
        return (List<Map<String, Object>>) value;
    }
}
