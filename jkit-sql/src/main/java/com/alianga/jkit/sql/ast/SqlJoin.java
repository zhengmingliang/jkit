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
        LATERAL_VIEW,
        /** Spark/Hive {@code LEFT ANTI JOIN} */
        LEFT_ANTI,
        /** Spark/Hive {@code LEFT SEMI JOIN} */
        LEFT_SEMI,
        /** Spark {@code RIGHT ANTI JOIN} */
        RIGHT_ANTI,
        /** Spark {@code RIGHT SEMI JOIN} */
        RIGHT_SEMI
    }

    private Type joinType;
    /** NATURAL 与连接类型正交：{@code NATURAL LEFT JOIN} 既是 NATURAL 也是 LEFT。 */
    private boolean natural;
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
     * @return 是否 {@code NATURAL} 连接；与 {@link #joinType()} 正交，
     *         {@code NATURAL LEFT JOIN} 返回 {@code true} 且类型为 {@code LEFT}
     * @since 2.0.1
     */
    public boolean natural() {
        return natural;
    }

    /**
     * @param natural 是否 {@code NATURAL} 连接
     * @since 2.0.1
     */
    public void setNatural(boolean natural) {
        this.natural = natural;
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
