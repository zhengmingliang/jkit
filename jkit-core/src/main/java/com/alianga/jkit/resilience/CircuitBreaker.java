package com.alianga.jkit.resilience;

import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.LongSupplier;

/**
 * 通用熔断器抽包：包裹任意 {@link Callable}，在连续失败达到阈值后拒绝请求（OPEN），
 * 冷却一段时间后放行少量探测（HALF_OPEN），探测成功则恢复（CLOSED）。与 HTTP 无关，
 * 可用于 notify 发送、外部 RPC 等"失败会持续一段时间"的场景。
 * <p>
 * 实例非单例共享：每个需要独立熔断策略的依赖（如某个通知渠道）应持有自己的 {@code CircuitBreaker}。
 * 内部状态使用原子变量，可在多线程下安全使用。
 */
public final class CircuitBreaker {
    /**
     * 创建一个处于 {@link State#CLOSED} 状态的熔断器。每个需要独立熔断策略的依赖
     * （如某个通知渠道）应持有自己的实例。
     */
    public CircuitBreaker() {
    }

    /**
     * 熔断器状态。
     */
    public enum State {
        /** 正常放行，失败计数达到阈值后转入 OPEN。 */
        CLOSED,
        /** 已熔断，直接拒绝任务并抛出 {@link CircuitBreakerOpenException}。 */
        OPEN,
        /** 半开，冷却结束后同一时刻只放行一个探测请求，探测成功则恢复（CLOSED）。 */
        HALF_OPEN
    }

    /**
     * 熔断器配置：默认连续失败 5 次熔断、半开需 1 次成功恢复、冷却 30s。
     */
    public static final class Config {
        private int failureThreshold = 5;
        private int successThreshold = 1;
        private long cooldownMs = 30_000L;
        private LongSupplier clock = System::currentTimeMillis;

        /**
         * @param value 连续失败多少次后熔断，至少 1
         * @return this
         */
        public Config failureThreshold(int value) {
            this.failureThreshold = Math.max(1, value);
            return this;
        }

        /**
         * @param value 半开状态下需要多少次连续成功才恢复，至少 1
         * @return this
         */
        public Config successThreshold(int value) {
            this.successThreshold = Math.max(1, value);
            return this;
        }

        /**
         * @param value 熔断后多久进入半开探测，至少 0
         * @return this
         */
        public Config cooldownMs(long value) {
            this.cooldownMs = Math.max(0L, value);
            return this;
        }

        /**
         * 替换时钟，主要用于测试（注入可控时间，避免真实等待冷却）。
         *
         * @param value 时钟函数（返回毫秒时间戳）
         * @return this
         */
        Config clock(LongSupplier value) {
            this.clock = value == null ? System::currentTimeMillis : value;
            return this;
        }

        int getFailureThreshold() {
            return failureThreshold;
        }

        int getSuccessThreshold() {
            return successThreshold;
        }

        long getCooldownMs() {
            return cooldownMs;
        }

        LongSupplier getClock() {
            return clock;
        }
    }

    private final AtomicReference<State> state = new AtomicReference<State>(State.CLOSED);
    private final AtomicInteger consecutiveFailures = new AtomicInteger(0);
    private final AtomicInteger halfOpenSuccesses = new AtomicInteger(0);
    private final AtomicLong openedAt = new AtomicLong(0L);
    /** 半开探测许可：进入 HALF_OPEN 时置 1，赢得许可的调用才能执行探测任务，其余拒绝。 */
    private final AtomicInteger probePermit = new AtomicInteger(0);

    /**
     * @return 当前熔断器状态
     */
    public State state() {
        return state.get();
    }

    /**
     * 用默认配置执行任务。
     *
     * @param task 待执行任务
     * @param <T> 返回类型
     * @return 任务结果
     * @throws Exception 任务异常，或熔断打开时抛出 {@link CircuitBreakerOpenException}
     */
    public <T> T run(Callable<T> task) throws Exception {
        return run(task, new Config());
    }

    /**
     * 用给定配置执行任务。
     *
     * @param task 待执行任务
     * @param config 熔断配置
     * @param <T> 返回类型
     * @return 任务结果
     * @throws Exception 任务异常，或熔断打开时抛出 {@link CircuitBreakerOpenException}
     */
    public <T> T run(Callable<T> task, Config config) throws Exception {
        if (task == null) {
            throw new IllegalArgumentException("task must not be null");
        }
        Config cfg = config == null ? new Config() : config;
        maybeHalfOpen(cfg);
        State current = state.get();
        if (current == State.OPEN) {
            throw new CircuitBreakerOpenException();
        }
        if (current == State.HALF_OPEN && !probePermit.compareAndSet(1, 0)) {
            // 半开状态下同一时刻只放行一个探测请求，其余按熔断拒绝，
            // 避免冷却结束瞬间全量并发流量涌入打挂未恢复的上游
            throw new CircuitBreakerOpenException();
        }
        try {
            T result = task.call();
            onSuccess(cfg);
            return result;
        } catch (Throwable t) {
            onFailure(cfg);
            if (t instanceof Exception) {
                throw (Exception) t;
            }
            if (t instanceof Error) {
                // OOM/StackOverflow 等保持原类型抛出，与 Retryer 语义一致
                throw (Error) t;
            }
            throw new ResilienceException("circuit breaker task failed", t);
        }
    }

    private void maybeHalfOpen(Config cfg) {
        if (state.get() == State.OPEN
                && cfg.getClock().getAsLong() - openedAt.get() >= cfg.getCooldownMs()) {
            if (state.compareAndSet(State.OPEN, State.HALF_OPEN)) {
                halfOpenSuccesses.set(0);
                probePermit.set(1);
            }
        }
    }

    private void onSuccess(Config cfg) {
        while (true) {
            State s = state.get();
            if (s == State.HALF_OPEN) {
                if (halfOpenSuccesses.incrementAndGet() >= cfg.getSuccessThreshold()) {
                    if (state.compareAndSet(State.HALF_OPEN, State.CLOSED)) {
                        consecutiveFailures.set(0);
                        return;
                    }
                    // 状态已被并发改变（如另一路径先转 OPEN），重读后重新裁决
                    continue;
                }
                // 未达恢复阈值：保持半开，放行下一个探测
                probePermit.set(1);
                return;
            }
            if (s == State.CLOSED) {
                consecutiveFailures.set(0);
            }
            // OPEN：本次成功来自已被裁决过的过期探测，忽略
            return;
        }
    }

    private void onFailure(Config cfg) {
        while (true) {
            State s = state.get();
            if (s == State.HALF_OPEN) {
                // 先写 openedAt 再 CAS，保证 OPEN 对外可见时冷却计时已生效，
                // 避免并发 maybeHalfOpen 用旧时间戳立即又转回半开
                openedAt.set(cfg.getClock().getAsLong());
                if (state.compareAndSet(State.HALF_OPEN, State.OPEN)) {
                    return;
                }
                continue;
            }
            if (s == State.OPEN) {
                return;
            }
            if (consecutiveFailures.incrementAndGet() >= cfg.getFailureThreshold()) {
                openedAt.set(cfg.getClock().getAsLong());
                if (state.compareAndSet(State.CLOSED, State.OPEN)) {
                    return;
                }
                continue;
            }
            return;
        }
    }
}
