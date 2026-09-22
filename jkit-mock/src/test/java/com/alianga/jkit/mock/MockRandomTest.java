package com.alianga.jkit.mock;

import org.junit.Assert;
import org.junit.Test;

import java.util.List;
import java.util.Map;

/**
 * MockRandom 占位符库单元测试，覆盖 Mock.js 的数据占位符定义（DPD）规范。
 *
 * @author 郑明亮
 */
public class MockRandomTest {

    private final MockRandom random = new MockRandom();

    @Test
    public void testPlaceholderRegistry() {
        List<String> names = MockRandom.placeholders();
        Assert.assertTrue(names.contains("boolean"));
        Assert.assertTrue(names.contains("natural"));
        Assert.assertTrue(names.contains("integer"));
        Assert.assertTrue(names.contains("cname"));
        Assert.assertTrue(names.contains("image"));
        Assert.assertTrue(names.contains("dataImage"));
        Assert.assertTrue(names.contains("guid"));
        Assert.assertTrue(names.contains("uuid"));
        Assert.assertTrue(names.contains("id"));
        Assert.assertTrue(names.contains("increment"));
        Assert.assertTrue(names.contains("pick"));
        Assert.assertTrue(names.contains("shuffle"));
    }

    @Test
    public void testUnknownPlaceholder() {
        try {
            random.invoke("noSuchPlaceholder");
            Assert.fail("未知占位符应抛异常");
        } catch (IllegalArgumentException expected) {
            Assert.assertTrue(expected.getMessage().contains("noSuchPlaceholder"));
        }
    }

    @Test
    public void testBool() {
        for (int i = 0; i < 50; i++) {
            Object value = random.invoke("boolean");
            Assert.assertTrue(value instanceof Boolean);
        }
    }

    @Test
    public void testNatural() {
        for (int i = 0; i < 100; i++) {
            long value = ((Number) random.invoke("natural")).longValue();
            Assert.assertTrue("natural 应非负：" + value, value >= 0);
        }
    }

    @Test
    public void testInteger() {
        for (int i = 0; i < 100; i++) {
            long value = ((Number) random.invoke("integer", 5, 10)).longValue();
            Assert.assertTrue("integer 应在 5-10：" + value, value >= 5 && value <= 10);
        }
    }

    @Test
    public void testFloat() {
        for (int i = 0; i < 100; i++) {
            String text = String.valueOf(random.invoke("float", 1, 10, 2, 4));
            int dot = text.indexOf('.');
            Assert.assertTrue("应含小数：" + text, dot > 0);
            int len = text.length() - dot - 1;
            Assert.assertTrue("小数位应在 2-4：" + text, len >= 2 && len <= 4);
        }
    }

    @Test
    public void testCharacter() {
        Assert.assertEquals(1, String.valueOf(random.invoke("character")).length());
        Assert.assertTrue(String.valueOf(random.invoke("character", "lower")).matches("[a-z]"));
        Assert.assertTrue(String.valueOf(random.invoke("character", "upper")).matches("[A-Z]"));
        Assert.assertTrue(String.valueOf(random.invoke("character", "number")).matches("\\d"));
        Assert.assertTrue(String.valueOf(random.invoke("character", "symbol")).length() == 1);
    }

    @Test
    public void testString() {
        Assert.assertEquals(8, String.valueOf(random.invoke("string", 8)).length());
        Assert.assertEquals(5, String.valueOf(random.invoke("string", "lower", 5)).length());
        Assert.assertTrue(String.valueOf(random.invoke("string", "upper", 6)).matches("[A-Z]{6}"));
        Assert.assertTrue(String.valueOf(random.invoke("string", "number", 6)).matches("\\d{6}"));
        for (int i = 0; i < 20; i++) {
            int len = String.valueOf(random.invoke("string", 3, 7)).length();
            Assert.assertTrue("长度应在 3-7：" + len, len >= 3 && len <= 7);
        }
    }

    @Test
    public void testRange() {
        // 与 Mock.js 一致：区间左闭右开，range(1,5) => [1,2,3,4]
        List<?> list = (List<?>) random.invoke("range", 1, 5);
        Assert.assertEquals(4, list.size());
        Assert.assertEquals(1L, ((Number) list.get(0)).longValue());
        Assert.assertEquals(4L, ((Number) list.get(3)).longValue());
        List<?> stepped = (List<?>) random.invoke("range", 1, 10, 2);
        Assert.assertEquals(5, stepped.size());
        Assert.assertEquals(9L, ((Number) stepped.get(4)).longValue());
        Assert.assertEquals(0, ((List<?>) random.invoke("range", 0)).size());
    }

