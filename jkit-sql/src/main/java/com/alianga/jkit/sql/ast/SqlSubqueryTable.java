package com.alianga.jkit.sql.ast;

import com.alianga.jkit.sql.visitor.SqlVisitor;

/**
 * FROM 子查询。
 *
 * @author 郑明亮
 * @since 2.0.1
 */
public final class SqlSubqueryTable extends SqlTableSource {
    private SqlStatement query;
    private boolean lateral;

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
     * @return 是否 {@code LATERAL} 子查询
     * @since 2.0.1
     */
    public boolean lateral() {
        return lateral;
    }

    /**
     * @param lateral {@code LATERAL}
     * @since 2.0.1
     */
    public void setLateral(boolean lateral) {
        this.lateral = lateral;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    protected void acceptChildren(SqlVisitor visitor) {
        child(visitor, query);
        children(visitor, columnAliases());
    }
}
