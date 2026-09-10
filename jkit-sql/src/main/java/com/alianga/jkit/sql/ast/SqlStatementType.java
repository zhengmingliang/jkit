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
    /** MySQL {@code RENAME TABLE a TO b}：独立语句，不是 ALTER 的一种。 */
    RENAME,
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
