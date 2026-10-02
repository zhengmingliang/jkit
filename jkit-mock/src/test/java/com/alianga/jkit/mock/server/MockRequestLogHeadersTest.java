package com.alianga.jkit.mock.server;

import org.junit.After;
import org.junit.Test;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.WebSocket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * 2.0.5 请求日志头快照：命中路由 / 未命中 404 / SSE / WS 握手四条路径的
 * 请求头与响应头采集，以及 {@link MockHttpServer#headerSnapshot(Map)} 本身。
 *
 * @author 郑明亮
 */
public class MockRequestLogHeadersTest {

    private final MockHttpServer server = new MockHttpServer();
    private final HttpClient client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(2))
            .build();

    @After
    public void tearDown() {
        server.stop();
    }

    @Test
    public void headerSnapshotTakesFirstValueSortedAndNullSafe() {
        Map<String, List<String>> raw = new LinkedHashMap<>();
        raw.put("X-B", Arrays.asList("b1", "b2"));
        raw.put("accept", Collections.singletonList("application/json"));
        Map<String, String> snapshot = MockHttpServer.headerSnapshot(raw);
        // 键名不区分大小写排序：accept 在 X-B 前
        List<String> keys = new java.util.ArrayList<>(snapshot.keySet());
        assertEquals(Arrays.asList("accept", "X-B"), keys);
        assertEquals("b1", snapshot.get("X-B"));
        assertEquals("application/json", snapshot.get("ACCEPT"));
        assertTrue(MockHttpServer.headerSnapshot(null).isEmpty());
        raw.put("empty", Collections.<String>emptyList());
        assertTrue(MockHttpServer.headerSnapshot(raw).get("empty") == null);
    }

    @Test
    public void matchedRouteLogsBothHeaderSides() throws Exception {
        int port = freePort();
        server.start(port, Collections.singletonList(
                MockRoute.builder().method("GET").path("/hello")
                        .statusCode(200)
                        .header("X-Mock", "1")
                        .body("{}")
                        .build()));
        client.send(HttpRequest.newBuilder()
                        .uri(URI.create("http://127.0.0.1:" + port + "/hello?a=1"))
                        .header("X-Trace", "tk-9")
                        .GET().build(),
                HttpResponse.BodyHandlers.ofString());
        MockRequestLog log = server.snapshotLogs().get(0);
        assertTrue("请求头应含 X-Trace",
                "tk-9".equals(log.requestHeaders.get("X-Trace")));
        assertNotNull("请求头应含 Host", log.requestHeaders.get("Host"));
        assertEquals("1", log.responseHeaders.get("X-Mock"));
        assertNotNull("响应头应含 Content-Type", log.responseHeaders.get("Content-type"));
        assertNotNull("响应头应含 CORS Allow-Origin",
                log.responseHeaders.get("Access-Control-Allow-Origin"));
    }

    @Test
    public void notFoundWithoutProxyStillLogsHeaders() throws Exception {
        int port = freePort();
        server.start(port, Collections.<MockRoute>emptyList());
        client.send(HttpRequest.newBuilder()
                        .uri(URI.create("http://127.0.0.1:" + port + "/miss"))
                        .header("X-Trace", "tk-404")
                        .GET().build(),
                HttpResponse.BodyHandlers.ofString());
        MockRequestLog log = server.snapshotLogs().get(0);
        assertEquals(404, log.status);
        assertEquals("tk-404", log.requestHeaders.get("X-Trace"));
        assertNotNull(log.responseHeaders.get("Content-type"));
    }

    @Test
    public void sseLogCarriesEventStreamResponseHeaders() throws Exception {
        int port = freePort();
        server.start(port, Collections.singletonList(
                MockRoute.builder().method("GET").path("/events").protocol("SSE")
                        .pushPolicy(MockPushPolicy.once()).body("{}").build()));
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("http://127.0.0.1:" + port + "/events"))
                .header("X-Sse", "1")
                .GET().build();
        HttpResponse<InputStream> response =
                client.send(request, HttpResponse.BodyHandlers.ofInputStream());
        BufferedReader reader = new BufferedReader(
                new InputStreamReader(response.body(), StandardCharsets.UTF_8));
        String firstLine = reader.readLine();
        while (firstLine != null && !firstLine.startsWith("data:")) {
            firstLine = reader.readLine();
        }
        assertNotNull("应至少收到一帧 data", firstLine);
        response.body().close();
        // SSE 条目在连接结束（写出失败探测）后才由统一出口落库，轮询等待
        MockRequestLog log = null;
        long deadline = System.currentTimeMillis() + 5000L;
        while (System.currentTimeMillis() < deadline) {
            for (MockRequestLog entry : server.snapshotLogs()) {
                if ("SSE".equals(entry.protocol)) {
                    log = entry;
                    break;
                }
            }
            if (log != null) {
                break;
            }
            Thread.sleep(100L);
        }
        assertNotNull("SSE 日志应在连接结束后落库", log);
        assertEquals("1", log.requestHeaders.get("X-Sse"));
        String ct = log.responseHeaders.get("Content-type");
        assertNotNull(ct);
        assertTrue("SSE 响应头应为 event-stream: " + ct, ct.contains("text/event-stream"));
    }

    @Test
    public void wsConnectLogCarriesHandshakeRequestHeaders() throws Exception {
        int port = freePort();
        server.setWebSocketPort(0);
        server.start(port, Collections.singletonList(
                MockRoute.builder().method("GET").path("/ws").protocol("WS")
                        .pushPolicy(MockPushPolicy.once()).body("{}").build()));
        int wsPort = server.actualWebSocketPort();
        LinkedBlockingQueue<String> frames = new LinkedBlockingQueue<>();
        WebSocket ws = client.newWebSocketBuilder()
                .header("X-Client", "probe")
                .buildAsync(URI.create("ws://127.0.0.1:" + wsPort + "/ws"),
                        new WsListener(frames))
                .get(5, TimeUnit.SECONDS);
        String frame = frames.poll(5, TimeUnit.SECONDS);
        assertNotNull("WS 应收到首帧", frame);
        ws.sendClose(WebSocket.NORMAL_CLOSURE, "bye").get(2, TimeUnit.SECONDS);
        MockRequestLog log = null;
        for (MockRequestLog entry : server.snapshotLogs()) {
            if ("WS".equals(entry.protocol) && entry.status == 101) {
                log = entry;
                break;
            }
        }
        assertNotNull("应有 CONNECT 日志条目", log);
        assertEquals("probe", log.requestHeaders.get("X-Client"));
        assertNotNull("握手头应含 Upgrade", log.requestHeaders.get("Upgrade"));
        assertTrue("CONNECT 无响应头帧", log.responseHeaders.isEmpty());
    }

    private static int freePort() throws Exception {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }

    /** 收帧监听：文本帧入队，供断言首帧内容。 */
    private static final class WsListener implements WebSocket.Listener {
        private final LinkedBlockingQueue<String> frames;

        WsListener(LinkedBlockingQueue<String> frames) {
            this.frames = frames;
        }

        @Override
        public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
            frames.add(data.toString());
            webSocket.request(1);
            return CompletableFuture.completedFuture(null);
        }
    }
}
