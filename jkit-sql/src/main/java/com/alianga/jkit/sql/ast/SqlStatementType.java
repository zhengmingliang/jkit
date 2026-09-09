package com.alianga.jkit.sql.ast;

/**
 * 语句种类。
 *
 * @author 郑明亮
 * @since 2.0.1
 */
public enum SqlStatementType {
    SELECT,
    INSERT,
    UPDATE,
    DELETE,
    MERGE,
    REPLACE,
    CREATE,
    DROP,
    ALTER,
    TRUNCATE,
    EXPLAIN,
    SET,
    USE,
    SHOW,
    CALL,
    GRANT,
    REVOKE,
    OTHER
}
