package com.alianga.jkit.sql.ast;

import com.alianga.jkit.sql.visitor.SqlVisitor;

/**
 * 二元表达式。
 *
 * @author 郑明亮
 * @since 2.0.1
 */
public final class SqlBinaryExpr extends SqlExpr {
    private SqlExpr left;
    private SqlBinaryOp operator;
    private SqlExpr right;

    /**
     * @param left 左
     * @param operator 运算符
     * @param right 右
     * @return 表达式
     */
    public static SqlBinaryExpr of(SqlExpr left, SqlBinaryOp operator, SqlExpr right) {
        SqlBinaryExpr e = new SqlBinaryExpr();
        e.left = left;
        e.operator = operator;
        e.right = right;
        return e;
    }

    /**
     * @return 左
     */
    public SqlExpr left() {
        return left;
    }

    /**
     * @param left 左
     */
    public void setLeft(SqlExpr left) {
        this.left = left;
    }

    /**
     * @return 运算符
     */
    public SqlBinaryOp operator() {
        return operator;
    }

    /**
     * @param operator 运算符
     */
    public void setOperator(SqlBinaryOp operator) {
        this.operator = operator;
    }

    /**
     * @return 右
     */
    public SqlExpr right() {
        return right;
    }

    /**
     * @param right 右
     */
    public void setRight(SqlExpr right) {
        this.right = right;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    protected void acceptChildren(SqlVisitor visitor) {
        child(visitor, left);
        child(visitor, right);
    }
}
