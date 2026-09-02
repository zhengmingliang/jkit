package com.alianga.jkit.json;

import com.alianga.jkit.json.annotations.JsonProperty;
import com.alianga.jkit.json.options.ReadOption;
import com.alianga.jkit.json.options.WriteOption;
import org.junit.Test;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class JSONTest {
    public static class User {
        private String name;
        private int age;
        private List<String> tags;

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }

        public int getAge() {
            return age;
        }

        public void setAge(int age) {
            this.age = age;
        }

        public List<String> getTags() {
            return tags;
        }

        public void setTags(List<String> tags) {
            this.tags = tags;
        }
    }

    /** 用于验证驼峰/下划线自动转换以及别名/忽略序列化/忽略反序列化等注解能力 */
    public static class CamelBean {
        private String userName;
        private String firstName;
        @JsonProperty(name = "alias_name")
        private String aliasField;
        @JsonProperty(serialize = false)
        private String skipOnWrite;
        @JsonProperty(deserialize = false)
        private String skipOnRead;

        public String getUserName() {
            return userName;
        }

        public void setUserName(String userName) {
            this.userName = userName;
        }

        public String getFirstName() {
            return firstName;
        }

        public void setFirstName(String firstName) {
            this.firstName = firstName;
        }

        public String getAliasField() {
            return aliasField;
        }

        public void setAliasField(String aliasField) {
            this.aliasField = aliasField;
        }

        public String getSkipOnWrite() {
            return skipOnWrite;
        }

        public void setSkipOnWrite(String skipOnWrite) {
            this.skipOnWrite = skipOnWrite;
        }

        public String getSkipOnRead() {
            return skipOnRead;
        }

        public void setSkipOnRead(String skipOnRead) {
            this.skipOnRead = skipOnRead;
        }
    }

    /** 用于验证自定义序列化/反序列化映射器的目标类型 */
    public static class Point {
        public final int x;
        public final int y;

        public Point(int x, int y) {
            this.x = x;
            this.y = y;
        }

        @Override
        public boolean equals(Object o) {
            if (!(o instanceof Point)) {
                return false;
            }
            Point p = (Point) o;
            return x == p.x && y == p.y;
        }

        @Override
        public int hashCode() {
            return x * 31 + y;
        }
    }

    /** 用于验证 @JsonProperty(mapper = ...) 字段级自定义映射 */
    public static class SecretBean {
        @JsonProperty(mapper = ReverseMapper.class)
        private String secret;

        public String getSecret() {
            return secret;
        }

        public void setSecret(String secret) {
            this.secret = secret;
        }
    }

    /** 将字符串反转后输出的字段映射器 */
    public static class ReverseMapper extends JSONTypeFieldMapper<String> {
        @Override
        public String readOf(Object value) throws Exception {
            return value == null ? null : new StringBuilder(String.valueOf(value)).reverse().toString();
        }

        @Override
        public JSONValue<?> writeAs(String value, JSONConfig jsonConfig) throws Exception {
            return value == null ? null : JSONValue.of(new StringBuilder(value).reverse().toString());
        }
    }

    public enum Color {
        RED, GREEN, BLUE
    }

    public enum Level {
        LOW, MEDIUM, HIGH
    }

    private static final String JSON_TEXT =
            "{\"name\":\"Tom\",\"age\":18,\"tags\":[\"a\",\"b\"],\"dept\":{\"id\":7,\"name\":\"dev\"}}";

    @Test
    public void testParseToMap() {
        Map<String, Object> map = (Map<String, Object>) JSON.parse(JSON_TEXT);
        assertEquals("Tom", map.get("name"));
        assertEquals(18, map.get("age"));
        assertEquals(2, ((List<?>) map.get("tags")).size());
    }

    @Test
    public void testParseObject() {
        User user = JSON.parseObject(JSON_TEXT, User.class);
        assertEquals("Tom", user.getName());
        assertEquals(18, user.getAge());
        assertEquals(2, user.getTags().size());
    }

    @Test
    public void testSerializeRoundTrip() {
        User user = new User();
        user.setName("Tom");
        user.setAge(18);
        user.setTags(Arrays.asList("a", "b"));

        String out = JSON.toJsonString(user);
        User back = JSON.parseObject(out, User.class);
        assertEquals("Tom", back.getName());
        assertEquals(18, back.getAge());
        assertEquals(2, back.getTags().size());
    }

    @Test
    public void testParseArray() {
        List<User> list = JSON.parseArray("[{\"name\":\"A\"},{\"name\":\"B\"}]", User.class);
        assertEquals(2, list.size());
        assertEquals("B", list.get(1).getName());
    }

    @Test
    public void testNdJson() {
        Map<String, Object> map = (Map<String, Object>) JSON.parse(JSON_TEXT);
        String nd = JSON.toNdJsonString(Arrays.asList(map, map));
        List parsed = JSON.parseNdJson(nd);
        assertEquals(2, parsed.size());
    }

    @Test
    public void testPrettifyJsonString() {
        String pretty = JSON.prettifyJsonString(JSON_TEXT);
        assertTrue(pretty.contains("\n"));
        Object value = JSON.parse(JSON_TEXT);
        String pretty2 = JSON.toPrettifyJsonString(value);
        assertTrue(pretty2.contains("\n"));
    }

    // ---------------------------------------------------------------------
    // 序列化格式化
    // ---------------------------------------------------------------------

    @Test
    public void testFormatOutTab() {
        Map<String, Object> map = new LinkedHashMap<String, Object>();
        map.put("a", 1);
        map.put("b", "x");
        String out = JSON.toJsonString(map, WriteOption.FormatOut);
        // 默认 tab 缩进
        assertTrue(out.contains("\n\t"));
    }

    @Test
    public void testFormatOutColonSpace() {
        Map<String, Object> map = new LinkedHashMap<String, Object>();
        map.put("a", 1);
        String out = JSON.toJsonString(map, WriteOption.FormatOut, WriteOption.FormatOutColonSpace);
        assertTrue(out.contains("\"a\": 1"));
    }

    @Test
    public void testFormatIndentUseSpace() {
        Map<String, Object> map = new LinkedHashMap<String, Object>();
        map.put("a", 1);
        String out = JSON.toJsonString(map, WriteOption.FormatOut, WriteOption.FormatIndentUseSpace);
        assertTrue(out.contains("\n    "));
    }

    @Test
    public void testFormatIndentUseSpace8() {
        Map<String, Object> map = new LinkedHashMap<String, Object>();
        map.put("a", 1);
        String out = JSON.toJsonString(map, WriteOption.FormatOut, WriteOption.FormatIndentUseSpace8);
        assertTrue(out.contains("\n        "));
    }

    @Test
    public void testJsonConfigFormat() {
        JSONConfig config = JSONConfig.formatOf();
        config.setFormatIndentUseSpace(true);
        config.setFormatIndentSpaceNum(2);
        Map<String, Object> map = new LinkedHashMap<String, Object>();
        map.put("a", 1);
        String out = JSON.toJsonString(map, config);
        assertTrue(out.contains("\n  "));
    }

    // ---------------------------------------------------------------------
    // 驼峰 / 下划线自动转换
    // ---------------------------------------------------------------------

    @Test
    public void testCamelCaseToUnderlineOnWrite() {
        CamelBean bean = new CamelBean();
        bean.setUserName("tom");
        bean.setFirstName("Tom");
        bean.setAliasField("alias");

        // 默认输出驼峰
        String defaultOut = JSON.toJsonString(bean);
        assertTrue(defaultOut.contains("\"userName\""));
        assertTrue(defaultOut.contains("\"alias_name\"")); // 注解别名生效

        // 开启驼峰转下划线
        String underlineOut = JSON.toJsonString(bean, WriteOption.CamelCaseToUnderline);
        assertTrue(underlineOut.contains("\"user_name\""));
        assertTrue(underlineOut.contains("\"first_name\""));
    }

    @Test
    public void testUnderlineToCamelCaseOnRead() {
        String json = "{\"user_name\":\"tom\",\"first_name\":\"Tom\"}";
        CamelBean bean = JSON.parseObject(json, CamelBean.class);
        assertEquals("tom", bean.getUserName());
        assertEquals("Tom", bean.getFirstName());

        // 同时支持驼峰形式读取
        CamelBean bean2 = JSON.parseObject("{\"userName\":\"jack\"}", CamelBean.class);
        assertEquals("jack", bean2.getUserName());
    }

    @Test
    public void testAnnotationNameAndSerializeAndDeserialize() {
        CamelBean bean = new CamelBean();
        bean.setAliasField("v1");
        bean.setSkipOnWrite("hidden");
        bean.setSkipOnRead("read-hidden");

        String out = JSON.toJsonString(bean);
        // 别名生效
        assertTrue(out.contains("\"alias_name\":\"v1\""));
        // serialize=false 不输出
        assertFalse(out.contains("skipOnWrite"));

        // deserialize=false 不读取
        CamelBean back = JSON.parseObject("{\"skipOnRead\":\"xx\",\"alias_name\":\"yy\"}", CamelBean.class);
        assertNull(back.getSkipOnRead());
        assertEquals("yy", back.getAliasField());
    }

    // ---------------------------------------------------------------------
    // 实体类解析绑定
    // ---------------------------------------------------------------------

    @Test
    public void testParseToExistingInstance() {
        User target = new User();
        Object back = JSON.parseToObject("{\"name\":\"Alice\",\"age\":30}", target);
        assertTrue(back == target);
        assertEquals("Alice", target.getName());
        assertEquals(30, target.getAge());
    }

    @Test
    public void testParseToList() {
        List<User> list = new java.util.ArrayList<User>();
        Object back = JSON.parseToList("[{\"name\":\"A\"},{\"name\":\"B\"}]", list, User.class);
        assertTrue(back == list);
        assertEquals(2, list.size());
    }

    @Test
    public void testTranslateAndClone() {
        Map<String, Object> map = new LinkedHashMap<String, Object>();
        map.put("name", "Tom");
        map.put("age", 18);
        // 转换为实体
        User user = JSON.translateTo(map, User.class);
        assertEquals("Tom", user.getName());
        assertEquals(18, user.getAge());

        // 深拷贝
        User copy = JSON.cloneObject(user);
        assertEquals(user.getName(), copy.getName());
        assertFalse(user == copy);
    }

    @Test
    public void testParseByteArrayAndCharArray() {
        User u1 = JSON.parseObject(JSON_TEXT.toCharArray(), User.class);
        assertEquals("Tom", u1.getName());
        User u2 = JSON.parseObject(JSON_TEXT.getBytes(), User.class);
        assertEquals("Tom", u2.getName());
    }

    // ---------------------------------------------------------------------
    // 自定义序列化 / 反序列化
    // ---------------------------------------------------------------------

    @Test
    public void testRegisterTypeMapper() {
        JSON.register(Point.class, new JSONTypeMapper<Point>() {
            @Override
            public Point readOf(Object value) throws Exception {
                if (value == null) {
                    return null;
                }
                String[] parts = String.valueOf(value).split(",");
                return new Point(Integer.parseInt(parts[0]), Integer.parseInt(parts[1]));
            }

            @Override
            public JSONValue<?> writeAs(Point value, JSONConfig jsonConfig) throws Exception {
                return value == null ? null : JSONValue.of(value.x + "," + value.y);
            }
        });

        try {
            Point point = new Point(3, 4);
            String out = JSON.toJsonString(point);
            assertEquals("\"3,4\"", out);

            Point back = JSON.parseObject("\"10,20\"", Point.class);
            assertEquals(new Point(10, 20), back);

            // 在其它实体中使用
            Map<String, Point> map = new LinkedHashMap<String, Point>();
            map.put("p", new Point(1, 2));
            String mapOut = JSON.toJsonString(map);
            assertEquals("{\"p\":\"1,2\"}", mapOut);
        } finally {
            // 避免影响其它测试（Point 注册为静态映射器）
            JSON.disableJIT();
        }
    }

    @Test
    public void testRegisterTypeSerializerAndDeserializer() {
        JSON.register(Version.class, new JSONTypeSerializer() {
            @Override
            protected void serialize(Object value, JSONWriter writer, JSONConfig jsonConfig, int indent)
                    throws Exception {
                Version version = (Version) value;
                writer.writeJSONString(version.major + "." + version.minor);
            }
        });

        try {
            String out = JSON.toJsonString(new Version(1, 2));
            assertEquals("\"1.2\"", out);
        } finally {
            JSON.disableJIT();
        }
    }

    public static class Version {
        public final int major;
        public final int minor;

        public Version(int major, int minor) {
            this.major = major;
            this.minor = minor;
        }
    }

    @Test
    public void testFieldMapperAnnotation() {
        SecretBean bean = new SecretBean();
        bean.setSecret("hello");
        String out = JSON.toJsonString(bean);
        // 字段映射器将 "hello" 反转为 "olleh"
        assertEquals("{\"secret\":\"olleh\"}", out);

        SecretBean back = JSON.parseObject("{\"secret\":\"olleh\"}", SecretBean.class);
        assertEquals("hello", back.getSecret());
    }

    // ---------------------------------------------------------------------
    // 其它能力
    // ---------------------------------------------------------------------

    @Test
    public void testWriteEnumAsOrdinal() {
        assertEquals("\"LOW\"", JSON.toJsonString(Level.LOW));
        assertEquals("0", JSON.toJsonString(Level.LOW, WriteOption.WriteEnumAsOrdinal));
    }

    @Test
    public void testSortJson() {
        String sorted = JSON.sortJsonString("{\"b\":2,\"a\":1}");
        assertTrue(sorted.startsWith("{\"a\":1"));
    }

    @Test
    public void testValidate() {
        assertTrue(JSON.validate(JSON_TEXT));
        assertFalse(JSON.validate("{bad json}"));
        assertFalse(JSON.validate((String) null));
        assertTrue(JSON.validate(JSON_TEXT.toCharArray()));
        assertTrue(JSON.validate(JSON_TEXT.getBytes()));
    }

    @Test
    public void testNonStandardJsonWithReadOptions() {
        // 注释 + 单引号 key + 无引号 key + 末尾逗号
        String json = "{ // comment\n 'a': 1, b: 2, }";
        Map<String, Object> map = (Map<String, Object>) JSON.parse(json,
                ReadOption.AllowComment,
                ReadOption.AllowSingleQuotes,
                ReadOption.AllowUnquotedFieldNames,
                ReadOption.AllowLastEndComma);
        assertEquals(1, ((Number) map.get("a")).intValue());
        assertEquals(2, ((Number) map.get("b")).intValue());
    }

    @Test
    public void testUseBigDecimalAsDefault() {
        Object number = JSON.parse("123456789012345678901234567890");
        // 超出 long 范围的整数解析为 BigInteger
        assertTrue(number instanceof java.math.BigInteger);

        // 浮点默认解析为 Double
        Object d = JSON.parseAs("3.14");
        assertTrue(d instanceof Double);

        // 开启后统一转为 BigDecimal
        Object bd = JSON.parseAs("3.14", ReadOption.UseBigDecimalAsDefault);
        assertTrue(bd instanceof BigDecimal);
    }

    @Test
    public void testParseNumberAs() {
        Integer i = JSON.parseNumberAs("123", Integer.class);
        assertEquals(123, i.intValue());
        Long l = JSON.parseNumberAs("123", Long.class);
        assertEquals(123L, l.longValue());
        Double d = JSON.parseNumberAs("123.5", Double.class);
        assertEquals(123.5, d.doubleValue(), 0.001);
    }

    @Test
    public void testDateSerialization() {
        Date date = new Date(0L);
        // 默认格式化为 yyyy-MM-dd HH:mm:ss
        String formatted = JSON.toJsonString(date);
        assertTrue(formatted.startsWith("\"1970-"));
        // 时间戳
        String asTime = JSON.toJsonString(date, WriteOption.DateAsTime);
        assertEquals("0", asTime);
    }
}