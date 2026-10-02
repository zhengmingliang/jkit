package com.alianga.jkit.mock.server;

import org.junit.After;
import org.junit.Test;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * 2.0.5 引擎新能力：HTTP 请求参数回显、SSE 长连接推送、伴随 WebSocket 服务。
 *
 * @author 郑明亮
 */
public class MockRealtimeMockTest {

    private final MockHttpServer server = new MockHttpServer();
    private final HttpClient client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(2))
            .build();

    @After
    public void tearDown() {
        server.stop();
    }

    @Test
    public void echoRequestReturnsAllParams() throws Exception {
        int port = freePort();
        server.start(port, Collections.singletonList(
                MockRoute.builder().method("POST").path("/echo/{id}")
                        .protocol("HTTP")
                        .echoRequest(MockEchoConfig.builder().enabled(true).build())
                        .build()));
        HttpResponse<String> resp = client.send(HttpRequest.newBuilder()
                .uri(URI.create("http://127.0.0.1:" + port + "/echo/9?debug=1&tag=a"))
                .header("X-Token", "tk-1")
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{\"name\":\"张三\"}"))
                .build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(200, resp.statusCode());
        String body = resp.body();
        assertTrue(body, body.contains("\"pathParams\""));
        assertTrue(body, body.contains("\"id\":\"9\""));
        assertTrue(body, body.contains("\"debug\":\"1\""));
        assertTrue(body, body.toLowerCase().contains("\"x-token\":\"tk-1\""));
        assertTrue(body, body.contains("\"name\":\"张三\""));
    }

    @Test
    public void echoRequestWithIncludesReturnsOnlySelected() throws Exception {
        int port = freePort();
        server.start(port, Collections.singletonList(
                MockRoute.builder().method("POST").path("/echo/{id}")
                        .echoRequest(MockEchoConfig.builder().enabled(true)
                                .include("query.debug")
                                .include("body.name")
                                .build())
                        .build()));
        HttpResponse<String> resp = client.send(HttpRequest.newBuilder()
                .uri(URI.create("http://127.0.0.1:" + port + "/echo/9?debug=1&secret=2"))
                .header("X-Token", "tk-1")
                .POST(HttpRequest.BodyPublishers.ofString("{\"name\":\"张三\",\"pwd\":\"x\"}"))
                .build(), HttpResponse.BodyHandlers.ofString());
        String body = resp.body();
        assertTrue(body, body.contains("\"debug\":\"1\""));
        assertTrue(body, body.contains("\"name\":\"张三\""));
        assertTrue(body, !body.contains("secret"));
        assertTrue(body, !body.toLowerCase().contains("x-token"));
        assertTrue(body, !body.contains("\"pwd\""));
    }

    @Test
    public void echoRequestMergesWithTemplateViaRequestPlaceholder() throws Exception {
        int port = freePort();
        server.start(port, Collections.singletonList(
                MockRoute.builder().method("GET").path("/wrap")
                        .body("{\"received\":{{request}},\"ok\":true}")
                        .echoRequest(MockEchoConfig.builder().enabled(true).build())
                        .build()));
        HttpResponse<String> resp = client.send(HttpRequest.newBuilder()
                .uri(URI.create("http://127.0.0.1:" + port + "/wrap?uid=7"))
                .GET().build(), HttpResponse.BodyHandlers.ofString());
        String body = resp.body();
        // {{request}} 嵌入后仍是合法 JSON
        Object parsed = com.alianga.jkit.json.JSON.parse(body);
        assertTrue(parsed instanceof java.util.Map);
        java.util.Map<?, ?> root = (java.util.Map<?, ?>) parsed;
        assertEquals(Boolean.TRUE, root.get("ok"));
        assertTrue(body, body.contains("\"uid\":\"7\""));
    }

    @Test
    public void legacyEchoStillReturnsRawBodyUnchanged() throws Exception {
        int port = freePort();
        server.start(port, Collections.singletonList(
                MockRoute.builder().method("POST").path("/raw")
                        .echo(true)
                        .body("ignored")
                        .build()));
        HttpResponse<String> resp = client.send(HttpRequest.newBuilder()
                .uri(URI.create("http://127.0.0.1:" + port + "/raw"))
                .header("Content-Type", "text/plain")
                .POST(HttpRequest.BodyPublishers.ofString("raw-payload"))
                .build(), HttpResponse.BodyHandlers.ofString());
        assertEquals("raw-payload", resp.body());
        assertEquals("text/plain", resp.headers().firstValue("Content-Type").orElse(""));
    }

    @Test
    public void ssePushesOnceOnConnect() throws Exception {
        int port = freePort();
        server.start(port, Collections.singletonList(
                MockRoute.builder().method("GET").path("/events/{id}")
                        .protocol("SSE")
                        .body("{\"id\":\"{{param.id}}\",\"tick\":1}")
                        .pushPolicy(MockPushPolicy.builder()
                                .mode(MockPushPolicy.Mode.ONCE).build())
                        .build()));
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("http://127.0.0.1:" + port + "/events/42"))
                .timeout(Duration.ofSeconds(5))
                .GET().build();
        HttpResponse<InputStream> resp = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
        assertEquals(200, resp.statusCode());
        assertTrue(resp.headers().firstValue("Content-Type").orElse("")
                .startsWith("text/event-stream"));
        String chunk = readUntilData(resp.body(), 4000);
        assertTrue(chunk, chunk.contains("data: {\"id\":\"42\",\"tick\":1}"));
        assertTrue(chunk, chunk.contains("id: 1"));
        resp.body().close();
    }

    @Test
    public void sseIntervalPushesConfiguredRepeatAndHonorsEventName() throws Exception {
        int port = freePort();
        server.start(port, Collections.singletonList(
                MockRoute.builder().method("GET").path("/ticks")
                        .protocol("SSE")
                        .body("{\"n\":1}")
                        .pushPolicy(MockPushPolicy.builder()
                                .mode(MockPushPolicy.Mode.INTERVAL)
                                .initialDelayMs(80)
                                .intervalMs(80)
                                .repeat(3)
                                .eventName("tick")
                                .build())
                        .build()));
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("http://127.0.0.1:" + port + "/ticks"))
                .timeout(Duration.ofSeconds(5))
                .GET().build();
        HttpResponse<InputStream> resp = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
        String text = readDataFrames(resp.body(), 3, 5000);
        long count = text.lines().filter(l -> l.startsWith("data:")).count();
        assertTrue("应收到 3 帧 data，实际: " + count, count >= 3);
        assertTrue(text, text.contains("event: tick"));
        resp.body().close();
    }

    @Test
    public void webSocketCompanionPushesOnceAndEchoesEvents() throws Exception {
        int httpPort = freePort();
        server.setWebSocketPort(0);
        server.start(httpPort, java.util.Arrays.asList(
                MockRoute.builder().path("/ws/once")
                        .protocol("WS")
                        .body("{\"hello\":\"{{param.x}}\"}")
                        .build(),
                MockRoute.builder().path("/ws/chat")
                        .protocol("WS")
                        .body("echo:{{event}}")
                        .pushPolicy(MockPushPolicy.builder()
                                .mode(MockPushPolicy.Mode.EVENT)
                                .event("hello")
                                .build())
                        .build()));
        int wsPort = server.actualWebSocketPort();
        assertEquals(httpPort + 1, wsPort);

        // ONCE：连接建立后即收到一帧
        MessageQueue once = new MessageQueue();
        java.net.http.WebSocket wsOnce = client.newWebSocketBuilder()
                .buildAsync(URI.create("ws://127.0.0.1:" + wsPort + "/ws/once"), once.listener())
                .get(5, TimeUnit.SECONDS);
        String first = once.poll();
        assertTrue(first, first.contains("\"hello\""));

        // WS 路由在 HTTP 端口被普通访问 → 426
        HttpResponse<String> plain = client.send(HttpRequest.newBuilder()
                .uri(URI.create("http://127.0.0.1:" + httpPort + "/ws/once"))
                .GET().build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(426, plain.statusCode());

        // WS 端口收到非握手普通 HTTP（无 Upgrade 头）→ 426 而非裸断连接
        HttpResponse<String> nonHandshake = client.send(HttpRequest.newBuilder()
                .uri(URI.create("http://127.0.0.1:" + wsPort + "/ws/once"))
                .timeout(Duration.ofSeconds(3))
                .GET().build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(426, nonHandshake.statusCode());

        // EVENT：发送命中关键字的消息，收到带回显的帧；不命中不响应
        MessageQueue chat = new MessageQueue();
        java.net.http.WebSocket wsChat = client.newWebSocketBuilder()
                .buildAsync(URI.create("ws://127.0.0.1:" + wsPort + "/ws/chat"), chat.listener())
                .get(5, TimeUnit.SECONDS);
        wsChat.sendText("hello world", true).get(2, TimeUnit.SECONDS);
        assertEquals("echo:hello world", chat.poll());
        wsChat.sendText("noop", true).get(2, TimeUnit.SECONDS);
        assertEquals(null, chat.poll(300));
        wsOnce.sendClose(java.net.http.WebSocket.NORMAL_CLOSURE, "bye");
        wsChat.sendClose(java.net.http.WebSocket.NORMAL_CLOSURE, "bye");
    }

    @Test
    public void newRuleOpsAndMethodSource() {
        MockRequestContext ctx = MockRequestContext.of("POST", "/x",
                Collections.<String, String>emptyMap(), "age=20&role=admin",
                Collections.<String, List<String>>emptyMap(),
                "{\"name\":\"张三三\",\"vip\":true}".getBytes(StandardCharsets.UTF_8));
        // query 无 name 参数 → 不命中；body.name 包含「三」→ 命中
        assertFalse(new MockResponseRule(MockResponseRule.Source.QUERY,
                MockResponseRule.Op.CONTAINS, "name", "三").matches(ctx));
        assertTrue(new MockResponseRule(MockResponseRule.Source.BODY,
                MockResponseRule.Op.CONTAINS, "name", "三").matches(ctx));
        assertTrue(new MockResponseRule(MockResponseRule.Source.QUERY,
                MockResponseRule.Op.GT, "age", "18").matches(ctx));
        assertFalse(new MockResponseRule(MockResponseRule.Source.QUERY,
                MockResponseRule.Op.LTE, "age", "18").matches(ctx));
        assertTrue(new MockResponseRule(MockResponseRule.Source.QUERY,
                MockResponseRule.Op.IN, "role", "guest,admin,user").matches(ctx));
        assertTrue(new MockResponseRule(MockResponseRule.Source.METHOD,
                MockResponseRule.Op.EQUALS, "", "POST").matches(ctx));
        assertFalse(new MockResponseRule(MockResponseRule.Source.METHOD,
                MockResponseRule.Op.EQUALS, "", "GET").matches(ctx));
        // 非数值 GT 不命中
        assertFalse(new MockResponseRule(MockResponseRule.Source.QUERY,
                MockResponseRule.Op.GT, "role", "1").matches(ctx));
    }

    @Test
    public void invalidDynamicTemplateExposesWarningHeader() throws Exception {
        int port = freePort();
        server.start(port, Collections.singletonList(
                MockRoute.builder().method("GET").path("/bad")
                        .dynamicTemplate(true).body("{ broken").build()));
        HttpResponse<String> resp = get(port, "/bad");
        assertEquals(200, resp.statusCode());
        assertTrue(resp.headers().firstValue("X-Mock-Warning").orElse("")
                .startsWith("dynamic-template-failed"));
    }

    @Test
    public void webSocketCompanionFallsBackWhenPreferredPortBusy() throws Exception {
        int httpPort = freePort();
        // 占住 HTTP+1，自动模式应顺延到 +2
        try (java.net.ServerSocket blocker = new java.net.ServerSocket()) {
            blocker.setReuseAddress(true);
            blocker.bind(new java.net.InetSocketAddress("127.0.0.1", httpPort + 1));
            server.setWebSocketPort(0);
            server.start(httpPort, Collections.singletonList(
                    MockRoute.builder().path("/ws").protocol("WS").body("hi").build()));
            assertEquals(httpPort + 2, server.actualWebSocketPort());
            assertNotNull(server.webSocketUrl());
        }
    }

    @Test
    public void sseAndWsLogsCarryProtocol() throws Exception {
        int httpPort = freePort();
        server.setWebSocketPort(0);
        server.start(httpPort, java.util.Arrays.asList(
                MockRoute.builder().path("/sse").protocol("SSE")
                        .body("{\"n\":1}")
                        .pushPolicy(MockPushPolicy.builder()
                                .mode(MockPushPolicy.Mode.INTERVAL)
                                .initialDelayMs(50).intervalMs(50).repeat(1).build())
                        .build(),
                MockRoute.builder().path("/ws").protocol("WS").body("hi").build()));
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("http://127.0.0.1:" + httpPort + "/sse"))
                .timeout(Duration.ofSeconds(5)).GET().build();
        HttpResponse<InputStream> sse = client.send(request,
                HttpResponse.BodyHandlers.ofInputStream());
        readDataFrames(sse.body(), 1, 3000);
        sse.body().close();
        // 等 SSE handler 写日志（有限次推送后连接关闭）
        waitForLog("SSE", 3000);

        MessageQueue queue = new MessageQueue();
        java.net.http.WebSocket socket = client.newWebSocketBuilder()
                .buildAsync(URI.create("ws://127.0.0.1:" + server.actualWebSocketPort() + "/ws"),
                        queue.listener())
                .get(5, TimeUnit.SECONDS);
        assertNotNull(queue.poll());
        socket.sendClose(java.net.http.WebSocket.NORMAL_CLOSURE, "bye");
        waitForLog("WS", 3000);

        boolean sawSse = false;
        boolean sawWs = false;
        for (MockRequestLog log : server.snapshotLogs()) {
            if ("SSE".equals(log.protocol) && "/sse".equals(log.path)) {
                sawSse = true;
            }
            if ("WS".equals(log.protocol) && "/ws".equals(log.path)) {
                sawWs = true;
            }
        }
        assertTrue("应有 SSE 协议日志", sawSse);
        assertTrue("应有 WS 协议日志", sawWs);
    }

    private void waitForLog(String protocol, long timeoutMs) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            for (MockRequestLog log : server.snapshotLogs()) {
                if (protocol.equals(log.protocol)) {
                    return;
                }
            }
            Thread.sleep(50);
        }
    }

    private HttpResponse<String> get(int port, String path) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("http://127.0.0.1:" + port + path))
                .timeout(Duration.ofSeconds(5))
                .GET()
                .build();
        return client.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private static String readUntilData(InputStream in, long timeoutMs) throws IOException {
        BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
        long deadline = System.currentTimeMillis() + timeoutMs;
        StringBuilder sb = new StringBuilder();
        while (System.currentTimeMillis() < deadline) {
            String line = reader.readLine();
            if (line == null) {
                break;
            }
            sb.append(line).append('\n');
            if (line.startsWith("data:")) {
                return sb.toString();
            }
        }
        return sb.toString();
    }

    private static String readDataFrames(InputStream in, int frames, long timeoutMs)
            throws IOException {
        BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
        long deadline = System.currentTimeMillis() + timeoutMs;
        StringBuilder sb = new StringBuilder();
        int seen = 0;
        while (System.currentTimeMillis() < deadline && seen < frames) {
            String line = reader.readLine();
            if (line == null) {
                break;
            }
            sb.append(line).append('\n');
            if (line.startsWith("data:")) {
                seen++;
            }
        }
        return sb.toString();
    }

    private static int freePort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            socket.setReuseAddress(true);
            return socket.getLocalPort();
        }
    }

    /** JDK WebSocket 客户端的消息收集器。 */
    private static final class MessageQueue {
        private final LinkedBlockingQueue<String> messages = new LinkedBlockingQueue<>();
        private final StringBuilder buffer = new StringBuilder();

        java.net.http.WebSocket.Listener listener() {
            return new java.net.http.WebSocket.Listener() {
                @Override
                public CompletionStage<?> onText(java.net.http.WebSocket webSocket,
                                                 CharSequence data, boolean last) {
                    buffer.append(data);
                    if (last) {
                        messages.add(buffer.toString());
                        buffer.setLength(0);
                    }
                    webSocket.request(1);
                    return CompletableFuture.completedFuture(null);
                }
            };
        }

        String poll() throws InterruptedException {
            return messages.poll(4, TimeUnit.SECONDS);
        }

        String poll(long ms) throws InterruptedException {
            return messages.poll(ms, TimeUnit.MILLISECONDS);
        }
    }
}
