package com.alianga.jkit.sql.ast;

import com.alianga.jkit.sql.visitor.SqlVisitor;

import java.util.List;

/**
 * AST 节点。
 *
 * @author 郑明亮
 * @since 2.1.0
 */
public abstract class SqlNode {
    /**
     * 深度优先遍历。{@link SqlVisitor#visit(SqlNode)} 返回 false 时跳过子节点。
     *
     * @param visitor 访问者
     * @return 始终 true
     */
    public boolean accept(SqlVisitor visitor) {
        if (visitor.visit(this)) {
            acceptChildren(visitor);
        }
        visitor.endVisit(this);
        return true;
    }

    /**
     * 遍历子节点。
     *
     * @param visitor 访问者
     */
    protected void acceptChildren(SqlVisitor visitor) {
    }

    /**
     * @param visitor 访问者
     * @param node 子节点，null 忽略
     */
    protected static void child(SqlVisitor visitor, SqlNode node) {
        if (node != null) {
            node.accept(visitor);
        }
    }

    /**
     * @param visitor 访问者
     * @param nodes 子节点列表，null 忽略
     */
    protected static void children(SqlVisitor visitor, List<? extends SqlNode> nodes) {
        if (nodes == null) {
            return;
        }
        for (int i = 0; i < nodes.size(); i++) {
            child(visitor, nodes.get(i));
        }
    }
}
