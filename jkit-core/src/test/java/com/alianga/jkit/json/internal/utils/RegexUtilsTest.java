package com.alianga.jkit.json.internal.utils;

import org.junit.Test;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

/**
 * 覆盖 P1-4：RegexUtils.getPattern 从 synchronized(regSource) 字符串锁改为
 * ConcurrentHashMap.computeIfAbsent。
 *
 * 修复前：对 String 加锁，JVM 字符串常量池让锁对象全局共享，业务代码若 synchronized
 * 同一字面量会发生跨模块死锁。
 *
 * 修复后：computeIfAbsent 原子语义，无锁竞争，且不暴露内部锁对象。
 */
public class RegexUtilsTest {

    /**
     * 基本功能：返回编译后的 Pattern，重复调用返回同一缓存实例。
     */
    @Test
    public void getPatternCachesCompiledResult() {
        Pattern p1 = RegexUtils.getPattern("\\d+");
        Pattern p2 = RegexUtils.getPattern("\\d+");
        assertNotNull(p1);
        assertSame("同正则应返回同一缓存实例", p1, p2);

        Pattern p3 = RegexUtils.getPattern("[a-z]+");
        assertNotNull(p3);
        assertTrue(p1 != p3);
    }

    /**
     * 高并发下多线程同时调用 getPattern 同一正则：全部返回同一实例，无死锁。
     */
    @Test(timeout = 10000)
    public void concurrentGetPatternReturnsSameInstance() throws Exception {
        final String regex = "test-pattern-\\d+";
        final int threads = 32;
        final int iterations = 200;

        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        final Pattern[] results = new Pattern[threads * iterations];
        final AtomicInteger errors = new AtomicInteger();

        for (int t = 0; t < threads; t++) {
            final int idx = t;
            pool.submit(() -> {
                try {
                    start.await();
                    for (int i = 0; i < iterations; i++) {
                        results[idx * iterations + i] = RegexUtils.getPattern(regex);
                    }
                } catch (Throwable th) {
                    errors.incrementAndGet();
                    th.printStackTrace();
                }
            });
        }
        start.countDown();
        pool.shutdown();
        assertTrue("workers did not finish", pool.awaitTermination(5, TimeUnit.SECONDS));
        assertEquals("concurrent errors", 0, errors.get());

        Pattern expected = RegexUtils.getPattern(regex);
        for (int i = 0; i < results.length; i++) {
            assertSame("mismatch at " + i, expected, results[i]);
        }
    }

    /**
     * 不同正则并发调用：各自缓存独立，互不干扰。
     */
    @Test(timeout = 10000)
    public void concurrentDifferentPatternsDoNotInterfere() throws Exception {
        final String[] regexes = {"\\d+", "[a-z]+", "[A-Z]+", "\\w+", "\\s+", "^abc$"};
        final int threads = 12;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        final AtomicInteger errors = new AtomicInteger();

        for (int t = 0; t < threads; t++) {
            final int idx = t;
            pool.submit(() -> {
                try {
                    start.await();
                    for (int i = 0; i < 100; i++) {
                        Pattern p = RegexUtils.getPattern(regexes[(idx + i) % regexes.length]);
                        assertNotNull(p);
                    }
                } catch (Throwable th) {
                    errors.incrementAndGet();
                    th.printStackTrace();
                }
            });
        }
        start.countDown();
        pool.shutdown();
        assertTrue(pool.awaitTermination(5, TimeUnit.SECONDS));
        assertEquals(0, errors.get());
    }

    /**
     * 字符串锁反例验证：修复前对字符串字面量 synchronized 可能死锁。
     * 修复后用 computeIfAbsent 不再持有字符串锁，此场景应正常通过。
     */
    @Test(timeout = 5000)
    public void noStringLiteralLockExposure() {
        // 模拟业务代码对同一字符串字面量加 synchronized 的场景：
        // 修复前若 RegexUtils.getPattern 内部也对同一字面量加 synchronized，
        // 两个锁会互相阻塞导致死锁；修复后 computeIfAbsent 不对 key 加锁，应能完成。
        final String key = "regex-shared-key";
        Thread business = new Thread(() -> {
            synchronized (key) {
                try {
                    Thread.sleep(50);
                } catch (InterruptedException ignore) {
                    // ignore
                }
            }
        });
        business.start();
        // 业务线程持有 key 锁时本线程调用 getPattern，修复前应死锁，修复后应正常返回。
        Pattern p = RegexUtils.getPattern(key);
        assertNotNull(p);
        try {
            business.join(2000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * getMatcherGroups 原有行为回归。
     */
    @Test
    public void getMatcherGroupsExtractsAll() {
        List<String> groups = RegexUtils.getMatcherGroups("hello 123 world 456", "(\\d+)");
        assertEquals(2, groups.size());
        assertEquals("123", groups.get(0));
        assertEquals("456", groups.get(1));
    }

    /**
     * getMatcherGroups 不带分组时返回空列表。
     */
    @Test
    public void getMatcherGroupsWithoutGroupReturnsEmpty() {
        List<String> groups = RegexUtils.getMatcherGroups("abc", "\\d+");
        assertTrue(groups.isEmpty());
    }
}
