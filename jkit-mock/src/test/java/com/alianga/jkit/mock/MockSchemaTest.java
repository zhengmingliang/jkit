package com.alianga.jkit.mock;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.util.List;
import java.util.Map;

import org.junit.Test;

/** MockSchema：由 JSON Schema 反向生成样例数据。 */
public class MockSchemaTest {

    @SuppressWarnings("unchecked")
    private static Map<String, Object> obj(Object value) {
        assertTrue("期望对象，实际 " + value, value instanceof Map);
        return (Map<String, Object>) value;
    }

    @Test
    public void testBasicTypesAndBounds() {
        String schema = "{\"type\":\"object\",\"properties\":{"
                + "\"age\":{\"type\":\"integer\",\"minimum\":18,\"maximum\":60},"
                + "\"name\":{\"type\":\"string\"},"
                + "\"vip\":{\"type\":\"boolean\"},"
                + "\"balance\":{\"type\":\"number\",\"minimum\":0,\"maximum\":9999}"
                + "},\"required\":[\"age\",\"name\",\"vip\",\"balance\"]}";
        for (int i = 0; i < 50; i++) {
            Map<String, Object> data = obj(MockSchema.mock(schema));
            Object age = data.get("age");
            assertTrue("age 应为整数：" + age, age instanceof Long);
            long ageValue = ((Long) age).longValue();
            assertTrue("age 越界：" + ageValue, ageValue >= 18 && ageValue <= 60);
            Object balance = data.get("balance");
            assertTrue("balance 应为小数：" + balance, balance instanceof Double);
            double b = ((Double) balance).doubleValue();
            assertTrue("balance 越界：" + b, b >= 0 && b <= 9999);
            assertTrue("vip 应为布尔", data.get("vip") instanceof Boolean);
            assertTrue("name 应为字符串", data.get("name") instanceof String);
        }
    }

    @Test
    public void testEnumAndConst() {
        String schema = "{\"type\":\"object\",\"properties\":{"
                + "\"gender\":{\"type\":\"string\",\"enum\":[\"男\",\"女\"]},"
                + "\"version\":{\"const\":\"v1\"}"
                + "}}";
        for (int i = 0; i < 30; i++) {
            Map<String, Object> data = obj(MockSchema.mock(schema));
            String gender = (String) data.get("gender");
            assertTrue("gender 取值异常：" + gender, "男".equals(gender) || "女".equals(gender));
            assertEquals("v1", data.get("version"));
        }
    }

    @Test
    public void testFormatAndPattern() {
        String schema = "{\"type\":\"object\",\"properties\":{"
                + "\"email\":{\"type\":\"string\",\"format\":\"email\"},"
                + "\"phone\":{\"type\":\"string\",\"pattern\":\"^1[3-9]\\\\d{9}$\"},"
                + "\"createdAt\":{\"type\":\"string\",\"format\":\"date-time\"},"
                + "\"uuid\":{\"type\":\"string\",\"format\":\"uuid\"}"
                + "}}";
        for (int i = 0; i < 30; i++) {
            Map<String, Object> data = obj(MockSchema.mock(schema));
            String email = (String) data.get("email");
            assertTrue("email 不合法：" + email, email != null && email.contains("@"));
            String phone = (String) data.get("phone");
            assertTrue("phone 不合法：" + phone, phone != null && phone.matches("^1[3-9]\\d{9}$"));
            String time = (String) data.get("createdAt");
            assertTrue("时间格式异常：" + time,
                    time != null && time.matches("\\d{4}-\\d{2}-\\d{2}.*"));
            String uuid = (String) data.get("uuid");
            assertTrue("uuid 异常：" + uuid, uuid != null && uuid.length() >= 32);
        }
    }

    @Test
    public void testStringLength() {
        String schema = "{\"type\":\"object\",\"properties\":{"
                + "\"code\":{\"type\":\"string\",\"minLength\":6,\"maxLength\":6},"
                + "\"short\":{\"type\":\"string\",\"maxLength\":3}"
                + "}}";
        for (int i = 0; i < 30; i++) {
            Map<String, Object> data = obj(MockSchema.mock(schema));
            assertEquals(6, ((String) data.get("code")).length());
            assertTrue("超长：" + data.get("short"), ((String) data.get("short")).length() <= 3);
        }
    }

