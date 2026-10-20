package com.alianga.jkit.mock.server;

import org.junit.After;
import org.junit.Test;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * 网络层故障注入（路由级）：启动真实引擎，用 HTTP 客户端请求，
 * 断言客户端实际看到的现象（连接被关、声明长度与实收不一致、分块间隔、JSON 解析失败），
 * 以及概率随机源注入后 0 / 100 / 中间值的行为，日志中的故障标识。
 *
 * @author 郑明亮
 * @since 2.0.5
 */
public class MockFaultInjectionTest {

    private final MockHttpServer server = new MockHttpServer();

    @After
    public void tearDown() {
        server.stop();
    }

    /** CONNECTION_CLOSE：客户端拿不到任何响应（HttpClient 报 IO 异常 / 空体），日志留故障标识。 */
    @Test
    public void connectionCloseDeliversNoResponse() throws Exception {
        int port = freePort();
        server.start(port, Collections.singletonList(route("GET", "/drop",
                MockFault.builder().mode(MockFault.Mode.CONNECTION_CLOSE).build())));
        boolean failedAsExpected = false;
        try {
            client().send(HttpRequest.newBuilder()
                            .uri(URI.create("http://127.0.0.1:" + port + "/drop"))
                            .GET().build(),
                    HttpResponse.BodyHandlers.ofString());
        } catch (java.io.IOException expected) {
            failedAsExpected = true;
        }
        assertTrue("连接关闭对客户端应表现为 IO 异常或空响应", failedAsExpected);
        assertEquals("CONNECTION_CLOSE", latestLogFault());
    }

