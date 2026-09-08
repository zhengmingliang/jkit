package com.alianga.jkit.sql.ast;

import com.alianga.jkit.sql.visitor.SqlVisitor;

/**
 * MERGE INTO ... USING ... ON ... WHEN MATCHED / NOT MATCHED。
 *
 * @author 郑明亮
 * @since 2.1.0
 */
public final class SqlMerge extends SqlStatement {
    private SqlTableSource into;
    private SqlTableSource using;
    private SqlExpr on;
    private SqlUpdate update;
    private SqlInsert insert;
    private SqlExpr deleteWhere;

    /**
     * {@inheritDoc}
     */
    @Override
    public SqlStatementType type() {
        return SqlStatementType.MERGE;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean isReadOnly() {
        return false;
    }

    /**
     * @return INTO
     */
    public SqlTableSource into() {
        return into;
    }

    /**
     * @param into INTO
     */
    public void setInto(SqlTableSource into) {
        this.into = into;
    }

    /**
     * @return USING
     */
    public SqlTableSource using() {
        return using;
    }

    /**
     * @param using USING
     */
    public void setUsing(SqlTableSource using) {
        this.using = using;
    }

    /**
     * @return ON
     */
    public SqlExpr on() {
        return on;
    }

    /**
     * @param on ON
     */
    public void setOn(SqlExpr on) {
        this.on = on;
    }

    /**
     * @return WHEN MATCHED THEN UPDATE
     */
    public SqlUpdate update() {
        return update;
    }

    /**
     * @param update UPDATE
     */
    public void setUpdate(SqlUpdate update) {
        this.update = update;
    }

    /**
     * @return WHEN NOT MATCHED THEN INSERT
     */
    public SqlInsert insert() {
        return insert;
    }

    /**
     * @param insert INSERT
     */
    public void setInsert(SqlInsert insert) {
        this.insert = insert;
    }

    /**
     * @return MATCHED 时 DELETE 条件
     */
    public SqlExpr deleteWhere() {
        return deleteWhere;
    }

    /**
     * @param deleteWhere DELETE 条件
     */
    public void setDeleteWhere(SqlExpr deleteWhere) {
        this.deleteWhere = deleteWhere;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    protected void acceptChildren(SqlVisitor visitor) {
        super.acceptChildren(visitor);
        child(visitor, into);
        child(visitor, using);
        child(visitor, on);
        child(visitor, update);
        child(visitor, insert);
        child(visitor, deleteWhere);
    }
}
