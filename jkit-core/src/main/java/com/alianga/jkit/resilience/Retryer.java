package com.alianga.jkit.resilience;

import java.util.concurrent.Callable;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Predicate;

/**
 * 通用重试抽包：把任意 {@link Callable} 包裹起来，在失败时按指数退避 + 抖动进行有限次重试，与 HTTP 无关。
 * <p>
 * 典型用途：包裹 notify 的消息发送、外部 RPC、一次性文件操作等可重试任务。默认对所有
 * {@link Exception} 重试（不重试 {@link Error}），并可通过 {@link RetryConfig#retryOn(Predicate)}
 * 收窄范围，避免对不可恢复错误（如参数错误）反复重试。
 * <p>
 * 退避公式：第 {@code attempt} 次重试前等待 {@code min(maxDelayMs, baseDelayMs * 2^(attempt-1))}，
 * 再叠加 ±jitter 的随机抖动（避免上游恢复瞬间所有客户端同时重试形成惊群）。
 */
public final class Retryer {
    private Retryer() {
    }

    /**
     * 休眠函数：重试间隔的等待实现，便于测试时替换为记录型实现。
     */
    @FunctionalInterface
    public interface Sleeper {
        /**
         * 休眠指定毫秒。
         *
         * @param millis 毫秒
         * @throws InterruptedException 线程被中断
         */
        void sleep(long millis) throws InterruptedException;
    }

    /**
     * 重试配置：默认 3 次尝试、200ms 指数退避（上限 10s）、±20% 抖动、重试所有非 {@link Error} 异常。
     */
    public static final class RetryConfig {
        private int maxAttempts = 3;
        private long baseDelayMs = 200L;
        private long maxDelayMs = 10_000L;
        private double jitter = 0.2;
        private Predicate<Throwable> retryOn = t -> !(t instanceof Error);
        private Sleeper sleeper = Thread::sleep;

        /**
         * @param value 最大尝试次数（含首次），至少 1
         * @return this
         */
        public RetryConfig maxAttempts(int value) {
            this.maxAttempts = Math.max(1, value);
            return this;
        }

        /**
         * @param value 首次重试前的退避基准毫秒，至少 0
         * @return this
         */
        public RetryConfig baseDelayMs(long value) {
            this.baseDelayMs = Math.max(0L, value);
            return this;
        }

        /**
         * @param value 退避上限毫秒，至少 0
         * @return this
         */
        public RetryConfig maxDelayMs(long value) {
            this.maxDelayMs = Math.max(0L, value);
            return this;
        }

        /**
         * 抖动系数：实际等待在 {@code base * (1 ± factor)} 之间随机，避免上游恢复瞬间惊群。
         *
         * @param value 取值 [0, 1]
         * @return this
         */
        public RetryConfig jitter(double value) {
            this.jitter = Math.min(1.0, Math.max(0.0, value));
            return this;
        }

        /**
         * 决定哪些异常允许重试。默认重试所有非 {@link Error} 异常。
         *
         * @param predicate 判定函数；传 {@code null} 表示任何异常都不重试
         * @return this
         */
        public RetryConfig retryOn(Predicate<Throwable> predicate) {
            this.retryOn = predicate == null ? (t -> false) : predicate;
            return this;
        }

        /**
         * 仅对指定类型的异常重试。
         *
         * @param type 异常类型
         * @return this
         */
        public RetryConfig retryOn(Class<? extends Throwable> type) {
            this.retryOn = type::isInstance;
            return this;
        }

        /**
         * 替换休眠实现，主要用于测试。
         *
         * @param value 休眠函数
         * @return this
         */
        RetryConfig sleeper(Sleeper value) {
            this.sleeper = value == null ? (m -> {}) : value;
            return this;
        }

        int getMaxAttempts() {
            return maxAttempts;
        }

        long getBaseDelayMs() {
            return baseDelayMs;
        }

        long getMaxDelayMs() {
            return maxDelayMs;
        }

        double getJitter() {
            return jitter;
        }

        Predicate<Throwable> getRetryOn() {
            return retryOn;
        }

        Sleeper getSleeper() {
            return sleeper;
        }
    }

    /**
     * 用默认配置重试任务：3 次尝试、200ms 指数退避（上限 10s）、±20% 抖动、重试所有非 {@link Error} 异常。
     *
     * @param task 待执行任务
     * @param <T> 返回类型
     * @return 任务结果
     * @throws Exception 任务自身抛出的异常（重试耗尽后抛出最后一次）
     */
    public static <T> T retry(Callable<T> task) throws Exception {
        return retry(task, defaults());
    }

    /**
     * 用给定配置重试任务。
     *
     * @param task 待执行任务
     * @param config 重试配置
     * @param <T> 返回类型
     * @return 任务结果
     * @throws Exception 任务自身抛出的异常（重试耗尽后抛出最后一次）
     */
    public static <T> T retry(Callable<T> task, RetryConfig config) throws Exception {
        if (task == null) {
            throw new IllegalArgumentException("task must not be null");
        }
        RetryConfig cfg = config == null ? defaults() : config;
        Throwable last = null;
        int attempts = cfg.getMaxAttempts();
        for (int attempt = 1; attempt <= attempts; attempt++) {
            try {
                return task.call();
            } catch (InterruptedException ie) {
                // 中断是协作式取消信号：恢复中断标志并立即终止，不当作普通失败重试
                Thread.currentThread().interrupt();
                throw ie;
            } catch (Throwable t) {
                last = t;
                if (attempt >= attempts || !cfg.getRetryOn().test(t)) {
                    break;
                }
                long wait = computeDelay(attempt, cfg);
                if (wait > 0L) {
                    try {
                        cfg.getSleeper().sleep(wait);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        ResilienceException interrupted = new ResilienceException("retry interrupted", ie);
                        if (last != null) {
                            interrupted.addSuppressed(last);
                        }
                        throw interrupted;
                    }
                }
            }
        }
        if (last instanceof Exception) {
            throw (Exception) last;
        }
        if (last instanceof Error) {
            throw (Error) last;
        }
        throw new ResilienceException("task failed without exception");
    }

    /**
     * 重试无返回值任务（任务抛出的异常以 {@link RuntimeException} 形式传播）。
     *
     * @param task 待执行任务
     * @param config 重试配置
     * @throws Exception 任务异常或重试耗尽后抛出的最后一次异常
     */
    public static void retry(Runnable task, RetryConfig config) throws Exception {
        retry(() -> {
            task.run();
            return null;
        }, config);
    }

    /**
     * @return 默认重试配置
     */
    public static RetryConfig defaults() {
        return new RetryConfig();
    }

    private static long computeDelay(int attempt, RetryConfig cfg) {
        long base = cfg.getBaseDelayMs();
        if (base <= 0L) {
            return 0L;
        }
        long value = base;
        for (int i = 1; i < attempt && value < cfg.getMaxDelayMs(); i++) {
            long next = value * 2L;
            if (next > cfg.getMaxDelayMs() || next < 0L) {
                value = cfg.getMaxDelayMs();
                break;
            }
            value = next;
        }
        value = Math.min(value, cfg.getMaxDelayMs());
        double jitter = cfg.getJitter();
        if (jitter <= 0.0) {
            return value;
        }
        double span = value * jitter;
        double offset = ThreadLocalRandom.current().nextDouble(-span, span);
        long result = (long) (value + offset);
        return result < 0L ? 0L : result;
    }
}
