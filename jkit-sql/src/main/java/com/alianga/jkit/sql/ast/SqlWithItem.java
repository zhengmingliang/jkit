package com.alianga.jkit.sql.ast;

import com.alianga.jkit.sql.visitor.SqlVisitor;

import java.util.List;

/**
 * WITH 子句中的一个 CTE。
 *
 * @author 郑明亮
 * @since 2.0.1
 */
public final class SqlWithItem extends SqlNode {
    private SqlIdentifier name;
    private List<SqlIdentifier> columns;
    private SqlStatement query;

    /**
     * @return CTE 名
     */
    public SqlIdentifier name() {
        return name;
    }

    /**
     * @param name CTE 名
     */
    public void setName(SqlIdentifier name) {
        this.name = name;
    }

    /**
     * @return 列名，可空
     */
    public List<SqlIdentifier> columns() {
        return columns;
    }

    /**
     * @param columns 列名
     */
    public void setColumns(List<SqlIdentifier> columns) {
        this.columns = columns;
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
        child(visitor, name);
        children(visitor, columns);
        child(visitor, query);
    }
}
