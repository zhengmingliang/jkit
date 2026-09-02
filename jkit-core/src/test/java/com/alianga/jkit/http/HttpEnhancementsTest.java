package com.alianga.jkit.http;

import com.alianga.jkit.HttpUtils;
import com.alianga.jkit.json.JSONNode;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.AfterClass;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpCookie;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.zip.DeflaterOutputStream;
import java.util.zip.GZIPOutputStream;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * HTTP 功能增强测试：请求模型、响应体、CookieJar、SSE 重连、原子下载。
 *
 * @author 郑明亮
 */
public class HttpEnhancementsTest {
    private static HttpServer server;
    private static String baseUrl;
    private static File workDir;

    @BeforeClass
    public static void startServer() throws IOException {
        workDir = new File("target/http-enhancements-test");
        workDir.mkdirs();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/q", exchange -> {
            readAll(exchange.getRequestBody());
            String raw = exchange.getRequestURI().getRawQuery();
            send(exchange, 200, "text/plain; charset=utf-8", raw == null ? "" : raw);
        });
        server.createContext("/echo-headers", HttpEnhancementsTest::echoHeaders);
        server.createContext("/echo-body", exchange -> {
            byte[] body = readAll(exchange.getRequestBody());
            send(exchange, 200, "text/plain; charset=utf-8", new String(body, StandardCharsets.UTF_8));
        });
        server.createContext("/json", exchange -> {
            readAll(exchange.getRequestBody());
            send(exchange, 200, "application/json; charset=utf-8",
                    "{\"name\":\"Tom\",\"age\":18,\"tags\":[\"a\",\"b\"]}");
        });
        server.createContext("/empty", exchange -> {
            readAll(exchange.getRequestBody());
            exchange.getResponseHeaders().set("Content-Type", "text/plain; charset=utf-8");
            exchange.sendResponseHeaders(200, -1);
            exchange.close();
        });
        server.createContext("/deflate", exchange -> {
            readAll(exchange.getRequestBody());
            byte[] compressed = deflate("deflated-content");
            exchange.getResponseHeaders().set("Content-Encoding", "deflate");
            send(exchange, 200, "text/plain; charset=utf-8", compressed);
        });
        server.createContext("/gzip", exchange -> {
            readAll(exchange.getRequestBody());
            byte[] compressed = gzip("gzipped-content");
            exchange.getResponseHeaders().set("Content-Encoding", "gzip");
            send(exchange, 200, "text/plain; charset=utf-8", compressed);
        });
        server.createContext("/br", exchange -> {
            readAll(exchange.getRequestBody());
            byte[] compressed = new byte[]{(byte) 139, 5, (byte) 128, 98, 114, 111, 116, 108, 105, 45, 104, 101,
                    108, 108, 111, 3};
            exchange.getResponseHeaders().set("Content-Encoding", "br");
            send(exchange, 200, "text/plain; charset=utf-8", compressed);
        });
        server.createContext("/sse-loop", HttpEnhancementsTest::sseLoop);
        server.createContext("/file", exchange -> {
            readAll(exchange.getRequestBody());
            send(exchange, 200, "application/octet-stream", "atomic-download-content-123");
        });
        server.start();
        baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @AfterClass
    public static void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Before
    public void reset() {
        HttpUtils.fakeIp = false;
        HttpUtils.setProxy(null);
        HttpUtils.setCookieJar(new CookieJarImpl());
        HttpUtils.supportHttps();
        HttpUtils.setMaxBufferBytes(64L * 1024 * 1024);
        HttpUtils.setConnectTimeout(60_000);
        HttpUtils.setReadTimeout(60_000);
        HttpUtils.config().setRetryPolicy(RetryPolicy.none());
    }

    @Test
    public void queryOnRequestIsEncodedAndMerged() throws Exception {
        HttpRequest request = HttpRequest.get(baseUrl + "/q")
                .query("name", "张三")
                .query("age", 18)
                .query("skip", null)
                .query("ids", Arrays.asList("1", "2"));
        HttpResponse response = HttpUtils.execute(request);
        try {
            String query = response.body().string();
            assertTrue(query.contains("name=%E5%BC%A0%E4%B8%89"));
            assertTrue(query.contains("age=18"));
            assertFalse(query.contains("skip="));
            assertTrue(query.contains("ids=1") && query.contains("ids=2"));
        } finally {
            response.close();
        }
    }

    @Test
    public void perRequestCookiesTakePriority() throws Exception {
        HttpRequest request = HttpRequest.get(baseUrl + "/echo-headers")
                .cookie("token", "abc")
                .cookie("trace", "1");
        HttpResponse response = HttpUtils.execute(request);
        try {
            String headers = response.body().string();
            assertTrue(headers.contains("Cookie=token=abc"));
            assertTrue(headers.contains("trace=1"));
        } finally {
            response.close();
        }
    }

    @Test
    public void commonHeaderShortcutsAndConditionalHeaders() throws Exception {
        HttpRequest request = HttpRequest.get(baseUrl + "/echo-headers")
                .userAgent("jkit-agent")
                .accept("application/json")
                .acceptLanguage("zh-CN")
                .referer(baseUrl + "/from")
                .bearerToken("tok-1")
                .ifNoneMatch("W/\"v1\"")
                .ifModifiedSince("Wed, 21 Oct 2026 07:28:00 GMT")
                .range(2, 5);
        assertEquals("W/\"v1\"", request.getHeader("If-None-Match"));
        assertEquals("bytes=2-5", request.getHeader("Range"));
        assertEquals("bytes=-100", HttpRequest.get(baseUrl).rangeSuffix(100).getHeader("Range"));
        assertEquals("tag-x", HttpRequest.get(baseUrl).tag("tag-x").getTag());

        HttpResponse response = HttpUtils.execute(request);
        try {
            String headers = response.body().string().toLowerCase();
            assertTrue(headers.contains("user-agent=jkit-agent"));
            assertTrue(headers.contains("accept=application/json"));
            assertTrue(headers.contains("accept-language=zh-cn"));
            assertTrue(headers.contains("referer="));
            assertTrue(headers.contains("authorization=bearer tok-1"));
            assertTrue(headers.contains("if-none-match=w/\"v1\""));
            assertTrue(headers.contains("if-modified-since=wed, 21 oct 2026"));
            assertTrue(headers.contains("range=bytes=2-5"));
        } finally {
            response.close();
        }
    }

    @Test
    public void stringBodyWithCharset() throws Exception {
        HttpRequest request = HttpRequest.post(baseUrl + "/echo-body")
                .body("中文内容", StandardCharsets.UTF_8);
        HttpResponse response = HttpUtils.execute(request);
        try {
            assertEquals("中文内容", response.body().string());
        } finally {
            response.close();
        }
    }

    @Test
    public void responseBodyJsonParseAndCharset() throws Exception {
        HttpResponse response = HttpUtils.getResponse(baseUrl + "/json");
        try {
            assertEquals(StandardCharsets.UTF_8, response.body().charset());
            assertFalse(response.body().isEmpty());

            java.util.Map map = response.body().json(java.util.Map.class);
            assertEquals("Tom", map.get("name"));

            java.util.Map<String, Object> jsonMap = response.body().jsonMap();
            assertEquals(18, ((Number) jsonMap.get("age")).intValue());

            JSONNode node = response.body().jsonNode();
            assertNotNull(node);
            assertEquals("Tom", node.getChildValue("name", String.class));
        } finally {
            response.close();
        }
    }

    @Test
    public void responseBodyTransferAndSave() throws Exception {
        HttpResponse response = HttpUtils.getResponse(baseUrl + "/json");
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        long copied;
        try {
            copied = response.body().transferTo(out);
        } finally {
            response.close();
        }
        assertTrue(copied > 0);
        assertTrue(new String(out.toByteArray(), StandardCharsets.UTF_8).contains("Tom"));

        File saved = new File(workDir, "body.json");
        HttpResponse second = HttpUtils.getResponse(baseUrl + "/json");
        try {
            assertEquals(saved, second.body().saveTo(saved));
        } finally {
            second.close();
        }
        assertTrue(saved.isFile() && saved.length() > 0);

        HttpResponse empty = HttpUtils.getResponse(baseUrl + "/empty");
        try {
            assertTrue(empty.body().isEmpty());
        } finally {
            empty.close();
        }
    }

    @Test
    public void streamingBodyCannotBeConsumedTwice() throws Exception {
        HttpRequest request = HttpRequest.get(baseUrl + "/json").streamResponse(true);
        HttpResponse response = HttpUtils.execute(request);
        try {
            byte[] first = response.body().bytes();
            assertTrue(first.length > 0);
            try {
                response.body().string();
                fail("expected IllegalStateException on double read");
            } catch (IllegalStateException e) {
                assertTrue(e.getMessage().contains("不能重复读取"));
            }
        } finally {
            response.close();
        }
    }

    @Test
    public void acceptEncodingKeepsBrotli() throws Exception {
        HttpRequest request = HttpRequest.get(baseUrl + "/echo-headers")
                .header("Accept-Encoding", "gzip, deflate, br");
        HttpResponse response = HttpUtils.execute(request);
        try {
            String body = response.body().string().toLowerCase();
            assertTrue(body.contains("gzip"));
            assertTrue(body.contains("br"));
        } finally {
            response.close();
        }
    }

    @Test
    public void deflateAndGzipDecoding() throws Exception {
        HttpResponse deflateResponse = HttpUtils.getResponse(baseUrl + "/deflate");
        try {
            assertEquals("deflated-content", deflateResponse.body().string());
        } finally {
            deflateResponse.close();
        }
        HttpResponse gzipResponse = HttpUtils.getResponse(baseUrl + "/gzip");
        try {
            assertEquals("gzipped-content", gzipResponse.body().string());
        } finally {
            gzipResponse.close();
        }
        HttpResponse brResponse = HttpUtils.getResponse(baseUrl + "/br");
        try {
            assertEquals("brotli-hello", brResponse.body().string());
        } finally {
            brResponse.close();
        }
    }

    @Test
    public void cookieJarFilteringByDomainPathSecure() {
        CookieJarImpl jar = new CookieJarImpl();
        URI uri = URI.create("http://example.com/a/b");
        List<HttpCookie> cookies = HttpIo.parseCookies(Arrays.asList(
                "session=1; Path=/",
                "wide=2; Domain=example.com; Path=/a",
                "secret=3; Secure; Path=/",
                "short=4; Max-Age=1; Path=/",
                "gone=5; Max-Age=0; Path=/"));
        jar.saveFromResponse(uri, cookies);

        List<HttpCookie> matched = jar.loadForRequest(URI.create("http://example.com/a/c"));
        assertNotNull(byName(matched, "session"));
        assertNotNull(byName(matched, "wide"));
        assertNull(byName(matched, "secret"));
        assertNull(byName(matched, "gone"));

        List<HttpCookie> secureMatched = jar.loadForRequest(URI.create("https://example.com/a/c"));
        assertNotNull(byName(secureMatched, "secret"));

        assertNull(byName(jar.loadForRequest(URI.create("http://other.com/a/c")), "session"));
        assertNull(byName(jar.loadForRequest(URI.create("http://example.com/x/y")), "wide"));
    }

    @Test
    public void cookieJarExpiryCleanup() throws Exception {
        CookieJarImpl jar = new CookieJarImpl();
        URI uri = URI.create("http://example.com/");
        jar.saveFromResponse(uri, HttpIo.parseCookies(Arrays.asList("old=1; Max-Age=1; Path=/")));
        assertNotNull(byName(jar.loadForRequest(uri), "old"));

        File netscape = new File(workDir, "expired.txt");
        try (java.io.PrintWriter writer = new java.io.PrintWriter(netscape, "UTF-8")) {
            writer.println("# Netscape HTTP Cookie File");
            writer.println("example.com\tTRUE\t/\tFALSE\t1000\tstale\t9");
        }
        CookieJarImpl revived = new CookieJarImpl();
        revived.importNetscape(netscape);
        assertNull(byName(revived.loadForRequest(uri), "stale"));
    }

    @Test
    public void cookieJarNetscapeAndJsonRoundTrip() throws Exception {
        CookieJarImpl jar = new CookieJarImpl();
        jar.saveFromResponse(URI.create("http://example.com/login"),
                HttpIo.parseCookies(Arrays.asList("sid=abc; Path=/; HttpOnly", "pref=dark; Domain=.example.com; Path=/")));

        File netscape = new File(workDir, "cookies.txt");
        jar.exportNetscape(netscape);
        CookieJarImpl fromNetscape = new CookieJarImpl().importNetscape(netscape);
        List<HttpCookie> restored = fromNetscape.loadForRequest(URI.create("http://example.com/login"));
        HttpCookie sid = byName(restored, "sid");
        assertNotNull(sid);
        assertEquals("abc", sid.getValue());
        assertNotNull(byName(restored, "pref"));

        File jsonFile = new File(workDir, "cookies.json");
        jar.saveTo(jsonFile);
        CookieJarImpl fromJson = new CookieJarImpl().loadFrom(jsonFile);
        HttpCookie jsonSid = byName(fromJson.loadForRequest(URI.create("http://example.com/login")), "sid");
        assertNotNull(jsonSid);
        assertEquals("abc", jsonSid.getValue());
        assertTrue("JSON 持久化应保留 HttpOnly", jsonSid.isHttpOnly());
        assertEquals(2, fromJson.size());

        assertTrue(fromJson.remove("sid"));
        assertNull(byName(fromJson.loadForRequest(URI.create("http://example.com/login")), "sid"));
        fromJson.clear();
        assertEquals(0, fromJson.size());
    }

    @Test
    public void cookieJarRemoveByDomainIgnoresLeadingDotAndCase() {
        CookieJarImpl jar = new CookieJarImpl();
        jar.saveFromResponse(URI.create("http://example.com/login"),
                HttpIo.parseCookies(Arrays.asList("pref=dark; Domain=.example.com; Path=/")));

        // 传入不带前导点的域也能删除存储为 .example.com 的 Cookie
        assertTrue(jar.remove("pref", "EXAMPLE.com"));
        assertNull(byName(jar.loadForRequest(URI.create("http://example.com/login")), "pref"));
        assertFalse(jar.remove("pref", "example.com"));
    }

    @Test
    public void sseReconnectCarriesLastEventId() throws Exception {
        CountDownLatch closed = new CountDownLatch(1);
        CopyOnWriteArrayList<SseEvent> events = new CopyOnWriteArrayList<SseEvent>();
        CopyOnWriteArrayList<Integer> reconnects = new CopyOnWriteArrayList<Integer>();
        SseReconnectOptions options = SseReconnectOptions.builder()
                .maxRetries(2)
                .initialBackoffMs(10)
                .build();
        HttpCall call = HttpUtils.sseReconnect(baseUrl + "/sse-loop", options, new SseListener() {
            @Override
            public void onEvent(SseEvent event) {
                events.add(event);
            }

            @Override
            public void onReconnect(int attempt, long delayMs, Throwable cause) {
                reconnects.add(attempt);
            }

            @Override
            public void onClosed() {
                closed.countDown();
            }
        });
        assertTrue("sse reconnect timed out", closed.await(15, TimeUnit.SECONDS));
        assertNotNull(call);
        assertFalse(events.isEmpty());
        assertEquals("first", events.get(0).getData());
        assertTrue("should receive resumed event, got " + events, containsData(events, "resumed:e1"));
        assertTrue("should receive done event, got " + events, containsData(events, "done"));
        assertFalse(reconnects.isEmpty());
    }

    @Test
    public void sseReconnectStopsOnCancel() throws Exception {
        CountDownLatch closed = new CountDownLatch(1);
        CopyOnWriteArrayList<SseEvent> events = new CopyOnWriteArrayList<SseEvent>();
        final HttpCall[] handle = new HttpCall[1];
        handle[0] = HttpUtils.sseReconnect(baseUrl + "/sse-loop",
                SseReconnectOptions.builder().maxRetries(-1).initialBackoffMs(50).build(),
                new SseListener() {
                    @Override
                    public void onEvent(SseEvent event) {
                        events.add(event);
                        if (handle[0] != null && "done".equals(event.getData())) {
                            handle[0].cancel();
                        }
                    }

                    @Override
                    public void onClosed() {
                        closed.countDown();
                    }
                });
        assertTrue("sse cancel timed out", closed.await(15, TimeUnit.SECONDS));
        assertTrue(events.size() >= 2);
    }

    @Test
    public void sseReconnectFromHttpRequest() throws Exception {
        CountDownLatch closed = new CountDownLatch(1);
        CopyOnWriteArrayList<SseEvent> events = new CopyOnWriteArrayList<SseEvent>();
        HttpRequest request = HttpRequest.get(baseUrl + "/sse-loop")
                .header("X-Trace", "reconnect");
        HttpCall call = HttpUtils.sseReconnect(request,
                SseReconnectOptions.builder().maxRetries(2).initialBackoffMs(10).build(),
                new SseListener() {
                    @Override
                    public void onEvent(SseEvent event) {
                        events.add(event);
                    }

                    @Override
                    public void onClosed() {
                        closed.countDown();
                    }
                });
        assertTrue("sse reconnect from request timed out", closed.await(15, TimeUnit.SECONDS));
        assertNotNull(call);
        assertEquals("first", events.get(0).getData());
        assertTrue(containsData(events, "resumed:e1"));
        assertTrue(containsData(events, "done"));
        assertNull(request.getHeader("Last-Event-ID"));
        assertNull(request.getHeader("Accept"));
    }

    @Test
    public void downloadAtomicVerifiesSha256() throws Exception {
        byte[] content = "atomic-download-content-123".getBytes(StandardCharsets.UTF_8);
        String sha256 = sha256Hex(content);
        File dest = new File(workDir, "atomic-ok.bin");
        String path = HttpUtils.downloadAtomic(baseUrl + "/file", dest, sha256);
        assertEquals(dest.getAbsolutePath(), path);
        assertEquals("atomic-download-content-123",
                new String(Files.readAllBytes(dest.toPath()), StandardCharsets.UTF_8));
        assertEquals(0, workDir.listFiles((dir, name) -> name.endsWith(".part")).length);

        File badDest = new File(workDir, "atomic-bad.bin");
        try {
            HttpUtils.downloadAtomic(baseUrl + "/file", badDest,
                    "0000000000000000000000000000000000000000000000000000000000000000");
            fail("expected checksum mismatch");
        } catch (IOException e) {
            assertTrue(e.getMessage().contains("SHA-256"));
        }
        assertFalse(badDest.exists());
        assertEquals(0, workDir.listFiles((dir, name) -> name.endsWith(".part")).length);
    }

    private static HttpCookie byName(List<HttpCookie> cookies, String name) {
        for (HttpCookie cookie : cookies) {
            if (cookie.getName().equals(name)) {
                return cookie;
            }
        }
        return null;
    }

    private static boolean containsData(List<SseEvent> events, String data) {
        for (SseEvent event : events) {
            if (data.equals(event.getData())) {
                return true;
            }
        }
        return false;
    }

    private static String sha256Hex(byte[] content) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        StringBuilder builder = new StringBuilder();
        for (byte b : digest.digest(content)) {
            builder.append(String.format("%02x", b));
        }
        return builder.toString();
    }

    private static byte[] deflate(String text) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        DeflaterOutputStream out = new DeflaterOutputStream(bos);
        out.write(text.getBytes(StandardCharsets.UTF_8));
        out.finish();
        out.close();
        return bos.toByteArray();
    }

    private static byte[] gzip(String text) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        GZIPOutputStream out = new GZIPOutputStream(bos);
        out.write(text.getBytes(StandardCharsets.UTF_8));
        out.finish();
        out.close();
        return bos.toByteArray();
    }

    private static void echoHeaders(HttpExchange exchange) throws IOException {
        readAll(exchange.getRequestBody());
        StringBuilder builder = new StringBuilder();
        for (java.util.Map.Entry<String, List<String>> entry : exchange.getRequestHeaders().entrySet()) {
            if (entry.getValue() == null || entry.getValue().isEmpty()) {
                continue;
            }
            builder.append(entry.getKey()).append('=').append(entry.getValue().get(0)).append('\n');
        }
        send(exchange, 200, "text/plain; charset=utf-8", builder.toString());
    }

    /**
     * SSE 重连测试端点：首次连接发 {@code id: e1} 事件后关闭；
     * 带 {@code Last-Event-ID: e1} 的重连发恢复事件与 {@code done} 事件。
     */
    private static void sseLoop(HttpExchange exchange) throws IOException {
        readAll(exchange.getRequestBody());
        String lastEventId = exchange.getRequestHeaders().getFirst("Last-Event-ID");
        exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
        exchange.sendResponseHeaders(200, 0);
        OutputStream os = exchange.getResponseBody();
        if (lastEventId != null && !lastEventId.isEmpty()) {
            os.write("data: resumed:".concat(lastEventId).concat("\n\n").getBytes(StandardCharsets.UTF_8));
            os.write("id: final\ndata: done\n\n".getBytes(StandardCharsets.UTF_8));
        } else {
            os.write("id: e1\ndata: first\n\n".getBytes(StandardCharsets.UTF_8));
        }
        os.close();
    }

    private static void send(HttpExchange exchange, int code, String contentType, String body) throws IOException {
        send(exchange, code, contentType, body.getBytes(StandardCharsets.UTF_8));
    }

    private static void send(HttpExchange exchange, int code, String contentType, byte[] body) throws IOException {
        if (contentType != null) {
            exchange.getResponseHeaders().set("Content-Type", contentType);
        }
        exchange.sendResponseHeaders(code, body.length);
        exchange.getResponseBody().write(body);
        exchange.close();
    }

    private static byte[] readAll(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[1024];
        int n;
        while ((n = in.read(buf)) != -1) {
            out.write(buf, 0, n);
        }
        return out.toByteArray();
    }
}
