package com.alianga.jkit.sql.ast;

import com.alianga.jkit.sql.visitor.SqlVisitor;

import java.util.ArrayList;
import java.util.List;

/**
 * 行构造或括号列表 {@code (a, b)}。
 *
 * @author 郑明亮
 * @since 2.0.1
 */
public final class SqlListExpr extends SqlExpr {
    private final List<SqlExpr> items = new ArrayList<SqlExpr>(2);

    /**
     * @return 元素
     */
    public List<SqlExpr> items() {
        return items;
    }

    /**
     * @param expr 追加
     */
    public void add(SqlExpr expr) {
        items.add(expr);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    protected void acceptChildren(SqlVisitor visitor) {
        children(visitor, items);
    }
}
