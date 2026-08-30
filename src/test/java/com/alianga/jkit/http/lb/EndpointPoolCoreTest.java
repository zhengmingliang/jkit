package com.alianga.jkit.http.lb;

import org.junit.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * 负载均衡内核测试：origin 归一化、四种策略、Lease 在途计数、滑窗熔断、
 * half-open 单探测、增量刷新保留健康状态、服务发现。
 *
 * @author 郑明亮
 */
public class EndpointPoolCoreTest {

    // ---------- origin 归一化 ----------

    @Test
    public void normalizeStripsDefaultPortsAndUserInfo() throws Exception {
        assertEquals("https://a.com", Endpoint.normalize("https://a.com"));
        assertEquals("https://a.com", Endpoint.normalize("https://a.com:443"));
        assertEquals("https://a.com", Endpoint.normalize("https://a.com:443/path?q=1#f"));
        assertEquals("http://a.com", Endpoint.normalize("http://a.com:80/x"));
        assertEquals("http://a.com:8080", Endpoint.normalize("http://a.com:8080/x"));
        assertEquals("凭据不得留在端点标识里", "https://a.com", Endpoint.normalize("https://user:pass@a.com/x"));
        assertEquals("host 应转小写", "https://a.com", Endpoint.normalize("https://A.COM"));
    }

    @Test
    public void normalizeInfersSchemeSensiblyForInternalTargets() throws Exception {
        assertEquals("带端口的按内网明文处理", "http://10.0.0.7:8080", Endpoint.normalize("10.0.0.7:8080"));
        assertEquals("裸 IP 按明文处理", "http://10.0.0.7", Endpoint.normalize("10.0.0.7"));
        assertEquals("纯域名默认 https", "https://a.com", Endpoint.normalize("a.com"));
        assertEquals("协议相对", "https://a.com", Endpoint.normalize("//a.com"));
    }

    @Test
    public void normalizeKeepsIpv6Brackets() throws Exception {
        assertEquals("http://[::1]:8080", Endpoint.normalize("http://[::1]:8080"));
        assertEquals("http://[fe80::1]", Endpoint.normalize("http://[fe80::1]"));
        assertEquals("裸 IPv6 应自动补方括号", "http://[fe80::1]", Endpoint.normalize("fe80::1"));
    }

    @Test
    public void normalizeRejectsGarbage() throws Exception {
        for (String bad : new String[]{null, "", "   ", "http://", "https://:8080"}) {
            try {
                Endpoint.normalize(bad);
                fail("应拒绝非法基址: " + bad);
            } catch (IllegalArgumentException expected) {
                // ok
            }
        }
        try {
            Endpoint.normalize("http://a.com:99999");
            fail("端口越界应报错");
        } catch (IllegalArgumentException expected) {
            // ok
        }
    }

    @Test
    public void weightIsClampedToSafeRange() throws Exception {
        assertEquals(1, new Endpoint("http://a", 0).getWeight());
        assertEquals(1, new Endpoint("http://a", -5).getWeight());
        assertEquals(Endpoint.MAX_WEIGHT, new Endpoint("http://a", Integer.MAX_VALUE).getWeight());
    }

    // ---------- 策略 ----------

    @Test
    public void smoothWeightedRoundRobinIsSmoothNotBursty() throws Exception {
        List<Endpoint> candidates = Arrays.asList(new Endpoint("http://a", 3), new Endpoint("http://b", 1));
        LoadBalanceStrategy strategy = LoadBalanceStrategies.smoothWeightedRoundRobin();
        List<String> sequence = new ArrayList<String>();
        Map<String, Integer> counts = new TreeMap<String, Integer>();
        for (int i = 0; i < 12; i++) {
            Endpoint selected = strategy.select(candidates, null);
            sequence.add(selected.getBaseUrl().substring(7));
            counts.put(selected.getBaseUrl(), count(counts, selected.getBaseUrl()) + 1);
        }
        assertEquals("三轮 3:1 的配比应精确", Integer.valueOf(9), counts.get("http://a"));
        assertEquals(Integer.valueOf(3), counts.get("http://b"));
        // 平滑性的实际含义：每 4 次里恰好出现一次低权重端点，
        // 而不是把它挤到每一轮的末尾（取模式轮询就是那样）
        for (int window = 0; window < 3; window++) {
            int bInWindow = 0;
            for (int i = window * 4; i < window * 4 + 4; i++) {
                if ("b".equals(sequence.get(i))) {
                    bInWindow++;
                }
            }
            assertEquals("第 " + window + " 个 4 次窗口内 b 应恰好出现 1 次，实际序列 " + sequence,
                    1, bInWindow);
        }
    }

