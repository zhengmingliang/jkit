package com.alianga.jkit.mock;

import org.junit.Assert;
import org.junit.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * MockDataFormatter 格式化单元测试，重点覆盖两种模式共用的 JSON 输出。
 *
 * @author 郑明亮
 */
public class MockDataFormatterTest {

    private final MockJs js = new MockJs();

    @Test
    public void testPrettyJsonObject() {
        Map<String, Object> data = new LinkedHashMap<String, Object>();
        data.put("code", 200);
        data.put("ok", true);
        data.put("name", "张三");
        String json = MockDataFormatter.prettyJson(data);
        Assert.assertTrue(json, json.startsWith("{"));
        Assert.assertTrue(json, json.contains("\n  \"code\": 200"));
        Assert.assertTrue(json, json.contains("\n  \"name\": \"张三\""));
        Assert.assertTrue(json, json.trim().endsWith("}"));
    }

    @Test
    public void testPrettyJsonNested() {
        Map<String, Object> inner = new LinkedHashMap<String, Object>();
        inner.put("id", 1);
        List<Object> list = new ArrayList<Object>();
        list.add(inner);
        Map<String, Object> data = new LinkedHashMap<String, Object>();
        data.put("list", list);
        String json = MockDataFormatter.prettyJson(data);
        Assert.assertTrue(json, json.contains("\"list\": ["));
        Assert.assertTrue(json, json.contains("\"id\": 1"));
    }

    @Test
    public void testPrettyJsonScalarAndEmpty() {
        Assert.assertEquals("null", MockDataFormatter.prettyJson(null));
        Assert.assertEquals("\"abc\"", MockDataFormatter.prettyJson("abc"));
        Assert.assertEquals("12", MockDataFormatter.prettyJson(12));
        Assert.assertEquals("{}", MockDataFormatter.prettyJson(new LinkedHashMap<String, Object>()));
        Assert.assertEquals("[]", MockDataFormatter.prettyJson(new ArrayList<Object>()));
    }

    @Test
    public void testPrettyJsonEscapes() {
        String json = MockDataFormatter.prettyJson("a\"b\\c\nd");
        Assert.assertEquals("\"a\\\"b\\\\c\\nd\"", json);
    }

    @Test
    public void testTwoModesShareJsonStyle() {
        Object templateData = js.mock((Object) "{'list|2':[{'id|+1':1}]}");
        String templateJson = MockDataFormatter.format(templateData, MockOutputFormat.JSON);

        MockDataProducer producer = new MockDataProducer();
        producer.addField(MockFieldType.UUID);
        producer.setCount(2);
        String fieldJson = MockDataFormatter.format(producer.generate(), MockOutputFormat.JSON);

        Assert.assertTrue("模板模式应缩进：" + templateJson, templateJson.contains("\n  "));
        Assert.assertTrue("字段模式应缩进：" + fieldJson, fieldJson.contains("\n  "));
        Assert.assertTrue(fieldJson, fieldJson.trim().startsWith("["));
        Assert.assertTrue(fieldJson, fieldJson.contains("\n    "));
    }

    @Test
    public void testToRowsDrillsIntoNestedList() {
        Object data = js.mock((Object) "{'list|3':[{'id|+1':1}]}");
        List<Map<String, Object>> rows = MockDataFormatter.toRows(data);
        Assert.assertEquals(3, rows.size());
        Assert.assertTrue(rows.get(0).containsKey("id"));
    }

    @Test
    public void testToRowsScalar() {
        List<Map<String, Object>> rows = MockDataFormatter.toRows("hello");
        Assert.assertEquals(1, rows.size());
        Assert.assertEquals("hello", rows.get(0).get("value"));
    }

    @Test
    public void testFormatNonJsonFallsBackToRows() {
        Object data = js.mock((Object) "{'list|2':[{'id|+1':1,'name':'@cname'}]}");
        String csv = MockDataFormatter.format(data, MockOutputFormat.CSV);
        Assert.assertTrue(csv, csv.startsWith("id,name"));
        Assert.assertEquals(3, csv.trim().split("\n").length);
    }
}