    /** EMPTY_RESPONSE：状态码与响应头正常，响应体空。 */
    @Test
    public void emptyResponseKeepsStatusAndHeadersWithoutBody() throws Exception {
        int port = freePort();
        server.start(port, Collections.singletonList(route("GET", "/empty",
                MockFault.builder().mode(MockFault.Mode.EMPTY_RESPONSE).build())));
        HttpResponse<String> resp = client().send(HttpRequest.newBuilder()
                        .uri(URI.create("http://127.0.0.1:" + port + "/empty"))
                        .header("X-Probe", "1")
                        .GET().build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(200, resp.statusCode());
        assertEquals("", resp.body());
        assertEquals("*", resp.headers().firstValue("Access-Control-Allow-Origin").orElse(""));
        assertEquals("EMPTY_RESPONSE", latestLogFault());
    }

    /** TRUNCATED_BODY：声明完整 Content-Length 只写一部分断流；比例配置生效于实收长度。 */
    @Test
    public void truncatedBodySendsLessThanDeclared() throws Exception {
        int port = freePort();
        byte[] payload = new byte[200];
        Arrays.fill(payload, (byte) 'x');
        server.start(port, Collections.singletonList(MockRoute.builder()
                .method("GET").path("/trunc").statusCode(200)
                .contentType("text/plain").body(new String(payload, java.nio.charset.StandardCharsets.UTF_8))
                .fault(MockFault.builder().mode(MockFault.Mode.TRUNCATED_BODY)
                        .truncatePercent(25).build())
                .build()));
        boolean cutShort = false;
        int received = -1;
        try {
            HttpResponse<byte[]> resp = client().send(HttpRequest.newBuilder()
                            .uri(URI.create("http://127.0.0.1:" + port + "/trunc"))
                            .GET().build(),
                    HttpResponse.BodyHandlers.ofByteArray());
            received = resp.body().length;
            cutShort = received < 200 && received >= 0;
        } catch (java.io.IOException truncated) {
            cutShort = true;
        }
        assertTrue("实收应少于声明的 200 字节（或读中断流）", cutShort);
        assertEquals("TRUNCATED_BODY(25%)", latestLogFault());
    }

    /** TRUNCATED_BODY 按字节数截断：bytes 优先于比例。 */
    @Test
    public void truncatedBodyHonorBytesOverPercent() throws Exception {
        int port = freePort();
        server.start(port, Collections.singletonList(MockRoute.builder()
                .method("GET").path("/bytes").statusCode(200)
                .contentType("text/plain").body("0123456789")
                .fault(MockFault.builder().mode(MockFault.Mode.TRUNCATED_BODY)
                        .truncateBytes(3).truncatePercent(80).build())
                .build()));
        boolean cutShort = false;
        try {
            client().send(HttpRequest.newBuilder()
                            .uri(URI.create("http://127.0.0.1:" + port + "/bytes"))
                            .GET().build(),
                    HttpResponse.BodyHandlers.ofString());
        } catch (java.io.IOException truncated) {
            cutShort = true;
        }
        assertTrue(cutShort);
        assertEquals("TRUNCATED_BODY(3B)", latestLogFault());
    }

    /** SLOW_BODY：分块输出，总耗时明显大于不故障路径（块间隔叠加）。 */
    @Test
    public void slowBodyArrivesInChunksWithDelays() throws Exception {
        int port = freePort();
        char[] chars = new char[36];
        Arrays.fill(chars, 'y');
        server.start(port, Collections.singletonList(MockRoute.builder()
                .method("GET").path("/slow").statusCode(200)
                .contentType("text/plain").body(new String(chars))
                .fault(MockFault.builder().mode(MockFault.Mode.SLOW_BODY)
                        .chunkBytes(12).chunkDelayMs(150).build())
                .build()));
        long start = System.nanoTime();
        HttpResponse<String> resp = client().send(HttpRequest.newBuilder()
                        .uri(URI.create("http://127.0.0.1:" + port + "/slow"))
                        .GET().build(),
                HttpResponse.BodyHandlers.ofString());
        long elapsedMs = (System.nanoTime() - start) / 1_000_000L;
        assertEquals("yyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyy", resp.body());
        assertTrue("36Byte/12B chunk=3 段 x 150ms 应至少叠出 ~300ms, actual=" + elapsedMs,
                elapsedMs >= 280);
        assertEquals("SLOW_BODY(12B/150ms)", latestLogFault());
    }

    /** INVALID_JSON：内容照旧形态损坏，JSON 解析必失败，Content-Type 不变。 */
    @Test
    public void invalidJsonBreaksParsingButKeepsContentType() throws Exception {
        int port = freePort();
        server.start(port, Collections.singletonList(route("GET", "/bad",
                MockFault.builder().mode(MockFault.Mode.INVALID_JSON).build())));
        HttpResponse<String> resp = client().send(HttpRequest.newBuilder()
                        .uri(URI.create("http://127.0.0.1:" + port + "/bad"))
                        .GET().build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals("application/json; charset=utf-8",
                resp.headers().firstValue("Content-Type").orElse(""));
        boolean parseFailed = false;
        try {
            com.alianga.jkit.json.JSON.parse(resp.body());
        } catch (RuntimeException expected) {
            parseFailed = true;
        }
        assertTrue("损坏后的体不应再是合法 JSON: " + resp.body(), parseFailed);
        assertEquals("INVALID_JSON", latestLogFault());
    }

    /** 概率 0 视为关闭：故障配置在但每次都正常响应。 */
    @Test
    public void probabilityZeroDisablesFault() throws Exception {
        int port = freePort();
        server.setFaultProbabilitySource(() -> 0);
        server.start(port, Collections.singletonList(route("GET", "/p0",
                MockFault.builder().mode(MockFault.Mode.EMPTY_RESPONSE)
                        .probabilityPercent(0).build())));
        HttpResponse<String> resp = client().send(HttpRequest.newBuilder()
                        .uri(URI.create("http://127.0.0.1:" + port + "/p0"))
                        .GET().build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals("正常响应体", resp.body());
        assertEquals("", latestLogFault());
    }

    /** 概率 100：注入固定随机源也必命中。 */
    @Test
    public void probabilityHundredAlwaysHits() throws Exception {
        int port = freePort();
        server.setFaultProbabilitySource(() -> 99);
        server.start(port, Collections.singletonList(route("GET", "/p100",
                MockFault.builder().mode(MockFault.Mode.EMPTY_RESPONSE)
                        .probabilityPercent(100).build())));
        HttpResponse<String> resp = client().send(HttpRequest.newBuilder()
                        .uri(URI.create("http://127.0.0.1:" + port + "/p100"))
                        .GET().build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals("", resp.body());
        assertEquals("EMPTY_RESPONSE", latestLogFault());
    }

    /** 中间概率：固定掷点低概率才命中、高掷点不命中；未命中日志无故障标识。 */
    @Test
    public void midProbabilityFollowsInjectedRoll() throws Exception {
        int port = freePort();
        server.setFaultProbabilitySource(() -> 30);
        server.start(port, Collections.singletonList(route("GET", "/mid",
                MockFault.builder().mode(MockFault.Mode.EMPTY_RESPONSE)
                        .probabilityPercent(30).build())));
        HttpResponse<String> hit = client().send(HttpRequest.newBuilder()
                        .uri(URI.create("http://127.0.0.1:" + port + "/mid"))
                        .GET().build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals("掷点 30 小于概率 30 不成立,应不命中", "正常响应体", hit.body());
        assertEquals("", latestLogFault());

        server.setFaultProbabilitySource(() -> 29);
        HttpResponse<String> miss = client().send(HttpRequest.newBuilder()
                        .uri(URI.create("http://127.0.0.1:" + port + "/mid"))
                        .GET().build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals("掷点 29 应命中故障", "", miss.body());
        assertEquals("EMPTY_RESPONSE", latestLogFault());
    }

    /** 故障与延迟叠加：先延迟再故障输出，耗时至少覆盖延迟。 */
    @Test
    public void latencyStacksWithFault() throws Exception {
        int port = freePort();
        server.start(port, Collections.singletonList(MockRoute.builder()
                .method("GET").path("/late").statusCode(200)
                .contentType("application/json; charset=utf-8").body("{\"a\":1}")
                .latencyMs(150)
                .fault(MockFault.builder().mode(MockFault.Mode.INVALID_JSON).build())
                .build()));
        long start = System.nanoTime();
        HttpResponse<String> resp = client().send(HttpRequest.newBuilder()
                        .uri(URI.create("http://127.0.0.1:" + port + "/late"))
                        .GET().build(),
                HttpResponse.BodyHandlers.ofString());
        long elapsedMs = (System.nanoTime() - start) / 1_000_000L;
        assertTrue("延迟 150ms 应叠加在故障前, actual=" + elapsedMs, elapsedMs >= 140);
        assertTrue(resp.body().contains("\"a\":1,}"));
        assertEquals("INVALID_JSON", latestLogFault());
    }

    /** SSE / WS 路由不参与故障注入（SSE 走长连接，语义不同）。 */
    @Test
    public void sseRouteIgnoresFault() throws Exception {
        int port = freePort();
        server.start(port, Collections.singletonList(MockRoute.builder()
                .method("GET").path("/stream").statusCode(200).protocol("SSE")
                .contentType("text/event-stream; charset=utf-8")
                // 有限推送（50ms 后推一次即收）让连接自行关闭，避免长连接悬挂
                .pushPolicy(MockPushPolicy.builder().mode(MockPushPolicy.Mode.INTERVAL)
                        .initialDelayMs(50L).intervalMs(50L).repeat(1).build())
                .body("hello")
                .fault(MockFault.builder().mode(MockFault.Mode.EMPTY_RESPONSE).build())
                .build()));
        // SSE 是长连接，ofString 会等到流结束才返回：用有界流读出首批字节即可验证正常分支
        HttpResponse<java.io.InputStream> resp = client().send(HttpRequest.newBuilder()
                        .uri(URI.create("http://127.0.0.1:" + port + "/stream"))
                        .GET().build(),
                HttpResponse.BodyHandlers.ofInputStream());
        byte[] buffer = new byte[512];
        int read = resp.body().read(buffer);
        resp.body().close();
        assertTrue("SSE 路由不走故障注入,应按推送策略正常输出", read > 0);
        String firstChunk = new String(buffer, 0, read, java.nio.charset.StandardCharsets.UTF_8);
        assertTrue(firstChunk.contains("data: hello"));
        // 服务端有限推送后自关连接，留痕也是异步的：轮询等日志（最多 ~3s）
        String fault = null;
        for (int i = 0; i < 60 && (fault = latestLogFault()) == null; i++) {
            Thread.sleep(50L);
        }
        assertEquals("", fault);
    }

    /** MockFault builder 非法参数：抛出而非静默回落。 */
    @Test
    public void builderRejectsIllegalArguments() {
        expectThrows("概率越界",
                () -> MockFault.builder().mode(MockFault.Mode.EMPTY_RESPONSE)
                        .probabilityPercent(101).build());
        expectThrows("概率为负",
                () -> MockFault.builder().mode(MockFault.Mode.EMPTY_RESPONSE)
                        .probabilityPercent(-1).build());
        expectThrows("截断字节数为负",
                () -> MockFault.builder().mode(MockFault.Mode.TRUNCATED_BODY)
                        .truncateBytes(-5).build());
        expectThrows("截断比例越界",
                () -> MockFault.builder().mode(MockFault.Mode.TRUNCATED_BODY)
                        .truncatePercent(100).build());
        expectThrows("分块字节数为负",
                () -> MockFault.builder().mode(MockFault.Mode.SLOW_BODY)
                        .chunkBytes(-1).build());
        expectThrows("分块间隔为负",
                () -> MockFault.builder().mode(MockFault.Mode.SLOW_BODY)
                        .chunkDelayMs(-1L).build());
    }

    private static void expectThrows(String hint, Runnable throwing) {
        boolean thrown = false;
        try {
            throwing.run();
        } catch (IllegalArgumentException expected) {
            thrown = true;
        }
        assertTrue("应抛 IllegalArgumentException：" + hint, thrown);
    }

    /** 最近一条请求日志的故障标识；无日志返回 null。 */
    private String latestLogFault() {
        List<MockRequestLog> logs = server.snapshotLogs();
        return logs.isEmpty() ? null : logs.get(0).fault;
    }

    private static MockRoute route(String method, String path, MockFault fault) {
        return MockRoute.builder()
                .method(method).path(path).statusCode(200)
                .contentType("application/json; charset=utf-8")
                .body("正常响应体")
                .fault(fault)
                .build();
    }

    private HttpClient client() {
        return HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
    }

    private static int freePort() throws java.io.IOException {
        try (java.net.ServerSocket socket = new java.net.ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }
}
