package com.alianga.jkit.sql.ast;

import com.alianga.jkit.sql.visitor.SqlVisitor;

import java.util.ArrayList;
import java.util.List;

/**
 * FROM 行构造：{@code (VALUES (1), (2)) AS v(id)}。
 *
 * @author 郑明亮
 * @since 2.0.1
 */
public final class SqlValuesTable extends SqlTableSource {
    private final List<SqlExpr> rows = new ArrayList<SqlExpr>(2);

    /**
     * @return 各行（多为 {@link SqlListExpr} 或单列标量）
     */
    public List<SqlExpr> rows() {
        return rows;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    protected void acceptChildren(SqlVisitor visitor) {
        children(visitor, rows);
        children(visitor, columnAliases());
    }
}
