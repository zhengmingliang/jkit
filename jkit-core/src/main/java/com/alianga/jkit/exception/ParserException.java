package com.alianga.jkit.exception;

/**
 * 解析过程中出现错误时抛出的运行时异常
 */
@SuppressWarnings("serial")
public class ParserException extends RuntimeException {
    /**
     * 构造解析异常
     *
     * @param message 异常描述信息
     * @param cause 导致解析失败的原始异常
     */
    public ParserException(String message, Throwable cause) {
        super(message, cause);
    }
}
