package com.alianga.jkit.sql.ast;

import com.alianga.jkit.sql.visitor.SqlVisitor;

import java.util.ArrayList;
import java.util.List;

/**
 * {@code ANALYZE} / {@code VACUUM} / {@code OPTIMIZE TABLE} / {@code REPAIR TABLE} /
 * {@code CHECK TABLE} 维护语句。
 *
 * <p>种类进 {@link #kind()}；表清单进 {@link #tables()}；
 * {@code TABLE}/{@code FULL}/{@code ANALYZE}/{@code VERBOSE} 等选项原文进 {@link #optionsRaw()}；
 * 无法识别的尾部进 {@link #raw()}。语句种类为 {@link SqlStatementType#OTHER}。</p>
 *
 * @author 郑明亮
 * @since 2.0.1
 */
public final class SqlMaintenanceStatement extends SqlStatement {
    /** ANALYZE / VACUUM / OPTIMIZE / REPAIR / CHECK。 */
    private String kind;
    /** 表清单。 */
    private final List<SqlIdentifier> tables = new ArrayList<SqlIdentifier>(2);
    /** TABLE/FULL/ANALYZE/VERBOSE 等选项原文，可空。 */
    private String optionsRaw;
    /** 残余原文，可空。 */
    private String raw;

    /**
     * {@inheritDoc}
     */
    @Override
    public SqlStatementType type() {
        return SqlStatementType.OTHER;
    }

    /**
     * @return ANALYZE / VACUUM / OPTIMIZE / REPAIR / CHECK
     */
    public String kind() {
        return kind;
    }

    /**
     * @param kind 维护种类
     */
    public void setKind(String kind) {
        this.kind = kind;
    }

    /**
     * @return 表清单
     */
    public List<SqlIdentifier> tables() {
        return tables;
    }

    /**
     * @return 选项原文，可空
     */
    public String optionsRaw() {
        return optionsRaw;
    }

    /**
     * @param optionsRaw 选项原文
     */
    public void setOptionsRaw(String optionsRaw) {
        this.optionsRaw = optionsRaw;
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
        children(visitor, tables);
    }
}
