package com.alianga.jkit.mock.server;

import org.junit.After;
import org.junit.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import com.sun.net.httpserver.HttpServer;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * 2.0.4 引擎新能力：路径参数提取、占位符渲染、Mock.js 动态模板、附加响应头、
 * 响应方案规则挑选、代理回源与 enabled 开关。
 *
 * @author 郑明亮
 */
public class MockHttpServerV2Test {

    private final MockHttpServer server = new MockHttpServer();
    private final HttpClient client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(2))
            .build();

    @After
    public void tearDown() {
        server.stop();
    }

    @Test
    public void matchPathParamsExtractsNames() {
        Map<String, String> params = MockHttpServer.matchPathParams("/users/{id}/posts/{pid}", "/users/42/posts/7");
        assertEquals("42", params.get("id"));
        assertEquals("7", params.get("pid"));

        assertEquals(Collections.emptyMap(), MockHttpServer.matchPathParams("/a", "/a"));
        assertNull(MockHttpServer.matchPathParams("/a/{id}", "/a/1/2"));
        assertNull(MockHttpServer.matchPathParams("/a/{id}", "/b/1"));
    }

    @Test
    public void templateRendererSubstitutesPlaceholders() {
        MockRequestContext ctx = MockRequestContext.of("GET", "/users/42", 
                Collections.singletonMap("id", "42"), "uid=7&tag=a",
                Collections.singletonMap("X-Token", java.util.Collections.singletonList("tk-1")),
                "{\"user\":{\"name\":\"张三\"}}".getBytes(StandardCharsets.UTF_8));

        assertEquals("/users/42", MockTemplateRenderer.render("{{path}}", ctx));
        assertEquals("GET", MockTemplateRenderer.render("{{method}}", ctx));
        assertEquals("42", MockTemplateRenderer.render("{{param.id}}", ctx));
        assertEquals("7", MockTemplateRenderer.render("{{query.uid}}", ctx));
        assertEquals("tk-1", MockTemplateRenderer.render("{{header.X-Token}}", ctx));
        assertEquals("tk-1", MockTemplateRenderer.render("{{header.x-token}}", ctx));
        assertEquals("张三", MockTemplateRenderer.render("{{body.user.name}}", ctx));
        assertEquals("42-7", MockTemplateRenderer.render("{{param.id}}-{{query.uid}}", ctx));
        // 未知占位符替换为空串
        assertEquals("", MockTemplateRenderer.render("{{query.missing}}", ctx));
        assertEquals("", MockTemplateRenderer.render("{{unknown}}", ctx));
    }

    @Test
    public void globalVariablesSubstitutedViaVarPrefix() throws Exception {
        int port = freePort();
        server.start(port, Collections.singletonList(
                MockRoute.builder().method("GET").path("/env")
                        .body("{\"base\":\"{{var.baseUrl}}\"}")
                        .header("X-Env", "{{var.env}}")
                        .build()));
        java.util.Map<String, String> vars = new java.util.LinkedHashMap<>();
        vars.put("baseUrl", "https://api.example.com");
        vars.put("env", "staging");
        server.setVariables(vars);
        HttpResponse<String> resp = get(port, "/env");
        assertTrue(resp.body().contains("api.example.com"));
        assertEquals("staging", resp.headers().firstValue("X-Env").orElse(""));
    }

    @Test
    public void disabledRouteIsSkipped() throws Exception {
        int port = freePort();
        server.start(port, Arrays.asList(
                MockRoute.builder().method("GET").path("/api").enabled(false).body("off").build(),
                MockRoute.builder().method("GET").path("/api").body("on").build()));
        HttpResponse<String> resp = get(port, "/api");
        assertEquals("on", resp.body());

        // 热更新启停
        server.setRoutes(Arrays.asList(
                MockRoute.builder().method("GET").path("/api").enabled(true).body("on").build()));
        assertEquals("on", get(port, "/api").body());
    }

    @Test
    public void placeholdersSubstitutedInStaticBodyAndHeaders() throws Exception {
        int port = freePort();
        server.start(port, Collections.singletonList(
                MockRoute.builder()
                        .method("GET").path("/greet/{name}")
                        .body("{\"hello\":\"{{param.name}}\",\"q\":\"{{query.tag}}\"}")
                        .header("X-Who", "user-{{param.name}}")
                        .build()));

        HttpResponse<String> resp = client.send(HttpRequest.newBuilder()
                .uri(URI.create("http://127.0.0.1:" + port + "/greet/tom?tag=vip"))
                .GET().build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(200, resp.statusCode());
        assertTrue(resp.body().contains("\"hello\":\"tom\""));
        assertTrue(resp.body().contains("\"q\":\"vip\""));
        assertEquals("user-tom", resp.headers().firstValue("X-Who").orElse(""));
    }

    @Test
    public void dynamicTemplateRendersMockJsEachRequest() throws Exception {
        int port = freePort();
        server.start(port, Collections.singletonList(
                MockRoute.builder()
                        .method("GET").path("/random/{id}")
                        .dynamicTemplate(true)
                        .body("{\"id\":\"{{param.id}}\",\"name\":\"@cname\"}")
                        .build()));

        HttpResponse<String> first = get(port, "/random/1");
        HttpResponse<String> second = get(port, "/random/1");
        assertEquals(200, first.statusCode());
        assertTrue(first.body().contains("\"id\":\"1\""));
        assertTrue(first.body().contains("\"name\""));
        // 同一路径两次请求，名字应随机（极小概率相同，重试一次降低偶发）
        boolean different = !first.body().equals(second.body());
        if (!different) {
            different = !first.body().equals(get(port, "/random/1").body());
        }
        assertTrue("动态模板两次请求应产生不同数据", different);
    }

    @Test
    public void dynamicTemplateInvalidTemplateFallsBackToRaw() throws Exception {
        int port = freePort();
        server.start(port, Collections.singletonList(
                MockRoute.builder().method("GET").path("/raw")
                        .dynamicTemplate(true).body("not-a-json-template @bad(").build()));
        HttpResponse<String> resp = get(port, "/raw");
        assertEquals(200, resp.statusCode());
        // MockJs 对非 JSON 模板宽容处理（按字符串值返回），原文保留即可
        assertTrue(resp.body().contains("not-a-json-template @bad("));
    }

    @Test
    public void responsePlanPicksByRules() throws Exception {
        int port = freePort();
        server.start(port, Collections.singletonList(
                MockRoute.builder()
                        .method("GET").path("/pay")
                        .response(MockResponse.builder()
                                .body("{\"code\":0}").build()) // 无规则：默认响应
                        .response(MockResponse.builder()
                                .statusCode(500)
                                .rule(MockResponseRule.queryEquals("debug", "1"))
                                .body("{\"code\":500}").build())
                        .response(MockResponse.builder()
                                .statusCode(403)
                                .rule(MockResponseRule.headerEquals("X-Role", "guest"))
                                .body("{\"code\":403}").build())
                        .build()));

        assertEquals("{\"code\":0}", get(port, "/pay").body());
        HttpResponse<String> debug = client.send(HttpRequest.newBuilder()
                .uri(URI.create("http://127.0.0.1:" + port + "/pay?debug=1"))
                .GET().build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(500, debug.statusCode());
        assertEquals("{\"code\":500}", debug.body());
        HttpResponse<String> guest = client.send(HttpRequest.newBuilder()
                .uri(URI.create("http://127.0.0.1:" + port + "/pay?debug=0"))
                .header("X-Role", "guest")
                .GET().build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(403, guest.statusCode());
        // 全不中回落第一条
        assertEquals("{\"code\":0}", get(port, "/pay?x=1").body());
    }

    @Test
    public void responseRuleBodyPathAndRegex() {
        MockRequestContext ctx = MockRequestContext.of("POST", "/x",
                Collections.<String, String>emptyMap(), null,
                Collections.<String, List<String>>emptyMap(),
                "{\"user\":{\"age\":18},\"tags\":[\"a\",\"b\"]}".getBytes(StandardCharsets.UTF_8));

        assertTrue(MockResponseRule.bodyEquals("user.age", "18").matches(ctx));
        assertFalse(MockResponseRule.bodyEquals("user.age", "19").matches(ctx));
        assertTrue(MockResponseRule.queryRegex("n", "^\\d+$")
                .matches(MockRequestContext.of("GET", "/x", Collections.<String, String>emptyMap(),
                        "n=123", Collections.<String, List<String>>emptyMap(), new byte[0])));
        assertFalse(MockResponseRule.queryRegex("n", "^\\d+$")
                .matches(MockRequestContext.of("GET", "/x", Collections.<String, String>emptyMap(),
                        "n=abc", Collections.<String, List<String>>emptyMap(), new byte[0])));
        // 数组下标 + EXISTS
        assertTrue(new MockResponseRule(MockResponseRule.Source.BODY, MockResponseRule.Op.EQUALS,
                "tags.1", "b").matches(ctx));
        assertTrue(MockResponseRule.exists(MockResponseRule.Source.HEADER, "X-Any")
                .matches(MockRequestContext.of("GET", "/x", Collections.<String, String>emptyMap(),
                        null, Collections.singletonMap("X-Any",
                                Collections.singletonList("1")), new byte[0])));
        // 非 JSON body + 点分路径 → null
        assertNull(ctx.bodyPathOrRaw("a.b"));
        // 非 JSON body + 空 key → 原文
        assertEquals("plain", MockRequestContext.of("POST", "/x",
                Collections.<String, String>emptyMap(), null,
                Collections.<String, List<String>>emptyMap(), "plain".getBytes(StandardCharsets.UTF_8))
                .bodyPathOrRaw(""));
    }

    @Test
    public void proxyForwardsUnmatchedRequests() throws Exception {
        // 起一个真实后端
        com.sun.net.httpserver.HttpHandler echo = ex -> {
            byte[] body = readAll(ex.getRequestBody());
            byte[] out = ("backend:" + ex.getRequestMethod() + ":" + new String(body, StandardCharsets.UTF_8))
                    .getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().set("X-Backend", "yes");
            ex.sendResponseHeaders(201, out.length);
            ex.getResponseBody().write(out);
            ex.close();
        };
        HttpServer backend = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        backend.createContext("/", echo);
        backend.start();
        try {
            int port = freePort();
            server.start(port, Collections.singletonList(
                    MockRoute.builder().method("GET").path("/mocked").body("from-mock").build()));
            server.setProxyTarget("http://127.0.0.1:" + backend.getAddress().getPort() + "/");

            assertEquals("from-mock", get(port, "/mocked").body());

            HttpResponse<String> forwarded = client.send(HttpRequest.newBuilder()
                    .uri(URI.create("http://127.0.0.1:" + port + "/real/foo?q=2"))
                    .header("X-Trace", "t-9")
                    .POST(HttpRequest.BodyPublishers.ofString("payload-1"))
                    .build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(201, forwarded.statusCode());
            assertEquals("backend:POST:payload-1", forwarded.body());
            assertEquals("yes", forwarded.headers().firstValue("X-Backend").orElse(""));

            // 代理请求在日志里有 proxied 标记
            List<MockRequestLog> logs = server.snapshotLogs();
            assertFalse(logs.isEmpty());
            assertTrue(logs.get(0).proxied);

            // 关闭代理后恢复 404
            server.setProxyTarget(null);
            assertEquals(404, get(port, "/real/foo").statusCode());
        } finally {
            backend.stop(0);
        }
    }

    @Test
    public void proxyFailureReturns502() throws Exception {
        int port = freePort();
        server.start(port, Collections.<MockRoute>emptyList());
        server.setProxyTarget("http://127.0.0.1:1"); // 不可达
        HttpResponse<String> resp = get(port, "/anything");
        assertEquals(502, resp.statusCode());
        assertTrue(resp.body().contains("proxy failed"));
    }

    private HttpResponse<String> get(int port, String path) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("http://127.0.0.1:" + port + path))
                .timeout(Duration.ofSeconds(5))
                .GET()
                .build();
        return client.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private static byte[] readAll(java.io.InputStream in) throws IOException {
        return in.readAllBytes();
    }

    private static int freePort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            socket.setReuseAddress(true);
            return socket.getLocalPort();
        }
    }
}
