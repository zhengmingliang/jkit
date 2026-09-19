package com.alianga.jkit.resilience;

/**
 * 熔断器处于 {@link CircuitBreaker.State#OPEN} 状态时，任务被直接拒绝抛出，
 * 表示上游依赖被认为不可用，短期内不应继续打请求。
 */
public class CircuitBreakerOpenException extends ResilienceException {
    /**
     * 构造默认信息的异常。
     */
    public CircuitBreakerOpenException() {
        super("circuit breaker is open");
    }
}
