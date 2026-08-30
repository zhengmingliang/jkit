package com.alianga.jkit.json;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class JSONSchemaTest {

    private static final String USER_SCHEMA =
            "{\n"
                    + "  \"type\": \"object\",\n"
                    + "  \"properties\": {\n"
                    + "    \"name\": {\n"
                    + "      \"must\": true,\n"
                    + "      \"type\": \"string\",\n"
                    + "      \"minLength\": 2,\n"
                    + "      \"maxLength\": 16,\n"
                    + "      \"rules\": [\n"
                    + "        {\"expression\": \"value.indexOf('test') > -1\", \"message\": \"必须包含test\"}\n"
                    + "      ]\n"
                    + "    },\n"
                    + "    \"age\": {\n"
                    + "      \"type\": \"number\",\n"
                    + "      \"minimum\": 20,\n"
                    + "      \"maximum\": 100\n"
                    + "    },\n"
                    + "    \"key\": {\n"
                    + "      \"type\": [\"number\", \"boolean\"]\n"
                    + "    }\n"
                    + "  }\n"
                    + "}";

    @Test
    public void testBasicValidate() {
        JSONSchema schema = JSONSchema.of(USER_SCHEMA);
        assertTrue(schema.validateSuccess("{\"name\":\"test user\",\"age\":33,\"key\":false}"));
        assertFalse(schema.validateSuccess("{\"name\":\"user\",\"age\":33}"));   // 不包含 test
        assertFalse(schema.validateSuccess("{\"name\":\"test\",\"age\":10}"));  // age 小于 20
        assertFalse(schema.validateSuccess("{\"age\":33}"));                    // name 必填缺失
    }

    @Test
    public void testTypeMismatch() {
        JSONSchema schema = JSONSchema.of(USER_SCHEMA);
        JSONSchemaResult result = schema.validate("{\"name\":\"test\",\"age\":\"not a number\"}");
        assertFalse(result.isSuccess());
    }

    @Test
    public void testMustRequired() {
        JSONSchema schema = JSONSchema.of(
                "{\"type\":\"object\",\"properties\":{\"name\":{\"type\":\"string\",\"must\":true}}}");
        assertTrue(schema.validateSuccess("{\"name\":\"a\"}"));
        assertFalse(schema.validateSuccess("{}"));
        assertFalse(schema.validateSuccess("{\"name\":null}"));
    }

    @Test
    public void testStringLengthAndPattern() {
        JSONSchema schema = JSONSchema.of(
                "{\"type\":\"object\",\"properties\":{\"v\":"
                        + "{\"type\":\"string\",\"minLength\":2,\"maxLength\":4,\"pattern\":\"^[a-z]+$\"}}}");
        assertTrue(schema.validateSuccess("{\"v\":\"abc\"}"));
        assertFalse(schema.validateSuccess("{\"v\":\"a\"}"));
        assertFalse(schema.validateSuccess("{\"v\":\"abcde\"}"));
        assertFalse(schema.validateSuccess("{\"v\":\"ABC\"}"));
    }

    @Test
    public void testNumberBounds() {
        JSONSchema schema = JSONSchema.of(
                "{\"type\":\"object\",\"properties\":{\"v\":"
                        + "{\"type\":\"number\",\"minimum\":1,\"maximum\":10,\"exclusiveMinimum\":true}}}");
        assertTrue(schema.validateSuccess("{\"v\":5}"));
        assertFalse(schema.validateSuccess("{\"v\":1}"));   // exclusiveMinimum 排除 1
        assertFalse(schema.validateSuccess("{\"v\":11}"));
    }

    @Test
    public void testIntegerType() {
        JSONSchema schema = JSONSchema.of(
                "{\"type\":\"object\",\"properties\":{\"v\":{\"type\":\"integer\"}}}");
        assertTrue(schema.validateSuccess("{\"v\":12}"));
        assertFalse(schema.validateSuccess("{\"v\":12.5}"));
    }

    @Test
    public void testEnumTypes() {
        JSONSchema schema = JSONSchema.of(
                "{\"type\":\"object\",\"properties\":{\"v\":{\"enum\":[\"string\",\"number\"]}}}");
        assertTrue(schema.validateSuccess("{\"v\":\"hello\"}"));
        assertTrue(schema.validateSuccess("{\"v\":123}"));
        assertFalse(schema.validateSuccess("{\"v\":true}"));
    }

    @Test
    public void testArrayValidation() {
        JSONSchema schema = JSONSchema.of(
                "{\"type\":\"object\",\"properties\":{\"arr\":"
                        + "{\"type\":\"array\",\"minItems\":1,\"maxItems\":3,\"items\":{\"type\":\"number\"}}}}");
        assertTrue(schema.validateSuccess("{\"arr\":[1,2,3]}"));
        assertFalse(schema.validateSuccess("{\"arr\":[]}"));
        assertFalse(schema.validateSuccess("{\"arr\":[1,2,3,4]}"));
        assertFalse(schema.validateSuccess("{\"arr\":[1,\"x\"]}"));
    }

    @Test
    public void testFormatEmailAndUrl() {
        JSONSchema email = JSONSchema.of(
                "{\"type\":\"object\",\"properties\":{\"v\":{\"type\":\"string\",\"format\":\"email\"}}}");
        assertTrue(email.validateSuccess("{\"v\":\"a@b.com\"}"));
        assertFalse(email.validateSuccess("{\"v\":\"not-an-email\"}"));

        JSONSchema url = JSONSchema.of(
                "{\"type\":\"object\",\"properties\":{\"v\":{\"type\":\"string\",\"format\":\"url\"}}}");
        assertTrue(url.validateSuccess("{\"v\":\"http://example.com/a\"}"));
        assertFalse(url.validateSuccess("{\"v\":\"example\"}"));
    }

    @Test
    public void testAnyOf() {
        JSONSchema schema = JSONSchema.of(
                "{\"type\":\"object\",\"properties\":{\"x\":"
                        + "{\"anyOf\":[{\"type\":\"string\"},{\"type\":\"number\"}]}}}");
        assertTrue(schema.validateSuccess("{\"x\":\"s\"}"));
        assertTrue(schema.validateSuccess("{\"x\":1}"));
        assertFalse(schema.validateSuccess("{\"x\":true}"));
    }

    @Test
    public void testOneOf() {
        JSONSchema schema = JSONSchema.of(
                "{\"type\":\"object\",\"properties\":{\"x\":"
                        + "{\"oneOf\":[{\"type\":\"string\",\"minLength\":5},{\"type\":\"number\"}]}}}");
        assertTrue(schema.validateSuccess("{\"x\":\"hello world\"}"));  // 匹配第一个子 schema
        assertTrue(schema.validateSuccess("{\"x\":5}"));                // 匹配第二个子 schema
        assertFalse(schema.validateSuccess("{\"x\":true}"));             // 都不匹配
    }

    @Test
    public void testNestedPropertiesAndDisableExtra() {
        String schemaJson = "{\n"
                + "  \"type\": \"object\",\n"
                + "  \"disableExtra\": true,\n"
                + "  \"properties\": {\n"
                + "    \"dept\": {\n"
                + "      \"type\": \"object\",\n"
                + "      \"properties\": {\n"
                + "        \"id\": {\"type\": \"integer\"},\n"
                + "        \"name\": {\"type\": \"string\", \"must\": true}\n"
                + "      }\n"
                + "    }\n"
                + "  }\n"
                + "}";
        JSONSchema schema = JSONSchema.of(schemaJson);
        assertTrue(schema.validateSuccess("{\"dept\":{\"id\":7,\"name\":\"dev\"}}"));
        assertFalse(schema.validateSuccess("{\"dept\":{\"id\":7}}"));           // name 缺失
        assertFalse(schema.validateSuccess("{\"dept\":{\"name\":\"dev\"},\"other\":1}")); // 根额外字段
    }

    @Test
    public void testRuleRegular() {
        JSONSchema schema = JSONSchema.of(
                "{\"type\":\"object\",\"properties\":{\"v\":"
                        + "{\"type\":\"string\",\"rules\":[{\"regular\":\"^[A-Z0-9]+$\",\"message\":\"必须大写\"}]}}}");
        assertTrue(schema.validateSuccess("{\"v\":\"ABC123\"}"));
        assertFalse(schema.validateSuccess("{\"v\":\"abc\"}"));
    }

    @Test
    public void testValidateNode() {
        JSONSchema schema = JSONSchema.of(USER_SCHEMA);
        JSONNode node = JSONNode.parse("{\"name\":\"test\",\"age\":33}");
        assertTrue(schema.validateSuccess(node));
        assertTrue(schema.validate(node).isSuccess());
    }
}