package com.alianga.jkit.mock;

import org.junit.Assert;
import org.junit.Test;

import java.util.List;
import java.util.Map;

/**
 * MockValid 校验与 JSON Schema 推导单元测试。
 *
 * @author 郑明亮
 */
public class MockValidTest {

    @Test
    public void testValidOk() {
        MockJs js = new MockJs();
        Object data = js.mock("{\"id|+1\": 1, \"name\": \"@cname\"}");
        List<String> errors = MockValid.valid("{\"id|+1\": 1, \"name\": \"@cname\"}", data);
        Assert.assertTrue("应无错误：" + errors, errors.isEmpty());
    }

    @Test
    public void testValidMissingField() {
        Map<String, Object> data = new java.util.LinkedHashMap<String, Object>();
        data.put("id", 1);
        List<String> errors = MockValid.valid("{\"id|+1\": 1, \"name\": \"@cname\"}", data);
        Assert.assertFalse("缺少 name 应报错", errors.isEmpty());
    }

    @Test
    public void testValidTypeMismatch() {
        Map<String, Object> data = new java.util.LinkedHashMap<String, Object>();
        data.put("id", "notANumber");
        List<String> errors = MockValid.valid("{\"id|+1\": 1}", data);
        Assert.assertFalse("类型不符应报错", errors.isEmpty());
    }

    @Test
    @SuppressWarnings("unchecked")
    public void testJsonSchemaBasic() {
        Map<String, Object> schema = MockValid.toJsonSchema(
                "{\"id|+1\": 1, \"name\": \"@cname\", \"vip|1\": true, \"score|1-100.2\": 1}");
        Assert.assertEquals("object", schema.get("type"));
        Map<String, Object> props = (Map<String, Object>) schema.get("properties");
        Assert.assertTrue(props.containsKey("id"));
        Assert.assertTrue(props.containsKey("name"));
        Assert.assertTrue(props.containsKey("vip"));
        Assert.assertEquals("boolean", ((Map<String, Object>) props.get("vip")).get("type"));
    }

    @Test
    @SuppressWarnings("unchecked")
    public void testJsonSchemaArray() {
        Map<String, Object> schema = MockValid.toJsonSchema(
                "{\"list|1-3\": [{\"id|+1\": 1}]}");
        Map<String, Object> props = (Map<String, Object>) schema.get("properties");
        Map<String, Object> list = (Map<String, Object>) props.get("list");
        Assert.assertEquals("array", list.get("type"));
        Assert.assertNotNull(list.get("range"));
    }

    @Test
    public void testMockValidOnAllSamples() {
        for (Map.Entry<String, String> entry : MockJs.samples().entrySet()) {
            MockJs js = new MockJs();
            Object data = js.mock(entry.getValue());
            List<String> errors = MockValid.valid(entry.getValue(), data);
            Assert.assertTrue("示例 " + entry.getKey() + " 校验失败：" + errors, errors.isEmpty());
        }
    }
}
