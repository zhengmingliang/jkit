package com.alianga.jkit.sql.ast;

import com.alianga.jkit.sql.visitor.SqlVisitor;

import java.util.ArrayList;
import java.util.List;

/**
 * {@code EXPLAIN} / {@code DESCRIBE}/{@code DESC} 语句。
 *
 * <p>{@link #describe()} 区分 DESCRIBE/DESC；{@link #analyze()} / {@link #format()} /
 * {@link #options()} 拆分 ANALYZE、FORMAT、BUFFERS 等选项；
 * 嵌套语句进 {@link #statement()}；仅表名时进 {@link #name()}；残余进 {@link #raw()}。
 * 语句种类为 {@link SqlStatementType#EXPLAIN}。</p>
 *
 * @author 郑明亮
 * @since 2.0.1
 */
public final class SqlExplainStatement extends SqlStatement {
    /** true 表示 DESCRIBE/DESC。 */
    private boolean describe;
    /** EXPLAIN ANALYZE 或选项列表中的 ANALYZE。 */
    private boolean analyze;
    /** FORMAT 值（JSON/TEXT/XML/YAML/…），可空。 */
    private String format;
    /** BUFFERS / VERBOSE / COSTS / WAL / TIMING / SUMMARY 等其它选项。 */
    private final List<String> options = new ArrayList<String>(2);
    /** 嵌套被解释语句，可空。 */
    private SqlStatement statement;
    /** DESCRIBE/EXPLAIN 表名，可空。 */
    private SqlIdentifier name;
    /** 残余原文，可空。 */
    private String raw;

    /**
     * {@inheritDoc}
     */
    @Override
    public SqlStatementType type() {
        return SqlStatementType.EXPLAIN;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean isReadOnly() {
        return true;
    }

    /**
     * @return 是否为 DESCRIBE/DESC
     */
    public boolean describe() {
        return describe;
    }

    /**
     * @param describe DESCRIBE/DESC
     */
    public void setDescribe(boolean describe) {
        this.describe = describe;
    }

    /**
     * @return 是否 ANALYZE
     */
    public boolean analyze() {
        return analyze;
    }

    /**
     * @param analyze ANALYZE
     */
    public void setAnalyze(boolean analyze) {
        this.analyze = analyze;
    }

    /**
     * @return FORMAT 值，可空
     */
    public String format() {
        return format;
    }

    /**
     * @param format FORMAT 值
     */
    public void setFormat(String format) {
        this.format = format;
    }

    /**
     * @return 其它选项列表
     */
    public List<String> options() {
        return options;
    }

    /**
     * @return 嵌套语句，可空
     */
    public SqlStatement statement() {
        return statement;
    }

    /**
     * @param statement 嵌套语句
     */
    public void setStatement(SqlStatement statement) {
        this.statement = statement;
    }

    /**
     * @return 表名，可空
     */
    public SqlIdentifier name() {
        return name;
    }

    /**
     * @param name 表名
     */
    public void setName(SqlIdentifier name) {
        this.name = name;
    }

    /**
     * @return 残余原文，可空
     */
    public String raw() {
        return raw;
    }

    /**
     * @param raw 残余原文
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
        child(visitor, statement);
    }
}
