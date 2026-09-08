package com.alianga.jkit.sql.ast;

import com.alianga.jkit.sql.visitor.SqlVisitor;

/**
 * 标量子查询或 FROM 以外出现的查询。
 *
 * @author 郑明亮
 * @since 2.1.0
 */
public final class SqlQueryExpr extends SqlExpr {
    private SqlStatement query;

    /**
     * @param query 子查询
     * @return 表达式
     */
    public static SqlQueryExpr of(SqlStatement query) {
        SqlQueryExpr e = new SqlQueryExpr();
        e.query = query;
        return e;
    }

    /**
     * @return 子查询
     */
    public SqlStatement query() {
        return query;
    }

    /**
     * @param query 子查询
     */
    public void setQuery(SqlStatement query) {
        this.query = query;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    protected void acceptChildren(SqlVisitor visitor) {
        child(visitor, query);
    }
}