    @Test
    public void weightedRandomRespectsWeightsAndSurvivesHugeWeights() throws Exception {
        List<Endpoint> candidates = Arrays.asList(new Endpoint("http://a", 9), new Endpoint("http://b", 1));
        LoadBalanceStrategy strategy = LoadBalanceStrategies.weightedRandom();
        int aCount = 0;
        for (int i = 0; i < 4000; i++) {
            if (strategy.select(candidates, null).getBaseUrl().endsWith("a")) {
                aCount++;
            }
        }
        assertTrue("9:1 权重下 a 应占大头，实际 " + aCount + "/4000", aCount > 3200 && aCount < 3900);

        // 权重之和会溢出 int 的场景：旧实现 nextInt(负数) 直接抛异常
        List<Endpoint> huge = Arrays.asList(
                new Endpoint("http://a", Endpoint.MAX_WEIGHT),
                new Endpoint("http://b", Endpoint.MAX_WEIGHT),
                new Endpoint("http://c", Endpoint.MAX_WEIGHT));
        for (int i = 0; i < 100; i++) {
            assertNotNull(strategy.select(huge, null));
        }
    }

    @Test
    public void allStrategiesSurviveOverflowWeights() throws Exception {
        List<Endpoint> huge = new ArrayList<Endpoint>();
        for (int i = 0; i < 5; i++) {
            huge.add(new Endpoint("http://h" + i, Endpoint.MAX_WEIGHT));
        }
        List<LoadBalanceStrategy> strategies = Arrays.asList(
                LoadBalanceStrategies.smoothWeightedRoundRobin(),
                LoadBalanceStrategies.weightedRandom(),
                LoadBalanceStrategies.p2cLeastLoaded(),
                LoadBalanceStrategies.p2cPeakEwma(),
                LoadBalanceStrategies.consistentHash());
        for (LoadBalanceStrategy strategy : strategies) {
            for (int i = 0; i < 50; i++) {
                assertNotNull(strategy.name() + " 不应崩溃", strategy.select(huge, "key-" + i));
            }
        }
    }

    @Test
    public void p2cPrefersTheLessLoadedEndpoint() throws Exception {
        Endpoint busy = new Endpoint("http://busy", 1);
        Endpoint idle = new Endpoint("http://idle", 1);
        EndpointPool pool = EndpointPool.builder()
                .add(busy).add(idle)
                .strategy(LoadBalanceStrategies.p2cLeastLoaded())
                .build();
        // 只有两个候选，P2C 每次都会拿到这两个，必须选在途少的
        List<EndpointPool.Lease> held = new ArrayList<EndpointPool.Lease>();
        try {
            // 人为把 busy 压上在途请求
            for (int i = 0; i < 5; i++) {
                EndpointPool.Lease lease = pool.acquire(Collections.singleton(idle.getBaseUrl()), null);
                assertEquals(busy.getBaseUrl(), lease.endpoint().getBaseUrl());
                held.add(lease);
            }
            assertEquals(5, busy.getInFlight());
            int idleWins = 0;
            for (int i = 0; i < 20; i++) {
                EndpointPool.Lease lease = pool.acquire(null, null);
                try {
                    if (lease.endpoint().getBaseUrl().equals(idle.getBaseUrl())) {
                        idleWins++;
                    }
                } finally {
                    lease.close();
                }
            }
            assertEquals("在途 5 vs 0，应每次都选空闲端点", 20, idleWins);
        } finally {
            for (EndpointPool.Lease lease : held) {
                lease.close();
            }
        }
        assertEquals("Lease 关闭后在途必须归零", 0, busy.getInFlight());
    }

