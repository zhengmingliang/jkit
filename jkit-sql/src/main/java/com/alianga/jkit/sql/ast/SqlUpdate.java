package com.alianga.jkit.sql.ast;

import com.alianga.jkit.sql.visitor.SqlVisitor;

import java.util.ArrayList;
import java.util.List;

/**
 * UPDATE。
 *
 * @author 郑明亮
 * @since 2.0.1
 */
public final class SqlUpdate extends SqlStatement {
    private SqlTableSource table;
    private boolean ignore;
    private boolean lowPriority;
    /** ODPS/MaxCompute {@code FORCE PARTITION …} / {@code FORCE ALL PARTITIONS} 原文。 */
    private String forcePartition;
    private final List<SqlBinaryExpr> setList = new ArrayList<SqlBinaryExpr>(4);
    private SqlExpr where;
    private SqlLimit limit;
    private final List<SqlOrderByItem> orderBy = new ArrayList<SqlOrderByItem>(2);
    private SqlTableSource from;
    private SqlExpr returning;
    private final List<SqlExpr> output = new ArrayList<SqlExpr>(2);
    private SqlTable outputInto;

    /**
     * {@inheritDoc}
     */
    @Override
    public SqlStatementType type() {
        return SqlStatementType.UPDATE;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean isReadOnly() {
        return false;
    }

    /**
     * @return 目标（可含 JOIN）
     */
    public SqlTableSource table() {
        return table;
    }

    /**
     * @return MySQL {@code UPDATE IGNORE}
     * @since 2.0.1
     */
    public boolean ignore() {
        return ignore;
    }

    /**
     * @param ignore {@code IGNORE}
     * @since 2.0.1
     */
    public void setIgnore(boolean ignore) {
        this.ignore = ignore;
    }

    /**
     * @return MySQL {@code UPDATE LOW_PRIORITY}
     * @since 2.0.1
     */
    public boolean lowPriority() {
        return lowPriority;
    }

    /**
     * @param lowPriority {@code LOW_PRIORITY}
     * @since 2.0.1
     */
    public void setLowPriority(boolean lowPriority) {
        this.lowPriority = lowPriority;
    }

    /**
     * ODPS/MaxCompute {@code FORCE PARTITION …} 原文。
     *
     * @return 原文，可空
     * @since 2.0.1
     */
    public String forcePartition() {
        return forcePartition;
    }

    /**
     * @param forcePartition {@code FORCE PARTITION …} 原文
     * @since 2.0.1
     */
    public void setForcePartition(String forcePartition) {
        this.forcePartition = forcePartition;
    }

    /**
     * @param table 目标
     */
    public void setTable(SqlTableSource table) {
        this.table = table;
    }

    /**
     * @return SET 列表
     */
    public List<SqlBinaryExpr> setList() {
        return setList;
    }

    /**
     * @return WHERE
     */
    public SqlExpr where() {
        return where;
    }

    /**
     * @param where WHERE
     */
    public void setWhere(SqlExpr where) {
        this.where = where;
    }

    /**
     * @return LIMIT
     */
    public SqlLimit limit() {
        return limit;
    }

    /**
     * @param limit LIMIT
     */
    public void setLimit(SqlLimit limit) {
        this.limit = limit;
    }

    /**
     * @return ORDER BY
     */
    public List<SqlOrderByItem> orderBy() {
        return orderBy;
    }

    /**
     * @return RETURNING
     */
    public SqlExpr returning() {
        return returning;
    }

    /**
     * @param returning RETURNING
     */
    public void setReturning(SqlExpr returning) {
        this.returning = returning;
    }

    /**
     * @return PG UPDATE … FROM
     */
    public SqlTableSource from() {
        return from;
    }

    /**
     * @param from FROM 表源
     */
    public void setFrom(SqlTableSource from) {
        this.from = from;
    }

    /**
     * @return SQL Server OUTPUT
     */
    public List<SqlExpr> output() {
        return output;
    }

    /**
     * {@inheritDoc}
     */

    /**
     * @return SQL Server {@code OUTPUT … INTO} 目标表/表变量（可 {@code @out} / {@code #tmp}）
     * @since 2.0.1
     */
    public SqlTable outputInto() {
        return outputInto;
    }

    /**
     * @param outputInto INTO 目标
     * @since 2.0.1
     */
    public void setOutputInto(SqlTable outputInto) {
        this.outputInto = outputInto;
    }

    @Override
    protected void acceptChildren(SqlVisitor visitor) {
        super.acceptChildren(visitor);
        child(visitor, table);
        children(visitor, setList);
        child(visitor, where);
        children(visitor, orderBy);
        child(visitor, limit);
        child(visitor, from);
        child(visitor, returning);
        children(visitor, output);
        child(visitor, outputInto);
    }
}
