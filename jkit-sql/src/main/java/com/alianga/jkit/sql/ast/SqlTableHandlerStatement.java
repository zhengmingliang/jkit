package com.alianga.jkit.sql.ast;

import com.alianga.jkit.sql.visitor.SqlVisitor;

/**
 * MySQL 表 {@code HANDLER t OPEN|READ|CLOSE …}（非过程 {@code DECLARE … HANDLER FOR}）。
 *
 * <p>抽表名、操作与可选 WHERE/LIMIT；复杂 READ 键比较原文进 {@link #keyRaw()}。
 * 语句种类仍为 {@link SqlStatementType#OTHER}。</p>
 *
 * @author 郑明亮
 * @since 2.0.1
 */
public final class SqlTableHandlerStatement extends SqlStatement {
    private SqlIdentifier table;
    /** OPEN / READ / CLOSE。 */
    private String operation;
    /** OPEN 可选别名。 */
    private String alias;
    /** READ 可选索引名。 */
    private SqlIdentifier indexName;
    /** READ 方向 FIRST/NEXT/PREV/LAST，可空。 */
    private String readDirection;
    /** READ 键比较与值原文（如 {@code = (1)} / {@code >= ('a')}），可空。 */
    private String keyRaw;
    private SqlExpr where;
    private SqlLimit limit;
    private String raw;

    /**
     * {@inheritDoc}
     */
    @Override
    public SqlStatementType type() {
        return SqlStatementType.OTHER;
    }

    /**
     * @return 表名，可空
     */
    public SqlIdentifier table() {
        return table;
    }

    /**
     * @param table 表名
     */
    public void setTable(SqlIdentifier table) {
        this.table = table;
    }

    /**
     * @return OPEN / READ / CLOSE，可空
     */
    public String operation() {
        return operation;
    }

    /**
     * @param operation 操作
     */
    public void setOperation(String operation) {
        this.operation = operation;
    }

    /**
     * @return OPEN 别名，可空
     */
    public String alias() {
        return alias;
    }

    /**
     * @param alias 别名
     */
    public void setAlias(String alias) {
        this.alias = alias;
    }

    /**
     * @return READ 索引名，可空
     */
    public SqlIdentifier indexName() {
        return indexName;
    }

    /**
     * @param indexName 索引名
     */
    public void setIndexName(SqlIdentifier indexName) {
        this.indexName = indexName;
    }

    /**
     * @return FIRST/NEXT/PREV/LAST，可空
     */
    public String readDirection() {
        return readDirection;
    }

    /**
     * @param readDirection 读方向
     */
    public void setReadDirection(String readDirection) {
        this.readDirection = readDirection;
    }

    /**
     * @return 键比较原文，可空
     */
    public String keyRaw() {
        return keyRaw;
    }

    /**
     * @param keyRaw 键比较原文
     */
    public void setKeyRaw(String keyRaw) {
        this.keyRaw = keyRaw;
    }

    /**
     * @return WHERE，可空
     */
    public SqlExpr where() {
        return where;
    }

    /**
     * @param where WHERE
     */
    public void setWhere(SqlExpr where) {
        this.where = where;
    }

    /**
     * @return LIMIT，可空
     */
    public SqlLimit limit() {
        return limit;
    }

    /**
     * @param limit LIMIT
     */
    public void setLimit(SqlLimit limit) {
        this.limit = limit;
    }

    /**
     * @return 整段原文，可空
     */
    public String raw() {
        return raw;
    }

    /**
     * @param raw 原文
     */
    public void setRaw(String raw) {
        this.raw = raw;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    protected void acceptChildren(SqlVisitor visitor) {
        super.acceptChildren(visitor);
        child(visitor, table);
        child(visitor, indexName);
        child(visitor, where);
        child(visitor, limit);
    }
}
