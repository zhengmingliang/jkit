package com.alianga.jkit.http;

import com.alianga.jkit.HttpUtils;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.AfterClass;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.HttpCookie;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * P2 架构能力：拦截器链、耗时统计、总时长上限、手动重定向跟随（含重定向链 Cookie）、
 * RetryPolicy 抖动与 Retry-After、Cookie 域校验与容量、throwOnHttpError、BOM 剥离、
 * 请求级 execute。
 *
 * @author 郑明亮
 */
public class HttpP2ArchitectureTest {
    private static HttpServer server;
    private static String baseUrl;
    private static final AtomicInteger HITS_503 = new AtomicInteger();
    private static final AtomicInteger HITS_SLOW = new AtomicInteger();
    private static final List<String> COOKIE_HEADERS_SEEN = new CopyOnWriteArrayList<String>();

    @BeforeClass
    public static void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/ok", exchange -> {
            readAll(exchange.getRequestBody());
            send(exchange, 200, "text/plain", "ok".getBytes(StandardCharsets.UTF_8));
        });
        server.createContext("/echo-method", exchange -> {
            byte[] body = readAll(exchange.getRequestBody());
            send(exchange, 200, "text/plain",
                    (exchange.getRequestMethod() + ":" + body.length).getBytes(StandardCharsets.UTF_8));
        });
        server.createContext("/echo-cookie", exchange -> {
            readAll(exchange.getRequestBody());
            String cookie = exchange.getRequestHeaders().getFirst("Cookie");
            COOKIE_HEADERS_SEEN.add(cookie == null ? "" : cookie);
            send(exchange, 200, "text/plain", (cookie == null ? "" : cookie).getBytes(StandardCharsets.UTF_8));
        });
        // 第一跳：下发 cookie 后 302 到 /echo-cookie。旧实现由引擎内部跟随，这个 cookie 会丢
        server.createContext("/login", exchange -> {
            readAll(exchange.getRequestBody());
            exchange.getResponseHeaders().add("Set-Cookie", "SESSION=abc123; Path=/");
            exchange.getResponseHeaders().set("Location", "/echo-cookie");
            exchange.sendResponseHeaders(302, -1);
            exchange.close();
        });
        // 303 应把 POST 改成 GET 并丢弃正文
        server.createContext("/post-redirect", exchange -> {
            readAll(exchange.getRequestBody());
            exchange.getResponseHeaders().set("Location", "/echo-method");
            exchange.sendResponseHeaders(303, -1);
            exchange.close();
        });
        // 307 应保持方法与正文
        server.createContext("/post-redirect-307", exchange -> {
            readAll(exchange.getRequestBody());
            exchange.getResponseHeaders().set("Location", "/echo-method");
            exchange.sendResponseHeaders(307, -1);
            exchange.close();
        });
        server.createContext("/loop", exchange -> {
            readAll(exchange.getRequestBody());
            exchange.getResponseHeaders().set("Location", "/loop");
            exchange.sendResponseHeaders(302, -1);
            exchange.close();
        });
        server.createContext("/retry-after", exchange -> {
            readAll(exchange.getRequestBody());
            if (HITS_503.incrementAndGet() == 1) {
                exchange.getResponseHeaders().set("Retry-After", "0");
                send(exchange, 503, "text/plain", "busy".getBytes(StandardCharsets.UTF_8));
            } else {
                send(exchange, 200, "text/plain", "recovered".getBytes(StandardCharsets.UTF_8));
            }
        });
        server.createContext("/slow", exchange -> {
            readAll(exchange.getRequestBody());
            HITS_SLOW.incrementAndGet();
            try {
                Thread.sleep(400);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            send(exchange, 200, "text/plain", "slow".getBytes(StandardCharsets.UTF_8));
        });
        server.createContext("/bom", exchange -> {
            readAll(exchange.getRequestBody());
            byte[] bom = {(byte) 0xEF, (byte) 0xBB, (byte) 0xBF};
            byte[] text = "{\"a\":1}".getBytes(StandardCharsets.UTF_8);
            byte[] all = new byte[bom.length + text.length];
            System.arraycopy(bom, 0, all, 0, bom.length);
            System.arraycopy(text, 0, all, bom.length, text.length);
            send(exchange, 200, "application/json; charset=utf-8", all);
        });
        server.createContext("/boom", exchange -> {
            readAll(exchange.getRequestBody());
            send(exchange, 500, "application/json", "{\"error\":\"kaboom\"}".getBytes(StandardCharsets.UTF_8));
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
        HttpUtils.setConnectTimeout(60_000);
        HttpUtils.setReadTimeout(60_000);
        HttpUtils.setMaxBufferBytes(64L * 1024 * 1024);
        HttpUtils.config().setRetryPolicy(RetryPolicy.none())
                .setTotalTimeoutMs(0)
                .setMaxRedirects(5)
                .setThrowOnHttpError(false)
                .setExecutor(null)
                .clearInterceptors();
        HITS_503.set(0);
        HITS_SLOW.set(0);
        COOKIE_HEADERS_SEEN.clear();
    }

    // ---------- 拦截器 ----------

    @Test
    public void interceptorCanRewriteRequestAndObserveResponse() throws IOException {
        final List<Integer> observedCodes = new ArrayList<Integer>();
        HttpUtils.config().addInterceptor(chain -> {
            HttpRequest request = chain.request().header("X-Trace-Id", "trace-1");
            HttpResponse response = chain.proceed(request);
            observedCodes.add(response.code());
            return response;
        });
        assertEquals("ok", HttpUtils.get(baseUrl + "/ok"));
        assertEquals(Collections.singletonList(200), observedCodes);
    }

    @Test
    public void interceptorsRunInRegistrationOrderOutermostFirst() throws IOException {
        final List<String> order = new ArrayList<String>();
        HttpUtils.config().addInterceptor(chain -> {
            order.add("first-in");
            HttpResponse response = chain.proceed(chain.request());
            order.add("first-out");
            return response;
        });
        HttpUtils.config().addInterceptor(chain -> {
            order.add("second-in");
            HttpResponse response = chain.proceed(chain.request());
            order.add("second-out");
            return response;
        });
        HttpUtils.get(baseUrl + "/ok");
        assertEquals(Arrays.asList("first-in", "second-in", "second-out", "first-out"), order);
    }

    @Test
    public void interceptorCanShortCircuitWithoutNetwork() throws IOException {
        HttpUtils.config().addInterceptor(chain ->
                new HttpResponse(299, "short", chain.request().getUrl(), null,
                        HttpResponseBody.ofBytes("cached".getBytes(StandardCharsets.UTF_8), "text/plain")));
        assertEquals("cached", HttpUtils.get(baseUrl + "/ok"));
    }

    @Test
    public void interceptorSeesSingleProceedEvenWithRetries() throws IOException {
        HttpUtils.config().setRetryPolicy(RetryPolicy.builder()
                .maxAttempts(3).retryStatus(503).build());
        final AtomicInteger proceedCalls = new AtomicInteger();
        HttpUtils.config().addInterceptor(chain -> {
            proceedCalls.incrementAndGet();
            return chain.proceed(chain.request());
        });
        assertEquals("recovered", HttpUtils.get(baseUrl + "/retry-after"));
        assertEquals("拦截器包裹整个发送过程，重试不应让它被多次调用", 1, proceedCalls.get());
        assertEquals("底层应实际请求了 2 次", 2, HITS_503.get());
    }

    // ---------- 耗时与尝试次数 ----------

    @Test
    public void responseCarriesElapsedTimeAndAttempts() throws IOException {
        HttpResponse response = HttpRequest.get(baseUrl + "/ok").execute();
        try {
            assertTrue("应记录耗时，实际 " + response.elapsedMs(), response.elapsedMs() >= 0);
            assertEquals(1, response.attempts());
        } finally {
            response.close();
        }
    }

    @Test
    public void attemptsCountsRetries() throws IOException {
        HttpUtils.config().setRetryPolicy(RetryPolicy.builder()
                .maxAttempts(3).retryStatus(503).build());
        HttpResponse response = HttpRequest.get(baseUrl + "/retry-after").execute();
        try {
            assertEquals(200, response.code());
            assertEquals("首次 503 + 重试成功 = 2 次", 2, response.attempts());
        } finally {
            response.close();
        }
    }

    // ---------- 总时长上限 ----------

    @Test
    public void totalTimeoutStopsRetryStorm() {
        HttpUtils.config()
                .setRetryPolicy(RetryPolicy.builder().maxAttempts(5).retryOnIOException()
                        .initialBackoffMs(50).build())
                .setTotalTimeoutMs(200);
        long start = System.currentTimeMillis();
        try {
            HttpUtils.get(baseUrl + "/slow");
        } catch (IOException expected) {
            // 可能是总时长超时，也可能是被收敛后的读超时
        }
        long cost = System.currentTimeMillis() - start;
        assertTrue("总时长上限 200ms，实际耗时 " + cost + "ms 不应接近 5 次 400ms", cost < 1500);
    }

    @Test
    public void perRequestTotalTimeoutOverridesGlobal() {
        HttpUtils.config().setTotalTimeoutMs(0);
        long start = System.currentTimeMillis();
        try {
            HttpRequest.get(baseUrl + "/slow").totalTimeoutMs(100).execute().close();
        } catch (IOException expected) {
            // 期望超时
        }
        long cost = System.currentTimeMillis() - start;
        assertTrue("请求级总时长 100ms，实际 " + cost + "ms", cost < 1000);
    }

    @Test
    public void noTotalTimeoutMeansNoLimit() throws IOException {
        HttpUtils.config().setTotalTimeoutMs(0);
        assertEquals("slow", HttpUtils.get(baseUrl + "/slow"));
    }

    // ---------- 手动重定向跟随 ----------

    @Test
    public void cookieSetOnRedirectHopIsKeptAndResent() throws IOException {
        String body = HttpUtils.get(baseUrl + "/login");
        assertTrue("重定向前一跳下发的 Cookie 必须带到下一跳，实际收到: " + body,
                body.contains("SESSION=abc123"));
    }

    @Test
    public void responseReportsFinalUrlAfterRedirect() throws IOException {
        HttpResponse response = HttpRequest.get(baseUrl + "/login").execute();
        try {
            assertEquals(200, response.code());
            assertTrue("requestUrl 应是最终落地地址，实际 " + response.requestUrl(),
                    response.requestUrl().endsWith("/echo-cookie"));
        } finally {
            response.close();
        }
    }

    @Test
    public void redirect303TurnsPostIntoGetAndDropsBody() throws IOException {
        String result = HttpUtils.postJson(baseUrl + "/post-redirect",
                Collections.singletonMap("k", "v"));
        assertEquals("303 后应变成无正文的 GET", "GET:0", result);
    }

    @Test
    public void redirect307KeepsMethodAndBody() throws IOException {
        String result = HttpUtils.postJson(baseUrl + "/post-redirect-307",
                Collections.singletonMap("k", "v"));
        assertTrue("307 应保持 POST 与正文，实际 " + result, result.startsWith("POST:"));
        assertFalse("307 的正文不能是空的", result.equals("POST:0"));
    }

    @Test
    public void redirectLoopStopsAtMaxRedirects() throws IOException {
        HttpUtils.config().setMaxRedirects(3);
        HttpResponse response = HttpRequest.get(baseUrl + "/loop").execute();
        try {
            assertEquals("超过上限后应把最后一个 302 原样返回，而不是无限循环", 302, response.code());
        } finally {
            response.close();
        }
    }

    @Test
    public void followRedirectsFalseDisablesFollowing() throws IOException {
        HttpResponse response = HttpRequest.get(baseUrl + "/login").followRedirects(false).execute();
        try {
            assertEquals(302, response.code());
        } finally {
            response.close();
        }
    }

    // ---------- RetryPolicy ----------

    @Test
    public void retryAfterHeaderIsHonored() throws IOException {
        HttpUtils.config().setRetryPolicy(RetryPolicy.builder()
                .maxAttempts(2).retryStatus(503).initialBackoffMs(10_000).build());
        long start = System.currentTimeMillis();
        assertEquals("recovered", HttpUtils.get(baseUrl + "/retry-after"));
        long cost = System.currentTimeMillis() - start;
        assertTrue("Retry-After: 0 应压过 10s 的退避，实际等了 " + cost + "ms", cost < 3000);
    }

    @Test
    public void retryAfterParsesSecondsAndDate() {
        HttpResponse seconds = responseWithHeader("Retry-After", "5");
        assertEquals(5000L, RetryPolicy.parseRetryAfterMs(seconds));
        assertEquals(-1L, RetryPolicy.parseRetryAfterMs(responseWithHeader("X", "y")));
        assertEquals(-1L, RetryPolicy.parseRetryAfterMs(responseWithHeader("Retry-After", "abc")));
        assertEquals(-1L, RetryPolicy.parseRetryAfterMs(null));
        long dateBased = RetryPolicy.parseRetryAfterMs(
                responseWithHeader("Retry-After", "Wed, 21 Oct 2015 07:28:00 GMT"));
        assertEquals("已过去的 HTTP-date 应折算为 0", 0L, dateBased);
    }

    @Test
    public void backoffJitterKeepsValuesWithinBand() {
        RetryPolicy policy = RetryPolicy.builder()
                .maxAttempts(5).initialBackoffMs(1000).maxBackoffMs(1000).jitterFactor(0.2).build();
        boolean sawDifferent = false;
        long first = policy.backoffMs(1);
        for (int i = 0; i < 50; i++) {
            long value = policy.backoffMs(1);
            assertTrue("抖动后应落在 800–1200ms，实际 " + value, value >= 800 && value <= 1200);
            if (value != first) {
                sawDifferent = true;
            }
        }
        assertTrue("抖动应产生不同的等待时间", sawDifferent);
    }

    @Test
    public void defaultsPresetRetriesCommonTransientCodes() {
        RetryPolicy policy = RetryPolicy.defaults();
        HttpRequest get = HttpRequest.get("http://x/y");
        assertEquals(3, policy.getMaxAttempts());
        for (int code : new int[]{408, 429, 500, 502, 503, 504}) {
            assertTrue("应重试 " + code, policy.shouldRetry(get, code));
        }
        assertFalse("400 不该重试", policy.shouldRetry(get, 400));
        assertFalse("404 不该重试", policy.shouldRetry(get, 404));
        assertTrue(policy.getJitterFactor() > 0);
        assertTrue(policy.isRespectRetryAfter());
    }

    @Test
    public void postIsNotRetriedUnlessIdempotencyKeyOrConnectFailure() {
        RetryPolicy policy = RetryPolicy.defaults();
        assertFalse("POST 默认不重试状态码", policy.shouldRetry(HttpRequest.post("http://x"), 503));
        assertTrue("PUT 幂等，可重试", policy.shouldRetry(HttpRequest.put("http://x"), 503));
        assertTrue("DELETE 幂等，可重试", policy.shouldRetry(HttpRequest.delete("http://x"), 503));
        assertTrue("带 Idempotency-Key 的 POST 可重试",
                policy.shouldRetry(HttpRequest.post("http://x").header("Idempotency-Key", "k1"), 503));
        assertTrue("连接阶段失败的 POST 可安全重试",
                policy.shouldRetry(HttpRequest.post("http://x"), new java.net.ConnectException("refused")));
        assertFalse("读超时的 POST 不能盲目重试",
                policy.shouldRetry(HttpRequest.post("http://x"), new java.net.SocketTimeoutException("read")));
    }

    @Test
    public void endpointFailureClassificationSeparatesBusinessErrors() {
        assertTrue(RetryPolicy.isEndpointFailure(503));
        assertTrue(RetryPolicy.isEndpointFailure(500));
        assertTrue(RetryPolicy.isEndpointFailure(429));
        assertFalse("业务 404 不是节点故障", RetryPolicy.isEndpointFailure(404));
        assertFalse("鉴权失败不是节点故障", RetryPolicy.isEndpointFailure(401));
        assertFalse(RetryPolicy.isEndpointFailure(400));
        assertTrue(RetryPolicy.isEndpointFailure(new java.net.ConnectException("x"), true));
        assertTrue(RetryPolicy.isEndpointFailure(new java.net.SocketTimeoutException("x"), true));
        assertFalse("关闭读超时计数时，读超时不算节点故障",
                RetryPolicy.isEndpointFailure(new java.net.SocketTimeoutException("x"), false));
    }

    // ---------- Cookie 安全 ----------

    @Test
    public void rejectsCookieForUnrelatedDomain() {
        CookieJarImpl jar = new CookieJarImpl();
        HttpCookie evil = new HttpCookie("SESSION", "stolen");
        evil.setDomain(".example.com");
        evil.setPath("/");
        jar.saveFromResponse(URI.create("http://attacker.test/x"), Collections.singletonList(evil));
        assertEquals("不得接受与请求主机无关的 Domain", 0, jar.size());
    }

    @Test
    public void rejectsCookieForPublicSuffix() {
        CookieJarImpl jar = new CookieJarImpl();
        HttpCookie wide = new HttpCookie("TRACK", "1");
        wide.setDomain(".co.uk");
        wide.setPath("/");
        jar.saveFromResponse(URI.create("http://shop.co.uk/x"), Collections.singletonList(wide));
        assertEquals("不得接受公共后缀级别的 Domain", 0, jar.size());

        assertTrue(CookieJarImpl.isPublicSuffix("co.uk"));
        assertTrue(CookieJarImpl.isPublicSuffix("com.cn"));
        assertTrue(CookieJarImpl.isPublicSuffix("com"));
        assertTrue(CookieJarImpl.isPublicSuffix("localhost"));
        assertFalse(CookieJarImpl.isPublicSuffix("example.com"));
        assertFalse(CookieJarImpl.isPublicSuffix("shop.co.uk"));
        assertFalse("IP 不算公共后缀", CookieJarImpl.isPublicSuffix("127.0.0.1"));
    }

    @Test
    public void acceptsLegitimateParentDomainCookie() {
        CookieJarImpl jar = new CookieJarImpl();
        HttpCookie ok = new HttpCookie("SESSION", "v");
        ok.setDomain(".example.com");
        ok.setPath("/");
        jar.saveFromResponse(URI.create("http://api.example.com/x"), Collections.singletonList(ok));
        assertEquals(1, jar.size());
        assertFalse(jar.loadForRequest(URI.create("http://api.example.com/y")).isEmpty());
    }

    @Test
    public void cookieJarEnforcesCapacity() {
        CookieJarImpl jar = new CookieJarImpl();
        jar.setMaxCookies(10);
        for (int i = 0; i < 50; i++) {
            HttpCookie cookie = new HttpCookie("c" + i, "v");
            cookie.setDomain("example.com");
            cookie.setPath("/");
            jar.saveFromResponse(URI.create("http://example.com/x"), Collections.singletonList(cookie));
        }
        assertEquals("超出上限应淘汰最旧条目", 10, jar.size());
        assertEquals(10, jar.getMaxCookies());
    }

    // ---------- throwOnHttpError / BOM ----------

    @Test
    public void throwOnHttpErrorTurnsErrorBodyIntoException() {
        HttpUtils.config().setThrowOnHttpError(true);
        try {
            HttpUtils.get(baseUrl + "/boom");
            fail("开启 throwOnHttpError 后 500 应抛出 HttpStatusException");
        } catch (HttpStatusException e) {
            assertEquals(500, e.getStatusCode());
            assertNotNull(e.getBodySnippet());
            assertTrue("异常应带上错误正文: " + e.getMessage(), e.getMessage().contains("kaboom"));
        } catch (IOException e) {
            fail("应是 HttpStatusException，实际 " + e);
        }
    }

    @Test
    public void throwOnHttpErrorDefaultsOffForBackwardCompatibility() throws IOException {
        assertFalse(HttpUtils.config().isThrowOnHttpError());
        assertTrue("默认仍把错误正文作为返回值",
                HttpUtils.get(baseUrl + "/boom").contains("kaboom"));
    }

    @Test
    public void bomIsStrippedFromStringBody() throws IOException {
        String body = HttpUtils.get(baseUrl + "/bom");
        assertEquals("UTF-8 BOM 必须被剥离", "{\"a\":1}", body);
        assertFalse(body.startsWith("\uFEFF"));
    }

    // ---------- 请求级 API 与线程池 ----------

    @Test
    public void requestLevelExecuteAndExecuteAsyncWork() throws Exception {
        HttpResponse sync = HttpRequest.get(baseUrl + "/ok").execute();
        try {
            assertEquals(200, sync.code());
        } finally {
            sync.close();
        }
        Future<HttpResponse> future = HttpRequest.get(baseUrl + "/ok").executeAsync();
        HttpResponse async = future.get();
        try {
            assertEquals(200, async.code());
        } finally {
            async.close();
        }
    }

    @Test
    public void customExecutorIsUsedForAsyncWork() throws Exception {
        final AtomicInteger submitted = new AtomicInteger();
        ExecutorService custom = Executors.newSingleThreadExecutor(r -> {
            submitted.incrementAndGet();
            Thread t = new Thread(r, "p2-custom-pool");
            t.setDaemon(true);
            return t;
        });
        try {
            HttpUtils.config().setExecutor(custom);
            assertEquals(custom, HttpUtils.asyncExecutor());
            HttpResponse response = HttpRequest.get(baseUrl + "/ok").executeAsync().get();
            response.close();
            assertTrue("异步任务应跑在自定义线程池上", submitted.get() > 0);
        } finally {
            HttpUtils.config().setExecutor(null);
            custom.shutdownNow();
        }
        assertNotNull("清空后回落到内置池", HttpUtils.asyncExecutor());
    }

    // ---------- URL 工具 ----------

    @Test
    public void redirectResolutionHandlesRelativeAndProtocolRelative() {
        assertEquals("http://a.com/b/c",
                HttpIo.resolveRedirect("http://a.com/b/x", "c"));
        assertEquals("http://a.com/abs",
                HttpIo.resolveRedirect("http://a.com/b/x", "/abs"));
        assertEquals("https://other.com/p",
                HttpIo.resolveRedirect("https://a.com/b", "//other.com/p"));
        assertEquals("https://x.com/y",
                HttpIo.resolveRedirect("http://a.com/b", "https://x.com/y"));
        assertNull("非 http(s) 协议不跟随",
                HttpIo.resolveRedirect("http://a.com/b", "file:///etc/passwd"));
        assertNull(HttpIo.resolveRedirect("http://a.com/b", ""));
        assertNull(HttpIo.resolveRedirect("http://a.com/b", null));
    }

    @Test
    public void sameOriginComparesSchemeHostAndEffectivePort() {
        assertTrue(HttpIo.sameOrigin("http://a.com/x", "http://a.com/y"));
        assertTrue("默认端口应与显式默认端口等价",
                HttpIo.sameOrigin("http://a.com/x", "http://a.com:80/y"));
        assertTrue(HttpIo.sameOrigin("https://a.com/x", "https://a.com:443/y"));
        assertFalse(HttpIo.sameOrigin("http://a.com/x", "https://a.com/x"));
        assertFalse(HttpIo.sameOrigin("http://a.com/x", "http://b.com/x"));
        assertFalse(HttpIo.sameOrigin("http://a.com/x", "http://a.com:8080/x"));
        assertFalse(HttpIo.sameOrigin(null, "http://a.com"));
    }

    private static HttpResponse responseWithHeader(String name, String value) {
        java.util.Map<String, List<String>> headers = new java.util.LinkedHashMap<String, List<String>>();
        headers.put(name, Collections.singletonList(value));
        return new HttpResponse(503, "x", "http://x/y", headers,
                HttpResponseBody.ofBytes(new byte[0], "text/plain"));
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