    @Test
    public void consistentHashIsStickyPerKeyAndSpreadsAcrossKeys() throws Exception {
        List<Endpoint> candidates = Arrays.asList(
                new Endpoint("http://a", 1), new Endpoint("http://b", 1), new Endpoint("http://c", 1));
        LoadBalanceStrategy strategy = LoadBalanceStrategies.consistentHash();
        String first = strategy.select(candidates, "user-42").getBaseUrl();
        for (int i = 0; i < 50; i++) {
            assertEquals("同一个键必须路由到同一端点", first,
                    strategy.select(candidates, "user-42").getBaseUrl());
        }
        Set<String> distinct = new HashSet<String>();
        for (int i = 0; i < 300; i++) {
            distinct.add(strategy.select(candidates, "user-" + i).getBaseUrl());
        }
        assertEquals("不同键应分散到所有端点", 3, distinct.size());
    }

    @Test
    public void consistentHashKeepsMostKeysWhenOneEndpointLeaves() throws Exception {
        List<Endpoint> before = Arrays.asList(
                new Endpoint("http://a"), new Endpoint("http://b"),
                new Endpoint("http://c"), new Endpoint("http://d"));
        List<Endpoint> after = Arrays.asList(
                new Endpoint("http://a"), new Endpoint("http://b"), new Endpoint("http://c"));
        LoadBalanceStrategy strategy = LoadBalanceStrategies.consistentHash();
        int stable = 0;
        int comparable = 0;
        for (int i = 0; i < 400; i++) {
            String key = "k" + i;
            String was = strategy.select(before, key).getBaseUrl();
            if (was.equals("http://d")) {
                continue;
            }
            comparable++;
            if (was.equals(strategy.select(after, key).getBaseUrl())) {
                stable++;
            }
        }
        assertTrue("摘掉一个端点后，其余键的归属应基本不变，实际 " + stable + "/" + comparable,
                stable > comparable * 0.8);
    }

    @Test
    public void consistentHashWithoutKeyFallsBackInsteadOfPinning() throws Exception {
        List<Endpoint> candidates = Arrays.asList(
                new Endpoint("http://a"), new Endpoint("http://b"), new Endpoint("http://c"));
        LoadBalanceStrategy strategy = LoadBalanceStrategies.consistentHash();
        Set<String> seen = new HashSet<String>();
        for (int i = 0; i < 200; i++) {
            seen.add(strategy.select(candidates, null).getBaseUrl());
        }
        assertTrue("没有亲和键时不应固定打向一个端点", seen.size() > 1);
    }

    // ---------- Lease / 在途计数 ----------

    @Test
    public void leaseTracksInFlightAndCannotBeLeaked() throws Exception {
        EndpointPool pool = EndpointPool.of("http://a");
        Endpoint endpoint = pool.endpoints().get(0);
        assertEquals(0, endpoint.getInFlight());
        EndpointPool.Lease lease = pool.acquire(null, null);
        assertEquals("acquire 应立即计入在途", 1, endpoint.getInFlight());
        lease.success();
        assertEquals("记录结果不释放在途", 1, endpoint.getInFlight());
        lease.close();
        assertEquals(0, endpoint.getInFlight());
        lease.close();
        assertEquals("重复 close 不得把在途计成负数", 0, endpoint.getInFlight());
    }

    @Test
    public void concurrentLeasesKeepInFlightConsistent() throws Exception {
        final EndpointPool pool = EndpointPool.builder().add("http://a").add("http://b").build();
        int threads = 8;
        final int perThread = 200;
        ExecutorService executor = Executors.newFixedThreadPool(threads);
        final CountDownLatch start = new CountDownLatch(1);
        final AtomicInteger errors = new AtomicInteger();
        try {
            for (int t = 0; t < threads; t++) {
                executor.submit(() -> {
                    try {
                        start.await();
                        for (int i = 0; i < perThread; i++) {
                            EndpointPool.Lease lease = pool.acquire(null, null);
                            try {
                                lease.success();
                            } finally {
                                lease.close();
                            }
                        }
                    } catch (Exception e) {
                        errors.incrementAndGet();
                    }
                });
            }
            start.countDown();
            executor.shutdown();
            assertTrue(executor.awaitTermination(30, TimeUnit.SECONDS));
        } finally {
            executor.shutdownNow();
        }
        assertEquals(0, errors.get());
        for (Endpoint endpoint : pool.endpoints()) {
            assertEquals("并发结束后在途必须归零: " + endpoint, 0, endpoint.getInFlight());
        }
    }

