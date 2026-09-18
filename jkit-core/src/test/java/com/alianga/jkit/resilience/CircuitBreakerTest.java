package com.alianga.jkit.resilience;

import org.junit.Assert;
import org.junit.Test;

import java.io.IOException;
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
}
