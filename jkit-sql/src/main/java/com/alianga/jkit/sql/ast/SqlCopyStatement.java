package com.alianga.jkit.sql.ast;

import com.alianga.jkit.sql.visitor.SqlVisitor;

import java.util.ArrayList;
import java.util.List;

/**
 * PostgreSQL {@code COPY table[(cols)] FROM|TO {STDIN|STDOUT|PROGRAM '…'|filename} [WITH (…)]}。
 *
 * <p>对标 {@link SqlLoadDataStatement}：抽表、方向、源种类与表达式、列清单；
 * {@code WITH (…)} 等以原文段保留。无法结构化时整段进 {@link #raw()}。
 * 语句种类为 {@link SqlStatementType#OTHER}。</p>
 *
 * @author 郑明亮
 * @since 2.0.1
 */
public final class SqlCopyStatement extends SqlStatement {
    private SqlIdentifier table;
    private final List<SqlIdentifier> columns = new ArrayList<SqlIdentifier>(4);
    /** true = TO；false = FROM。 */
    private boolean to;
    /**
     * 源种类：{@code STDIN} / {@code STDOUT} / {@code PROGRAM} / {@code FILE}，可空。
     */
    private String sourceKind;
    /** 文件名或 PROGRAM 命令表达式，可空。 */
    private SqlExpr source;
    /** WITH (…) 或旧式 WITH 选项原文，可空。 */
    private String withClause;
    /** 查询形式 {@code COPY (query) TO …} 的 query 原文，可空。 */
    private String query;
    /** 整段/残余原文，可空。 */
    private String raw;

    /**
     * {@inheritDoc}
     */
    @Override
    public SqlStatementType type() {
        return SqlStatementType.OTHER;
    }

    /**
     * @return 目标/源表，可空
     */
    public SqlIdentifier table() {
        return table;
    }

    /**
     * @param table 表名
     */
    public void setTable(SqlIdentifier table) {
        this.table = table;
    }

    /**
     * @return 列清单
     */
    public List<SqlIdentifier> columns() {
        return columns;
    }

    /**
     * @return 是否 COPY … TO（否则 FROM）
     */
    public boolean to() {
        return to;
    }

    /**
     * @param to TO 方向
     */
    public void setTo(boolean to) {
        this.to = to;
    }

    /**
     * @return STDIN / STDOUT / PROGRAM / FILE，可空
     */
    public String sourceKind() {
        return sourceKind;
    }

    /**
     * @param sourceKind 源种类
     */
    public void setSourceKind(String sourceKind) {
        this.sourceKind = sourceKind;
    }

    /**
     * @return 文件名或 PROGRAM 命令，可空
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
     * @return WITH 子句原文，可空
     */
    public String withClause() {
        return withClause;
    }

    /**
     * @param withClause WITH 原文
     */
    public void setWithClause(String withClause) {
        this.withClause = withClause;
    }

    /**
     * @return COPY (query) 的 query 原文，可空
     */
    public String query() {
        return query;
    }

    /**
     * @param query 查询原文
     */
    public void setQuery(String query) {
        this.query = query;
    }

    /**
     * @return 整段/残余原文，可空
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
        child(visitor, table);
        children(visitor, columns);
        child(visitor, source);
    }
}