    // ---------- 熔断 ----------

    @Test
    public void consecutiveFailuresTripBreaker() throws Exception {
        EndpointPool pool = EndpointPool.builder()
                .add("http://a").add("http://b")
                .breaker(BreakerOptions.builder()
                        .consecutiveFailureThreshold(3).cooldownMs(60_000).build())
                .build();
        Endpoint a = pool.byOrigin("http://a");
        for (int i = 0; i < 3; i++) {
            EndpointPool.Lease lease = pool.acquire(Collections.singleton("http://b"), null);
            assertEquals(a.getBaseUrl(), lease.endpoint().getBaseUrl());
            lease.failure("boom");
            lease.close();
        }
        assertTrue("连续 3 次失败应触发熔断", a.isCoolingDown(System.currentTimeMillis()));
        for (int i = 0; i < 10; i++) {
            EndpointPool.Lease lease = pool.acquire(null, null);
            try {
                assertEquals("熔断后流量应全部转到健康端点", "http://b", lease.endpoint().getBaseUrl());
            } finally {
                lease.close();
            }
        }
    }

    @Test
    public void failureRateWindowTripsBreakerUnderMixedTraffic() throws Exception {
        // 连续失败计数永远到不了阈值（成功会不断清零），只能靠窗口失败率发现问题
        EndpointPool pool = EndpointPool.builder()
                .add("http://a")
                .breaker(BreakerOptions.builder()
                        .consecutiveFailureThreshold(100)
                        .minimumRequests(10)
                        .failureRateThreshold(0.5)
                        .cooldownMs(30_000)
                        .build())
                .build();
        Endpoint a = pool.endpoints().get(0);
        int completed = 0;
        for (int i = 0; i < 12; i++) {
            EndpointPool.Lease lease;
            try {
                lease = pool.acquire(null, null);
            } catch (IOException circuitOpen) {
                // 熔断后 acquire 会快速失败，说明目的已达到
                break;
            }
            if (i % 2 == 0) {
                lease.failure("boom");
            } else {
                lease.success();
            }
            lease.close();
            completed++;
        }
        assertTrue("交替成功/失败时也应靠失败率熔断，已完成 " + completed
                        + " 次，窗口统计: " + a.getWindowFailures() + "/" + a.getWindowTotal(),
                a.isCoolingDown(System.currentTimeMillis()));
        assertTrue("熔断后 acquire 应快速失败而不是继续打故障节点", completed < 12);
    }

    @Test
    public void neutralResultsDoNotTripBreaker() throws Exception {
        EndpointPool pool = EndpointPool.builder()
                .add("http://a")
                .breaker(BreakerOptions.builder().consecutiveFailureThreshold(2).build())
                .build();
        Endpoint a = pool.endpoints().get(0);
        for (int i = 0; i < 20; i++) {
            EndpointPool.Lease lease = pool.acquire(null, null);
            lease.neutral();
            lease.close();
        }
        assertFalse("业务 4xx 这类中性结果不得把健康节点熔断", a.isCoolingDown(System.currentTimeMillis()));
        assertEquals(0, a.getConsecutiveFailures());
    }

    @Test
    public void cooldownGrowsWithRepeatedTrips() throws Exception {
        BreakerOptions options = BreakerOptions.builder()
                .cooldownMs(1000).maxCooldownMs(8000).build();
        assertEquals(1000L, options.cooldownMsFor(1));
        assertEquals(2000L, options.cooldownMsFor(2));
        assertEquals(4000L, options.cooldownMsFor(3));
        assertEquals(8000L, options.cooldownMsFor(4));
        assertEquals("应受上限约束", 8000L, options.cooldownMsFor(20));
    }

