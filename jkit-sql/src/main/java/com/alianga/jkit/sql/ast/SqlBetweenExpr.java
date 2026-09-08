package com.alianga.jkit.sql.ast;

import com.alianga.jkit.sql.visitor.SqlVisitor;

/**
 * {@code expr BETWEEN begin AND end}。
 *
 * @author 郑明亮
 * @since 2.1.0
 */
public final class SqlBetweenExpr extends SqlExpr {
    private SqlExpr expr;
    private SqlExpr begin;
    private SqlExpr end;
    private boolean not;

    /**
     * @return 被测表达式
     */
    public SqlExpr expr() {
        return expr;
    }

    /**
     * @param expr 被测表达式
     */
    public void setExpr(SqlExpr expr) {
        this.expr = expr;
    }

    /**
     * @return 下界
     */
    public SqlExpr begin() {
        return begin;
    }

    /**
     * @param begin 下界
     */
    public void setBegin(SqlExpr begin) {
        this.begin = begin;
    }

    /**
     * @return 上界
     */
    public SqlExpr end() {
        return end;
    }

    /**
     * @param end 上界
     */
    public void setEnd(SqlExpr end) {
        this.end = end;
    }

    /**
     * @return NOT BETWEEN
     */
    public boolean not() {
        return not;
    }

    /**
     * @param not NOT BETWEEN
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
        child(visitor, begin);
        child(visitor, end);
    }
}
