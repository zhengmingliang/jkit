package com.alianga.jkit.sql.visitor;

import com.alianga.jkit.sql.ast.SqlNode;

/**
 * AST 访问者。返回 false 时跳过该节点的子树（与 Druid 一致）。
 *
 * @author 郑明亮
 * @since 2.0.1
 */
public interface SqlVisitor {
    /**
     * 进入节点。
     *
     * @param node 节点
     * @return 是否继续访问子节点
     */
    boolean visit(SqlNode node);

    /**
     * 离开节点。
     *
     * @param node 节点
     */
    void endVisit(SqlNode node);
}