    @Test
    public void disabledBreakerNeverTrips() throws Exception {
        EndpointPool pool = EndpointPool.builder()
                .add("http://a").breaker(BreakerOptions.disabled()).build();
        Endpoint a = pool.endpoints().get(0);
        for (int i = 0; i < 50; i++) {
            EndpointPool.Lease lease = pool.acquire(null, null);
            lease.failure("boom");
            lease.close();
        }
        assertFalse(a.isCoolingDown(System.currentTimeMillis()));
    }

    // ---------- half-open 单探测 ----------

    @Test
    public void allDownReleasesExactlyOneProbe() throws Exception {
        EndpointPool pool = EndpointPool.builder()
                .add("http://a").add("http://b")
                .breaker(BreakerOptions.builder().consecutiveFailureThreshold(1).cooldownMs(50).build())
                .build();
        for (Endpoint endpoint : pool.endpoints()) {
            EndpointPool.Lease lease = pool.acquire(
                    Collections.singleton(other(pool, endpoint).getBaseUrl()), null);
            lease.failure("down");
            lease.close();
        }
        long now = System.currentTimeMillis();
        for (Endpoint endpoint : pool.endpoints()) {
            assertTrue(endpoint + " 应处于冷却", endpoint.isCoolingDown(now));
        }
        Thread.sleep(80); // 等冷却结束，进入 half-open

        int probes = 0;
        int rejected = 0;
        List<EndpointPool.Lease> leases = new ArrayList<EndpointPool.Lease>();
        try {
            for (int i = 0; i < 10; i++) {
                try {
                    EndpointPool.Lease lease = pool.acquire(null, null);
                    leases.add(lease);
                    if (lease.isProbe()) {
                        probes++;
                    }
                } catch (IOException circuitOpen) {
                    rejected++;
                }
            }
        } finally {
            for (EndpointPool.Lease lease : leases) {
                lease.close();
            }
        }
        assertEquals("每个半开端点各放 1 个探测，2 个端点共 2 个", 2, probes);
        assertEquals("其余请求必须快速失败，不能一起打向已知故障的节点", 8, rejected);
    }

    @Test
    public void singleEndpointReleasesExactlyOneProbeWhenHalfOpen() throws Exception {
        EndpointPool pool = EndpointPool.builder()
                .add("http://only")
                .breaker(BreakerOptions.builder().consecutiveFailureThreshold(1).cooldownMs(50).build())
                .build();
        EndpointPool.Lease first = pool.acquire(null, null);
        first.failure("down");
        first.close();
        // 冷却期内一律快速失败
        try {
            pool.acquire(null, null);
            fail("冷却期内应快速失败");
        } catch (IOException expected) {
            assertTrue(expected.getMessage().contains("circuit is open"));
        }
        Thread.sleep(80);
        int probes = 0;
        int rejected = 0;
        List<EndpointPool.Lease> leases = new ArrayList<EndpointPool.Lease>();
        try {
            for (int i = 0; i < 5; i++) {
                try {
                    EndpointPool.Lease lease = pool.acquire(null, null);
                    leases.add(lease);
                    if (lease.isProbe()) {
                        probes++;
                    }
                } catch (IOException circuitOpen) {
                    rejected++;
                }
            }
        } finally {
            for (EndpointPool.Lease lease : leases) {
                lease.close();
            }
        }
        assertEquals("单端点池半开时只放 1 个探测", 1, probes);
        assertEquals(4, rejected);
    }

