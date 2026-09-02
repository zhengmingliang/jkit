package com.alianga.jkit.csv;

/**
 * CSV 读写过程中发生错误时抛出的运行时异常。
 */
public class CSVException extends RuntimeException {
    /**
     * 使用指定的错误信息构造异常。
     *
     * @param message 错误描述信息
     */
    public CSVException(String message) {
        super(message);
    }

    /**
     * 使用指定的错误信息和根本原因构造异常。
     *
     * @param message 错误描述信息
     * @param cause   导致该异常的根本原因
     */
    public CSVException(String message, Throwable cause) {
        super(message, cause);
    }
}
