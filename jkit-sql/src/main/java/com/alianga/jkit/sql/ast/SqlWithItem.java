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
    /** Oracle {@code SEARCH DEPTH|BREADTH FIRST BY … SET col} 原文。 */
    private String searchClause;
    /** Oracle {@code CYCLE … SET … TO … DEFAULT …} 原文。 */
    private String cycleClause;

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
     * Oracle 递归 CTE {@code SEARCH …} 子句原文。
     *
     * @return 原文，可空
     * @since 2.0.1
     */
    public String searchClause() {
        return searchClause;
    }

    /**
     * @param searchClause {@code SEARCH …} 原文
     * @since 2.0.1
     */
    public void setSearchClause(String searchClause) {
        this.searchClause = searchClause;
    }

    /**
     * Oracle 递归 CTE {@code CYCLE …} 子句原文。
     *
     * @return 原文，可空
     * @since 2.0.1
     */
    public String cycleClause() {
        return cycleClause;
    }

    /**
     * @param cycleClause {@code CYCLE …} 原文
     * @since 2.0.1
     */
    public void setCycleClause(String cycleClause) {
        this.cycleClause = cycleClause;
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
