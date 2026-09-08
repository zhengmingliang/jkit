package com.alianga.jkit.sql.ast;

import com.alianga.jkit.sql.visitor.SqlVisitor;

/**
 * 一元表达式：{@code NOT}、{@code -}、{@code ~}、{@code EXISTS}。
 *
 * @author 郑明亮
 * @since 2.1.0
 */
public final class SqlUnaryExpr extends SqlExpr {
    /**
     * 一元运算符。
     */
    public enum Op {
        NOT,
        PLUS,
        MINUS,
        TILDE,
        EXISTS
    }

    private Op operator;
    private SqlExpr expr;

    /**
     * @param operator 运算符
     * @param expr 操作数
     * @return 表达式
     */
    public static SqlUnaryExpr of(Op operator, SqlExpr expr) {
        SqlUnaryExpr e = new SqlUnaryExpr();
        e.operator = operator;
        e.expr = expr;
        return e;
    }

    /**
     * @return 运算符
     */
    public Op operator() {
        return operator;
    }

    /**
     * @param operator 运算符
     */
    public void setOperator(Op operator) {
        this.operator = operator;
    }

    /**
     * @return 操作数
     */
    public SqlExpr expr() {
        return expr;
    }

    /**
     * @param expr 操作数
     */
    public void setExpr(SqlExpr expr) {
        this.expr = expr;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    protected void acceptChildren(SqlVisitor visitor) {
        child(visitor, expr);
    }
}
