package com.alianga.jkit.json.exceptions;

/**
 * JSON 处理过程中抛出的运行时异常，用于表示解析、序列化或校验失败。
 */
public class JSONException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    /**
     * 构造一个不带错误信息和原因的异常。
     */
    public JSONException() {
    }

    /**
     * 构造一个带错误信息的异常。
     *
     * @param message 错误描述信息
     */
    public JSONException(String message) {
        super(message);
    }

    /**
     * 构造一个带根本原因的异常。
     *
     * @param cause 导致该异常的原始异常
     */
    public JSONException(Throwable cause) {
        super(cause);
    }

    /**
     * 构造一个同时带错误信息和根本原因的异常。
     *
     * @param message 错误描述信息
     * @param cause 导致该异常的原始异常
     */
    public JSONException(String message, Throwable cause) {
        super(message, cause);
    }
}
