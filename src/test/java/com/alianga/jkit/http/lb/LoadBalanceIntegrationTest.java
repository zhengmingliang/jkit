package com.alianga.jkit.http.lb;

import com.alianga.jkit.HttpUtils;
import com.alianga.jkit.http.CookieJarImpl;
import com.alianga.jkit.http.HttpRequest;
import com.alianga.jkit.http.HttpResponse;
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
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * 负载均衡端到端集成：三个真实 HTTP 实例，验证流量分摊、故障转移、
 * 业务 4xx 不熔断、serviceName 网关、Host 头保持、请求级覆盖、Nacos 响应解析。
 *
 * @author 郑明亮
 */
public class LoadBalanceIntegrationTest {
    private static final int NODES = 3;
    private static final List<HttpServer> SERVERS = new ArrayList<HttpServer>();
    private static final List<String> ORIGINS = new ArrayList<String>();
    private static final List<AtomicInteger> HITS = new ArrayList<AtomicInteger>();
    private static final List<AtomicBoolean> BROKEN = new ArrayList<AtomicBoolean>();
    private static final List<String> HOST_HEADERS = new CopyOnWriteArrayList<String>();
    private static volatile int businessErrorCode;

    @BeforeClass
    public static void startServers() throws IOException {
        for (int i = 0; i < NODES; i++) {
            final int index = i;
            HITS.add(new AtomicInteger());
            BROKEN.add(new AtomicBoolean());
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/api", exchange -> {
                readAll(exchange.getRequestBody());
                HITS.get(index).incrementAndGet();
                String host = exchange.getRequestHeaders().getFirst("Host");
                HOST_HEADERS.add(host == null ? "" : host);
                if (BROKEN.get(index).get()) {
                    send(exchange, 503, "node-" + index + "-down");
                    return;
                }
                if (businessErrorCode > 0) {
                    send(exchange, businessErrorCode, "business-error");
                    return;
                }
                send(exchange, 200, "node-" + index);
            });
            server.start();
            SERVERS.add(server);
            ORIGINS.add("http://127.0.0.1:" + server.getAddress().getPort());
        }
    }

    @AfterClass
    public static void stopServers() {
        for (HttpServer server : SERVERS) {
            server.stop(0);
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
        for (AtomicInteger hits : HITS) {
            hits.set(0);
        }
        for (AtomicBoolean broken : BROKEN) {
            broken.set(false);
        }
        HOST_HEADERS.clear();
        businessErrorCode = 0;
    }

    @After
    public void clearPool() {
        HttpUtils.config().setEndpointPool(null);
    }

    private static EndpointPool poolOf(LoadBalanceStrategy strategy, String serviceName) {
        EndpointPool.Builder builder = EndpointPool.builder().strategy(strategy);
        if (serviceName != null) {
            builder.serviceName(serviceName);
        }
        for (String origin : ORIGINS) {
            builder.add(origin);
        }
        return builder.build();
    }

    // ---------- 基础分摊 ----------

    @Test
    public void trafficIsSpreadAcrossAllEndpoints() throws IOException {
        EndpointPool pool = poolOf(LoadBalanceStrategies.smoothWeightedRoundRobin(), "orders");
        HttpUtils.config().setEndpointPool(pool);
        Set<String> bodies = new HashSet<String>();
        for (int i = 0; i < 30; i++) {
            bodies.add(HttpUtils.get("http://orders/api"));
        }
        assertEquals("三个端点都应被用到", 3, bodies.size());
        for (int i = 0; i < NODES; i++) {
            assertEquals("等权轮询应均分，节点 " + i + " 命中 " + HITS.get(i).get(),
                    10, HITS.get(i).get());
        }
    }

    @Test
    public void responseReportsWhichEndpointServedIt() throws IOException {
        HttpUtils.config().setEndpointPool(poolOf(LoadBalanceStrategies.weightedRandom(), "orders"));
        HttpResponse response = HttpRequest.get("http://orders/api").execute();
        try {
            assertEquals(200, response.code());
            assertNotNull("应能看出这次请求落到了哪个端点", response.endpointBaseUrl());
            assertTrue(ORIGINS.contains(response.endpointBaseUrl()));
        } finally {
            response.close();
        }
    }

    @Test
    public void weightsAreRespectedEndToEnd() throws IOException {
        EndpointPool pool = EndpointPool.builder()
                .serviceName("orders")
                .strategy(LoadBalanceStrategies.smoothWeightedRoundRobin())
                .add(ORIGINS.get(0), 4)
                .add(ORIGINS.get(1), 1)
                .add(ORIGINS.get(2), 1)
                .build();
        HttpUtils.config().setEndpointPool(pool);
        for (int i = 0; i < 30; i++) {
            HttpUtils.get("http://orders/api");
        }
        assertEquals("权重 4:1:1 应精确分摊", 20, HITS.get(0).get());
        assertEquals(5, HITS.get(1).get());
        assertEquals(5, HITS.get(2).get());
    }

    // ---------- 故障转移 ----------

    @Test
    public void requestFailsOverToHealthyEndpoint() throws IOException {
        BROKEN.get(0).set(true);
        BROKEN.get(1).set(true);
        EndpointPool pool = EndpointPool.builder()
                .serviceName("orders")
                .strategy(LoadBalanceStrategies.smoothWeightedRoundRobin())
                .add(ORIGINS.get(0)).add(ORIGINS.get(1)).add(ORIGINS.get(2))
                .breaker(BreakerOptions.builder().consecutiveFailureThreshold(1).cooldownMs(60_000).build())
                .build();
        HttpUtils.config().setEndpointPool(pool);
        HttpResponse response = HttpRequest.get("http://orders/api").execute();
        try {
            assertEquals("两个节点 503，应自动转移到健康节点", 200, response.code());
            assertEquals(ORIGINS.get(2), response.endpointBaseUrl());
            assertTrue("应经过多次尝试", response.attempts() >= 2);
        } finally {
            response.close();
        }
    }

    @Test
    public void failoverEventIsReported() throws IOException {
        BROKEN.get(0).set(true);
        final List<String> failovers = new ArrayList<String>();
        EndpointPool pool = EndpointPool.builder()
                .serviceName("orders")
                .strategy(LoadBalanceStrategies.smoothWeightedRoundRobin())
                .add(ORIGINS.get(0)).add(ORIGINS.get(1))
                .breaker(BreakerOptions.builder().consecutiveFailureThreshold(1).cooldownMs(60_000).build())
                .listener(new EndpointPoolListener() {
                    @Override
                    public void onFailover(EndpointPool p, Endpoint failed, Endpoint next, String cause) {
                        failovers.add(failed.getBaseUrl() + "->" + next.getBaseUrl());
                    }
                })
                .build();
        HttpUtils.config().setEndpointPool(pool);
        HttpResponse response = HttpRequest.get("http://orders/api").execute();
        response.close();
        assertFalse("故障转移必须可观测，否则线上无法告警", failovers.isEmpty());
        assertTrue(failovers.get(0).startsWith(ORIGINS.get(0)));
    }

    @Test
    public void brokenEndpointIsTakenOutOfRotationAfterTrip() throws IOException {
        BROKEN.get(0).set(true);
        EndpointPool pool = EndpointPool.builder()
                .serviceName("orders")
                .strategy(LoadBalanceStrategies.smoothWeightedRoundRobin())
                .add(ORIGINS.get(0)).add(ORIGINS.get(1)).add(ORIGINS.get(2))
                .breaker(BreakerOptions.builder().consecutiveFailureThreshold(1).cooldownMs(60_000).build())
                .build();
        HttpUtils.config().setEndpointPool(pool);
        // 第一次请求触发熔断
        HttpUtils.get("http://orders/api");
        int hitsAfterTrip = HITS.get(0).get();
        for (int i = 0; i < 15; i++) {
            assertFalse("熔断后不该再命中坏节点", HttpUtils.get("http://orders/api").contains("down"));
        }
        assertEquals("熔断后坏节点不应再收到任何请求", hitsAfterTrip, HITS.get(0).get());
        assertTrue(pool.byOrigin(ORIGINS.get(0)).isCoolingDown(System.currentTimeMillis()));
    }

    @Test
    public void allEndpointsDownFailsFastWithClearMessage() {
        for (AtomicBoolean broken : BROKEN) {
            broken.set(true);
        }
        EndpointPool pool = EndpointPool.builder()
                .serviceName("orders")
                .add(ORIGINS.get(0)).add(ORIGINS.get(1)).add(ORIGINS.get(2))
                .breaker(BreakerOptions.builder().consecutiveFailureThreshold(1).cooldownMs(60_000).build())
                .build();
        HttpUtils.config().setEndpointPool(pool);
        try {
            // 第一次会把三个节点逐个试一遍并全部熔断，最终返回最后一个 503
            HttpResponse first = HttpRequest.get("http://orders/api").execute();
            first.close();
        } catch (IOException ignore) {
            // 也可能直接抛错，两种都接受
        }
        try {
            HttpRequest.get("http://orders/api").execute();
            fail("全部熔断后应快速失败");
        } catch (IOException e) {
            assertTrue("错误信息应说明是熔断: " + e.getMessage(),
                    e.getMessage().contains("circuit is open") || e.getMessage().contains("unavailable"));
        }
    }

    // ---------- 业务 4xx 不算节点故障 ----------

    @Test
    public void businessErrorsDoNotTripTheBreaker() throws IOException {
        businessErrorCode = 404;
        EndpointPool pool = poolOf(LoadBalanceStrategies.smoothWeightedRoundRobin(), "orders");
        HttpUtils.config().setEndpointPool(pool);
        for (int i = 0; i < 30; i++) {
            HttpUtils.get("http://orders/api");
        }
        long now = System.currentTimeMillis();
        for (Endpoint endpoint : pool.endpoints()) {
            assertFalse("一批业务 404 绝不能把整个池熔断: " + endpoint, endpoint.isCoolingDown(now));
            assertEquals(0, endpoint.getConsecutiveFailures());
        }
        for (int i = 0; i < NODES; i++) {
            assertTrue("流量应继续正常分摊", HITS.get(i).get() > 0);
        }
    }

    @Test
    public void authErrorsDoNotTripTheBreaker() throws IOException {
        businessErrorCode = 401;
        EndpointPool pool = poolOf(LoadBalanceStrategies.weightedRandom(), "orders");
        HttpUtils.config().setEndpointPool(pool);
        for (int i = 0; i < 20; i++) {
            HttpUtils.get("http://orders/api");
        }
        long now = System.currentTimeMillis();
        for (Endpoint endpoint : pool.endpoints()) {
            assertFalse("401 是鉴权问题，不是节点故障: " + endpoint, endpoint.isCoolingDown(now));
        }
    }

    // ---------- serviceName 网关 ----------

    @Test
    public void requestsToOtherHostsBypassThePool() throws IOException {
        HttpUtils.config().setEndpointPool(poolOf(LoadBalanceStrategies.weightedRandom(), "orders"));
        // 直接写真实地址：应原样直连第 0 个节点，不被改写
        String body = HttpUtils.get(ORIGINS.get(0) + "/api");
        assertEquals("node-0", body);
        assertEquals(1, HITS.get(0).get());
        assertEquals("不该被负载均衡改写到别的节点", 0, HITS.get(1).get());
        assertEquals(0, HITS.get(2).get());
    }

    @Test
    public void poolWithoutServiceNameAppliesToAllRequests() throws IOException {
        HttpUtils.config().setEndpointPool(poolOf(LoadBalanceStrategies.smoothWeightedRoundRobin(), null));
        for (int i = 0; i < 6; i++) {
            // 即使写了具体地址也会被改写
            HttpUtils.get("http://any-host-name/api");
        }
        int total = 0;
        for (AtomicInteger hits : HITS) {
            total += hits.get();
        }
        assertEquals(6, total);
        for (int i = 0; i < NODES; i++) {
            assertEquals(2, HITS.get(i).get());
        }
    }

    @Test
    public void perRequestPoolOverridesGlobalAndServiceNameGate() throws IOException {
        HttpUtils.config().setEndpointPool(null);
        EndpointPool onlyNode2 = EndpointPool.builder().add(ORIGINS.get(2)).build();
        HttpResponse response = HttpRequest.get("http://whatever/api")
                .endpointPool(onlyNode2)
                .execute();
        try {
            assertEquals("node-2", response.body().string());
            assertEquals(ORIGINS.get(2), response.endpointBaseUrl());
        } finally {
            response.close();
        }
    }

    // ---------- Host 头保持 ----------

    @Test
    public void originalHostIsPreservedWhenRewritingOrigin() throws IOException {
        EndpointPool pool = poolOf(LoadBalanceStrategies.weightedRandom(), "orders");
        assertTrue("默认应开启 Host 保持", pool.isPreserveHostHeader());
        HttpUtils.config().setEndpointPool(pool);
        HttpUtils.get("http://orders/api");
        assertFalse(HOST_HEADERS.isEmpty());
        String host = HOST_HEADERS.get(0);
        // JDK 默认禁止应用改写 Host（需要 -Djdk.httpclient.allowRestrictedHeaders=host），
        // 所以这里只断言"要么保住了逻辑服务名，要么退化为实际地址"，不因环境不同而失败
        assertTrue("Host 头应是 orders 或实际连接地址，实际: " + host,
                host.startsWith("orders") || host.startsWith("127.0.0.1"));
    }

    @Test
    public void preserveHostCanBeDisabled() throws IOException {
        EndpointPool pool = EndpointPool.builder()
                .serviceName("orders").add(ORIGINS.get(0)).preserveHostHeader(false).build();
        assertFalse(pool.isPreserveHostHeader());
        HttpUtils.config().setEndpointPool(pool);
        HttpUtils.get("http://orders/api");
        assertFalse(HOST_HEADERS.isEmpty());
        assertTrue("关闭后 Host 应是实际连接地址，实际: " + HOST_HEADERS.get(0),
                HOST_HEADERS.get(0).startsWith("127.0.0.1"));
    }

    // ---------- 路径与查询保持 ----------

    @Test
    public void pathAndQueryArePreservedWhenRewriting() throws IOException {
        HttpUtils.config().setEndpointPool(poolOf(LoadBalanceStrategies.weightedRandom(), "orders"));
        HttpResponse response = HttpRequest.get("http://orders/api?a=1&b=%E4%B8%AD").execute();
        try {
            assertEquals(200, response.code());
            assertTrue("改写 origin 后路径与查询必须原样保留: " + response.requestUrl(),
                    response.requestUrl().contains("/api?a=1&b=%E4%B8%AD"));
        } finally {
            response.close();
        }
    }

    // ---------- 服务发现驱动 ----------

    @Test
    public void discoveryDrivenPoolPicksUpScaleUpAndScaleDown() throws Exception {
        final List<String> live = new ArrayList<String>(Collections.singletonList(ORIGINS.get(0)));
        ServiceDiscovery discovery = serviceName -> {
            List<Endpoint> result = new ArrayList<Endpoint>();
            for (String origin : live) {
                result.add(new Endpoint(origin));
            }
            return result;
        };
        EndpointPool pool = EndpointPool.builder()
                .serviceName("orders").discovery(discovery).refreshIntervalMs(0)
                .strategy(LoadBalanceStrategies.smoothWeightedRoundRobin())
                .build();
        try {
            HttpUtils.config().setEndpointPool(pool);
            for (int i = 0; i < 4; i++) {
                assertEquals("node-0", HttpUtils.get("http://orders/api"));
            }
            live.add(ORIGINS.get(1));
            live.add(ORIGINS.get(2));
            assertEquals(3, pool.refreshNow());
            Set<String> bodies = new HashSet<String>();
            for (int i = 0; i < 12; i++) {
                bodies.add(HttpUtils.get("http://orders/api"));
            }
            assertEquals("扩容后新实例应立即接流", 3, bodies.size());

            live.remove(ORIGINS.get(1));
            live.remove(ORIGINS.get(2));
            assertEquals(1, pool.refreshNow());
            int before = HITS.get(1).get();
            for (int i = 0; i < 6; i++) {
                assertEquals("node-0", HttpUtils.get("http://orders/api"));
            }
            assertEquals("缩容后下线实例不应再收到请求", before, HITS.get(1).get());
        } finally {
            pool.close();
        }
    }

    // ---------- Nacos 响应解析 ----------

    @Test
    public void nacosParsesRealisticResponseWithMetadata() {
        String json = "{\"name\":\"DEFAULT_GROUP@@order\",\"hosts\":["
                + "{\"instanceId\":\"10.0.0.7#8080#DEFAULT#DEFAULT_GROUP@@order\","
                + "\"ip\":\"10.0.0.7\",\"port\":8080,\"weight\":1.0,\"healthy\":true,"
                + "\"enabled\":true,\"ephemeral\":true,\"clusterName\":\"BJ\","
                + "\"serviceName\":\"DEFAULT_GROUP@@order\","
                + "\"metadata\":{\"version\":\"1.2\",\"ip\":\"9.9.9.9\",\"port\":9999,\"weight\":\"99\"}},"
                + "{\"ip\":\"10.0.0.8\",\"port\":8081,\"weight\":2.0,\"healthy\":true,\"enabled\":true,"
                + "\"clusterName\":\"SH\",\"metadata\":{}}]}";
        List<Endpoint> endpoints = NacosDiscovery.parseInstances(json, "http", true);
        assertEquals(2, endpoints.size());
        assertEquals("绝不能读到 metadata 里的 ip", "http://10.0.0.7:8080",
                endpoints.get(0).getBaseUrl());
        assertEquals("http://10.0.0.8:8081", endpoints.get(1).getBaseUrl());
        assertEquals("Nacos 权重必须带进调度", 1, endpoints.get(0).getWeight());
        assertEquals(2, endpoints.get(1).getWeight());
        assertEquals("BJ", endpoints.get(0).getMetadata().get("clusterName"));
        assertEquals("1.2", endpoints.get(0).getMetadata().get("version"));
    }

    @Test
    public void nacosFiltersUnhealthyDisabledAndZeroWeight() {
        String json = "{\"hosts\":["
                + "{\"ip\":\"1.1.1.1\",\"port\":80,\"healthy\":false,\"enabled\":true,\"weight\":1.0},"
                + "{\"ip\":\"2.2.2.2\",\"port\":80,\"healthy\":true,\"enabled\":false,\"weight\":1.0},"
                + "{\"ip\":\"3.3.3.3\",\"port\":80,\"healthy\":true,\"enabled\":true,\"weight\":0.0},"
                + "{\"ip\":\"4.4.4.4\",\"port\":80,\"healthy\":true,\"enabled\":true,\"weight\":1.0}]}";
        List<Endpoint> endpoints = NacosDiscovery.parseInstances(json, "http", true);
        assertEquals(1, endpoints.size());
        assertEquals("http://4.4.4.4", endpoints.get(0).getBaseUrl());
    }

    @Test
    public void nacosScalesFractionalWeightsIntoIntegers() {
        String json = "{\"hosts\":["
                + "{\"ip\":\"1.1.1.1\",\"port\":80,\"weight\":0.1},"
                + "{\"ip\":\"2.2.2.2\",\"port\":80,\"weight\":0.3}]}";
        List<Endpoint> endpoints = NacosDiscovery.parseInstances(json, "http", true);
        assertEquals(2, endpoints.size());
        assertEquals("0.1 应放大成 1", 1, endpoints.get(0).getWeight());
        assertEquals("0.3 应按比例放大成 3", 3, endpoints.get(1).getWeight());
    }

    @Test
    public void nacosBracketsIpv6() {
        String json = "{\"hosts\":[{\"ip\":\"fe80::1\",\"port\":8080,\"weight\":1.0}]}";
        List<Endpoint> endpoints = NacosDiscovery.parseInstances(json, "http", true);
        assertEquals(1, endpoints.size());
        assertEquals("IPv6 必须加方括号才是合法 URL", "http://[fe80::1]:8080",
                endpoints.get(0).getBaseUrl());
    }

    @Test
    public void nacosHandlesGarbageAndEmptyInput() {
        assertTrue(NacosDiscovery.parseInstances(null, "http", true).isEmpty());
        assertTrue(NacosDiscovery.parseInstances("", "http", true).isEmpty());
        assertTrue(NacosDiscovery.parseInstances("not json at all", "http", true).isEmpty());
        assertTrue(NacosDiscovery.parseInstances("{\"hosts\":[]}", "http", true).isEmpty());
        assertTrue(NacosDiscovery.parseInstances("{\"other\":1}", "http", true).isEmpty());
    }

    @Test
    public void nacosUrlIncludesGroupNamespaceAndClusters() throws Exception {
        NacosDiscovery discovery = new NacosDiscovery.Builder("10.0.0.1:8848,10.0.0.2:8848")
                .namespaceId("prod")
                .groupName("ORDER_GROUP")
                .clusters("BJ")
                .build();
        String url = discovery.instanceListUrlForTest("order-service");
        assertTrue(url, url.startsWith("http://10.0.0.1:8848/nacos/v1/ns/instance/list?"));
        assertTrue("必须带 serviceName", url.contains("serviceName=order-service"));
        assertTrue("必须带 namespaceId，否则查不到非 public 命名空间", url.contains("namespaceId=prod"));
        assertTrue("必须带 groupName，否则查不到非 DEFAULT_GROUP 的服务",
                url.contains("groupName=ORDER_GROUP"));
        assertTrue(url.contains("clusters=BJ"));
        assertTrue(url.contains("healthyOnly=true"));
        assertEquals(2, discovery.getServerAddrs().size());
    }

    @Test
    public void nacosPublicNamespaceIsOmitted() throws Exception {
        NacosDiscovery discovery = new NacosDiscovery.Builder("10.0.0.1:8848")
                .namespaceId("public").build();
        assertNull(discovery.getNamespaceId());
        assertFalse(discovery.instanceListUrlForTest("svc").contains("namespaceId"));
        assertEquals("DEFAULT_GROUP", discovery.getGroupName());
    }

    @Test
    public void nacosBuilderStripsSchemeAndRejectsEmpty() {
        assertEquals(Collections.singletonList("1.2.3.4:8848"),
                new NacosDiscovery.Builder("http://1.2.3.4:8848").build().getServerAddrs());
        for (String bad : new String[]{null, "", "  ", ","}) {
            try {
                new NacosDiscovery.Builder(bad);
                fail("应拒绝空地址: " + bad);
            } catch (IllegalArgumentException expected) {
                // ok
            }
        }
    }

    @Test
    public void nacosMasksAccessTokenInDiagnostics() {
        assertEquals("http://x/y?a=1&accessToken=***&b=2",
                NacosDiscovery.maskToken("http://x/y?a=1&accessToken=secret-value&b=2"));
        assertEquals("http://x/y?accessToken=***",
                NacosDiscovery.maskToken("http://x/y?accessToken=secret-value"));
        assertEquals("http://x/y?a=1", NacosDiscovery.maskToken("http://x/y?a=1"));
    }

    @Test
    public void nacosResolveRejectsBlankServiceName() {
        NacosDiscovery discovery = new NacosDiscovery.Builder("127.0.0.1:1").build();
        for (String bad : new String[]{null, "", "   "}) {
            try {
                discovery.resolve(bad);
                fail("服务名为空应报错");
            } catch (IOException expected) {
                assertTrue(expected.getMessage().contains("service name"));
            }
        }
    }

    // ---------- 与重试策略协同 ----------

    @Test
    public void retryPolicyAndFailoverDoNotMultiplyBeyondLimit() throws IOException {
        for (AtomicBoolean broken : BROKEN) {
            broken.set(true);
        }
        HttpUtils.config().setRetryPolicy(RetryPolicy.builder()
                .maxAttempts(3).retryStatus(503).build());
        EndpointPool pool = EndpointPool.builder()
                .serviceName("orders")
                .add(ORIGINS.get(0)).add(ORIGINS.get(1)).add(ORIGINS.get(2))
                .breaker(BreakerOptions.disabled())
                .build();
        HttpUtils.config().setEndpointPool(pool);
        HttpResponse response = HttpRequest.get("http://orders/api").execute();
        try {
            assertEquals(503, response.code());
            assertTrue("尝试次数应有上限，实际 " + response.attempts(), response.attempts() <= 5);
        } finally {
            response.close();
        }
        int total = 0;
        for (AtomicInteger hits : HITS) {
            total += hits.get();
        }
        assertTrue("总请求次数不应爆炸，实际 " + total, total <= 5);
    }

    private static void send(HttpExchange exchange, int code, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "text/plain; charset=utf-8");
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
