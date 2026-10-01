package com.alianga.jkit.mock.server;

import org.junit.After;
import org.junit.Test;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Collections;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * 阶段三引擎能力：CRUD 资源（列表/新增/改/删/过滤/分页/排序 + 落盘重载）、
 * 延迟随机区间与 {@code {name*}} 通配尾段。
 *
 * @author 郑明亮
 */
public class MockCrudAndMiscTest {

    private final MockHttpServer server = new MockHttpServer();
    private final HttpClient client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(2))
            .build();

    @After
    public void tearDown() {
        server.stop();
    }

    @Test
    public void crudResourceFullLifecycle() throws Exception {
        int port = freePort();
        String seed = "[{\"id\":1,\"name\":\"张三\",\"age\":20},{\"id\":2,\"name\":\"李四\",\"age\":30}]";
        server.start(port, Collections.singletonList(
                MockRoute.builder().method("GET").path("/users")
                        .crud(true).body(seed).build()));

        // 列表（含播种数据）
        HttpResponse<String> list = get(port, "/users");
        assertEquals(200, list.statusCode());
        assertTrue(list.body().contains("张三"));

        // 等值过滤 + 全文
        assertEquals(200, get(port, "/users?age=20").statusCode());
        assertTrue(get(port, "/users?age=20").body().contains("张三"));
        assertEquals("[]", get(port, "/users?age=99").body());
        assertTrue(get(port, "/users?q=李四").body().contains("李四"));

        // 排序 + 分页
        String asc = get(port, "/users?_sort=age&_order=asc").body();
        assertTrue("升序应张三在前", asc.indexOf("张三") < asc.indexOf("李四"));
        String desc = get(port, "/users?_sort=age&_order=desc").body();
        assertTrue("降序应李四在前", desc.indexOf("李四") < desc.indexOf("张三"));
        String page = get(port, "/users?_page=1&_limit=1").body();
        assertTrue("分页只出 1 条", page.startsWith("[") && !page.contains("李四"));

        // 新增（自增 id）
        HttpResponse<String> created = post(port, "/users", "{\"name\":\"王五\",\"age\":40}");
        assertEquals(201, created.statusCode());
        // prettyJson 带空格，比较前去空白
        String compact = created.body().replaceAll("\\s", "");
        assertTrue(compact.contains("\"id\":3"));
        assertTrue(created.body().contains("王五"));

        // 单查 / 更新（PUT 整替 + PATCH 合并）/ 删除
        assertEquals(200, get(port, "/users/3").statusCode());
        assertEquals(404, get(port, "/users/99").statusCode());

        HttpResponse<String> patched = send(port, "PATCH", "/users/3", "{\"age\":41}");
        assertEquals(200, patched.statusCode());
        assertTrue("PATCH 应保留 name", patched.body().contains("王五"));
        assertTrue("PATCH 应更新 age", patched.body().replaceAll("\\s", "").contains("\"age\":41"));

        HttpResponse<String> put = send(port, "PUT", "/users/3", "{\"name\":\"王六\"}");
        assertEquals(200, put.statusCode());
        assertTrue("PUT 整替后不应有 age", !put.body().contains("\"age\""));

        HttpResponse<String> deleted = send(port, "DELETE", "/users/3", "");
        assertEquals(204, deleted.statusCode());
        assertEquals(404, get(port, "/users/3").statusCode());

        // 不支持的方法
        assertEquals(405, send(port, "PATCH", "/users", "{}").statusCode());
    }

    @Test
    public void crudStorePersistsAcrossRestarts() throws Exception {
        Path dir = Files.createTempDirectory("apimock-crud");
        int port = freePort();
        server.start(port, Collections.singletonList(
                MockRoute.builder().method("GET").path("/books")
                        .crud(true).body("[{\"id\":1,\"title\":\"a\"}]").build()));
        server.setDataStore(new MockCrudStore(dir));
        send(port, "POST", "/books", "{\"title\":\"b\"}");
        server.stop();

        MockHttpServer second = new MockHttpServer();
        try {
            second.start(port, Collections.singletonList(
                    MockRoute.builder().method("GET").path("/books")
                            .crud(true).body("[{\"id\":1,\"title\":\"a\"}]").build()));
            second.setDataStore(new MockCrudStore(dir));
            String body = get(port, "/books").body();
            assertTrue("重启后数据应从盘加载", body.contains("b") && body.contains("a"));
        } finally {
            second.stop();
        }
    }

    @Test
    public void latencyRangeRandomizes() throws Exception {
        int port = freePort();
        server.start(port, Collections.singletonList(
                MockRoute.builder().method("GET").path("/jitter")
                        .latencyMs(50).latencyMaxMs(300).body("ok").build()));

        long minSeen = Long.MAX_VALUE;
        long maxSeen = 0;
        for (int i = 0; i < 12; i++) {
            long start = System.nanoTime();
            get(port, "/jitter");
            long ms = (System.nanoTime() - start) / 1_000_000L;
            minSeen = Math.min(minSeen, ms);
            maxSeen = Math.max(maxSeen, ms);
        }
        assertTrue("多次请求应观察到明显波动: min=" + minSeen + " max=" + maxSeen,
                maxSeen - minSeen >= 80);
    }

    @Test
    public void tailWildcardMatchesRestSegments() throws Exception {
        int port = freePort();
        server.start(port, Collections.singletonList(
                MockRoute.builder().method("GET").path("/static/{file*}")
                        .body("served").build()));

        assertEquals(200, get(port, "/static").statusCode());
        assertEquals(200, get(port, "/static/a.js").statusCode());
        HttpResponse<String> nested = get(port, "/static/js/app/main.js");
        assertEquals(200, nested.statusCode());
        assertEquals(404, get(port, "/other/a.js").statusCode());
        // {file*} 捕获值
        assertTrue(MockHttpServer.matchPathParams("/static/{file*}", "/static/js/app.js")
                .get("file").equals("js/app.js"));
    }

    @Test
    public void routeMetadataFieldsArePurelyInformational() {
        MockRoute route = MockRoute.builder().method("GET").path("/users")
                .name("用户列表").description("联调用 mock").build();
        assertEquals("用户列表", route.name);
        assertEquals("联调用 mock", route.description);
        // 元信息不影响匹配与响应
        assertEquals(Collections.emptyMap(), MockHttpServer.matchPathParams("/users", "/users"));
        assertEquals("", new MockRoute("GET", "/x", 200, 0, "text/plain", "ok").name);
    }

    private HttpResponse<String> get(int port, String path) throws IOException, InterruptedException {
        return send(port, "GET", path, "");
    }

    private HttpResponse<String> post(int port, String path, String json) throws IOException, InterruptedException {
        return send(port, "POST", path, json);
    }

    private HttpResponse<String> send(int port, String method, String path, String body)
            throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("http://127.0.0.1:" + port + path))
                .timeout(Duration.ofSeconds(5))
                .method(method, body.isEmpty()
                        ? HttpRequest.BodyPublishers.noBody()
                        : HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                .build();
        return client.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private static int freePort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            socket.setReuseAddress(true);
            return socket.getLocalPort();
        }
    }
}
