package com.alianga.jkit.yaml;

import org.junit.Test;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class YamlBindingTest {

    private static final String NESTED_YAML = "name: demo\n"
            + "port: 8080\n"
            + "enabled: true\n"
            + "max-size: 16\n"
            + "address:\n"
            + "  city: shanghai\n"
            + "  street: nanjing-rd\n"
            + "items:\n"
            + "  - name: a\n"
            + "    value: 10\n"
            + "  - name: b\n"
            + "    value: 20\n"
            + "labels:\n"
            + "  env: test\n"
            + "  owner: jkit\n";

    @Test
    public void testNestedObjectAndListBinding() {
        Server server = YamlDocument.parse(NESTED_YAML, Server.class);
        assertEquals("demo", server.getName());
        assertEquals(8080, server.getPort());
        assertTrue(server.isEnabled());
        assertEquals(16, server.getMaxSize());
        assertNotNull(server.getAddress());
        assertEquals("shanghai", server.getAddress().getCity());
        assertEquals("nanjing-rd", server.getAddress().getStreet());
        assertEquals(2, server.getItems().size());
        assertEquals("a", server.getItems().get(0).getName());
        assertEquals(20, server.getItems().get(1).getValue());
        assertEquals("test", server.getLabels().get("env"));
        assertEquals("jkit", server.getLabels().get("owner"));
    }

    @Test
    public void testFromMapBinding() {
        Map<String, Object> address = new LinkedHashMap<String, Object>();
        address.put("city", "beijing");
        address.put("street", "changan");
        Map<String, Object> map = new LinkedHashMap<String, Object>();
        map.put("name", "from-map");
        map.put("port", 9000);
        map.put("enabled", true);
        map.put("address", address);

        Server server = YamlDocument.toEntity(map, Server.class);
        assertEquals("from-map", server.getName());
        assertEquals(9000, server.getPort());
        assertEquals("beijing", server.getAddress().getCity());
    }

    @Test
    public void testTabIndentRejected() {
        try {
            YamlDocument.parse("root:\n\tchild: 1\n");
            fail("tab indent should fail");
        } catch (YamlParseException ex) {
            assertTrue(ex.getMessage().contains("tab"));
        }
    }

    @Test
    public void testCommentsAreIgnored() {
        String yaml = "# heading\nname: demo # inline\n# trailing\nport: 1\n";
        Server server = YamlDocument.parse(yaml, Server.class);
        assertEquals("demo", server.getName());
        assertEquals(1, server.getPort());

        String written = YamlDocument.parse(yaml).toYamlString();
        assertFalse(written.contains("heading"));
        assertFalse(written.contains("inline"));
    }

    @Test
    public void testSimpleWriteBackRoundTrip() {
        YamlDocument doc = YamlDocument.parse("name: demo\nport: 8080\n");
        String yaml = doc.toYamlString();
        Server server = YamlDocument.parse(yaml, Server.class);
        assertEquals("demo", server.getName());
        assertEquals(8080, server.getPort());
    }

    @Test
    public void testAnchorParseStillWorksAfterWrite() {
        String yaml = "base: &base\n  name: zhangsan\nserver:\n  <<: *base\n  port: 8080\n";
        Map<String, Object> map = YamlDocument.parse(yaml).toMap();
        @SuppressWarnings("unchecked")
        Map<String, Object> server = (Map<String, Object>) map.get("server");
        assertEquals("zhangsan", server.get("name"));
        assertEquals("8080", server.get("port"));
    }

    @Test
    public void testQuotedHashAndCrlf() {
        String yaml = "name: \"a#b\"\r\nport: 2\r\n";
        Server server = YamlDocument.parse(yaml, Server.class);
        assertEquals("a#b", server.getName());
        assertEquals(2, server.getPort());
    }

    @Test
    public void testToEntityNullMap() {
        assertNull(YamlDocument.toEntity(null, Server.class));
        Map<String, Object> empty = new HashMap<String, Object>();
        Server server = YamlDocument.toEntity(empty, Server.class);
        assertNotNull(server);
        assertNull(server.getName());
    }

    @Test
    public void testUnderlineAndKebabSetter() {
        String yaml = "login_timeout: 30\nfront-distributed: true\n";
        AppConfig app = YamlDocument.parse(yaml, AppConfig.class);
        assertEquals(30, app.getLoginTimeout());
        assertTrue(app.isFrontDistributed());
    }

    /**
     * 嵌套对象绑定的样本类。
     */
    public static class Address {
        private String city;
        private String street;

        public String getCity() {
            return city;
        }

        public void setCity(String city) {
            this.city = city;
        }

        public String getStreet() {
            return street;
        }

        public void setStreet(String street) {
            this.street = street;
        }
    }

    /**
     * 对象列表绑定的样本类。
     */
    public static class Item {
        private String name;
        private int value;

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }

        public int getValue() {
            return value;
        }

        public void setValue(int value) {
            this.value = value;
        }
    }

    /**
     * 顶层绑定的样本类，覆盖基本类型、嵌套对象、对象列表与 Map。
     */
    public static class Server {
        private String name;
        private int port;
        private boolean enabled;
        private int maxSize;
        private Address address;
        private List<Item> items;
        private Map<String, String> labels;

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }

        public int getPort() {
            return port;
        }

        public void setPort(int port) {
            this.port = port;
        }

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public int getMaxSize() {
            return maxSize;
        }

        public void setMaxSize(int maxSize) {
            this.maxSize = maxSize;
        }

        public Address getAddress() {
            return address;
        }

        public void setAddress(Address address) {
            this.address = address;
        }

        public List<Item> getItems() {
            return items;
        }

        public void setItems(List<Item> items) {
            this.items = items;
        }

        public Map<String, String> getLabels() {
            return labels;
        }

        public void setLabels(Map<String, String> labels) {
            this.labels = labels;
        }
    }

    /**
     * 校验下划线与中划线 key 都能匹配到驼峰 setter 的样本类。
     */
    public static class AppConfig {
        private int loginTimeout;
        private boolean frontDistributed;

        public int getLoginTimeout() {
            return loginTimeout;
        }

        public void setLoginTimeout(int loginTimeout) {
            this.loginTimeout = loginTimeout;
        }

        public boolean isFrontDistributed() {
            return frontDistributed;
        }

        public void setFrontDistributed(boolean frontDistributed) {
            this.frontDistributed = frontDistributed;
        }
    }
}
