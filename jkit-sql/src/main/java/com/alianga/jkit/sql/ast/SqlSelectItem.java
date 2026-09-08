package com.alianga.jkit.sql.ast;

import com.alianga.jkit.sql.visitor.SqlVisitor;

/**
 * SELECT 列表项。
 *
 * @author 郑明亮
 * @since 2.1.0
 */
public final class SqlSelectItem extends SqlNode {
    private SqlExpr expr;
    private String alias;

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
     * @return 别名，可空
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
     * {@inheritDoc}
     */
    @Override
    protected void acceptChildren(SqlVisitor visitor) {
        child(visitor, expr);
    }
}
