package com.alianga.jkit.mock.server;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * 请求参数回显组装器单测：全量、白名单、多值、非 JSON body 与点分路径下钻。
 *
 * @author 郑明亮
 */
public class MockRequestEchoTest {

    private static MockRequestContext context(String rawQuery, Map<String, List<String>> headers,
                                              String body) {
        return MockRequestContext.of("POST", "/users/7",
                Collections.singletonMap("id", "7"), rawQuery, headers,
                body.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    public void echoAllContainsEverySource() {
        Map<String, Object> value = MockRequestEcho.echoValue(
                context("debug=1&tag=a",
                        headerMap("X-Token", "tk-1", "Content-Type", "application/json"),
                        "{\"name\":\"张三\",\"age\":18}"),
                MockEchoConfig.builder().enabled(true).build());

        assertEquals("POST", value.get("method"));
        assertEquals("/users/7", value.get("path"));
        assertEquals(Collections.singletonMap("id", "7"), value.get("pathParams"));
        assertEquals("1", ((Map<?, ?>) value.get("query")).get("debug"));
        assertEquals("tk-1", ((Map<?, ?>) value.get("headers")).get("X-Token"));
        assertEquals("张三", ((Map<?, ?>) value.get("body")).get("name"));
    }

    @Test
    public void echoWithIncludesOnlySelectedFields() {
        MockEchoConfig config = MockEchoConfig.builder().enabled(true)
                .include("query.debug")
                .include("body.name")
                .include("param.id")
                .build();
        Map<String, Object> value = MockRequestEcho.echoValue(
                context("debug=1&secret=2",
                        headerMap("X-Token", "tk-1"),
                        "{\"name\":\"张三\",\"pwd\":\"secret\"}"),
                config);

        // method/path 始终带上；headers 未选不带
        assertEquals("POST", value.get("method"));
        assertFalse(value.containsKey("headers"));
        assertEquals("1", ((Map<?, ?>) value.get("query")).get("debug"));
        assertFalse(((Map<?, ?>) value.get("query")).containsKey("secret"));
        assertEquals("张三", ((Map<?, ?>) value.get("body")).get("name"));
        assertEquals("7", ((Map<?, ?>) value.get("param")).get("id"));
    }

    @Test
    public void echoWholeSourceViaBareInclude() {
        MockEchoConfig config = MockEchoConfig.builder().enabled(true)
                .include("header")
                .build();
        Map<String, Object> value = MockRequestEcho.echoValue(
                context("debug=1", headerMap("X-Token", "tk-1"), "{}"), config);
        assertEquals("tk-1", ((Map<?, ?>) value.get("header")).get("X-Token"));
        assertFalse(value.containsKey("query"));
    }

    @Test
    public void echoMultiValuesBecomeJsonArray() {
        MockRequestContext ctx = MockRequestContext.of("GET", "/x",
                Collections.<String, String>emptyMap(), "tag=a&tag=b",
                Collections.singletonMap("X-Multi", Arrays.asList("1", "2")), new byte[0]);
        Map<String, Object> value = MockRequestEcho.echoValue(ctx,
                MockEchoConfig.builder().enabled(true).build());
        assertEquals(Arrays.asList("a", "b"), ((Map<?, ?>) value.get("query")).get("tag"));
        assertEquals(Arrays.asList("1", "2"),
                ((Map<?, ?>) value.get("headers")).get("X-Multi"));
    }

    @Test
    public void echoNonJsonBodyBecomesRawString() {
        Map<String, Object> value = MockRequestEcho.echoValue(
                context(null, Collections.<String, List<String>>emptyMap(), "plain-text-body"),
                MockEchoConfig.builder().enabled(true).build());
        assertEquals("plain-text-body", value.get("body"));
    }

    @Test
    public void echoBodyPathDrillsIntoJson() {
        MockEchoConfig config = MockEchoConfig.builder().enabled(true)
                .include("body.items.0.id")
                .build();
        Map<String, Object> value = MockRequestEcho.echoValue(
                context(null, Collections.<String, List<String>>emptyMap(),
                        "{\"items\":[{\"id\":42}]}"),
                config);
        // bodyPathOrRaw 对非字符串叶子统一转字符串
        assertEquals("42", ((Map<?, ?>) value.get("body")).get("items.0.id"));
        String json = MockRequestEcho.echoJson(context(null,
                Collections.<String, List<String>>emptyMap(),
                "{\"items\":[{\"id\":42}]}"), config);
        assertTrue(json, json.contains("\"body\"") && json.contains("\"items.0.id\":\"42\""));
    }

    @Test
    public void missingIncludePathIsSkipped() {
        MockEchoConfig config = MockEchoConfig.builder().enabled(true)
                .include("query.missing")
                .include("body.nope")
                .build();
        Map<String, Object> value = MockRequestEcho.echoValue(
                context("a=1", Collections.<String, List<String>>emptyMap(), "{}"), config);
        assertFalse(value.containsKey("query"));
        assertFalse(value.containsKey("body"));
    }

    @Test
    public void includesDeduplicated() {
        MockEchoConfig config = MockEchoConfig.builder().enabled(true)
                .include("query.a").include("query.a").include(" query.b ").build();
        assertEquals(Arrays.asList("query.a", "query.b"), config.includes);
    }

    private static Map<String, List<String>> headerMap(String... kv) {
        Map<String, List<String>> map = new java.util.LinkedHashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) {
            map.put(kv[i], Collections.singletonList(kv[i + 1]));
        }
        return map;
    }
}
