package com.alianga.jkit.yaml;

import org.junit.Test;

import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Properties;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class YamlDocumentTest {

    /** 用于实体绑定测试的 bean */
    public static class UserConfig {
        private String username;
        private String password;
        private int age;
        private boolean active;
        private double score;
        private Date date;
        private List<String> roles;
        private List<String> tags;

        public String getUsername() {
            return username;
        }

        public void setUsername(String username) {
            this.username = username;
        }

        public String getPassword() {
            return password;
        }

        public void setPassword(String password) {
            this.password = password;
        }

        public int getAge() {
            return age;
        }

        public void setAge(int age) {
            this.age = age;
        }

        public boolean isActive() {
            return active;
        }

        public void setActive(boolean active) {
            this.active = active;
        }

        public double getScore() {
            return score;
        }

        public void setScore(double score) {
            this.score = score;
        }

        public Date getDate() {
            return date;
        }

        public void setDate(Date date) {
            this.date = date;
        }

        public List<String> getRoles() {
            return roles;
        }

        public void setRoles(List<String> roles) {
            this.roles = roles;
        }

        public List<String> getTags() {
            return tags;
        }

        public void setTags(List<String> tags) {
            this.tags = tags;
        }
    }

    private static final String SIMPLE_YAML =
            "username: 'zhangsan'\n"
                    + "password: '123456'\n"
                    + "age: !!int 18\n"
                    + "active: true\n"
                    + "score: 98.5\n"
                    + "date: !!timestamp 2018-01-01t16:59:43.10-05:00\n"
                    + "roles:\n"
                    + "  - 'admin'\n"
                    + "  - 'admin1'\n"
                    + "  - 'admin2'\n"
                    + "tags:\n"
                    + "  - a\n"
                    + "  - b\n";

    private static final String NESTED_YAML =
            "kind: ConfigMap\n"
                    + "apiVersion: v1\n"
                    + "metadata:\n"
                    + "  name: fluentd-es-config\n"
                    + "  namespace: logging\n"
                    + "  labels:\n"
                    + "    addon: Reconcile\n"
                    + "data:\n"
                    + "  system.conf: |-\n"
                    + "    line1\n"
                    + "    line2\n";

    @Test
    public void testParseAndToMap() {
        YamlDocument doc = YamlDocument.parse(NESTED_YAML);
        assertFalse(doc.isMultiple());
        assertNotNull(doc.getRoot());

        Map<String, Object> map = doc.toMap();
        assertEquals("ConfigMap", map.get("kind"));
        assertEquals("v1", map.get("apiVersion"));
        Map<String, Object> metadata = (Map<String, Object>) map.get("metadata");
        assertEquals("fluentd-es-config", metadata.get("name"));
        Map<String, Object> labels = (Map<String, Object>) metadata.get("labels");
        assertEquals("Reconcile", labels.get("addon"));
    }

    @Test
    public void testParseCharArray() {
        YamlDocument doc = YamlDocument.parse(SIMPLE_YAML.toCharArray());
        Map<String, Object> map = doc.toMap();
        assertEquals("zhangsan", map.get("username"));
    }

    @Test
    public void testToEntity() {
        UserConfig user = YamlDocument.parse(SIMPLE_YAML, UserConfig.class);
        assertEquals("zhangsan", user.getUsername());
        assertEquals("123456", user.getPassword());
        assertEquals(18, user.getAge());
        assertTrue(user.isActive());
        assertEquals(98.5, user.getScore(), 0.001);
        assertNotNull(user.getDate());
        assertEquals(3, user.getRoles().size());
        assertEquals("admin2", user.getRoles().get(2));
        assertEquals(2, user.getTags().size());
        assertEquals("b", user.getTags().get(1));
    }

    @Test
    public void testToEntityViaDocument() {
        YamlDocument doc = YamlDocument.parse(SIMPLE_YAML);
        UserConfig user = doc.toEntity(UserConfig.class);
        assertEquals("zhangsan", user.getUsername());
    }

    @Test
    public void testToProperties() {
        YamlDocument doc = YamlDocument.parse(NESTED_YAML);
        Properties properties = doc.toProperties();
        assertEquals("ConfigMap", properties.getProperty("kind"));
        assertEquals("fluentd-es-config", properties.getProperty("metadata.name"));
        assertEquals("logging", properties.getProperty("metadata.namespace"));
        assertEquals("Reconcile", properties.getProperty("metadata.labels.addon"));
    }

    @Test
    public void testGetPathValue() {
        YamlDocument doc = YamlDocument.parse(NESTED_YAML);
        YamlNode root = doc.getRoot();

        String kind = root.getPathValue("/kind", String.class);
        assertEquals("ConfigMap", kind);

        String name = root.getPathValue("/metadata/name", String.class);
        assertEquals("fluentd-es-config", name);

        // 相对路径
        String namespace = root.get("/metadata").getPathValue("namespace", String.class);
        assertEquals("logging", namespace);
    }

    @Test
    public void testGetNodeByPath() {
        YamlDocument doc = YamlDocument.parse(NESTED_YAML);
        YamlNode root = doc.getRoot();

        YamlNode metadata = root.get("/metadata");
        assertNotNull(metadata);
        assertEquals("fluentd-es-config", metadata.get("name").getValue());

        // 不存在的路径返回 null
        assertNull(root.get("/metadata/not-exist"));
    }

    @Test
    public void testSetPathValue() {
        YamlDocument doc = YamlDocument.parse(NESTED_YAML);
        YamlNode root = doc.getRoot();
        root.setPathValue("/metadata/name", "new-name");
        assertEquals("new-name", root.getPathValue("/metadata/name", String.class));
    }

    @Test
    public void testToYamlStringRoundTrip() {
        YamlDocument doc = YamlDocument.parse(NESTED_YAML);
        String yaml = doc.toYamlString();
        assertTrue(yaml.contains("kind: ConfigMap"));

        // 重新解析
        YamlDocument again = YamlDocument.parse(yaml);
        assertEquals("fluentd-es-config", again.getRoot().getPathValue("/metadata/name", String.class));
    }

    @Test
    public void testTextBlock() {
        YamlDocument doc = YamlDocument.parse(NESTED_YAML);
        String content = doc.getRoot().getPathValue("/data/system.conf", String.class);
        assertEquals("line1\nline2", content);
    }

    @Test
    public void testMultipleDocuments() {
        String yaml = "name: doc1\n"
                + "---\n"
                + "name: doc2\n";
        YamlDocument doc = YamlDocument.parse(yaml);
        assertTrue(doc.isMultiple());
        List<YamlNode> nodes = doc.getYamlNodeList();
        assertEquals(2, nodes.size());
        assertEquals("doc1", nodes.get(0).get("name").getValue());
        assertEquals("doc2", nodes.get(1).get("name").getValue());
    }

    @Test
    public void testAnchorsAndReferences() {
        String yaml = "base: &base\n"
                + "  name: zhangsan\n"
                + "server:\n"
                + "  <<: *base\n"
                + "  port: 8080\n";
        YamlDocument doc = YamlDocument.parse(yaml);
        Map<String, Object> map = doc.toMap();
        Map<String, Object> server = (Map<String, Object>) map.get("server");
        assertNotNull(server);
        assertEquals("zhangsan", server.get("name"));
        assertEquals("8080", server.get("port"));
    }

    @Test
    public void testInlineJson() {
        String yaml = "json: { hello: aa, lisi: bb, cc: 123 }\n"
                + "list: [ a, b, c, d ]\n";
        YamlDocument doc = YamlDocument.parse(yaml);
        Map<String, Object> map = doc.toMap();
        Map<String, Object> json = (Map<String, Object>) map.get("json");
        assertEquals("aa", json.get("hello"));
        assertEquals(123, ((Number) json.get("cc")).intValue());

        List<Object> list = (List<Object>) map.get("list");
        assertEquals(4, list.size());
        assertEquals("d", list.get(3));
    }

    @Test
    public void testTypeConversion() {
        String yaml = "intVal: !!int 18\n"
                + "floatVal: !!float 1.5\n"
                + "boolVal: !!bool true\n"
                + "strVal: !!str hello\n";
        YamlDocument doc = YamlDocument.parse(yaml);
        YamlNode root = doc.getRoot();
        assertEquals(18, ((Number) root.get("/intVal").getValue()).intValue());
        assertEquals(1.5f, ((Number) root.get("/floatVal").getValue()).floatValue(), 0.001);
        assertEquals(Boolean.TRUE, root.get("/boolVal").getValue());
        assertEquals("hello", root.get("/strVal").getValue());
    }

    @Test
    public void testParseInputStream() throws Exception {
        YamlDocument doc = YamlDocument.read(
                new java.io.ByteArrayInputStream(SIMPLE_YAML.getBytes("UTF-8")));
        assertEquals("zhangsan", doc.getRoot().getPathValue("/username", String.class));
    }

    @Test
    public void testFullKeyAndLeafValue() {
        YamlDocument doc = YamlDocument.parse(NESTED_YAML);
        YamlNode root = doc.getRoot();
        YamlNode nameNode = root.get("/metadata/labels/addon");
        assertTrue(nameNode.leaf);
        assertEquals("metadata.labels.addon", nameNode.getFullKey());
        assertEquals("Reconcile", nameNode.getValue());
    }
}
