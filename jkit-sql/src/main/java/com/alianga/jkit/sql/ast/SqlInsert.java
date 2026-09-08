package com.alianga.jkit.sql.ast;

import com.alianga.jkit.sql.visitor.SqlVisitor;

import java.util.ArrayList;
import java.util.List;

/**
 * INSERT / REPLACE。
 *
 * @author 郑明亮
 * @since 2.1.0
 */
public final class SqlInsert extends SqlStatement {
    private boolean replace;
    private boolean delayed;
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
     * @since 2.1.0
     */
    public boolean delayed() {
        return delayed;
    }

    /**
     * @param delayed {@code DELAYED}
     * @since 2.1.0
     */
    public void setDelayed(boolean delayed) {
        this.delayed = delayed;
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
     * @since 2.1.0
     */
    public SqlTable outputInto() {
        return outputInto;
    }

    /**
     * @param outputInto INTO 目标
     * @since 2.1.0
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
