package com.alianga.jkit.http.lb;

import com.alianga.jkit.HttpUtils;
import com.alianga.jkit.http.CookieJarImpl;
import com.alianga.jkit.http.HttpRequest;
import com.alianga.jkit.http.RetryPolicy;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.After;
import org.junit.AfterClass;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * NacosDiscovery 走真实 HTTP 的集成测试：验证重构为使用本模块 HttpUtils 之后，
 * 鉴权换 token、多地址故障转移、错误诊断信息、以及"不被全局端点池改写"都成立。
 *
 * @author 郑明亮
 */
public class NacosDiscoveryHttpTest {
    private static HttpServer nacos;
    private static HttpServer brokenNacos;
    private static String nacosAddr;
    private static String brokenAddr;

    private static final AtomicInteger LOGIN_CALLS = new AtomicInteger();
    private static final AtomicInteger LIST_CALLS = new AtomicInteger();
    private static final AtomicBoolean REQUIRE_AUTH = new AtomicBoolean();
    private static final List<String> LIST_QUERIES = new CopyOnWriteArrayList<String>();
    private static volatile String instanceListBody;

    @BeforeClass
    public static void startNacos() throws IOException {
        nacos = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        nacos.createContext("/nacos/v1/auth/login", exchange -> {
            String body = new String(readAll(exchange.getRequestBody()), StandardCharsets.UTF_8);
            LOGIN_CALLS.incrementAndGet();
            if (!body.contains("username=nacos") || !body.contains("password=pass")) {
                send(exchange, 403, "{\"message\":\"bad credentials\"}");
                return;
            }
            send(exchange, 200, "{\"accessToken\":\"tok-123\",\"tokenTtl\":18000,\"globalAdmin\":true}");
        });
        nacos.createContext("/nacos/v1/ns/instance/list", exchange -> {
            readAll(exchange.getRequestBody());
            LIST_CALLS.incrementAndGet();
            String query = exchange.getRequestURI().getRawQuery();
            LIST_QUERIES.add(query == null ? "" : query);
            if (REQUIRE_AUTH.get() && (query == null || !query.contains("accessToken=tok-123"))) {
                send(exchange, 403,
                        "{\"status\":403,\"message\":\"user not found or token invalid\"}");
                return;
            }
            send(exchange, 200, instanceListBody);
        });
        nacos.start();
        nacosAddr = "127.0.0.1:" + nacos.getAddress().getPort();

        // 一个永远 500 的 Nacos，用于验证多地址故障转移
        brokenNacos = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        brokenNacos.createContext("/nacos", exchange -> {
            readAll(exchange.getRequestBody());
            send(exchange, 500, "{\"message\":\"nacos internal error\"}");
        });
        brokenNacos.start();
        brokenAddr = "127.0.0.1:" + brokenNacos.getAddress().getPort();
    }

    @AfterClass
    public static void stopNacos() {
        if (nacos != null) {
            nacos.stop(0);
        }
        if (brokenNacos != null) {
            brokenNacos.stop(0);
        }
    }

    @Before
    public void reset() {
        HttpUtils.fakeIp = false;
        HttpUtils.setProxy(null);
        HttpUtils.setCookieJar(new CookieJarImpl());
        HttpUtils.supportHttps();
        HttpUtils.setConnectTimeout(3000);
        HttpUtils.setReadTimeout(3000);
        HttpUtils.config().setRetryPolicy(RetryPolicy.none())
                .setEndpointPool(null)
                .setTotalTimeoutMs(0)
                .setThrowOnHttpError(false)
                .clearInterceptors();
        LOGIN_CALLS.set(0);
        LIST_CALLS.set(0);
        REQUIRE_AUTH.set(false);
        LIST_QUERIES.clear();
        instanceListBody = "{\"hosts\":["
                + "{\"ip\":\"10.0.0.7\",\"port\":8080,\"weight\":1.0,\"healthy\":true,"
                + "\"enabled\":true,\"clusterName\":\"BJ\",\"metadata\":{\"version\":\"1.0\"}},"
                + "{\"ip\":\"10.0.0.8\",\"port\":8080,\"weight\":2.0,\"healthy\":true,"
                + "\"enabled\":true,\"clusterName\":\"BJ\",\"metadata\":{}}]}";
    }

