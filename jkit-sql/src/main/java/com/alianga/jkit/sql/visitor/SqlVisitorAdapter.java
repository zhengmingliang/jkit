package com.alianga.jkit.sql.visitor;

import com.alianga.jkit.sql.ast.SqlNode;

/**
 * 空访问者，默认遍历整棵树。
 *
 * @author 郑明亮
 * @since 2.1.0
 */
public class SqlVisitorAdapter implements SqlVisitor {
    /**
     * {@inheritDoc}
     */
    @Override
    public boolean visit(SqlNode node) {
        return true;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void endVisit(SqlNode node) {
    }
}
