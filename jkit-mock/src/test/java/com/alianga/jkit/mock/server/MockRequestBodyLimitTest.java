package com.alianga.jkit.mock.server;

import com.sun.net.httpserver.HttpServer;
import org.junit.After;
import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * 回归测试：请求体大小上限（审计 P0-1）。
 * <p>
 * 背景：{@link MockHttpServer#start} 原先 {@code readAllBytes()} 无上限，
 * echo/回显路由遇大文件上传直接进堆。修复后默认 {@link MockHttpServer#DEFAULT_MAX_BODY_BYTES}
 * 64MB，超过立即 413（不进路由/回显/转发，日志照常留痕），可用
 * {@link MockHttpServer#setMaxBodyBytes(long)} 调整为 ≤0 表示不限。
 *
 * @author 郑明亮
 */
public class MockRequestBodyLimitTest {

    private final MockHttpServer server = new MockHttpServer();
    private final AtomicInteger logged413 = new AtomicInteger();
    private HttpServer slowUpstream;

    {
        // 监听器在实例初始化时挂上（每个测试方法新实例都会生效）
        server.addLogListener(e -> {
            if (e.status == 413) {
                logged413.incrementAndGet();
            }
        });
    }

    @After
    public void tearDown() {
        server.stop();
        if (slowUpstream != null) {
            slowUpstream.stop(0);
        }
    }

    /** 默认上限内正常返回；echo 路由原样回显。 */
    @Test
    public void ordinaryBodyWithinDefaultLimitOk() throws Exception {
        int port = freePort();
        server.start(port, Collections.singletonList(echoRoute()));
        java.net.http.HttpResponse<String> resp = post(port, "/echo", body(64));
        assertEquals(200, resp.statusCode());
        assertEquals(new String(body(64), StandardCharsets.UTF_8), resp.body());
        assertEquals(0, logged413.get());
    }

    /** 上限调小后 POST 超限：413 + payload too large，response 不携带请求体内容。 */
    @Test
    public void oversizedBodyReturns413() throws Exception {
        int port = freePort();
        server.setMaxBodyBytes(8);
        server.start(port, Collections.singletonList(echoRoute()));
        java.net.http.HttpResponse<String> resp = post(port, "/echo", body(100));
        assertEquals(413, resp.statusCode());
        assertTrue(resp.body().contains("payload too large"));
        // 超限即断，不进行任何路由匹配，也不应该把 100 字节当 echo 输出
        assertFalse(resp.body().contains(new String(bodyOf(8, 'x'), StandardCharsets.UTF_8)));
        assertEquals(1, logged413.get());
    }

    /** 上限设 0 = 不限制：与旧版行为一致。 */
    @Test
    public void zeroLimitMeansUnlimited() throws Exception {
        int port = freePort();
        server.setMaxBodyBytes(0);
        server.start(port, Collections.singletonList(echoRoute()));
        java.net.http.HttpResponse<String> resp = post(port, "/echo", body(256));
        assertEquals(200, resp.statusCode());
        assertEquals(Long.MAX_VALUE, server.getMaxBodyBytes());
    }

    /** 恰好等于上限：只读取全量并放行（未超限不算 413）。 */
    @Test
    public void exactlyLimitIsNotOversize() throws Exception {
        int port = freePort();
        server.setMaxBodyBytes(8);
        server.start(port, Collections.singletonList(echoRoute()));
        java.net.http.HttpResponse<String> resp = post(port, "/echo", body(8));
        assertEquals(200, resp.statusCode());
        assertEquals(0, logged413.get());
    }

    /** 超限请求只读前缀进日志，不整段进堆；且事态发生在转发前，上游收不到任何字节。 */
    @Test
    public void oversizeRequestIsRejectedBeforeProxy() throws Exception {
        int port = freePort();
        slowUpstream = startCountingUpstream();
        server.setMaxBodyBytes(4);
        server.setProxyTarget("http://127.0.0.1:" + upstreamPort);
        server.start(port, Collections.<MockRoute>emptyList());
        java.net.http.HttpResponse<String> resp = post(port, "/anything", body(50));
        assertEquals(413, resp.statusCode());
        assertTrue(resp.body().contains("payload too large"));
        // 超限在转发之前劫袭，上游从未被命中
        assertEquals(0, hits.get());
    }

    private final AtomicInteger hits = new AtomicInteger();
    private int upstreamPort;

    private static MockRoute echoRoute() {
        return MockRoute.builder()
                .method("*").path("/echo")
                .statusCode(200)
                .contentType("text/plain; charset=utf-8")
                .echo(true)
                .build();
    }

    private static byte[] body(int size) {
        return bodyOf(size, 'x');
    }

    private static byte[] bodyOf(int size, char fill) {
        byte[] out = new byte[size];
        java.util.Arrays.fill(out, (byte) fill);
        return out;
    }

    private java.net.http.HttpResponse<String> post(int port, String path, byte[] content) throws IOException, InterruptedException {
        return java.net.http.HttpClient.newHttpClient().send(
                java.net.http.HttpRequest.newBuilder(
                                java.net.URI.create("http://127.0.0.1:" + port + path))
                        .POST(java.net.http.HttpRequest.BodyPublishers.ofByteArray(content))
                        .build(),
                java.net.http.HttpResponse.BodyHandlers.ofString());
    }

    private static java.net.URI URI(String url) {
        return java.net.URI.create(url);
    }

    /** 计数型回声上游：招引来请求即 hit++ 并返回 200；用于断言“超限请求未被转发”。 */
    private HttpServer startCountingUpstream() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            upstreamPort = socket.getLocalPort();
        }
        HttpServer upstream = HttpServer.create(new InetSocketAddress("127.0.0.1", upstreamPort), 0);
        upstream.createContext("/", ex -> {
            hits.incrementAndGet();
            byte[] body = "upstream-hit".getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(200, body.length);
            try (OutputStream out = ex.getResponseBody()) {
                out.write(body);
            }
            ex.close();
        });
        upstream.start();
        return upstream;
    }

    private static int freePort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }
}
