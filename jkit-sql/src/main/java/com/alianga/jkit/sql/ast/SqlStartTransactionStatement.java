package com.alianga.jkit.sql.ast;

import com.alianga.jkit.sql.visitor.SqlVisitor;

/**
 * {@code START TRANSACTION …} / {@code BEGIN [WORK|TRANSACTION] …} 事务开启。
 *
 * <p>结构化隔离级别、{@code READ WRITE|ONLY}、{@code WITH CONSISTENT SNAPSHOT}（及 PG
 * {@code [NOT] DEFERRABLE}）；无法识别的特征进 {@link #raw()}。
 * 过程块 {@code BEGIN … END} 仍走 {@link SqlBlockStatement}，不经本类型。
 * 语句种类为 {@link SqlStatementType#OTHER}。</p>
 *
 * @author 郑明亮
 * @since 2.0.1
 */
public final class SqlStartTransactionStatement extends SqlStatement {
    /** true = BEGIN [WORK|TRANSACTION]；false = START TRANSACTION。 */
    private boolean beginForm;
    /** BEGIN WORK。 */
    private boolean work;
    /** ISOLATION LEVEL 后的级别原文，如 {@code REPEATABLE READ}，可空。 */
    private String isolationLevel;
    /**
     * {@code READ ONLY} → true；{@code READ WRITE} → false；未指定 → null。
     */
    private Boolean readOnly;
    /** MySQL {@code WITH CONSISTENT SNAPSHOT}。 */
    private boolean consistentSnapshot;
    /**
     * PG {@code DEFERRABLE} → true；{@code NOT DEFERRABLE} → false；未指定 → null。
     */
    private Boolean deferrable;
    /** 无法结构化的特征/尾部原文，可空。 */
    private String raw;

    /**
     * {@inheritDoc}
     */
    @Override
    public SqlStatementType type() {
        return SqlStatementType.OTHER;
    }

    /**
     * @return 是否为 BEGIN 形式（相对 START TRANSACTION）
     */
    public boolean beginForm() {
        return beginForm;
    }

    /**
     * @param beginForm BEGIN 形式
     */
    public void setBeginForm(boolean beginForm) {
        this.beginForm = beginForm;
    }

    /**
     * @return 是否 BEGIN WORK
     */
    public boolean work() {
        return work;
    }

    /**
     * @param work BEGIN WORK
     */
    public void setWork(boolean work) {
        this.work = work;
    }

    /**
     * @return 隔离级别原文，可空
     */
    public String isolationLevel() {
        return isolationLevel;
    }

    /**
     * @param isolationLevel 隔离级别
     */
    public void setIsolationLevel(String isolationLevel) {
        this.isolationLevel = isolationLevel;
    }

    /**
     * @return READ ONLY / READ WRITE；未指定为 null
     */
    public Boolean readOnly() {
        return readOnly;
    }

    /**
     * @param readOnly true=READ ONLY，false=READ WRITE
     */
    public void setReadOnly(Boolean readOnly) {
        this.readOnly = readOnly;
    }

    /**
     * @return 是否 WITH CONSISTENT SNAPSHOT
     */
    public boolean consistentSnapshot() {
        return consistentSnapshot;
    }

    /**
     * @param consistentSnapshot WITH CONSISTENT SNAPSHOT
     */
    public void setConsistentSnapshot(boolean consistentSnapshot) {
        this.consistentSnapshot = consistentSnapshot;
    }

    /**
     * @return DEFERRABLE 语义；未指定为 null
     */
    public Boolean deferrable() {
        return deferrable;
    }

    /**
     * @param deferrable DEFERRABLE / NOT DEFERRABLE
     */
    public void setDeferrable(Boolean deferrable) {
        this.deferrable = deferrable;
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
    }
}
