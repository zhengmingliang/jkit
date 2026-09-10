package com.alianga.jkit.sql.ast;

import com.alianga.jkit.sql.visitor.SqlVisitor;

/**
 * {@code COMMIT} / {@code ROLLBACK [TO [SAVEPOINT] name]} / {@code SAVEPOINT name} /
 * {@code RELEASE [SAVEPOINT] name} 事务控制。
 *
 * <p>种类进 {@link #kind()}；保存点名进 {@link #savepoint()}；
 * {@code ROLLBACK TO …} 时 {@link #toSavepoint()} 为 true；
 * {@code WORK} / {@code AND [NO] CHAIN} 等残余进 {@link #raw()}。
 * 语句种类为 {@link SqlStatementType#OTHER}。
 * 开启事务仍走 {@link SqlStartTransactionStatement}。</p>
 *
 * @author 郑明亮
 * @since 2.0.1
 */
public final class SqlTransactionControlStatement extends SqlStatement {
    /** COMMIT / ROLLBACK / SAVEPOINT / RELEASE。 */
    private String kind;
    /** 保存点名，可空。 */
    private SqlIdentifier savepoint;
    /** 是否 ROLLBACK TO [SAVEPOINT]。 */
    private boolean toSavepoint;
    /** WORK / AND CHAIN 等残余原文，可空。 */
    private String raw;

    /**
     * {@inheritDoc}
     */
    @Override
    public SqlStatementType type() {
        return SqlStatementType.OTHER;
    }

    /**
     * @return COMMIT / ROLLBACK / SAVEPOINT / RELEASE
     */
    public String kind() {
        return kind;
    }

    /**
     * @param kind 控制种类
     */
    public void setKind(String kind) {
        this.kind = kind;
    }

    /**
     * @return 保存点名，可空
     */
    public SqlIdentifier savepoint() {
        return savepoint;
    }

    /**
     * @param savepoint 保存点名
     */
    public void setSavepoint(SqlIdentifier savepoint) {
        this.savepoint = savepoint;
    }

    /**
     * @return 是否 ROLLBACK TO …
     */
    public boolean toSavepoint() {
        return toSavepoint;
    }

    /**
     * @param toSavepoint ROLLBACK TO
     */
    public void setToSavepoint(boolean toSavepoint) {
        this.toSavepoint = toSavepoint;
    }

    /**
     * @return 残余原文，可空
     */
    public String raw() {
        return raw;
    }

    /**
     * @param raw 残余原文
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
        child(visitor, savepoint);
    }
}
