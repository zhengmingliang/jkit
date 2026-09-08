package com.alianga.jkit.sql.ast;

import com.alianga.jkit.sql.visitor.SqlVisitor;

import java.util.List;

/**
 * {@code expr IN ( ... )} 或 {@code expr IN (subquery)}。
 *
 * @author 郑明亮
 * @since 2.0.1
 */
public final class SqlInExpr extends SqlExpr {
    private SqlExpr expr;
    private List<SqlExpr> values;
    private SqlStatement subquery;
    private boolean not;

    /**
     * @return 左表达式
     */
    public SqlExpr expr() {
        return expr;
    }

    /**
     * @param expr 左表达式
     */
    public void setExpr(SqlExpr expr) {
        this.expr = expr;
    }

    /**
     * @return 值列表，与 subquery 互斥
     */
    public List<SqlExpr> values() {
        return values;
    }

    /**
     * @param values 值列表
     */
    public void setValues(List<SqlExpr> values) {
        this.values = values;
    }

    /**
     * @return 子查询
     */
    public SqlStatement subquery() {
        return subquery;
    }

    /**
     * @param subquery 子查询
     */
    public void setSubquery(SqlStatement subquery) {
        this.subquery = subquery;
    }

    /**
     * @return NOT IN
     */
    public boolean not() {
        return not;
    }

    /**
     * @param not NOT IN
     */
    public void setNot(boolean not) {
        this.not = not;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    protected void acceptChildren(SqlVisitor visitor) {
        child(visitor, expr);
        children(visitor, values);
        child(visitor, subquery);
    }
}
