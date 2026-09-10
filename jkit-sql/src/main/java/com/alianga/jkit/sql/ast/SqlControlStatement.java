package com.alianga.jkit.sql.ast;

import com.alianga.jkit.sql.visitor.SqlVisitor;

import java.util.ArrayList;
import java.util.List;

/**
 * 过程体内轻量控制流：{@code IF}/{@code WHILE}/{@code LOOP}/{@code REPEAT}。
 *
 * <p>条件尽量结构化；ELSEIF 以列表形式保留；无法拆分时整段进 {@link #raw()}。
 * 语句种类仍为 {@link SqlStatementType#OTHER}。</p>
 *
 * @author 郑明亮
 * @since 2.0.1
 */
public final class SqlControlStatement extends SqlStatement {
    /**
     * 控制流种类。
     */
    public enum Kind {
        IF,
        WHILE,
        LOOP,
        REPEAT
    }

    private Kind kind = Kind.IF;
    private String label;
    private SqlExpr condition;
    private final List<SqlStatement> bodyStatements = new ArrayList<SqlStatement>(2);
    private final List<SqlControlStatement> elseIfs = new ArrayList<SqlControlStatement>(1);
    private final List<SqlStatement> elseStatements = new ArrayList<SqlStatement>(1);
    /** 整段原文（含 END IF 等），便于往返。 */
    private String raw;

    /**
     * {@inheritDoc}
     */
    @Override
    public SqlStatementType type() {
        return SqlStatementType.OTHER;
    }

    /**
     * @return IF / WHILE / LOOP / REPEAT
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
     * @return 可选标签（{@code lab: LOOP}），可空
     */
    public String label() {
        return label;
    }

    /**
     * @param label 标签
     */
    public void setLabel(String label) {
        this.label = label;
    }

    /**
     * @return IF/WHILE 条件，或 REPEAT 的 UNTIL 条件；可空
     */
    public SqlExpr condition() {
        return condition;
    }

    /**
     * @param condition 条件
     */
    public void setCondition(SqlExpr condition) {
        this.condition = condition;
    }

    /**
     * @return THEN / DO / LOOP / REPEAT 主体语句
     */
    public List<SqlStatement> bodyStatements() {
        return bodyStatements;
    }

    /**
     * @return ELSEIF 分支（仅 IF；每个元素的 condition + bodyStatements）
     */
    public List<SqlControlStatement> elseIfs() {
        return elseIfs;
    }

    /**
     * @return ELSE 分支语句（仅 IF）
     */
    public List<SqlStatement> elseStatements() {
        return elseStatements;
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
        child(visitor, condition);
        children(visitor, bodyStatements);
        children(visitor, elseIfs);
        children(visitor, elseStatements);
    }
}