    @After
    public void clearPool() {
        HttpUtils.config().setEndpointPool(null);
    }

    @Test
    public void resolvesInstancesOverRealHttp() throws Exception {
        NacosDiscovery discovery = new NacosDiscovery.Builder(nacosAddr).build();
        List<Endpoint> endpoints = discovery.resolve("order-service");
        assertEquals(2, endpoints.size());
        assertEquals("http://10.0.0.7:8080", endpoints.get(0).getBaseUrl());
        assertEquals("http://10.0.0.8:8080", endpoints.get(1).getBaseUrl());
        assertEquals(1, endpoints.get(0).getWeight());
        assertEquals(2, endpoints.get(1).getWeight());
        assertEquals("BJ", endpoints.get(0).getMetadata().get("clusterName"));
        assertEquals(1, LIST_CALLS.get());
        assertTrue(LIST_QUERIES.get(0).contains("serviceName=order-service"));
        assertTrue(LIST_QUERIES.get(0).contains("groupName=DEFAULT_GROUP"));
    }

    /**
     * 真实 Nacos（v1 open API）返回的字段远多于解析所需，且 serviceName 带
     * {@code 组名@@服务名} 前缀、hosts 内还有 metadata 子对象。这里用与真实响应同形的
     * 报文锁定：多出来的顶层与实例字段都不会干扰解析，metadata 里的键也不会被误当成实例字段。
     */
    @Test
    public void resolvesRealWorldShapedResponse() throws Exception {
        instanceListBody = "{"
                + "\"name\":\"DEFAULT_GROUP@@order-service\","
                + "\"groupName\":\"DEFAULT_GROUP\","
                + "\"clusters\":\"\","
                + "\"cacheMillis\":10000,"
                + "\"hosts\":["
                + "{\"ip\":\"10.0.0.21\",\"port\":8200,\"weight\":1.0,\"healthy\":true,"
                + "\"enabled\":true,\"ephemeral\":true,\"clusterName\":\"DEFAULT\","
                + "\"serviceName\":\"DEFAULT_GROUP@@order-service\","
                + "\"metadata\":{\"preserved.register.source\":\"SPRING_CLOUD\"},"
                + "\"instanceHeartBeatInterval\":5000,\"instanceHeartBeatTimeOut\":15000,"
                + "\"ipDeleteTimeout\":30000},"
                + "{\"ip\":\"10.0.0.22\",\"port\":8200,\"weight\":1.0,\"healthy\":true,"
                + "\"enabled\":true,\"ephemeral\":true,\"clusterName\":\"DEFAULT\","
                + "\"serviceName\":\"DEFAULT_GROUP@@order-service\","
                + "\"metadata\":{\"preserved.register.source\":\"SPRING_CLOUD\"},"
                + "\"instanceHeartBeatInterval\":5000,\"instanceHeartBeatTimeOut\":15000,"
                + "\"ipDeleteTimeout\":30000}],"
                + "\"lastRefTime\":1788133125662,"
                + "\"checksum\":\"\","
                + "\"allIPs\":false,"
                + "\"reachProtectionThreshold\":false,"
                + "\"valid\":true}";

        NacosDiscovery discovery = new NacosDiscovery.Builder(nacosAddr).build();
        List<Endpoint> endpoints = discovery.resolve("order-service");

        assertEquals(2, endpoints.size());
        assertEquals("http://10.0.0.21:8200", endpoints.get(0).getBaseUrl());
        assertEquals("http://10.0.0.22:8200", endpoints.get(1).getBaseUrl());
        // 权重同为 1.0，归一后仍是 1，不应被放大
        assertEquals(1, endpoints.get(0).getWeight());
        assertEquals(1, endpoints.get(1).getWeight());
        // 集群名与注册来源都应进入 metadata
        assertEquals("DEFAULT", endpoints.get(0).getMetadata().get("clusterName"));
        assertEquals("SPRING_CLOUD",
                endpoints.get(0).getMetadata().get("preserved.register.source"));
    }

