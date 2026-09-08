package com.alianga.jkit.sql.ast;

import com.alianga.jkit.sql.visitor.SqlVisitor;

import java.util.ArrayList;
import java.util.List;

/**
 * DELETE。
 *
 * @author 郑明亮
 * @since 2.0.1
 */
public final class SqlDelete extends SqlStatement {
    private SqlTableSource table;
    private SqlTableSource from;
    private boolean usingKeyword;
    private SqlExpr where;
    private SqlLimit limit;
    private final List<SqlOrderByItem> orderBy = new ArrayList<SqlOrderByItem>(2);
    private SqlExpr returning;
    private final List<SqlExpr> output = new ArrayList<SqlExpr>(2);
    private SqlTable outputInto;

    /**
     * {@inheritDoc}
     */
    @Override
    public SqlStatementType type() {
        return SqlStatementType.DELETE;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean isReadOnly() {
        return false;
    }

    /**
     * @return 删除目标
     */
    public SqlTableSource table() {
        return table;
    }

    /**
     * @param table 目标
     */
    public void setTable(SqlTableSource table) {
        this.table = table;
    }

    /**
     * @return DELETE ... FROM / USING 附加表源
     */
    public SqlTableSource from() {
        return from;
    }

    /**
     * @param from FROM
     */
    public void setFrom(SqlTableSource from) {
        this.from = from;
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
     * @return 附加表源是否用 USING 关键字（PG）；false 时回写 FROM（MySQL 多表删除）
     */
    public boolean usingKeyword() {
        return usingKeyword;
    }

    /**
     * @param usingKeyword USING
     */
    public void setUsingKeyword(boolean usingKeyword) {
        this.usingKeyword = usingKeyword;
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
        child(visitor, from);
        child(visitor, where);
        children(visitor, orderBy);
        child(visitor, limit);
        child(visitor, returning);
        children(visitor, output);
        child(visitor, outputInto);
    }
}
