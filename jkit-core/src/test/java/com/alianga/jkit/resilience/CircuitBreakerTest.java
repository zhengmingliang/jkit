package com.alianga.jkit.resilience;

import org.junit.Assert;
import org.junit.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;

public class CircuitBreakerTest {

    private static final class MutableClock implements LongSupplier {
        final AtomicLong now = new AtomicLong(0L);

        @Override
        public long getAsLong() {
            return now.get();
        }
    }

    @Test
    public void closedAllowsCallsAndResetsFailures() throws Exception {
        CircuitBreaker breaker = new CircuitBreaker();
        Assert.assertEquals(CircuitBreaker.State.CLOSED, breaker.state());
        AtomicInteger calls = new AtomicInteger(0);
        String r = breaker.run(() -> {
            calls.incrementAndGet();
            return "ok";
        });
        Assert.assertEquals("ok", r);
        Assert.assertEquals(1, calls.get());
    }

    @Test
    public void opensAfterFailureThreshold() throws Exception {
        MutableClock clock = new MutableClock();
        CircuitBreaker breaker = new CircuitBreaker();
        CircuitBreaker.Config config = new CircuitBreaker.Config().failureThreshold(3).cooldownMs(1000L)
                .clock(clock);
        for (int i = 0; i < 3; i++) {
            try {
                breaker.run(() -> {
                    throw new IOException("down");
                }, config);
            } catch (IOException ignored) {
                // 期望失败
            }
        }
        Assert.assertEquals(CircuitBreaker.State.OPEN, breaker.state());
        Assert.assertThrows(CircuitBreakerOpenException.class, () -> breaker.run(() -> "x", config));
    }

    @Test
    public void openRejectsWithoutCallingTask() throws Exception {
        MutableClock clock = new MutableClock();
        CircuitBreaker breaker = new CircuitBreaker();
        CircuitBreaker.Config config = new CircuitBreaker.Config().failureThreshold(2).cooldownMs(1000L)
                .clock(clock);
        for (int i = 0; i < 2; i++) {
            try {
                breaker.run(() -> {
                    throw new IOException("down");
                }, config);
            } catch (IOException ignored) {
                // 期望失败
            }
        }
        AtomicInteger calls = new AtomicInteger(0);
        Assert.assertThrows(CircuitBreakerOpenException.class, () -> breaker.run(() -> {
            calls.incrementAndGet();
            return "x";
        }, config));
        Assert.assertEquals(0, calls.get());
    }

    @Test
    public void halfOpenAfterCooldownAllowsProbe() throws Exception {
        MutableClock clock = new MutableClock();
        CircuitBreaker breaker = new CircuitBreaker();
        CircuitBreaker.Config config = new CircuitBreaker.Config().failureThreshold(2).cooldownMs(1000L)
                .clock(clock);
        for (int i = 0; i < 2; i++) {
            try {
                breaker.run(() -> {
                    throw new IOException("down");
                }, config);
            } catch (IOException ignored) {
                // 期望失败
            }
        }
        Assert.assertEquals(CircuitBreaker.State.OPEN, breaker.state());
        clock.now.set(1001L);
        AtomicInteger calls = new AtomicInteger(0);
        String r = breaker.run(() -> {
            calls.incrementAndGet();
            return "probe";
        }, config);
        Assert.assertEquals("probe", r);
        Assert.assertEquals(1, calls.get());
    }

    @Test
    public void halfOpenClosesOnSuccess() throws Exception {
        MutableClock clock = new MutableClock();
        CircuitBreaker breaker = new CircuitBreaker();
        CircuitBreaker.Config config = new CircuitBreaker.Config().failureThreshold(2).cooldownMs(1000L)
                .clock(clock);
        for (int i = 0; i < 2; i++) {
            try {
                breaker.run(() -> {
                    throw new IOException("down");
                }, config);
            } catch (IOException ignored) {
                // 期望失败
            }
        }
        clock.now.set(1001L);
        breaker.run(() -> "recovered");
        Assert.assertEquals(CircuitBreaker.State.CLOSED, breaker.state());
        // 恢复后调用不再被拒绝
        Assert.assertEquals("ok", breaker.run(() -> "ok"));
    }

    @Test
    public void halfOpenReopensOnFailure() throws Exception {
        MutableClock clock = new MutableClock();
        CircuitBreaker breaker = new CircuitBreaker();
        CircuitBreaker.Config config = new CircuitBreaker.Config().failureThreshold(2).cooldownMs(1000L)
                .clock(clock);
        for (int i = 0; i < 2; i++) {
            try {
                breaker.run(() -> {
                    throw new IOException("down");
                }, config);
            } catch (IOException ignored) {
                // 期望失败
            }
        }
        clock.now.set(1001L);
        try {
            breaker.run(() -> {
                throw new IOException("still down");
            }, config);
        } catch (IOException ignored) {
            // 期望失败
        }
        Assert.assertEquals(CircuitBreaker.State.OPEN, breaker.state());
    }