    @Test
    public void probeSuccessClosesBreakerAndFiresEvent() throws Exception {
        final AtomicBoolean closed = new AtomicBoolean();
        final AtomicInteger opened = new AtomicInteger();
        EndpointPool pool = EndpointPool.builder()
                .add("http://a")
                .breaker(BreakerOptions.builder().consecutiveFailureThreshold(1).cooldownMs(50).build())
                .listener(new EndpointPoolListener() {
                    @Override
                    public void onBreakerOpen(EndpointPool p, Endpoint e, long cooldownMs) {
                        opened.incrementAndGet();
                    }

                    @Override
                    public void onBreakerClose(EndpointPool p, Endpoint e) {
                        closed.set(true);
                    }
                })
                .build();
        Endpoint a = pool.endpoints().get(0);
        EndpointPool.Lease first = pool.acquire(null, null);
        first.failure("down");
        first.close();
        assertEquals("熔断事件应上报", 1, opened.get());
        assertTrue(a.isCoolingDown(System.currentTimeMillis()));

        Thread.sleep(80);
        EndpointPool.Lease probe = pool.acquire(null, null);
        assertTrue("冷却结束后第一个请求应是探测", probe.isProbe());
        probe.success();
        probe.close();
        assertFalse("探测成功应解除熔断", a.isCoolingDown(System.currentTimeMillis()));
        assertTrue("恢复事件应上报", closed.get());
    }

    @Test
    public void manualDisableTakesEndpointOutOfRotation() throws Exception {
        EndpointPool pool = EndpointPool.builder().add("http://a").add("http://b").build();
        Endpoint a = pool.byOrigin("http://a");
        a.setDisabled(true);
        for (int i = 0; i < 20; i++) {
            EndpointPool.Lease lease = pool.acquire(null, null);
            try {
                assertEquals("手动摘流的端点不应再接流量", "http://b", lease.endpoint().getBaseUrl());
            } finally {
                lease.close();
            }
        }
        a.setDisabled(false);
        Set<String> seen = new HashSet<String>();
        for (int i = 0; i < 40; i++) {
            EndpointPool.Lease lease = pool.acquire(null, null);
            try {
                seen.add(lease.endpoint().getBaseUrl());
            } finally {
                lease.close();
            }
        }
        assertEquals("恢复后应重新参与调度", 2, seen.size());
    }

    // ---------- 池构造与去重 ----------

    @Test
    public void duplicateEndpointsAreDeduped() throws Exception {
        EndpointPool pool = EndpointPool.builder()
                .add("https://a.com", 1)
                .add("https://a.com/", 5)
                .add("https://A.com:443", 9)
                .build();
        assertEquals("同一 origin 只能有一个端点，否则健康状态会被劈成两份",
                1, pool.endpoints().size());
        assertEquals("应保留首次定义的权重", 1, pool.endpoints().get(0).getWeight());
        assertNotNull(pool.byOrigin("https://a.com/some/path?x=1"));
    }