    @Test
    public void testArrayBounds() {
        String schema = "{\"type\":\"object\",\"properties\":{"
                + "\"tags\":{\"type\":\"array\",\"minItems\":2,\"maxItems\":4,"
                + "\"items\":{\"type\":\"string\"}}"
                + "}}";
        for (int i = 0; i < 30; i++) {
            Map<String, Object> data = obj(MockSchema.mock(schema));
            List<?> tags = (List<?>) data.get("tags");
            assertTrue("数组长度越界：" + tags.size(), tags.size() >= 2 && tags.size() <= 4);
            for (Object item : tags) {
                assertTrue("元素应为字符串", item instanceof String);
            }
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    public void testRefAndDefinitions() {
        String schema = "{\"type\":\"object\",\"properties\":{"
                + "\"item\":{\"$ref\":\"#/definitions/Item\"}"
                + "},\"definitions\":{\"Item\":{\"type\":\"object\",\"properties\":{"
                + "\"sku\":{\"type\":\"string\"},\"qty\":{\"type\":\"integer\",\"minimum\":1,\"maximum\":5}"
                + "},\"required\":[\"sku\",\"qty\"]}}}";
        Map<String, Object> data = obj(MockSchema.mock(schema));
        Map<String, Object> item = (Map<String, Object>) data.get("item");
        assertNotNull("$ref 未解析", item);
        assertTrue("缺少 sku", item.containsKey("sku"));
        long qty = ((Long) item.get("qty")).longValue();
        assertTrue("qty 越界：" + qty, qty >= 1 && qty <= 5);
    }

    @Test
    public void testRefSelfLoopStops() {
        // 自引用：靠 maxDepth 兜底，必须能在有限时间内返回
        String schema = "{\"type\":\"object\",\"properties\":{\"child\":{\"$ref\":\"#\"}}}";
        Object data = MockSchema.mock(schema);
        assertNotNull(data);
    }

    @Test
    public void testAllOfAndOneOf() {
        String allOf = "{\"allOf\":[{\"type\":\"object\",\"properties\":{\"a\":{\"type\":\"integer\"}}},"
                + "{\"type\":\"object\",\"properties\":{\"b\":{\"type\":\"boolean\"}}}]}";
        Map<String, Object> merged = obj(MockSchema.mock(allOf));
        assertTrue("allOf 未合并 a", merged.containsKey("a"));
        assertTrue("allOf 未合并 b", merged.containsKey("b"));

        String oneOf = "{\"oneOf\":[{\"type\":\"integer\"},{\"type\":\"boolean\"}]}";
        boolean sawInt = false;
        boolean sawBool = false;
        for (int i = 0; i < 40; i++) {
            Object value = MockSchema.mock(oneOf);
            sawInt |= value instanceof Long;
            sawBool |= value instanceof Boolean;
        }
        assertTrue("oneOf 未覆盖整数分支", sawInt);
        assertTrue("oneOf 未覆盖布尔分支", sawBool);
    }

    @Test
    public void testExclusiveAndMultipleOf() {
        String schema = "{\"type\":\"object\",\"properties\":{"
                + "\"score\":{\"type\":\"integer\",\"exclusiveMinimum\":0,\"exclusiveMaximum\":10},"
                + "\"step\":{\"type\":\"integer\",\"minimum\":0,\"maximum\":100,\"multipleOf\":5}"
                + "}}";
        for (int i = 0; i < 50; i++) {
            Map<String, Object> data = obj(MockSchema.mock(schema));
            long score = ((Long) data.get("score")).longValue();
            assertTrue("score 越界：" + score, score > 0 && score < 10);
            long step = ((Long) data.get("step")).longValue();
            assertEquals("step 不是 5 的倍数：" + step, 0, step % 5);
        }
    }

    @Test
    public void testRequiredOnlyAndOptionalSwitch() {
        String schema = "{\"type\":\"object\",\"properties\":{"
                + "\"id\":{\"type\":\"integer\"},\"memo\":{\"type\":\"string\"}"
                + "},\"required\":[\"id\"]}";
        Map<String, Object> all = obj(MockSchema.mock(schema));
        assertTrue(all.containsKey("id"));
        assertTrue(all.containsKey("memo"));

        Map<String, Object> requiredOnly = obj(MockSchema.mock(schema,
                MockSchemaOptions.builder().includeOptional(false).build()));
        assertTrue(requiredOnly.containsKey("id"));
        assertTrue("未跳过非必填属性", !requiredOnly.containsKey("memo"));

        // required 里声明、但 properties 未定义的属性也要生成出来
        Map<String, Object> inferred = obj(MockSchema.mock(
                "{\"type\":\"object\",\"required\":[\"name\",\"email\"]}",
                MockSchemaOptions.builder().includeOptional(false).build()));
        assertTrue(inferred.containsKey("name"));
        assertTrue(inferred.containsKey("email"));
        assertTrue("email 未按语义生成：" + inferred.get("email"),
                String.valueOf(inferred.get("email")).contains("@"));
    }

    @Test
    public void testSemanticByName() {
        String schema = "{\"type\":\"object\",\"properties\":{"
                + "\"name\":{\"type\":\"string\"},"
                + "\"email\":{\"type\":\"string\"},"
                + "\"age\":{\"type\":\"integer\"}"
                + "}}";
        Map<String, Object> data = obj(MockSchema.mock(schema));
        assertTrue("name 不应为空", !((String) data.get("name")).isEmpty());
        assertTrue("email 应含 @", ((String) data.get("email")).contains("@"));
        assertTrue("age 应为整数", data.get("age") instanceof Long);
    }

    @Test
    public void testMockManyAndSeed() {
        String schema = "{\"type\":\"object\",\"properties\":{\"id\":{\"type\":\"integer\"},"
                + "\"name\":{\"type\":\"string\"}}}";
        List<Object> many = new MockSchema().mockMany(schema, 10);
        assertEquals(10, many.size());
        for (Object item : many) {
            assertTrue(item instanceof Map);
        }

        // 同一种子应得到完全一致的样例
        String first = MockSchema.mockJson(schema);
        MockSchema seeded = new MockSchema(new MockRandom(42L));
        String a = MockDataFormatter.prettyJson(seeded.generate(schema));
        MockSchema seededAgain = new MockSchema(new MockRandom(42L));
        String b = MockDataFormatter.prettyJson(seededAgain.generate(schema));
        assertEquals("同种子结果应一致", a, b);
        assertNotNull(first);
    }

    @Test
    public void testSamplesGenerate() {
        for (String name : MockSchema.samples().keySet()) {
            Object data = MockSchema.mock(MockSchema.samples().get(name));
            assertNotNull(name + " 示例生成失败", data);
        }
    }

    @Test
    public void testMockJsonIsPretty() {
        String json = MockSchema.mockJson(
                "{\"type\":\"object\",\"properties\":{\"a\":{\"type\":\"integer\"}}}");
        assertTrue("应为格式化 JSON：" + json, json.contains("\n") && json.trim().startsWith("{"));
    }
}
