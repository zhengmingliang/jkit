package com.alianga.jkit.sql.ast;

import com.alianga.jkit.sql.visitor.SqlVisitor;

import java.util.List;

/**
 * JOIN。
 *
 * @author 郑明亮
 * @since 2.0.1
 */
public final class SqlJoin extends SqlTableSource {
    /**
     * 连接类型。
     */
    public enum Type {
        INNER,
        LEFT,
        RIGHT,
        FULL,
        CROSS,
        COMMA,
        STRAIGHT,
        NATURAL,
        /** SQL Server CROSS APPLY */
        CROSS_APPLY,
        /** SQL Server OUTER APPLY */
        OUTER_APPLY,
        /** Hive LATERAL VIEW */
        LATERAL_VIEW
    }

    private Type joinType;
    private SqlTableSource left;
    private SqlTableSource right;
    private SqlExpr condition;
    private List<SqlIdentifier> using;

    /**
     * @return 类型
     */
    public Type joinType() {
        return joinType;
    }

    /**
     * @param joinType 类型
     */
    public void setJoinType(Type joinType) {
        this.joinType = joinType;
    }

    /**
     * @return 左
     */
    public SqlTableSource left() {
        return left;
    }

    /**
     * @param left 左
     */
    public void setLeft(SqlTableSource left) {
        this.left = left;
    }

    /**
     * @return 右
     */
    public SqlTableSource right() {
        return right;
    }

    /**
     * @param right 右
     */
    public void setRight(SqlTableSource right) {
        this.right = right;
    }

    /**
     * @return ON 条件
     */
    public SqlExpr condition() {
        return condition;
    }

    /**
     * @param condition ON 条件
     */
    public void setCondition(SqlExpr condition) {
        this.condition = condition;
    }

    /**
     * @return USING 列
     */
    public List<SqlIdentifier> using() {
        return using;
    }

    /**
     * @param using USING 列
     */
    public void setUsing(List<SqlIdentifier> using) {
        this.using = using;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    protected void acceptChildren(SqlVisitor visitor) {
        child(visitor, left);
        child(visitor, right);
        child(visitor, condition);
        children(visitor, using);
    }
}