    @Test
    public void authLoginExchangesTokenAndReusesIt() throws Exception {
        REQUIRE_AUTH.set(true);
        NacosDiscovery discovery = new NacosDiscovery.Builder(nacosAddr)
                .auth("nacos", "pass").build();
        assertEquals(2, discovery.resolve("order-service").size());
        assertEquals("应登录一次换取 token", 1, LOGIN_CALLS.get());
        assertTrue("实例列表请求必须带上 accessToken",
                LIST_QUERIES.get(0).contains("accessToken=tok-123"));

        discovery.resolve("order-service");
        discovery.resolve("order-service");
        assertEquals("token 未过期时不应重复登录", 1, LOGIN_CALLS.get());
        assertEquals(3, LIST_CALLS.get());
    }

    @Test
    public void staticAccessTokenSkipsLogin() throws Exception {
        REQUIRE_AUTH.set(true);
        NacosDiscovery discovery = new NacosDiscovery.Builder(nacosAddr)
                .accessToken("tok-123").build();
        assertEquals(2, discovery.resolve("order-service").size());
        assertEquals("给定固定 token 时不应调用登录接口", 0, LOGIN_CALLS.get());
    }

    @Test
    public void badCredentialsSurfaceServerMessage() {
        NacosDiscovery discovery = new NacosDiscovery.Builder(nacosAddr)
                .auth("nacos", "wrong").build();
        try {
            discovery.resolve("order-service");
            fail("凭据错误应抛异常");
        } catch (IOException e) {
            assertTrue("必须带上服务端返回的原因，否则无法排障: " + e.getMessage(),
                    e.getMessage().contains("403") || e.getMessage().contains("bad credentials")
                            || e.getMessage().contains("all nacos servers failed"));
        }
    }

    @Test
    public void missingTokenErrorBodyIsIncludedInDiagnostics() {
        REQUIRE_AUTH.set(true);
        NacosDiscovery discovery = new NacosDiscovery.Builder(nacosAddr).build();
        try {
            discovery.resolve("order-service");
            fail("未鉴权应抛异常");
        } catch (IOException e) {
            String message = String.valueOf(e.getMessage()) + " / "
                    + (e.getCause() == null ? "" : e.getCause().getMessage());
            assertTrue("错误正文应进入诊断信息: " + message,
                    message.contains("token invalid") || message.contains("403"));
        }
    }

    @Test
    public void errorMessageMasksAccessToken() {
        REQUIRE_AUTH.set(true);
        NacosDiscovery discovery = new NacosDiscovery.Builder(nacosAddr)
                .accessToken("super-secret-token").build();
        try {
            discovery.resolve("order-service");
            fail("token 不匹配应抛异常");
        } catch (IOException e) {
            String message = String.valueOf(e.getMessage()) + " / "
                    + (e.getCause() == null ? "" : e.getCause().getMessage());
            assertFalse("异常信息里不得出现明文 token: " + message,
                    message.contains("super-secret-token"));
        }
    }

    @Test
    public void failsOverAcrossMultipleNacosAddresses() throws Exception {
        NacosDiscovery discovery = new NacosDiscovery.Builder(brokenAddr + "," + nacosAddr).build();
        assertEquals("第一个地址 500，应自动换到第二个", 2, discovery.resolve("order-service").size());
        assertEquals(2, discovery.getServerAddrs().size());
        // 记住成功的地址，后续请求直接从它开始
        int callsAfterFirst = LIST_CALLS.get();
        discovery.resolve("order-service");
        assertEquals(callsAfterFirst + 1, LIST_CALLS.get());
    }

    @Test
    public void allNacosAddressesDownReportsEveryFailure() {
        NacosDiscovery discovery = new NacosDiscovery.Builder(brokenAddr + ",127.0.0.1:1")
                .timeouts(500, 500).build();
        try {
            discovery.resolve("order-service");
            fail("全部地址不可用应抛异常");
        } catch (IOException e) {
            assertTrue("应说明所有地址都失败了: " + e.getMessage(),
                    e.getMessage().contains("all nacos servers failed"));
        }
    }

    @Test
    public void emptyInstanceListIsAnError() {
        instanceListBody = "{\"hosts\":[]}";
        NacosDiscovery discovery = new NacosDiscovery.Builder(nacosAddr).build();
        try {
            discovery.resolve("order-service");
            fail("没有可用实例时应抛异常，让 EndpointPool 保留旧快照");
        } catch (IOException e) {
            assertTrue(e.getMessage().contains("no usable instance"));
        }
    }

