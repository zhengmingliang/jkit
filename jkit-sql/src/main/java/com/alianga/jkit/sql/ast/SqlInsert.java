package com.alianga.jkit.sql.ast;

import com.alianga.jkit.sql.visitor.SqlVisitor;

import java.util.ArrayList;
import java.util.List;

/**
 * INSERT / REPLACE。
 *
 * @author 郑明亮
 * @since 2.0.1
 */
public final class SqlInsert extends SqlStatement {
    private boolean replace;
    private boolean delayed;
    private boolean ignore;
    private boolean lowPriority;
    private boolean highPriority;
    /** Hive：{@code INSERT OVERWRITE}。 */
    private boolean overwrite;
    /** Hive：{@code INSERT OVERWRITE TABLE t} 带 TABLE 关键字。 */
    private boolean tableKeyword;
    /** Hive：{@code PARTITION (dt='2024')} 子句原文（含外层括号）。 */
    private String partitionRaw;
    private SqlTable table;
    private final List<SqlIdentifier> columns = new ArrayList<SqlIdentifier>(4);
    private final List<List<SqlExpr>> valuesList = new ArrayList<List<SqlExpr>>(2);
    private SqlStatement query;
    private final List<SqlBinaryExpr> setList = new ArrayList<SqlBinaryExpr>(2);
    private final List<SqlBinaryExpr> duplicateUpdates = new ArrayList<SqlBinaryExpr>(2);
    private final List<SqlIdentifier> conflictTarget = new ArrayList<SqlIdentifier>(2);
    private boolean onConflict;
    private boolean conflictDoNothing;
    private SqlIdentifier conflictConstraint;
    private boolean insertAll;
    private boolean insertFirst;
    private final List<SqlInsertBranch> branches = new ArrayList<SqlInsertBranch>(2);
    private SqlExpr returning;
    private final List<SqlExpr> output = new ArrayList<SqlExpr>(2);
    private SqlTable outputInto;

    /**
     * {@inheritDoc}
     */
    @Override
    public SqlStatementType type() {
        return replace ? SqlStatementType.REPLACE : SqlStatementType.INSERT;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean isReadOnly() {
        return false;
    }

    /**
     * @return REPLACE INTO
     */
    public boolean replace() {
        return replace;
    }

    /**
     * @param replace REPLACE
     */
    public void setReplace(boolean replace) {
        this.replace = replace;
    }

    /**
     * @return MySQL {@code INSERT DELAYED}
     * @since 2.0.1
     */
    public boolean delayed() {
        return delayed;
    }

    /**
     * @param delayed {@code DELAYED}
     * @since 2.0.1
     */
    public void setDelayed(boolean delayed) {
        this.delayed = delayed;
    }

    /**
     * @return MySQL {@code INSERT IGNORE}（错误降级为警告）
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
     * @return MySQL {@code INSERT LOW_PRIORITY}
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
     * @return MySQL {@code INSERT HIGH_PRIORITY}
     * @since 2.0.1
     */
    public boolean highPriority() {
        return highPriority;
    }

    /**
     * @param highPriority {@code HIGH_PRIORITY}
     * @since 2.0.1
     */
    public void setHighPriority(boolean highPriority) {
        this.highPriority = highPriority;
    }

    /**
     * @return Hive {@code INSERT OVERWRITE}
     * @since 2.0.1
     */
    public boolean overwrite() {
        return overwrite;
    }

    /**
     * @param overwrite {@code OVERWRITE}
     * @since 2.0.1
     */
    public void setOverwrite(boolean overwrite) {
        this.overwrite = overwrite;
    }

    /**
     * @return 带 {@code TABLE} 关键字（Hive {@code INSERT OVERWRITE TABLE t}）
     * @since 2.0.1
     */
    public boolean tableKeyword() {
        return tableKeyword;
    }

    /**
     * @param tableKeyword {@code TABLE} 关键字
     * @since 2.0.1
     */
    public void setTableKeyword(boolean tableKeyword) {
        this.tableKeyword = tableKeyword;
    }

    /**
     * @return Hive {@code PARTITION (...)} 子句原文（含外层括号），无则 null
     * @since 2.0.1
     */
    public String partitionRaw() {
        return partitionRaw;
    }

    /**
     * @param partitionRaw {@code PARTITION (...)} 原文
     * @since 2.0.1
     */
    public void setPartitionRaw(String partitionRaw) {
        this.partitionRaw = partitionRaw;
    }

    /**
     * @return 目标表
     */
    public SqlTable table() {
        return table;
    }

    /**
     * @param table 目标表
     */
    public void setTable(SqlTable table) {
        this.table = table;
    }

    /**
     * @return 列
     */
    public List<SqlIdentifier> columns() {
        return columns;
    }

    /**
     * @return VALUES 多行
     */
    public List<List<SqlExpr>> valuesList() {
        return valuesList;
    }

    /**
     * @return INSERT ... SELECT
     */
    public SqlStatement query() {
        return query;
    }

    /**
     * @param query SELECT
     */
    public void setQuery(SqlStatement query) {
        this.query = query;
    }

    /**
     * @return INSERT ... SET
     */
    public List<SqlBinaryExpr> setList() {
        return setList;
    }

    /**
     * @return ON DUPLICATE KEY UPDATE / ON CONFLICT DO UPDATE SET
     */
    public List<SqlBinaryExpr> duplicateUpdates() {
        return duplicateUpdates;
    }

    /**
     * @return ON CONFLICT 目标列
     */
    public List<SqlIdentifier> conflictTarget() {
        return conflictTarget;
    }

    /**
     * @return 是否 ON CONFLICT
     */
    public boolean onConflict() {
        return onConflict;
    }

    /**
     * @param onConflict ON CONFLICT
     */
    public void setOnConflict(boolean onConflict) {
        this.onConflict = onConflict;
    }

    /**
     * @return DO NOTHING
     */
    public boolean conflictDoNothing() {
        return conflictDoNothing;
    }

    /**
     * @param conflictDoNothing DO NOTHING
     */
    public void setConflictDoNothing(boolean conflictDoNothing) {
        this.conflictDoNothing = conflictDoNothing;
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
     * @return ON CONFLICT ON CONSTRAINT 约束名
     */
    public SqlIdentifier conflictConstraint() {
        return conflictConstraint;
    }

    /**
     * @param conflictConstraint 约束名
     */
    public void setConflictConstraint(SqlIdentifier conflictConstraint) {
        this.conflictConstraint = conflictConstraint;
    }

    /**
     * @return Oracle INSERT ALL
     */
    public boolean insertAll() {
        return insertAll;
    }

    /**
     * @param insertAll INSERT ALL
     */
    public void setInsertAll(boolean insertAll) {
        this.insertAll = insertAll;
    }

    /**
     * @return Oracle INSERT FIRST
     */
    public boolean insertFirst() {
        return insertFirst;
    }

    /**
     * @param insertFirst INSERT FIRST
     */
    public void setInsertFirst(boolean insertFirst) {
        this.insertFirst = insertFirst;
    }

    /**
     * @return INSERT ALL/FIRST 分支
     */
    public List<SqlInsertBranch> branches() {
        return branches;
    }

    /**
     * @return SQL Server OUTPUT 列表
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
        children(visitor, columns);
        for (int i = 0; i < valuesList.size(); i++) {
            children(visitor, valuesList.get(i));
        }
        child(visitor, query);
        children(visitor, setList);
        children(visitor, duplicateUpdates);
        children(visitor, conflictTarget);
        child(visitor, conflictConstraint);
        children(visitor, branches);
        child(visitor, returning);
        children(visitor, output);
        child(visitor, outputInto);
    }
}
