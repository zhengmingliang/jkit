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
    private SqlTable table;
    private final List<SqlIdentifier> columns = new ArrayList<SqlIdentifier>(4);
    private final List<List<SqlExpr>> valuesList = new ArrayList<List<SqlExpr>>(2);
    private SqlStatement query;
    private final List<SqlBinaryExpr> setList = new ArrayList<SqlBinaryExpr>(2);
    private final List<SqlBinaryExpr> duplicateUpdates = new ArrayList<SqlBinaryExpr>(2);
    private final List<SqlIdentifier> conflictTarget = new ArrayList<SqlIdentifier>(2);
    private boolean onConflict;
    private boolean conflictDoNothing;
    private SqlExpr returning;

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
     * {@inheritDoc}
     */
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
        child(visitor, returning);
    }
}
