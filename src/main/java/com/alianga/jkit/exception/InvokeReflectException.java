package com.alianga.jkit.exception;

/**
 * <p> 反射调用失败时抛出的运行时异常，用于包装反射过程中产生的原始异常
 */
@SuppressWarnings("serial")
public class InvokeReflectException extends RuntimeException {
    /**
     * 使用指定的原始异常构造反射调用异常
     *
     * @param cause 导致反射调用失败的原始异常
     */
    public InvokeReflectException(Throwable cause) {
        super(cause);
    }
}
