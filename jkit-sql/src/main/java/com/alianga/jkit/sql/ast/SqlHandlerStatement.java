package com.alianga.jkit.sql.ast;

import com.alianga.jkit.sql.visitor.SqlVisitor;

import java.util.ArrayList;
import java.util.List;

/**
 * 过程体内 {@code DECLARE {CONTINUE|EXIT|UNDO} HANDLER FOR … statement}。
 *
 * <p>条件以原文列表保留；处理体尽量结构化。失败时整段进 {@link #raw()}。
 * 语句种类仍为 {@link SqlStatementType#OTHER}。顶层表 {@code HANDLER t OPEN} 不走本节点。</p>
 *
 * @author 郑明亮
 * @since 2.0.1
 */
public final class SqlHandlerStatement extends SqlStatement {
    /** CONTINUE / EXIT / UNDO。 */
    private String action;
    /** {@code FOR} 后各条件原文（{@code NOT FOUND} / {@code SQLEXCEPTION} / {@code SQLSTATE '…'} 等）。 */
    private final List<String> conditions = new ArrayList<String>(2);
    /** 处理体语句（单句或 BEGIN 内多句）。 */
    private final List<SqlStatement> bodyStatements = new ArrayList<SqlStatement>(1);
    /** 整段原文，便于往返。 */
    private String raw;

    /**
     * {@inheritDoc}
     */
    @Override
    public SqlStatementType type() {
        return SqlStatementType.OTHER;
    }

    /**
     * @return CONTINUE / EXIT / UNDO，可空
     */
    public String action() {
        return action;
    }

    /**
     * @param action 动作
     */
    public void setAction(String action) {
        this.action = action;
    }

    /**
     * @return FOR 条件原文列表
     */
    public List<String> conditions() {
        return conditions;
    }

    /**
     * @return 处理体语句
     */
    public List<SqlStatement> bodyStatements() {
        return bodyStatements;
    }

    /**
     * @return 整段原文，可空
     */
    public String raw() {
        return raw;
    }

    /**
     * @param raw 原文
     */
    public void setRaw(String raw) {
        this.raw = raw;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    protected void acceptChildren(SqlVisitor visitor) {
        super.acceptChildren(visitor);
        children(visitor, bodyStatements);
    }
}
