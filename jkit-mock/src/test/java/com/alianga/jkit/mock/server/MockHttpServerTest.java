package com.alianga.jkit.mock.server;

import org.junit.After;
import org.junit.Test;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * {@link MockHttpServer}：启停、路由匹配、延迟、回显、动态 Schema、预检与日志留痕。
 * 测试运行在 JDK 17+（HttpClient 均为 JDK 11+ API）。
 *
 * @author 郑明亮
 */
public class MockHttpServerTest {

    private final MockHttpServer server = new MockHttpServer();
    private final HttpClient client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(2))
            .build();

    @After
    public void tearDown() {
        server.stop();
    }

    @Test
    public void startAndHealthReturns200() throws Exception {
        int port = freePort();
        server.start(port, MockRoute.demoRoutes());
        assertTrue(server.isRunning());
        assertEquals("http://127.0.0.1:" + port, server.baseUrl());

        HttpResponse<String> resp = get(port, "/health");
        assertEquals(200, resp.statusCode());
        assertTrue(resp.body().contains("\"ok\":true"));
    }

    @Test
    public void emptyRoutesServe404InsteadOfDemo() throws Exception {
        int port = freePort();
        server.start(port, Collections.<MockRoute>emptyList());
        assertEquals(404, get(port, "/health").statusCode());
        assertEquals(404, get(port, "/api/hello").statusCode());
    }

    @Test
    public void customRouteLatencyRoughlyObserved() throws Exception {
        int port = freePort();
        long latency = 120L;
        server.start(port, Collections.singletonList(
                new MockRoute("GET", "/slow", 200, latency, "text/plain", "ok")));

        long start = System.nanoTime();
        HttpResponse<String> resp = get(port, "/slow");
        long elapsedMs = (System.nanoTime() - start) / 1_000_000L;

        assertEquals(200, resp.statusCode());
        assertEquals("ok", resp.body());
        assertTrue("期望至少接近延迟, actual=" + elapsedMs, elapsedMs >= latency - 30);
    }

    @Test
    public void pathParamMatchesOneSegment() throws Exception {
        int port = freePort();
        server.start(port, Collections.singletonList(
                new MockRoute("GET", "/users/{id}", 200, 0, "text/plain", "user")));

        assertEquals(200, get(port, "/users/42").statusCode());
        assertEquals(200, get(port, "/users/42/").statusCode());
        assertEquals(404, get(port, "/users/42/extra").statusCode());
        assertEquals(404, get(port, "/users").statusCode());
    }

    @Test
    public void encodedPathMatchesDecodedRoute() throws Exception {
        int port = freePort();
        server.start(port, Collections.singletonList(
                new MockRoute("GET", "/files/报告.pdf", 200, 0, "text/plain", "ok")));

        String encoded = URLEncoder.encode("报告.pdf", "UTF-8").replace("+", "%20");
        HttpResponse<String> resp = get(port, "/files/" + encoded);
        assertEquals("percent-encoded 请求应命中解码后的路由", 200, resp.statusCode());
        assertEquals(404, get(port, "/files/other.pdf").statusCode());
    }

    @Test
    public void unmatchedOptionsPreflightReturns204WithCors() throws Exception {
        int port = freePort();
        server.start(port, Collections.singletonList(
                new MockRoute("GET", "/x", 200, 0, "text/plain", "x")));

        HttpRequest preflight = HttpRequest.newBuilder()
                .uri(URI.create("http://127.0.0.1:" + port + "/x"))
                .header("Origin", "http://web.local")
                .header("Access-Control-Request-Method", "GET")
                .method("OPTIONS", HttpRequest.BodyPublishers.noBody())
                .build();
        HttpResponse<String> resp = client.send(preflight, HttpResponse.BodyHandlers.ofString());
        assertEquals(204, resp.statusCode());
        assertEquals("*", resp.headers().firstValue("Access-Control-Allow-Origin").orElse(""));
    }

    @Test
    public void echoRouteEchoesBodyAndContentType() throws Exception {
        int port = freePort();
        server.start(port, Collections.singletonList(
                new MockRoute("POST", "/api/echo", 200, 0, "application/json; charset=utf-8", "", "", false, true)));

        HttpRequest post = HttpRequest.newBuilder()
                .uri(URI.create("http://127.0.0.1:" + port + "/api/echo"))
                .header("Content-Type", "text/csv")
                .POST(HttpRequest.BodyPublishers.ofString("a,b\n1,2"))
                .build();
        HttpResponse<String> resp = client.send(post, HttpResponse.BodyHandlers.ofString());
        assertEquals(200, resp.statusCode());
        assertEquals("a,b\n1,2", resp.body());
        assertTrue(resp.headers().firstValue("Content-Type").orElse("").contains("text/csv"));
    }

    @Test
    public void dynamicSchemaGeneratesJsonBody() throws Exception {
        int port = freePort();
        String schema = "{"
                + "\"type\":\"object\","
                + "\"properties\":{\"name\":{\"type\":\"string\"},\"id\":{\"type\":\"integer\",\"minimum\":1}},"
                + "\"required\":[\"name\",\"id\"]"
                + "}";
        server.start(port, Collections.singletonList(
                new MockRoute("GET", "/schema", 200, 0,
                        "application/json; charset=utf-8", "", schema, true)));
        HttpResponse<String> resp = get(port, "/schema");
        assertEquals(200, resp.statusCode());
        assertTrue(resp.body().contains("\"name\""));
        assertTrue(resp.body().contains("\"id\""));
    }

    @Test
    public void dynamicSchemaFailureFallsBackToBody() throws Exception {
        int port = freePort();
        server.start(port, Arrays.asList(
                new MockRoute("GET", "/bad-with-body", 200, 0,
                        "application/json; charset=utf-8", "{\"fixed\":true}", "not-a-schema", true),
                new MockRoute("GET", "/bad-no-body", 200, 0,
                        "application/json; charset=utf-8", "", "not-a-schema", true)));

        assertEquals("{\"fixed\":true}", get(port, "/bad-with-body").body());
        String body = get(port, "/bad-no-body").body();
        assertTrue("应返回 schema mock failed 错误 JSON，actual=" + body,
                body.contains("schema mock failed"));
    }

    @Test
    public void invalidStatusCodeServedAs500AndLogged() throws Exception {
        int port = freePort();
        server.start(port, Collections.singletonList(
                new MockRoute("GET", "/broken", 50, 0, "text/plain", "x")));

        HttpResponse<String> resp = get(port, "/broken");
        assertEquals("越界状态码应按 500 兜底而不是裸断连接", 500, resp.statusCode());
        assertTrue(resp.body().contains("invalid statusCode"));
        assertFalse(server.snapshotLogs().isEmpty());
        assertEquals(500, server.snapshotLogs().get(0).status);
    }

    @Test
    public void clientTimeoutDuringLatencyStillLogged() throws Exception {
        int port = freePort();
        server.start(port, Collections.singletonList(
                new MockRoute("GET", "/slow", 200, 1500, "text/plain", "ok")));

        HttpRequest hurry = HttpRequest.newBuilder()
                .uri(URI.create("http://127.0.0.1:" + port + "/slow"))
                .timeout(Duration.ofMillis(150))
                .GET()
                .build();
        try {
            client.send(hurry, HttpResponse.BodyHandlers.ofString());
        } catch (IOException expected) {
            // 客户端超时断开属预期
        }
        // 服务端最终会完成/放弃响应并留痕
        Thread.sleep(2200);
        assertFalse("客户端断开后请求仍应留下日志", server.snapshotLogs().isEmpty());
    }

    @Test
    public void stopReleasesPort() throws Exception {
        int port = freePort();
        server.start(port, MockRoute.demoRoutes());
        assertTrue(server.isRunning());
        server.stop();
        assertFalse(server.isRunning());

        // 端口应可再次绑定
        try (ServerSocket socket = new ServerSocket(port)) {
            assertEquals(port, socket.getLocalPort());
        }

        server.start(port, MockRoute.demoRoutes());
        assertEquals(200, get(port, "/health").statusCode());
    }

    @Test
    public void unmatchedReturns404() throws Exception {
        int port = freePort();
        server.start(port, Collections.singletonList(
                new MockRoute("GET", "/only", 200, 0, "text/plain", "x")));
        HttpResponse<String> resp = get(port, "/missing");
        assertEquals(404, resp.statusCode());
        assertTrue(resp.body().contains("no matching route"));
    }

    @Test
    public void methodMatchingIsCaseInsensitiveAndWildcard() {
        assertTrue(MockHttpServer.matchesMethod("get", "GET"));
        assertTrue(MockHttpServer.matchesMethod("*", "DELETE"));
        assertFalse(MockHttpServer.matchesMethod("POST", "GET"));
    }

    @Test
    public void setRoutesHotUpdatesBodyWhileRunning() throws Exception {
        int port = freePort();
        server.start(port, Collections.singletonList(
                new MockRoute("GET", "/hot", 200, 0, "text/plain", "v1")));
        assertEquals("v1", get(port, "/hot").body());

        server.setRoutes(Collections.singletonList(
                new MockRoute("GET", "/hot", 200, 0, "text/plain", "v2")));
        assertEquals("v2", get(port, "/hot").body());
        assertEquals(1, server.snapshotRoutes().size());
    }

    @Test
    public void setRoutesHotAddsAndRemovesRoutes() throws Exception {
        int port = freePort();
        server.start(port, Collections.singletonList(
                new MockRoute("GET", "/a", 200, 0, "text/plain", "a")));
        assertEquals(200, get(port, "/a").statusCode());

        server.setRoutes(Collections.singletonList(
                new MockRoute("GET", "/b", 201, 0, "text/plain", "b")));
        assertEquals(404, get(port, "/a").statusCode());
        HttpResponse<String> resp = get(port, "/b");
        assertEquals(201, resp.statusCode());
        assertEquals("b", resp.body());
    }

    @Test
    public void setRoutesHotUpdatesDynamicSchemaFlag() throws Exception {
        int port = freePort();
        String schema = "{"
                + "\"type\":\"object\","
                + "\"properties\":{\"n\":{\"type\":\"string\"}},"
                + "\"required\":[\"n\"]"
                + "}";
        // 先固定 body，关闭动态
        server.start(port, Collections.singletonList(
                new MockRoute("GET", "/s", 200, 0,
                        "application/json; charset=utf-8", "{\"n\":\"fixed\"}", schema, false)));
        assertEquals("{\"n\":\"fixed\"}", get(port, "/s").body());

        server.setRoutes(Collections.singletonList(
                new MockRoute("GET", "/s", 200, 0,
                        "application/json; charset=utf-8", "{\"n\":\"fixed\"}", schema, true)));
        HttpResponse<String> resp = get(port, "/s");
        assertEquals(200, resp.statusCode());
        assertTrue(resp.body().contains("\"n\""));
        assertTrue(resp.body().startsWith("{"));
    }

    @Test
    public void customContentTypeHeaderPassedThrough() throws Exception {
        int port = freePort();
        server.start(port, Collections.singletonList(
                new MockRoute("GET", "/plain", 200, 0, "text/plain; charset=utf-8", "hi")));
        HttpResponse<String> resp = get(port, "/plain");
        assertEquals(200, resp.statusCode());
        assertEquals("hi", resp.body());
        String ct = resp.headers().firstValue("Content-Type").orElse("");
        assertTrue("expected text/plain, actual=" + ct, ct.toLowerCase().contains("text/plain"));
    }

    @Test
    public void logListenerReceivesEachRequest() throws Exception {
        int port = freePort();
        final List<MockRequestLog> received = new java.util.concurrent.CopyOnWriteArrayList<>();
        server.addLogListener(new java.util.function.Consumer<MockRequestLog>() {
            @Override
            public void accept(MockRequestLog log) {
                received.add(log);
            }
        });
        server.start(port, Collections.singletonList(
                new MockRoute("GET", "/listen", 200, 0, "text/plain", "ok")));
        get(port, "/listen?q=1");
        assertEquals(1, received.size());
        assertTrue(received.get(0).query.contains("q=1"));
    }

    private HttpResponse<String> get(int port, String path) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("http://127.0.0.1:" + port + path))
                .timeout(Duration.ofSeconds(5))
                .GET()
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