    @Test
    public void testDateAndTime() {
        Assert.assertTrue(String.valueOf(random.invoke("date")).matches("\\d{4}-\\d{2}-\\d{2}"));
        Assert.assertTrue(String.valueOf(random.invoke("time")).matches("\\d{2}:\\d{2}:\\d{2}"));
        Assert.assertTrue(String.valueOf(random.invoke("datetime"))
                .matches("\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}"));
        Assert.assertTrue(String.valueOf(random.invoke("datetime", "yyyy/MM/dd"))
                .matches("\\d{4}/\\d{2}/\\d{2}"));
        Assert.assertTrue(String.valueOf(random.invoke("now", "year"))
                .matches("\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}"));
    }

    @Test
    public void testImage() {
        String url = String.valueOf(random.invoke("image", "200x100"));
        Assert.assertTrue("图片地址：" + url, url.contains("dummyimage.com"));
        Assert.assertTrue(url.contains("200x100"));
        String full = String.valueOf(random.invoke("image", "200x100", "#ff0000", "#ffffff", "png", "Hi"));
        Assert.assertTrue(full, full.contains("#") || full.contains("ff0000"));
    }

    @Test
    public void testDataImage() {
        String value = String.valueOf(random.invoke("dataImage", "200x100"));
        Assert.assertTrue(value, value.startsWith("data:image/svg+xml;base64,"));
    }

    @Test
    public void testColor() {
        Assert.assertTrue(String.valueOf(random.invoke("color")).matches("#[0-9a-fA-F]{6}"));
        Assert.assertTrue(String.valueOf(random.invoke("hex")).matches("#[0-9a-fA-F]{6}"));
        Assert.assertTrue(String.valueOf(random.invoke("rgb"))
                .matches("rgb\\(\\s*\\d{1,3},\\s*\\d{1,3},\\s*\\d{1,3}\\)"));
        Assert.assertTrue(String.valueOf(random.invoke("rgba"))
                .matches("rgba\\(\\s*\\d{1,3},\\s*\\d{1,3},\\s*\\d{1,3},\\s*[01](\\.\\d+)?\\)"));
        Assert.assertTrue(String.valueOf(random.invoke("hsl"))
                .matches("hsl\\(\\s*\\d{1,3},\\s*\\d{1,3},\\s*\\d{1,3}\\)"));
    }

    @Test
    public void testText() {
        Assert.assertTrue(String.valueOf(random.invoke("paragraph")).length() > 0);
        Assert.assertTrue(String.valueOf(random.invoke("sentence")).endsWith("."));
        Assert.assertTrue(String.valueOf(random.invoke("word")).length() > 0);
        Assert.assertTrue(String.valueOf(random.invoke("title")).length() > 0);
        Assert.assertTrue(String.valueOf(random.invoke("cparagraph")).length() > 0);
        Assert.assertTrue(String.valueOf(random.invoke("csentence")).length() > 0);
        Assert.assertTrue(String.valueOf(random.invoke("cword")).length() > 0);
        Assert.assertTrue(String.valueOf(random.invoke("ctitle")).length() > 0);
    }

    @Test
    public void testName() {
        Assert.assertTrue(String.valueOf(random.invoke("name")).contains(" "));
        Assert.assertTrue(String.valueOf(random.invoke("cname")).length() >= 2);
        Assert.assertTrue(String.valueOf(random.invoke("cfirst")).length() >= 1);
        Assert.assertTrue(String.valueOf(random.invoke("clast")).length() >= 1);
        Assert.assertNotNull(random.invoke("first"));
        Assert.assertNotNull(random.invoke("last"));
    }

    @Test
    public void testWeb() {
        Assert.assertTrue(String.valueOf(random.invoke("url")).contains("://"));
        Assert.assertTrue(String.valueOf(random.invoke("domain")).contains("."));
        Assert.assertTrue(String.valueOf(random.invoke("email")).contains("@"));
        Assert.assertTrue(String.valueOf(random.invoke("ip"))
                .matches("\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}"));
        Assert.assertTrue(String.valueOf(random.invoke("protocol")).length() > 0);
        Assert.assertTrue(String.valueOf(random.invoke("tld")).length() > 0);
    }

    @Test
    public void testAddress() {
        Assert.assertTrue(String.valueOf(random.invoke("region")).length() > 0);
        Assert.assertTrue(String.valueOf(random.invoke("province")).length() > 0);
        Assert.assertTrue(String.valueOf(random.invoke("city")).length() > 0);
        Assert.assertTrue(String.valueOf(random.invoke("county")).length() > 0);
        Assert.assertTrue(String.valueOf(random.invoke("zip")).matches("\\d{6}"));
        Assert.assertTrue(String.valueOf(random.invoke("city", true)).length() > 0);
    }

    @Test
    public void testHelper() {
        Assert.assertEquals("Abc", random.invoke("capitalize", "abc"));
        Assert.assertEquals("ABC", random.invoke("upper", "abc"));
        Assert.assertEquals("abc", random.invoke("lower", "ABC"));
        for (int i = 0; i < 20; i++) {
            Assert.assertTrue("pick 结果", "ab".contains(String.valueOf(random.invoke("pick", "a", "b"))));
        }
    }

