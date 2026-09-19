package com.alianga.jkit.resilience;

import org.junit.Assert;
import org.junit.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicInteger;

public class RetryerTest {

    private static final class RecordingSleeper implements Retryer.Sleeper {
        final List<Long> waits = new ArrayList<Long>();

        @Override
        public void sleep(long millis) {
            waits.add(millis);
        }
    }

    @Test
    public void succeedsOnFirstTryWithoutRetry() throws Exception {
        AtomicInteger calls = new AtomicInteger(0);
        String result = Retryer.retry(() -> {
            calls.incrementAndGet();
            return "ok";
        });
        Assert.assertEquals("ok", result);
        Assert.assertEquals(1, calls.get());
    }

    @Test
    public void retriesThenSucceeds() throws Exception {
        RecordingSleeper sleeper = new RecordingSleeper();
        Retryer.RetryConfig config = Retryer.defaults().maxAttempts(3).baseDelayMs(100L).maxDelayMs(1000L)
                .jitter(0.0).sleeper(sleeper);
        final AtomicInteger calls = new AtomicInteger(0);
        Callable<String> task = () -> {
            int n = calls.incrementAndGet();
            if (n < 3) {
                throw new IOException("fail #" + n);
            }
            return "recovered";
        };
        String result = Retryer.retry(task, config);
        Assert.assertEquals("recovered", result);
        Assert.assertEquals(3, calls.get());
        Assert.assertEquals(2, sleeper.waits.size());
        Assert.assertEquals(Long.valueOf(100L), sleeper.waits.get(0));
        Assert.assertEquals(Long.valueOf(200L), sleeper.waits.get(1));
    }

    @Test
    public void exhaustsAttemptsThenThrowsLast() {
        Retryer.RetryConfig config = Retryer.defaults().maxAttempts(4).sleeper(m -> {
        });
        final AtomicInteger calls = new AtomicInteger(0);
        IOException last = Assert.assertThrows(IOException.class, () -> Retryer.retry(() -> {
            calls.incrementAndGet();
            throw new IOException("boom");
        }, config));
        Assert.assertEquals("boom", last.getMessage());
        Assert.assertEquals(4, calls.get());
    }

    @Test
    public void noRetryWhenPredicateRejects() {
        Retryer.RetryConfig config = Retryer.defaults().maxAttempts(5)
                .retryOn(IllegalStateException.class).sleeper(m -> {
                });
        final AtomicInteger calls = new AtomicInteger(0);
        Assert.assertThrows(IllegalArgumentException.class, () -> Retryer.retry(() -> {
            calls.incrementAndGet();
            throw new IllegalArgumentException("bad");
        }, config));
        Assert.assertEquals(1, calls.get());
    }

    @Test
    public void doesNotRetryError() {
        Retryer.RetryConfig config = Retryer.defaults().sleeper(m -> {
        });
        final AtomicInteger calls = new AtomicInteger(0);
        Assert.assertThrows(AssertionError.class, () -> Retryer.retry(() -> {
            calls.incrementAndGet();
            throw new AssertionError("fatal");
        }, config));
        Assert.assertEquals(1, calls.get());
    }

    @Test
    public void nullPredicateMeansNoRetry() {
        Retryer.RetryConfig config = Retryer.defaults().maxAttempts(3).retryOn((java.util.function.Predicate<Throwable>) null)
                .sleeper(m -> {
                });
        final AtomicInteger calls = new AtomicInteger(0);
        Assert.assertThrows(IOException.class, () -> Retryer.retry(() -> {
            calls.incrementAndGet();
            throw new IOException("x");
        }, config));
        Assert.assertEquals(1, calls.get());
    }

    @Test
    public void backoffNeverExceedsMaxDelay() throws Exception {
        RecordingSleeper sleeper = new RecordingSleeper();
        Retryer.RetryConfig config = Retryer.defaults().maxAttempts(6).baseDelayMs(100L).maxDelayMs(500L)
                .jitter(0.0).sleeper(sleeper);
        final AtomicInteger calls = new AtomicInteger(0);
        // 6 次尝试 => 5 次等待；退避 100,200,400,500,500，均 <= 500
        try {
            Retryer.retry(() -> {
                calls.incrementAndGet();
                throw new IOException("always");
            }, config);
        } catch (IOException ignored) {
            // 期望最终失败
        }
        Assert.assertEquals(5, sleeper.waits.size());
        for (Long w : sleeper.waits) {
            Assert.assertTrue("wait must be positive", w > 0L);
            Assert.assertTrue("wait must not exceed maxDelay", w <= 500L);
        }
    }

    @Test
    public void runnableVariantRethrowsAsRuntimeException() {
        Retryer.RetryConfig config = Retryer.defaults().maxAttempts(2).retryOn(RuntimeException.class)
                .sleeper(m -> {
                });
        final AtomicInteger calls = new AtomicInteger(0);
        Assert.assertThrows(IllegalStateException.class, () -> Retryer.retry(() -> {
            calls.incrementAndGet();
            throw new IllegalStateException("runnable fail");
        }, config));
        Assert.assertEquals(2, calls.get());
    }

    @Test
    public void taskInterruptedStopsRetryAndRestoresFlag() {
        // 中断是协作式取消信号：不当作普通失败重试，且恢复中断标志
        final AtomicInteger calls = new AtomicInteger(0);
        try {
            Retryer.retry(() -> {
                calls.incrementAndGet();
                throw new InterruptedException("stopped");
            });
            Assert.fail("应抛出 InterruptedException");
        } catch (InterruptedException expected) {
            // 期望路径
        } catch (Exception unexpected) {
            Assert.fail("应原样抛出 InterruptedException: " + unexpected);
        }
        Assert.assertEquals("中断后不应继续重试", 1, calls.get());
        Assert.assertTrue("中断标志应恢复", Thread.currentThread().isInterrupted());
        // 清除本线程中断标志，避免影响同线程的后续测试
        Thread.interrupted();
    }
}