    @Test
    public void successResetsFailureCounter() throws Exception {
        MutableClock clock = new MutableClock();
        CircuitBreaker breaker = new CircuitBreaker();
        CircuitBreaker.Config config = new CircuitBreaker.Config().failureThreshold(3).cooldownMs(1000L)
                .clock(clock);
        try {
            breaker.run(() -> {
                throw new IOException("1");
            }, config);
        } catch (IOException ignored) {
            // 期望失败
        }
        breaker.run(() -> "ok");
        // 一次成功把失败计数清零，再失败两次不该打开
        try {
            breaker.run(() -> {
                throw new IOException("2");
            }, config);
        } catch (IOException ignored) {
            // 期望失败
        }
        try {
            breaker.run(() -> {
                throw new IOException("3");
            }, config);
        } catch (IOException ignored) {
            // 期望失败
        }
        Assert.assertEquals(CircuitBreaker.State.CLOSED, breaker.state());
    }

    // ------------------------------------------------------------------
    // 修复回归：半开此前不限探测并发且状态转换基于陈旧快照（无条件 set），
    // 并发探测的成功可能把刚 OPEN 的熔断覆盖回 CLOSED。现在半开同一时刻
    // 只放行一个探测，状态转换全部 CAS。
    // ------------------------------------------------------------------

    @Test
    public void halfOpenAllowsOnlyOneConcurrentProbe() throws Exception {
        MutableClock clock = new MutableClock();
        final CircuitBreaker breaker = new CircuitBreaker();
        final CircuitBreaker.Config config = new CircuitBreaker.Config().failureThreshold(2).cooldownMs(1000L)
                .clock(clock);
        for (int i = 0; i < 2; i++) {
            try {
                breaker.run(() -> {
                    throw new IOException("down");
                }, config);
            } catch (IOException ignored) {
                // 期望失败
            }
        }
        clock.now.set(1001L);
        final CountDownLatch probeStarted = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);
        final AtomicInteger executed = new AtomicInteger(0);
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            Future<String> probe = pool.submit(() -> {
                return breaker.run(() -> {
                    executed.incrementAndGet();
                    probeStarted.countDown();
                    release.await();
                    return "probe";
                }, config);
            });
            Assert.assertTrue("探测任务应开始执行", probeStarted.await(5, TimeUnit.SECONDS));
            // 探测进行中：其余调用按熔断拒绝，而不是全部放行
            for (int i = 0; i < 8; i++) {
                try {
                    breaker.run(() -> "x", config);
                    Assert.fail("半开探测期间应拒绝新调用");
                } catch (CircuitBreakerOpenException expected) {
                    // 期望拒绝
                }
            }
            Assert.assertEquals(1, executed.get());
            release.countDown();
            Assert.assertEquals("probe", probe.get(5, TimeUnit.SECONDS));
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    public void halfOpenWithHigherSuccessThresholdKeepsProbing() throws Exception {
        // 成功阈值 >1 时，一次成功后必须继续放行下一个探测，而不是卡死在半开
        MutableClock clock = new MutableClock();
        CircuitBreaker breaker = new CircuitBreaker();
        CircuitBreaker.Config config = new CircuitBreaker.Config().failureThreshold(2).successThreshold(2)
                .cooldownMs(1000L).clock(clock);
        for (int i = 0; i < 2; i++) {
            try {
                breaker.run(() -> {
                    throw new IOException("down");
                }, config);
            } catch (IOException ignored) {
                // 期望失败
            }
        }
        clock.now.set(1001L);
        Assert.assertEquals("probe1", breaker.run(() -> "probe1", config));
        Assert.assertEquals("第一次成功不应立即恢复", CircuitBreaker.State.HALF_OPEN, breaker.state());
        Assert.assertEquals("probe2", breaker.run(() -> "probe2", config));
        Assert.assertEquals(CircuitBreaker.State.CLOSED, breaker.state());
    }

    @Test
    public void concurrentFailingProbesEndOpen() throws Exception {
        // 并发探测全部失败：必须稳定回到 OPEN，不允许出现陈旧快照导致的错误状态
        for (int round = 0; round < 20; round++) {
            MutableClock clock = new MutableClock();
            final CircuitBreaker breaker = new CircuitBreaker();
            final CircuitBreaker.Config config = new CircuitBreaker.Config().failureThreshold(2).cooldownMs(1000L)
                    .clock(clock);
            for (int i = 0; i < 2; i++) {
                try {
                    breaker.run(() -> {
                        throw new IOException("down");
                    }, config);
                } catch (IOException ignored) {
                    // 期望失败
                }
            }
            clock.now.set(1001L * (round + 2));
            final CyclicBarrier barrier = new CyclicBarrier(8);
            ExecutorService pool = Executors.newFixedThreadPool(8);
            try {
                List<Future<String>> futures = new ArrayList<Future<String>>();
                for (int i = 0; i < 8; i++) {
                    futures.add(pool.submit(() -> {
                        barrier.await();
                        try {
                            return breaker.run(() -> {
                                throw new IOException("still down");
                            }, config);
                        } catch (CircuitBreakerOpenException e) {
                            return "rejected";
                        } catch (Exception e) {
                            // 探测真正执行后的业务失败路径
                            return "failed";
                        }
                    }));
                }
                int executed = 0;
                for (Future<String> f : futures) {
                    if (!"rejected".equals(f.get(5, TimeUnit.SECONDS))) {
                        executed++;
                    }
                }
                Assert.assertEquals("每轮只应有一个探测真正执行", 1, executed);
                Assert.assertEquals(CircuitBreaker.State.OPEN, breaker.state());
            } finally {
                pool.shutdownNow();
            }
        }
    }
}
