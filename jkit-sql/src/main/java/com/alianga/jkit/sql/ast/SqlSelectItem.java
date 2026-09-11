package com.alianga.jkit.sql.ast;

import com.alianga.jkit.sql.visitor.SqlVisitor;

import java.util.ArrayList;
import java.util.List;

/**
 * SELECT 列表项。
 *
 * @author 郑明亮
 * @since 2.0.1
 */
public final class SqlSelectItem extends SqlNode {
    private SqlExpr expr;
    private String alias;
    /** Hive UDTF 多列别名：{@code fn(...) AS (c0, c1)}。 */
    private final List<SqlIdentifier> columnAliases = new ArrayList<SqlIdentifier>(2);

    /**
     * @return 表达式
     */
    public SqlExpr expr() {
        return expr;
    }

    /**
     * @param expr 表达式
     */
    public void setExpr(SqlExpr expr) {
        this.expr = expr;
    }

    /**
     * @return 别名，可空
     */
    public String alias() {
        return alias;
    }

    /**
     * @param alias 别名
     */
    public void setAlias(String alias) {
        this.alias = alias;
    }

    /**
     * Hive UDTF 多列别名列表。
     *
     * @return 列别名，可能为空
     * @since 2.0.1
     */
    public List<SqlIdentifier> columnAliases() {
        return columnAliases;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    protected void acceptChildren(SqlVisitor visitor) {
        child(visitor, expr);
        children(visitor, columnAliases);
    }
}
