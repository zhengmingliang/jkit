package com.alianga.jkit.sql.ast;

import com.alianga.jkit.sql.visitor.SqlVisitor;

/**
 * 具名表达式对：{@code DEFINE A AS expr} 或 MEASURES 中的 {@code expr [AS] alias}。
 *
 * @author 郑明亮
 * @since 2.0.1
 */
public final class SqlNamedExpr extends SqlNode {
    private String name;
    private SqlExpr expr;
    /** 未能结构化时的原文兜底。 */
    private String raw;
    /** true 表示 {@code name AS expr}（DEFINE）；false 表示 {@code expr [AS] name}（MEASURES）。 */
    private boolean nameFirst;

    /**
     * @return 名称 / 别名，可空
     */
    public String name() {
        return name;
    }

    /**
     * @param name 名称
     */
    public void setName(String name) {
        this.name = name;
    }

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
     * @return 原文兜底，可空
     */
    public String raw() {
        return raw;
    }

    /**
     * @param raw 原文
     */
    public void setRaw(String raw) {
        this.raw = raw;
    }

    /**
     * @return true 为 name-first（DEFINE）
     */
    public boolean nameFirst() {
        return nameFirst;
    }

    /**
     * @param nameFirst DEFINE 风格
     */
    public void setNameFirst(boolean nameFirst) {
        this.nameFirst = nameFirst;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    protected void acceptChildren(SqlVisitor visitor) {
        child(visitor, expr);
    }
}
