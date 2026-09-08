package com.alianga.jkit.sql.ast;

import com.alianga.jkit.sql.visitor.SqlVisitor;

/**
 * ORDER BY 项。
 *
 * @author 郑明亮
 * @since 2.1.0
 */
public final class SqlOrderByItem extends SqlNode {
    private SqlExpr expr;
    private boolean asc = true;
    private String nulls;

    /**
     * @return 表达式
     */
    public SqlExpr expr() {
        return expr;
    }

    /**
     * @param expr 表达式
     */
    public void setExpr(SqlExpr expr) {
        this.expr = expr;
    }

    /**
     * @return true 为 ASC
     */
    public boolean asc() {
        return asc;
    }

    /**
     * @param asc ASC
     */
    public void setAsc(boolean asc) {
        this.asc = asc;
    }

    /**
     * @return NULLS FIRST / LAST，可空
     */
    public String nulls() {
        return nulls;
    }

    /**
     * @param nulls NULLS 子句
     */
    public void setNulls(String nulls) {
        this.nulls = nulls;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    protected void acceptChildren(SqlVisitor visitor) {
        child(visitor, expr);
    }
}
