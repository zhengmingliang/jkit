package com.alianga.jkit.expression;

/**
 * @time 2021/9/25 22:43
 */
public class ExpressionException extends RuntimeException {
    /**
     * 使用指定的错误描述构造表达式异常。
     *
     * @param message 错误描述信息
     */
    public ExpressionException(String message) {
        super(message);
    }

    /**
     * 使用指定的错误描述和根源异常构造表达式异常。
     *
     * @param message 错误描述信息
     * @param cause   导致该异常的根源异常
     */
    public ExpressionException(String message, Throwable cause) {
        super(message, cause);
    }
}
