package com.alianga.jkit.sql.ast;

import com.alianga.jkit.sql.visitor.SqlVisitor;

/**
 * T-SQL 控制流守卫 {@code IF <expr> <stmt> [ELSE <stmt>]}。
 *
 * <p>用于 SQL Server 初始化脚本里常见的前置幂等删表
 * {@code IF OBJECT_ID('t','U') IS NOT NULL DROP TABLE t;}。condition 保留原始判定表达式，
 * body 为被守卫的语句（如 DROP / UPDATE / INSERT），{@link #type()} 委托给 body，
 * 因此下游格式化与跨方言转换可正常识别内层语句。</p>
 *
 * @author 郑明亮
 * @since 2.0.2
 */
public final class SqlGuardedStatement extends SqlStatement {
    private String condition;
    private SqlStatement body;

    /**
     * {@inheritDoc}
     */
    @Override
    public SqlStatementType type() {
        return body != null ? body.type() : SqlStatementType.OTHER;
    }

    /**
     * @return 守卫判定表达式原文，如 {@code OBJECT_ID('t','U') IS NOT NULL}
     */
    public String condition() {
        return condition;
    }

    /**
     * @param condition 判定表达式
     */
    public void setCondition(String condition) {
        this.condition = condition;
    }

    /**
     * @return 被守卫的语句
     */
    public SqlStatement body() {
        return body;
    }

    /**
     * @param body 被守卫的语句
     */
    public void setBody(SqlStatement body) {
        this.body = body;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    protected void acceptChildren(SqlVisitor visitor) {
        super.acceptChildren(visitor);
        child(visitor, body);
    }
}
