package com.alianga.jkit.json;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * 标准 JSON Schema {@code required} 关键字的回归。
 *
 * <p>此前 {@code required} 能被解析进 {@link JSONSchema#getRequired()}，但校验完全不看它——
 * 按标准写法声明的必填字段缺失时依旧返回成功，属于静默失效。
 */
public class JSONSchemaRequiredTest {

    @Test
    public void standardRequiredArrayIsEnforced() {
        JSONSchema schema = JSONSchema.of("{\"type\":\"object\",\"required\":[\"name\"],"
                + "\"properties\":{\"name\":{\"type\":\"string\"},\"age\":{\"type\":\"number\"}}}");
        assertEquals(1, schema.getRequired().size());
        assertTrue(schema.getRequired().contains("name"));

        assertTrue(schema.validateSuccess("{\"name\":\"a\"}"));
        assertFalse("缺 name 应失败", schema.validateSuccess("{\"age\":1}"));
        assertFalse(schema.validateSuccess("{}"));
    }

    @Test
    public void requiredFailureHasMessage() {
        JSONSchema schema = JSONSchema.of("{\"type\":\"object\",\"required\":[\"name\"],"
                + "\"properties\":{\"name\":{\"type\":\"string\"}}}");
        JSONSchemaResult result = schema.validate("{}");
        assertFalse(result.isSuccess());
        assertTrue("失败信息应指明缺失字段: " + result.getMessage(),
                result.getMessage() != null && result.getMessage().contains("name"));
    }

    @Test
    public void requiredOnlyNeedsPresence() {
        // 标准语义：required 只要求字段存在；{"v":null} 不再报「缺字段」，
        // 若 schema 还声明了 type，那失败原因是类型不匹配而不是必填
        JSONSchema schema = JSONSchema.of("{\"type\":\"object\",\"required\":[\"v\"],"
                + "\"properties\":{\"v\":{\"type\":\"string\"}}}");
        JSONSchemaResult nullValue = schema.validate("{\"v\":null}");
        assertFalse(nullValue.isSuccess());
        assertTrue("应因类型不匹配失败，而不是必填: " + nullValue.getMessage(),
                nullValue.getMessage() != null && nullValue.getMessage().contains("type not match"));

        JSONSchemaResult missing = schema.validate("{}");
        assertFalse(missing.isSuccess());
        assertTrue("缺失字段应报 required: " + missing.getMessage(),
                missing.getMessage() != null && missing.getMessage().contains("is required"));
    }

    @Test
    public void mustStillRequiresNonNull() {
        // 本库自有写法：must 写在字段自己的 schema 里，且值不能是 null
        JSONSchema schema = JSONSchema.of("{\"type\":\"object\","
                + "\"properties\":{\"v\":{\"type\":\"string\",\"must\":true}}}");
        assertTrue(schema.validateSuccess("{\"v\":\"a\"}"));
        assertFalse(schema.validateSuccess("{\"v\":null}"));
        assertFalse(schema.validateSuccess("{}"));
    }

    @Test
    public void requiredAndMustCanCoexist() {
        JSONSchema schema = JSONSchema.of("{\"type\":\"object\",\"required\":[\"a\",\"b\"],"
                + "\"properties\":{\"a\":{\"type\":\"string\"},\"b\":{\"type\":\"number\",\"must\":true}}}");
        assertTrue(schema.validateSuccess("{\"a\":\"x\",\"b\":1}"));
        assertFalse("required 缺 a", schema.validateSuccess("{\"b\":1}"));
        assertFalse("required 缺 b", schema.validateSuccess("{\"a\":\"x\"}"));
        assertFalse("must 的 b 不能为 null", schema.validateSuccess("{\"a\":\"x\",\"b\":null}"));
    }

    @Test
    public void requiredWorksWithoutProperties() {
        // 只有 required、没有 properties 时同样要拦住
        JSONSchema schema = JSONSchema.of("{\"type\":\"object\",\"required\":[\"token\"]}");
        assertTrue(schema.validateSuccess("{\"token\":\"abc\"}"));
        assertFalse(schema.validateSuccess("{}"));
    }

    @Test
    public void requiredAppliesToNestedObject() {
        JSONSchema schema = JSONSchema.of("{\"type\":\"object\",\"properties\":{\"user\":{\"type\":\"object\","
                + "\"required\":[\"id\"],\"properties\":{\"id\":{\"type\":\"number\"},\"name\":{\"type\":\"string\"}}}}}");
        assertTrue(schema.validateSuccess("{\"user\":{\"id\":1}}"));
        assertFalse("嵌套对象缺 id 应失败", schema.validateSuccess("{\"user\":{\"name\":\"a\"}}"));
    }

    @Test
    public void requiredDoesNotSkipTypeCheck() {
        // 字段存在但类型不符，仍然报类型错误
        JSONSchema schema = JSONSchema.of("{\"type\":\"object\",\"required\":[\"v\"],"
                + "\"properties\":{\"v\":{\"type\":\"number\"}}}");
        assertFalse(schema.validateSuccess("{\"v\":\"not a number\"}"));
        assertTrue(schema.validateSuccess("{\"v\":1}"));
    }

    @Test
    public void emptyOrAbsentRequiredKeepsBehaviour() {
        JSONSchema without = JSONSchema.of("{\"type\":\"object\",\"properties\":{\"v\":{\"type\":\"string\"}}}");
        assertTrue("没有 required 时可选字段缺失应通过", without.validateSuccess("{}"));
        assertTrue(without.validateSuccess("{\"v\":\"a\"}"));

        JSONSchema empty = JSONSchema.of("{\"type\":\"object\",\"required\":[],\"properties\":{}}");
        assertTrue(empty.validateSuccess("{}"));
    }
}