    @Test
    public void emptyPoolWithoutDiscoveryIsRejected() throws Exception {
        try {
            EndpointPool.builder().build();
            fail("既没有端点也没有服务发现时应报错");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("at least one endpoint"));
        }
    }

    @Test
    public void serviceNameIsNormalizedToLowerCase() throws Exception {
        EndpointPool pool = EndpointPool.builder().serviceName("Order-Service").add("http://a").build();
        assertEquals("order-service", pool.getServiceName());
        assertNull(EndpointPool.builder().serviceName("  ").add("http://a").build().getServiceName());
    }

    // ---------- 服务发现与增量刷新 ----------

    @Test
    public void discoveryRefreshPreservesHealthStateOfSurvivingEndpoints() throws Exception {
        final List<Endpoint> current = new ArrayList<Endpoint>(Arrays.asList(
                new Endpoint("http://a:8080"), new Endpoint("http://b:8080")));
        ServiceDiscovery discovery = serviceName -> new ArrayList<Endpoint>(current);
        EndpointPool pool = EndpointPool.builder()
                .serviceName("svc")
                .discovery(discovery)
                .refreshIntervalMs(0)
                .breaker(BreakerOptions.builder().consecutiveFailureThreshold(1).cooldownMs(60_000).build())
                .build();
        try {
            Endpoint a = pool.byOrigin("http://a:8080");
            EndpointPool.Lease lease = pool.acquire(Collections.singleton("http://b:8080"), null);
            assertEquals(a.getBaseUrl(), lease.endpoint().getBaseUrl());
            lease.failure("down");
            lease.close();
            assertTrue(a.isCoolingDown(System.currentTimeMillis()));

            // 扩容：新增一个端点，a 仍在列表里
            current.add(new Endpoint("http://c:8080"));
            assertEquals(3, pool.refreshNow());
            Endpoint aAfter = pool.byOrigin("http://a:8080");
            assertTrue("刷新不得把已熔断端点的状态清零（否则坏节点立刻恢复接流）",
                    aAfter.isCoolingDown(System.currentTimeMillis()));

            // 缩容：移除 b
            current.removeIf(e -> e.getBaseUrl().equals("http://b:8080"));
            assertEquals(2, pool.refreshNow());
            assertNull("下线的端点应被移除", pool.byOrigin("http://b:8080"));
            assertNotNull(pool.byOrigin("http://c:8080"));
        } finally {
            pool.close();
        }
    }

    @Test
    public void discoveryFailureKeepsPreviousSnapshot() throws Exception {
        final AtomicBoolean fail = new AtomicBoolean();
        ServiceDiscovery discovery = serviceName -> {
            if (fail.get()) {
                throw new IOException("registry down");
            }
            return Collections.singletonList(new Endpoint("http://a:8080"));
        };
        final AtomicInteger failures = new AtomicInteger();
        EndpointPool pool = EndpointPool.builder()
                .serviceName("svc").discovery(discovery).refreshIntervalMs(0)
                .listener(new EndpointPoolListener() {
                    @Override
                    public void onDiscoveryFailure(EndpointPool p, Exception error) {
                        failures.incrementAndGet();
                    }
                })
                .build();
        try {
            assertEquals(1, pool.endpoints().size());
            fail.set(true);
            try {
                pool.refreshNow();
                fail("发现失败应抛异常");
            } catch (IOException expected) {
                // ok
            }
            assertEquals("失败必须上报事件，否则线上无法告警", 1, failures.get());
            assertEquals("fail-static：发现失败时保留旧端点，绝不清空", 1, pool.endpoints().size());
        } finally {
            pool.close();
        }
    }

    @Test
    public void emptyDiscoveryResultIsTreatedAsFailure() throws Exception {
        final AtomicBoolean empty = new AtomicBoolean();
        ServiceDiscovery discovery = serviceName -> empty.get()
                ? Collections.<Endpoint>emptyList()
                : Collections.singletonList(new Endpoint("http://a:8080"));
        EndpointPool pool = EndpointPool.builder()
                .serviceName("svc").discovery(discovery).refreshIntervalMs(0).build();
        try {
            empty.set(true);
            try {
                pool.refreshNow();
                fail("返回空列表应视为异常而不是清空池");
            } catch (IOException expected) {
                assertTrue(expected.getMessage().contains("no endpoints"));
            }
            assertEquals(1, pool.endpoints().size());
        } finally {
            pool.close();
        }
    }

    @Test
    public void endpointsChangedEventReportsAddedAndRemoved() throws Exception {
        final List<Endpoint> current = new ArrayList<Endpoint>(
                Collections.singletonList(new Endpoint("http://a:1")));
        ServiceDiscovery discovery = serviceName -> new ArrayList<Endpoint>(current);
        final List<String> events = new ArrayList<String>();
        EndpointPool pool = EndpointPool.builder()
                .serviceName("svc").discovery(discovery).refreshIntervalMs(0)
                .listener(new EndpointPoolListener() {
                    @Override
                    public void onEndpointsChanged(EndpointPool p, int added, int removed, int total) {
                        events.add(added + "/" + removed + "/" + total);
                    }
                })
                .build();
        try {
            events.clear();
            current.add(new Endpoint("http://b:1"));
            current.add(new Endpoint("http://c:1"));
            pool.refreshNow();
            assertEquals(Collections.singletonList("2/0/3"), events);

            events.clear();
            current.removeIf(e -> e.getBaseUrl().equals("http://a:1"));
            pool.refreshNow();
            assertEquals(Collections.singletonList("0/1/2"), events);
        } finally {
            pool.close();
        }
    }

    @Test
    public void staticDiscoveryReturnsGivenEndpoints() throws Exception {
        ServiceDiscovery discovery = new ServiceDiscovery.Static("http://a:1", "http://b:1");
        assertEquals(2, discovery.resolve("anything").size());
        try {
            new ServiceDiscovery.Static();
            fail("空静态列表应报错");
        } catch (IllegalArgumentException expected) {
            // ok
        }
    }

    @Test
    public void dnsDiscoveryResolvesLocalhostWithPortAndScheme() throws Exception {
        ServiceDiscovery discovery = new ServiceDiscovery.Dns("http", 8080);
        List<Endpoint> endpoints = discovery.resolve("localhost");
        assertFalse(endpoints.isEmpty());
        for (Endpoint endpoint : endpoints) {
            assertTrue("应带上指定端口: " + endpoint, endpoint.getBaseUrl().endsWith(":8080"));
            assertTrue(endpoint.getBaseUrl().startsWith("http://"));
        }
        try {
            discovery.resolve("no-such-host.invalid");
            fail("解析不到主机应抛 IOException");
        } catch (IOException expected) {
            // ok
        }
    }

    @Test
    public void dnsHostExtractionHandlesUrlForms() throws Exception {
        assertEquals("a.com", ServiceDiscovery.Dns.hostOf("http://a.com/x?y=1"));
        assertEquals("a.com", ServiceDiscovery.Dns.hostOf("a.com:8080"));
        assertEquals("a.com", ServiceDiscovery.Dns.hostOf("a.com"));
        assertEquals("a.com", ServiceDiscovery.Dns.hostOf("http://u:p@a.com/x"));
        assertEquals("[::1]", ServiceDiscovery.Dns.hostOf("http://[::1]:8080/x"));
    }

    @Test
    public void fallbackDiscoveryUsesFirstSuccess() throws Exception {
        ServiceDiscovery failing = serviceName -> {
            throw new IOException("nope");
        };
        ServiceDiscovery discovery = new ServiceDiscovery.Fallback(
                failing, new ServiceDiscovery.Static("http://backup:1"));
        List<Endpoint> endpoints = discovery.resolve("svc");
        assertEquals(1, endpoints.size());
        assertEquals("http://backup:1", endpoints.get(0).getBaseUrl());

        try {
            new ServiceDiscovery.Fallback(failing, failing).resolve("svc");
            fail("全部失败应抛异常");
        } catch (IOException expected) {
            assertTrue(expected.getMessage().contains("all discovery delegates failed"));
        }
    }

    // ---------- 全部不可用 ----------

    @Test
    public void acquireFailsClearlyWhenEveryEndpointIsExcluded() throws Exception {
        EndpointPool pool = EndpointPool.builder().add("http://a").add("http://b").build();
        Set<String> all = new LinkedHashSet<String>(Arrays.asList("http://a", "http://b"));
        try {
            pool.acquire(all, null);
            fail("全部端点被排除时应报错，而不是随便返回一个");
        } catch (IOException expected) {
            assertTrue(expected.getMessage().contains("unavailable"));
        }
    }

    private static int count(Map<String, Integer> map, String key) {
        Integer value = map.get(key);
        return value == null ? 0 : value;
    }

    private static Endpoint other(EndpointPool pool, Endpoint endpoint) {
        for (Endpoint candidate : pool.endpoints()) {
            if (!candidate.getBaseUrl().equals(endpoint.getBaseUrl())) {
                return candidate;
            }
        }
        return endpoint;
    }

    @Test
    public void metadataIsCarriedAndImmutable() throws Exception {
        Map<String, String> metadata = new HashMap<String, String>();
        metadata.put("clusterName", "BJ");
        Endpoint endpoint = new Endpoint("http://a:1", 2, metadata);
        assertEquals("BJ", endpoint.getMetadata().get("clusterName"));
        try {
            endpoint.getMetadata().put("x", "y");
            fail("元数据应只读");
        } catch (UnsupportedOperationException expected) {
            // ok
        }
    }
}
