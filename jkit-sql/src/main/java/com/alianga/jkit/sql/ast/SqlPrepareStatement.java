package com.alianga.jkit.sql.ast;

import com.alianga.jkit.sql.visitor.SqlVisitor;

import java.util.ArrayList;
import java.util.List;

/**
 * {@code PREPARE}/{@code EXECUTE}/{@code DEALLOCATE PREPARE}，以及可选 {@code EXECUTE IMMEDIATE}。
 *
 * <p>抽语句名、{@code FROM}/{@code IMMEDIATE} 源表达式与 {@code USING} 绑定；无法结构化时整段进 {@link #raw()}。
 * 语句种类仍为 {@link SqlStatementType#OTHER}。</p>
 *
 * @author 郑明亮
 * @since 2.0.1
 */
public final class SqlPrepareStatement extends SqlStatement {
    /**
     * 预备语句族种类。
     */
    public enum Kind {
        /** {@code PREPARE name FROM expr} */
        PREPARE,
        /** {@code EXECUTE name [USING …]} */
        EXECUTE,
        /** {@code EXECUTE IMMEDIATE expr [USING …]} */
        EXECUTE_IMMEDIATE,
        /** {@code DEALLOCATE PREPARE name} */
        DEALLOCATE
    }

    private Kind kind = Kind.PREPARE;
    private SqlIdentifier name;
    /** PREPARE FROM / EXECUTE IMMEDIATE 的源（字面量或变量等）。 */
    private SqlExpr source;
    private final List<SqlExpr> usingBinds = new ArrayList<SqlExpr>(2);
    private String raw;

    /**
     * {@inheritDoc}
     */
    @Override
    public SqlStatementType type() {
        return SqlStatementType.OTHER;
    }

    /**
     * @return PREPARE / EXECUTE / EXECUTE_IMMEDIATE / DEALLOCATE
     */
    public Kind kind() {
        return kind;
    }

    /**
     * @param kind 种类
     */
    public void setKind(Kind kind) {
        this.kind = kind;
    }

    /**
     * @return 预备语句名，可空（IMMEDIATE 无名义名）
     */
    public SqlIdentifier name() {
        return name;
    }

    /**
     * @param name 语句名
     */
    public void setName(SqlIdentifier name) {
        this.name = name;
    }

    /**
     * @return FROM / IMMEDIATE 源表达式，可空
     */
    public SqlExpr source() {
        return source;
    }

    /**
     * @param source 源表达式
     */
    public void setSource(SqlExpr source) {
        this.source = source;
    }

    /**
     * @return EXECUTE / IMMEDIATE 的 USING 绑定列表
     */
    public List<SqlExpr> usingBinds() {
        return usingBinds;
    }

    /**
     * @return 整段原文，可空
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
     * {@inheritDoc}
     */
    @Override
    protected void acceptChildren(SqlVisitor visitor) {
        super.acceptChildren(visitor);
        child(visitor, name);
        child(visitor, source);
        children(visitor, usingBinds);
    }
}
