package com.alianga.jkit.sql.auto;

/**
 * 一条结构变更（或校验失败项）。
 *
 * @author 郑明亮
 * @since 2.0.1
 */
public final class SqlAutoChange {
    /**
     * 变更种类。
     */
    public enum Kind {
        /** 建表。 */
        CREATE_TABLE,
        /** 删表。 */
        DROP_TABLE,
        /** 加列。 */
        ADD_COLUMN,
        /** 改列类型 / 可空。 */
        ALTER_COLUMN,
        /** 删列。 */
        DROP_COLUMN,
        /** 建索引。 */
        CREATE_INDEX,
        /** 列 / 表注释。 */
        COMMENT,
        /** 序列 / 触发器（无 IDENTITY 的方言）。 */
        SEQUENCE,
        /** 校验失败（不附带可执行 SQL）。 */
        VALIDATE
    }

    private final Kind kind;
    private final String table;
    private final String detail;
    private final String sql;

    /**
     * @param kind 种类
     * @param table 表名
     * @param detail 说明（列名 / 索引名等）
     * @param sql 可执行 SQL，校验项可空
     */
    public SqlAutoChange(Kind kind, String table, String detail, String sql) {
        this.kind = kind == null ? Kind.VALIDATE : kind;
        this.table = table == null ? "" : table;
        this.detail = detail == null ? "" : detail;
        this.sql = sql == null ? "" : sql;
    }

    /**
     * @return 种类
     */
    public Kind kind() {
        return kind;
    }

    /**
     * @return 表名
     */
    public String table() {
        return table;
    }

    /**
     * @return 说明
     */
    public String detail() {
        return detail;
    }

    /**
     * @return SQL，可能为空
     */
    public String sql() {
        return sql;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public String toString() {
        return kind + " " + table + (detail.isEmpty() ? "" : " " + detail)
                + (sql.isEmpty() ? "" : " :: " + sql);
    }
}
