package com.alianga.jkit.sql.ast;

import com.alianga.jkit.sql.visitor.SqlVisitor;

import java.util.ArrayList;
import java.util.List;

/**
 * 过程体内轻量控制流：{@code IF}/{@code WHILE}/{@code LOOP}/{@code REPEAT}/{@code CASE}，
 * 以及 {@code LEAVE}/{@code ITERATE}/{@code RETURN}。
 *
 * <p>条件尽量结构化；ELSEIF / CASE 的 WHEN 以列表形式保留；无法拆分时整段进 {@link #raw()}。
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
        REPEAT,
        /** {@code CASE … WHEN … END CASE}；WHEN 分支在 {@link #elseIfs()}，可选比较值在 {@link #condition()}。 */
        CASE,
        LEAVE,
        ITERATE,
        /** {@code RETURN [expr]}；表达式在 {@link #condition()}。 */
        RETURN
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
     * @return IF / WHILE / LOOP / REPEAT / CASE / LEAVE / ITERATE / RETURN
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
     * @return 循环标签（{@code lab: LOOP}）或 LEAVE/ITERATE 目标标签，可空
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
     * @return IF/WHILE 条件、REPEAT 的 UNTIL、简单 CASE 比较值、或 RETURN 表达式；可空
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
     * @return ELSEIF（IF）或 WHEN（CASE）分支；每个元素的 condition + bodyStatements
     */
    public List<SqlControlStatement> elseIfs() {
        return elseIfs;
    }

    /**
     * @return ELSE 分支语句（IF / CASE）
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
