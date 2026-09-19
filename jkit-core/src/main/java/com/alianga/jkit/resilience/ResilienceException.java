package com.alianga.jkit.resilience;

/**
 * 韧性抽包内部异常：重试被中断、或任务以非 {@link Exception} / 非 {@link Error} 形式失败时使用。
 * 调用方通常只需把它当作可重试任务失败的信号，必要时读取 {@link #getCause()}。
 */
public class ResilienceException extends RuntimeException {
    /**
     * @param message 异常信息
     */
    public ResilienceException(String message) {
        super(message);
    }

    /**
     * @param message 异常信息
     * @param cause 原因
     */
    public ResilienceException(String message, Throwable cause) {
        super(message, cause);
    }
}
