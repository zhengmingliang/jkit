package com.alianga.jkit.sql.ast;

import com.alianga.jkit.sql.visitor.SqlVisitor;

import java.util.ArrayList;
import java.util.List;

/**
 * UPDATE。
 *
 * @author 郑明亮
 * @since 2.1.0
 */
public final class SqlUpdate extends SqlStatement {
    private SqlTableSource table;
    private final List<SqlBinaryExpr> setList = new ArrayList<SqlBinaryExpr>(4);
    private SqlExpr where;
    private SqlLimit limit;
    private final List<SqlOrderByItem> orderBy = new ArrayList<SqlOrderByItem>(2);
    private SqlExpr returning;

    /**
     * {@inheritDoc}
     */
    @Override
    public SqlStatementType type() {
        return SqlStatementType.UPDATE;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean isReadOnly() {
        return false;
    }

    /**
     * @return 目标（可含 JOIN）
     */
    public SqlTableSource table() {
        return table;
    }

    /**
     * @param table 目标
     */
    public void setTable(SqlTableSource table) {
        this.table = table;
    }

    /**
     * @return SET 列表
     */
    public List<SqlBinaryExpr> setList() {
        return setList;
    }

    /**
     * @return WHERE
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
     * @return LIMIT
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
     * @return ORDER BY
     */
    public List<SqlOrderByItem> orderBy() {
        return orderBy;
    }

    /**
     * @return RETURNING
     */
    public SqlExpr returning() {
        return returning;
    }

    /**
     * @param returning RETURNING
     */
    public void setReturning(SqlExpr returning) {
        this.returning = returning;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    protected void acceptChildren(SqlVisitor visitor) {
        super.acceptChildren(visitor);
        child(visitor, table);
        children(visitor, setList);
        child(visitor, where);
        children(visitor, orderBy);
        child(visitor, limit);
        child(visitor, returning);
    }
}
