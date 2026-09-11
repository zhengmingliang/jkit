package com.alianga.jkit.sql.auto;

/**
 * 自动建表 / 更新表结构失败。
 *
 * @author 郑明亮
 * @since 2.0.1
 */
public class SqlAutoException extends RuntimeException {
    /**
     * @param message 说明
     */
    public SqlAutoException(String message) {
        super(message);
    }

    /**
     * @param message 说明
     * @param cause 原因
     */
    public SqlAutoException(String message, Throwable cause) {
        super(message, cause);
    }
}
