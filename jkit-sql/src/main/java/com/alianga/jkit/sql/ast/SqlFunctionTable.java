package com.alianga.jkit.sql.ast;

import com.alianga.jkit.sql.visitor.SqlVisitor;

/**
 * FROM 表函数：{@code UNNEST(...)}、{@code generate_series(...)}、{@code OPENJSON(...) [WITH (...)]}，或 {@code TABLE(fn(...))}。
 *
 * @author 郑明亮
 * @since 2.0.1
 */
public final class SqlFunctionTable extends SqlTableSource {
    private SqlExpr function;
    private boolean tableKeyword;
    private boolean lateral;
    private String withDefinition;

    /**
     * @return 函数调用（一般为 {@link SqlFunctionExpr}）
     */
    public SqlExpr function() {
        return function;
    }

    /**
     * @param function 函数调用
     */
    public void setFunction(SqlExpr function) {
        this.function = function;
    }

    /**
     * @return 是否带 {@code TABLE(...)} 包装（Oracle 等）
     */
    public boolean tableKeyword() {
        return tableKeyword;
    }

    /**
     * @param tableKeyword {@code TABLE(...)}
     */
    public void setTableKeyword(boolean tableKeyword) {
        this.tableKeyword = tableKeyword;
    }

    /**
     * @return 是否 {@code LATERAL}
     */
    public boolean lateral() {
        return lateral;
    }

    /**
     * @param lateral {@code LATERAL}
     */
    public void setLateral(boolean lateral) {
        this.lateral = lateral;
    }

    /**
     * SQL Server {@code OPENJSON(...) WITH (...)} 等表函数的 WITH 子句原文（含括号内）。
     *
     * @return WITH 定义，无则 null
     * @since 2.0.1
     */
    public String withDefinition() {
        return withDefinition;
    }

    /**
     * @param withDefinition WITH 子句内容（建议含外层括号内原文）
     * @since 2.0.1
     */
    public void setWithDefinition(String withDefinition) {
        this.withDefinition = withDefinition;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    protected void acceptChildren(SqlVisitor visitor) {
        child(visitor, function);
        children(visitor, columnAliases());
    }
}
