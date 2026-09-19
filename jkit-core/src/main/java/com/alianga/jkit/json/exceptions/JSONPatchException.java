package com.alianga.jkit.json.exceptions;

/**
 * JSON Patch（RFC 6902）应用过程中抛出的运行时异常，用于表示指针非法、
 * 操作不存在、目标位置越界或 {@code test} 断言失败等。
 */
public class JSONPatchException extends JSONException {
    private static final long serialVersionUID = 1L;

    /**
     * 构造一个带错误信息的异常。
     *
     * @param message 错误描述信息
     */
    public JSONPatchException(String message) {
        super(message);
    }

    /**
     * 构造一个带根本原因与错误信息的异常。
     *
     * @param message 错误描述信息
     * @param cause 导致该异常的原始异常
     */
    public JSONPatchException(String message, Throwable cause) {
        super(message, cause);
    }
}
