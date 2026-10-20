package com.alianga.jkit.mock.server;

import org.junit.After;
import org.junit.Test;

import java.net.ServerSocket;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * 回归测试：多响应方案的序贯 / 随机挑选（审计 P0-5）。
 * <p>
 * SEQUENCE 按命中次数轮播（含热更新重置计数）、RANDOM 按权重随机分布、
 * 单方案直接命中不消耗计数、FIRST 保持历史规则语义不变。
 *
 * @author 郑明亮
 */
public class MockPlanStrategyTest {

    private final MockHttpServer server = new MockHttpServer();

    @After
    public void tearDown() {
        server.stop();
    }

    /** 记录一条 GET 路由的响应序列：按序选中各方案的状态码。 */
    private static MockRoute sequencedRoute() {
        return MockRoute.builder()
                .method("GET").path("/s")
                .statusCode(200)
                .contentType("application/json; charset=utf-8")
                .response(MockResponse.builder().statusCode(200).body("{\"n\":1}").build())
                .response(MockResponse.builder().statusCode(500).body("{\"n\":2}").build())
                .response(MockResponse.builder().statusCode(503).body("{\"n\":3}").build())
                .responseStrategy(MockPlanStrategy.SEQUENCE)
                .build();
    }

    /** SEQUENCE：第 1 次拿第 1 条，之后循环轮播；状态码序列 200/500/503/200/…。 */
    @Test
    public void sequenceRotatesPlansInOrder() throws Exception {
        int port = freePort();
        server.start(port, java.util.Collections.singletonList(sequencedRoute()));
        int[] expect = {200, 500, 503, 200, 500, 503, 200};
        for (int i = 0; i < expect.length; i++) {
            assertEquals("第 " + (i + 1) + " 次命中的状态码",
                    expect[i], get(port, "/s").statusCode());
        }
        assertEquals("序贯命中计数", expect.length,
                server.snapshotRoutes().get(0).planHitCount());
    }

    /** SEQUENCE 忽略激活规则：带规则的方案也参与轮播。 */
    @Test
    public void sequenceIgnoresRules() throws Exception {
        MockRoute route = MockRoute.builder()
                .method("GET").path("/r")
                .response(MockResponse.builder().statusCode(200).body("first")
                        .rule(new MockResponseRule(MockResponseRule.Source.QUERY,
                                MockResponseRule.Op.EQUALS, "env", "prod"))
                        .build())
                .response(MockResponse.builder().statusCode(418).body("second").build())
                .responseStrategy(MockPlanStrategy.SEQUENCE)
                .build();
        int port = freePort();
        server.start(port, java.util.Collections.singletonList(route));
        assertEquals(200, get(port, "/r?env=dev").statusCode());
        assertEquals(418, get(port, "/r?env=prod").statusCode());
    }

    /** 单方案不走策略：不消耗序贯计数，规则也不评估。 */
    @Test
    public void singlePlanShortCircuitsStrategy() throws Exception {
        MockRoute route = MockRoute.builder()
                .method("GET").path("/one")
                .response(MockResponse.builder().statusCode(201).body("only").build())
                .responseStrategy(MockPlanStrategy.SEQUENCE)
                .build();
        int port = freePort();
        server.start(port, java.util.Collections.singletonList(route));
        for (int i = 0; i < 4; i++) {
            assertEquals(201, get(port, "/one").statusCode());
        }
        assertEquals("单方案不进轮播计数", 0, server.snapshotRoutes().get(0).planHitCount());
    }

    /** 热更新换新路由实例：序贯计数从 0 重新开始。 */
    @Test
    public void hotReloadResetsSequenceCounter() throws Exception {
        int port = freePort();
        server.start(port, java.util.Collections.singletonList(sequencedRoute()));
        get(port, "/s");
        get(port, "/s");
        // 同一构造顺序 new 出一条「等价」路由，身份不同 → 计数应重置，重新从 200 开始
        server.setRoutes(java.util.Collections.singletonList(sequencedRoute()));
        assertEquals(200, get(port, "/s").statusCode());
        assertEquals(500, get(port, "/s").statusCode());
    }

    /** RANDOM：7:3 权重，30 次内两个方案都应出现；高权重方案占比更高。 */
    @Test
    public void randomPicksFollowWeights() throws Exception {
        MockRoute route = MockRoute.builder()
                .method("GET").path("/w")
                .response(MockResponse.builder().statusCode(200).body("major").weight(7).build())
                .response(MockResponse.builder().statusCode(500).body("minor").weight(3).build())
                .responseStrategy(MockPlanStrategy.RANDOM)
                .build();
        int port = freePort();
        server.start(port, java.util.Collections.singletonList(route));
        AtomicInteger major = new AtomicInteger();
        AtomicInteger minor = new AtomicInteger();
        for (int i = 0; i < 60; i++) {
            java.net.http.HttpResponse<String> resp = get(port, "/w");
            if (resp.statusCode() == 200) {
                major.incrementAndGet();
            } else {
                minor.incrementAndGet();
            }
        }
        assertTrue("两个方案都应被随机命中，major=" + major.get() + " minor=" + minor.get(),
                major.get() > 0 && minor.get() > 0);
        assertTrue("7:3 权重下 200 应占多数，major=" + major.get(),
                major.get() > minor.get());
    }

    /** 权重下限 1：负数/0 视作 1，不会让请求因权重和异常而翻车。 */
    @Test
    public void weightFloorsAtOne() {
        MockResponse tiny = MockResponse.builder().statusCode(500).weight(-3).build();
        assertEquals(1, tiny.weight);
        MockResponse unset = MockResponse.builder().statusCode(200).build();
        assertEquals("未设置权重默认 1", 1, unset.weight);
    }

    /** FIRST（默认）：规则评估语义与历史一致——规则命中优先，全不中回落默认方案。 */
    @Test
    public void firstStrategyKeepsHistorySemantics() throws Exception {
        MockRoute route = MockRoute.builder()
                .method("GET").path("/f")
                .response(MockResponse.builder().statusCode(500).body("bad")
                        .rule(new MockResponseRule(MockResponseRule.Source.QUERY,
                                MockResponseRule.Op.EQUALS, "fail", "1"))
                        .build())
                .response(MockResponse.builder().statusCode(200).body("default").build())
                .build();
        int port = freePort();
        server.start(port, java.util.Collections.singletonList(route));
        assertEquals(500, get(port, "/f?fail=1").statusCode());
        assertEquals(200, get(port, "/f?fail=0").statusCode());
        assertEquals(200, get(port, "/f").statusCode());
    }

    private java.net.http.HttpResponse<String> get(int port, String path)
            throws Exception {
        return java.net.http.HttpClient.newHttpClient().send(
                java.net.http.HttpRequest.newBuilder(
                        java.net.URI.create("http://127.0.0.1:" + port + path)).GET().build(),
                java.net.http.HttpResponse.BodyHandlers.ofString());
    }

    private static int freePort() throws Exception {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }
}