    @Test
    public void testMisc() {
        Assert.assertTrue(String.valueOf(random.invoke("guid"))
                .matches("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}"));
        Assert.assertTrue(String.valueOf(random.invoke("uuid"))
                .matches("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}"));
        Assert.assertTrue(String.valueOf(random.invoke("id")).matches("\\d{18}|\\d{17}[xX]"));
        long first = ((Number) random.invoke("increment")).longValue();
        long second = ((Number) random.invoke("increment")).longValue();
        Assert.assertEquals(first + 1, second);
    }

    @Test
    public void testAlias() {
        Assert.assertTrue(random.invoke("bool") instanceof Boolean);
        Assert.assertTrue(random.invoke("int") instanceof Long);
        Assert.assertTrue(random.invoke("img") instanceof String);
        Assert.assertTrue(random.invoke("inc") instanceof Long);
    }

    @Test
    public void testExtend() {
        MockRandom r = new MockRandom();
        r.extend("employeeNo", (random1, args) -> "E" + random1.invoke("integer", 1000, 9999));
        String value = String.valueOf(r.invoke("employeeNo"));
        Assert.assertTrue("工号：" + value, value.matches("E\\d{4}"));
        Assert.assertTrue(r.allPlaceholders().contains("employeeno"));
    }

    @Test
    public void testExtendOverridesBuiltin() {
        MockRandom r = new MockRandom();
        r.extend("cname", (random1, args) -> "固定姓名");
        Assert.assertEquals("固定姓名", r.invoke("cname"));
    }

    @Test
    public void testExtendInvalid() {
        MockRandom r = new MockRandom();
        try {
            r.extend("  ", (random1, args) -> "x");
            Assert.fail("空名应抛异常");
        } catch (IllegalArgumentException expected) {
            Assert.assertTrue(expected.getMessage().contains("占位符名"));
        }
        try {
            r.extend("x", null);
            Assert.fail("空实现应抛异常");
        } catch (IllegalArgumentException expected) {
            Assert.assertTrue(expected.getMessage().contains("生成逻辑"));
        }
    }

    @Test
    public void testExtendedPlaceholderInTemplate() {
        MockRandom r = new MockRandom();
        r.extend("employeeNo", (random1, args) -> "E" + random1.invoke("integer", 1000, 9999));
        MockJs js = new MockJs(r);
        Object data = js.mock((Object) "{'no':'@employeeNo'}");
        Map<?, ?> map = (Map<?, ?>) data;
        Assert.assertTrue("模板里应用扩展占位符：" + map.get("no"),
                String.valueOf(map.get("no")).matches("E\\d{4}"));
    }

    @Test
    public void testBusinessPlaceholders() {
        Object phone = random.invoke("phone");
        Assert.assertTrue("手机号：" + phone,
                String.valueOf(phone).matches("1[3-9]\\d{9}"));
        Object gender = random.invoke("gender");
        Assert.assertTrue("性别：" + gender,
                "男".equals(gender) || "女".equals(gender));
        Assert.assertTrue(String.valueOf(random.invoke("company")).length() > 0);
        Assert.assertTrue(String.valueOf(random.invoke("department")).length() > 0);
        Assert.assertTrue(String.valueOf(random.invoke("position")).length() > 0);
        for (int i = 0; i < 30; i++) {
            long salary = ((Number) random.invoke("salary")).longValue();
            Assert.assertTrue("薪资：" + salary, salary >= 5000 && salary <= 50000);
        }
        Assert.assertTrue(String.valueOf(random.invoke("bankCard")).matches("\\d{16,}"));
        Assert.assertTrue(String.valueOf(random.invoke("creditCard")).matches("\\d{16,}"));
        Assert.assertTrue(String.valueOf(random.invoke("currency")).matches("[A-Z]{3}"));
        Assert.assertTrue("MAC：" + random.invoke("mac"),
                String.valueOf(random.invoke("mac")).matches("[0-9A-F]{2}(:[0-9A-F]{2}){5}"));
        Assert.assertTrue(String.valueOf(random.invoke("userAgent")).contains("Mozilla"));
        Assert.assertEquals(12, String.valueOf(random.invoke("password")).length());
        Assert.assertEquals(32, String.valueOf(random.invoke("token")).length());
        Assert.assertTrue(((Number) random.invoke("timestamp")).longValue() > 0);
        Assert.assertTrue(String.valueOf(random.invoke("fileName")).contains("."));
        Assert.assertTrue(String.valueOf(random.invoke("mime")).contains("/"));
    }

    @Test
    public void testBusinessPlaceholdersRegistered() {
        List<String> names = MockRandom.placeholders();
        for (String name : new String[]{"phone", "gender", "company", "department", "position",
                "salary", "bankCard", "creditCard", "currency", "mac", "userAgent", "password",
                "token", "timestamp", "fileName", "mime"}) {
            Assert.assertTrue("应注册 " + name, names.contains(name));
        }
    }
}
