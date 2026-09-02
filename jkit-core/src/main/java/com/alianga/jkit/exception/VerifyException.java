package com.alianga.jkit.exception;

/**
 * 确认异常
 *
 * @since 1.4.1
 */
public class VerifyException extends RuntimeException {
    /**
     * Constructs a {@code VerifyException} with no message and no cause.
     *
     * @since 1.4.1
     */
    public VerifyException() {
    }

    /**
     * Constructs a {@code VerifyException} with the message {@code message}.
     *
     * @param message the detail message, may be {@code null}
     * @since 1.4.1
     */
    public VerifyException(String message) {
        super(message);
    }

    /**
     * Constructs a {@code VerifyException} with the cause {@code cause} and a message that is {@code
     * null} if {@code cause} is null, and {@code cause.toString()} otherwise.
     *
     * @param cause the cause of this exception, may be {@code null}
     * @since 1.4.1
     */
    public VerifyException(Throwable cause) {
        super(cause);
    }

    /**
     * Constructs a {@code VerifyException} with the message {@code message} and the cause {@code
     * cause}.
     *
     * @param message the detail message, may be {@code null}
     * @param cause the cause of this exception, may be {@code null}
     * @since 1.4.1
     */
    public VerifyException(String message, Throwable cause) {
        super(message, cause);
    }
}
