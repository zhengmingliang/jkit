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
    private boolean parenthesized;

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
     * 回写时是否整体套一层括号。改写器把函数调用展开成运算符时用得着：
     * {@code DATEDIFF(a,b) * 2} 若展开成 {@code a - b * 2} 会因优先级算错，
     * 置 true 后输出 {@code (a - b) * 2}。
     *
     * @return true 输出 {@code (left op right)}
     */
    public boolean parenthesized() {
        return parenthesized;
    }

    /**
     * @param parenthesized true 回写时整体套括号
     */
    public void setParenthesized(boolean parenthesized) {
        this.parenthesized = parenthesized;
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
