package com.alianga.jkit.html;

/**
 * CSS 选择器解析或匹配过程中抛出的异常。
 *
 * @author 郑明亮
 * @since 2.0.2
 */
public class SelectorException extends RuntimeException {
    /**
     * 构造带信息的异常。
     *
     * @param message 异常信息
     */
    public SelectorException(String message) {
        super(message);
    }

    /**
     * 构造带原因链的异常。
     *
     * @param message 异常信息
     * @param cause 原始原因
     */
    public SelectorException(String message, Throwable cause) {
        super(message, cause);
    }
}
