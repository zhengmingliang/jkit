package com.alianga.jkit.sql.ast;

import com.alianga.jkit.sql.visitor.SqlVisitor;

import java.util.ArrayList;
import java.util.List;

/**
 * 顶层匿名块：{@code DECLARE … BEGIN … END} 或裸 {@code BEGIN … END}。
 *
 * <p>{@link #declares()} 为 {@code DECLARE} 与 {@code BEGIN} 之间的声明（可空）；
 * {@link #bodyStatements()} 为 {@code BEGIN} 内语句。语句种类仍为 {@link SqlStatementType#OTHER}。
 * 会话式单行 {@code DECLARE x INT} 不走本节点。</p>
 *
 * @author 郑明亮
 * @since 2.0.1
 */
public final class SqlBlockStatement extends SqlStatement {
    /** 是否以 {@code DECLARE} 开头（相对裸 BEGIN 块）。 */
    private boolean withDeclare;
    private final List<SqlStatement> declares = new ArrayList<SqlStatement>(2);
    private final List<SqlStatement> bodyStatements = new ArrayList<SqlStatement>(2);
    /** {@code DECLARE} 与 {@code BEGIN} 之间原文（结构化失败时保留），可空。 */
    private String declareRaw;
    /** 整段原文，便于往返。 */
    private String raw;

    /**
     * {@inheritDoc}
     */
    @Override
    public SqlStatementType type() {
        return SqlStatementType.OTHER;
    }

    /**
     * @return 是否 {@code DECLARE … BEGIN … END}
     */
    public boolean withDeclare() {
        return withDeclare;
    }

    /**
     * @param withDeclare 是否带 DECLARE 头
     */
    public void setWithDeclare(boolean withDeclare) {
        this.withDeclare = withDeclare;
    }

    /**
     * @return DECLARE 区语句（变量等），可空列表
     */
    public List<SqlStatement> declares() {
        return declares;
    }

    /**
     * @return BEGIN 体内语句
     */
    public List<SqlStatement> bodyStatements() {
        return bodyStatements;
    }

    /**
     * @return DECLARE 区原文，可空
     */
    public String declareRaw() {
        return declareRaw;
    }

    /**
     * @param declareRaw DECLARE 区原文
     */
    public void setDeclareRaw(String declareRaw) {
        this.declareRaw = declareRaw;
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
        children(visitor, declares);
        children(visitor, bodyStatements);
    }
}
