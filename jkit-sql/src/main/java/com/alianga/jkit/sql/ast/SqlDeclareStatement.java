package com.alianga.jkit.sql.ast;

import com.alianga.jkit.sql.visitor.SqlVisitor;

import java.util.ArrayList;
import java.util.List;

/**
 * 过程体内 {@code DECLARE}：变量 / {@code CONDITION} / {@code CURSOR FOR select}。
 *
 * <p>无法结构化时整段进 {@link #raw()}，语句种类仍为 {@link SqlStatementType#OTHER}。
 * 顶层匿名 {@code DECLARE … BEGIN … END} 走 {@link SqlBlockStatement}；表 {@code HANDLER t OPEN} 走 {@link SqlTableHandlerStatement}。</p>
 *
 * @author 郑明亮
 * @since 2.0.1
 */
public final class SqlDeclareStatement extends SqlStatement {
    /**
     * DECLARE 种类。
     */
    public enum Kind {
        /** {@code DECLARE a[, b] type [DEFAULT expr]} */
        VARIABLE,
        /** {@code DECLARE name CONDITION FOR …} */
        CONDITION,
        /** {@code DECLARE name CURSOR FOR select} */
        CURSOR
    }

    private Kind kind = Kind.VARIABLE;
    private final List<SqlIdentifier> names = new ArrayList<SqlIdentifier>(2);
    /** 变量类型原文，可空。 */
    private String typeRaw;
    /** {@code DEFAULT} 表达式，可空。 */
    private SqlExpr defaultValue;
    /** {@code CONDITION FOR} 后原文，可空。 */
    private String conditionFor;
    /** {@code CURSOR FOR} 查询，可空。 */
    private SqlStatement cursorQuery;
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
     * @return VARIABLE / CONDITION / CURSOR
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
     * @return 变量名列表，或 CONDITION/CURSOR 单名
     */
    public List<SqlIdentifier> names() {
        return names;
    }

    /**
     * @return 变量类型原文，可空
     */
    public String typeRaw() {
        return typeRaw;
    }

    /**
     * @param typeRaw 类型原文
     */
    public void setTypeRaw(String typeRaw) {
        this.typeRaw = typeRaw;
    }

    /**
     * @return DEFAULT 表达式，可空
     */
    public SqlExpr defaultValue() {
        return defaultValue;
    }

    /**
     * @param defaultValue DEFAULT 值
     */
    public void setDefaultValue(SqlExpr defaultValue) {
        this.defaultValue = defaultValue;
    }

    /**
     * @return CONDITION FOR 原文，可空
     */
    public String conditionFor() {
        return conditionFor;
    }

    /**
     * @param conditionFor CONDITION FOR 原文
     */
    public void setConditionFor(String conditionFor) {
        this.conditionFor = conditionFor;
    }

    /**
     * @return CURSOR FOR 查询，可空
     */
    public SqlStatement cursorQuery() {
        return cursorQuery;
    }

    /**
     * @param cursorQuery 游标查询
     */
    public void setCursorQuery(SqlStatement cursorQuery) {
        this.cursorQuery = cursorQuery;
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
        children(visitor, names);
        child(visitor, defaultValue);
        child(visitor, cursorQuery);
    }
}
