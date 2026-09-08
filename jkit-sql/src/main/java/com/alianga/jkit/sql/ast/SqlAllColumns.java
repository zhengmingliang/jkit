package com.alianga.jkit.sql.ast;

import com.alianga.jkit.sql.visitor.SqlVisitor;

/**
 * {@code *} 或 {@code t.*}。
 *
 * @author 郑明亮
 * @since 2.0.1
 */
public final class SqlAllColumns extends SqlExpr {
    private SqlIdentifier owner;

    /**
     * @return 限定表，可空
     */
    public SqlIdentifier owner() {
        return owner;
    }

    /**
     * @param owner 限定表
     */
    public void setOwner(SqlIdentifier owner) {
        this.owner = owner;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    protected void acceptChildren(SqlVisitor visitor) {
        child(visitor, owner);
    }
}
