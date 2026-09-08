package com.alianga.jkit.sql.ast;

import com.alianga.jkit.sql.visitor.SqlVisitor;

/**
 * CAST(expr AS type) 或 expr::type。
 *
 * @author 郑明亮
 * @since 2.0.1
 */
public final class SqlCastExpr extends SqlExpr {
    private SqlExpr expr;
    private String dataType;
    private boolean postgresStyle;

    /**
     * @return 被转换表达式
     */
    public SqlExpr expr() {
        return expr;
    }

    /**
     * @param expr 被转换表达式
     */
    public void setExpr(SqlExpr expr) {
        this.expr = expr;
    }

    /**
     * @return 目标类型原文
     */
    public String dataType() {
        return dataType;
    }

    /**
     * @param dataType 目标类型
     */
    public void setDataType(String dataType) {
        this.dataType = dataType;
    }

    /**
     * @return 是否 {@code ::} 写法
     */
    public boolean postgresStyle() {
        return postgresStyle;
    }

    /**
     * @param postgresStyle {@code ::}
     */
    public void setPostgresStyle(boolean postgresStyle) {
        this.postgresStyle = postgresStyle;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    protected void acceptChildren(SqlVisitor visitor) {
        child(visitor, expr);
    }
}
