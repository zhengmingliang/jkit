package com.alianga.jkit.sql.ast;

import com.alianga.jkit.sql.visitor.SqlVisitor;

import java.util.ArrayList;
import java.util.List;

/**
 * Oracle {@code INSERT ALL/FIRST} 的一条 {@code [WHEN … THEN] INTO … VALUES …} / {@code ELSE INTO …} 分支。
 *
 * @author 郑明亮
 * @since 2.1.0
 */
public final class SqlInsertBranch extends SqlNode {
    private SqlExpr when;
    private boolean elseBranch;
    private SqlTable table;
    private final List<SqlIdentifier> columns = new ArrayList<SqlIdentifier>(4);
    private final List<SqlExpr> values = new ArrayList<SqlExpr>(4);

    /**
     * @return WHEN 条件，可空
     */
    public SqlExpr when() {
        return when;
    }

    /**
     * @param when WHEN 条件
     */
    public void setWhen(SqlExpr when) {
        this.when = when;
    }

    /**
     * @return 是否 ELSE 分支
     */
    public boolean elseBranch() {
        return elseBranch;
    }

    /**
     * @param elseBranch ELSE
     */
    public void setElseBranch(boolean elseBranch) {
        this.elseBranch = elseBranch;
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
     * @return 列清单
     */
    public List<SqlIdentifier> columns() {
        return columns;
    }

    /**
     * @return VALUES 表达式
     */
    public List<SqlExpr> values() {
        return values;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    protected void acceptChildren(SqlVisitor visitor) {
        child(visitor, when);
        child(visitor, table);
        children(visitor, columns);
        children(visitor, values);
    }
}