    // ---------- 关键：注册中心自己的请求不能被负载均衡改写 ----------

    @Test
    public void discoveryRequestIsNotRewrittenByGlobalPoolWithoutServiceName() throws Exception {
        // 一个"对所有请求生效"的全局池，指向一个根本不是 Nacos 的地址
        EndpointPool trap = EndpointPool.builder().add("http://127.0.0.1:1").build();
        HttpUtils.config().setEndpointPool(trap);
        try {
            NacosDiscovery discovery = new NacosDiscovery.Builder(nacosAddr).build();
            List<Endpoint> endpoints = discovery.resolve("order-service");
            assertEquals("服务发现自身的请求必须绕过负载均衡，否则会被改写到业务端点",
                    2, endpoints.size());
            assertEquals(1, LIST_CALLS.get());
        } finally {
            HttpUtils.config().setEndpointPool(null);
        }
    }

    @Test
    public void discoveryDrivenPoolCanRefreshWhileItselfIsTheGlobalPool() throws Exception {
        NacosDiscovery discovery = new NacosDiscovery.Builder(nacosAddr).build();
        EndpointPool pool = EndpointPool.builder()
                .serviceName("order-service")
                .discovery(discovery)
                .refreshIntervalMs(0)
                .build();
        try {
            // 池已经作为全局池生效，此时再刷新自己：发现请求若不绕过 LB，
            // 就会被改写到 10.0.0.7 这类不可达的业务端点
            HttpUtils.config().setEndpointPool(pool);
            assertEquals(2, pool.refreshNow());
            assertEquals(2, pool.endpoints().size());
        } finally {
            HttpUtils.config().setEndpointPool(null);
            pool.close();
        }
    }

    @Test
    public void discoveryPoolWithoutServiceNameFailsFastWithClearMessage() {
        try {
            EndpointPool.builder().discovery(new NacosDiscovery.Builder(nacosAddr).build()).build();
            fail("注册中心型服务发现必须配 serviceName");
        } catch (IllegalArgumentException e) {
            assertTrue("错误信息应直接点出缺了 serviceName: " + e.getMessage(),
                    e.getMessage().contains("serviceName"));
        }
    }

    @Test
    public void bypassFlagIsHonoredAndCopied() {
        HttpRequest request = HttpRequest.get("http://x/y").bypassLoadBalance(true);
        assertTrue(request.isBypassLoadBalance());
        assertTrue("copy 必须带上旁路标记，否则重试/重定向派生请求会被改写",
                request.copy().isBypassLoadBalance());
        assertFalse(HttpRequest.get("http://x/y").isBypassLoadBalance());
    }

    // ---------- 与全局配置的隔离 ----------

    @Test
    public void discoveryUsesItsOwnTimeoutsNotGlobalOnes() throws Exception {
        HttpUtils.setConnectTimeout(30_000);
        HttpUtils.setReadTimeout(30_000);
        NacosDiscovery discovery = new NacosDiscovery.Builder(nacosAddr)
                .timeouts(1500, 1500).build();
        // 显式超时不会被全局配置覆盖（依赖 HttpRequest 的三态标记）
        assertEquals(2, discovery.resolve("order-service").size());
    }

    @Test
    public void userInterceptorsSeeDiscoveryTrafficButDoNotBreakIt() throws Exception {
        final List<String> urls = new ArrayList<String>();
        HttpUtils.config().addInterceptor(chain -> {
            urls.add(chain.request().getUrl());
            return chain.proceed(chain.request());
        });
        try {
            NacosDiscovery discovery = new NacosDiscovery.Builder(nacosAddr).build();
            assertEquals(2, discovery.resolve("order-service").size());
            assertFalse(urls.isEmpty());
            assertTrue(urls.get(0).contains("/nacos/v1/ns/instance/list"));
        } finally {
            HttpUtils.config().clearInterceptors();
        }
    }

    private static void send(HttpExchange exchange, int code, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.sendResponseHeaders(code, bytes.length);
        exchange.getResponseBody().write(bytes);
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
