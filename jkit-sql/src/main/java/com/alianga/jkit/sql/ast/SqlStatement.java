package com.alianga.jkit.sql.ast;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * SQL 语句。
 *
 * @author 郑明亮
 * @since 2.0.1
 */
public abstract class SqlStatement extends SqlNode {
    private List<SqlWithItem> withItems;
    private boolean withRecursive;
    private List<String> comments;

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
     * @return 语句前保留的普通注释（仅 keepComments 时有值），可能为空列表
     * @since 2.0.1
     */
    public List<String> comments() {
        if (comments == null) {
            return Collections.emptyList();
        }
        return comments;
    }

    /**
     * @param comments 语句前注释
     * @since 2.0.1
     */
    public void setComments(List<String> comments) {
        this.comments = comments;
    }

    /**
     * @param comment 追加一条注释原文
     * @since 2.0.1
     */
    public void addComment(String comment) {
        if (comment == null || comment.isEmpty()) {
            return;
        }
        if (comments == null) {
            comments = new ArrayList<String>(2);
        }
        comments.add(comment);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    protected void acceptChildren(com.alianga.jkit.sql.visitor.SqlVisitor visitor) {
        children(visitor, withItems);
    }
}
