package com.alianga.jkit.sql.ast;

import com.alianga.jkit.sql.visitor.SqlVisitor;

/**
 * SELECT 级命名窗口：{@code WINDOW w AS (PARTITION BY ... ORDER BY ...)}。
 *
 * @author 郑明亮
 * @since 2.1.0
 */
public final class SqlWindowDefinition extends SqlNode {
    private SqlIdentifier name;
    private SqlOverExpr spec;

    /**
     * @return 窗口名
     */
    public SqlIdentifier name() {
        return name;
    }

    /**
     * @param name 窗口名
     */
    public void setName(SqlIdentifier name) {
        this.name = name;
    }

    /**
     * @return 窗口规格（与 {@code OVER (...)} 括号体同构）
     */
    public SqlOverExpr spec() {
        return spec;
    }

    /**
     * @param spec 窗口规格
     */
    public void setSpec(SqlOverExpr spec) {
        this.spec = spec;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    protected void acceptChildren(SqlVisitor visitor) {
        child(visitor, name);
        child(visitor, spec);
    }
}
