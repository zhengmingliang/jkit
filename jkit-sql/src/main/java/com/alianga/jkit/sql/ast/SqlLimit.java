package com.alianga.jkit.sql.ast;

import com.alianga.jkit.sql.visitor.SqlVisitor;

/**
 * LIMIT / OFFSET / FETCH。
 *
 * @author 郑明亮
 * @since 2.1.0
 */
public final class SqlLimit extends SqlNode {
    private SqlExpr offset;
    private SqlExpr rowCount;
    private boolean mysqlCommaStyle;

    /**
     * @return OFFSET
     */
    public SqlExpr offset() {
        return offset;
    }

    /**
     * @param offset OFFSET
     */
    public void setOffset(SqlExpr offset) {
        this.offset = offset;
    }

    /**
     * @return 行数
     */
    public SqlExpr rowCount() {
        return rowCount;
    }

    /**
     * @param rowCount 行数
     */
    public void setRowCount(SqlExpr rowCount) {
        this.rowCount = rowCount;
    }

    /**
     * @return {@code LIMIT offset, count} 写法
     */
    public boolean mysqlCommaStyle() {
        return mysqlCommaStyle;
    }

    /**
     * @param mysqlCommaStyle 逗号写法
     */
    public void setMysqlCommaStyle(boolean mysqlCommaStyle) {
        this.mysqlCommaStyle = mysqlCommaStyle;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    protected void acceptChildren(SqlVisitor visitor) {
        child(visitor, offset);
        child(visitor, rowCount);
    }
}
