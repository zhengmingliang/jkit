package com.alianga.jkit.sql.ast;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * SQL 语句。
 *
 * @author 郑明亮
 * @since 2.1.0
 */
public abstract class SqlStatement extends SqlNode {
    private List<SqlWithItem> withItems;
    private boolean withRecursive;

    /**
     * @return 语句种类
     */
    public abstract SqlStatementType type();

    /**
     * @return 是否只读（SELECT / SHOW / EXPLAIN 只读查询）
     */
    public boolean isReadOnly() {
        SqlStatementType t = type();
        return t == SqlStatementType.SELECT || t == SqlStatementType.SHOW
                || t == SqlStatementType.EXPLAIN;
    }

    /**
     * @return CTE 列表，可能为空列表
     */
    public List<SqlWithItem> withItems() {
        if (withItems == null) {
            return Collections.emptyList();
        }
        return withItems;
    }

    /**
     * @param items CTE
     */
    public void setWithItems(List<SqlWithItem> items) {
        this.withItems = items;
    }

    /**
     * @return WITH RECURSIVE
     */
    public boolean withRecursive() {
        return withRecursive;
    }

    /**
     * @param withRecursive WITH RECURSIVE
     */
    public void setWithRecursive(boolean withRecursive) {
        this.withRecursive = withRecursive;
    }

    /**
     * @param item CTE
     */
    public void addWithItem(SqlWithItem item) {
        if (withItems == null) {
            withItems = new ArrayList<SqlWithItem>(2);
        }
        withItems.add(item);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    protected void acceptChildren(com.alianga.jkit.sql.visitor.SqlVisitor visitor) {
        children(visitor, withItems);
    }
}
