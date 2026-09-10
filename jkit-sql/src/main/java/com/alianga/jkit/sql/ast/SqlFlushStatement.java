package com.alianga.jkit.sql.ast;

import com.alianga.jkit.sql.visitor.SqlVisitor;

import java.util.ArrayList;
import java.util.List;

/**
 * MySQL {@code FLUSH [LOCAL|NO_WRITE_TO_BINLOG] option[, option…]}。
 *
 * <p>选项如 {@code PRIVILEGES}、{@code LOGS}、{@code STATUS}、{@code TABLES [tbl…]} 等进
 * {@link #options()}；{@code TABLES} 后的表名进 {@link #tables()}；
 * {@code WITH READ LOCK}/{@code FOR EXPORT} 进 {@link #tablesModifier()}；
 * 无法识别的残余进 {@link #raw()}。语句种类为 {@link SqlStatementType#OTHER}。</p>
 *
 * @author 郑明亮
 * @since 2.0.1
 */
public final class SqlFlushStatement extends SqlStatement {
    /** LOCAL / NO_WRITE_TO_BINLOG。 */
    private boolean noWriteToBinlog;
    /** 选项名列表（如 PRIVILEGES、LOGS、BINARY LOGS、TABLES）。 */
    private final List<String> options = new ArrayList<String>(2);
    /** FLUSH TABLES 后的表清单。 */
    private final List<SqlIdentifier> tables = new ArrayList<SqlIdentifier>(2);
    /** WITH READ LOCK / FOR EXPORT，可空。 */
    private String tablesModifier;
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
     * @return 是否 LOCAL / NO_WRITE_TO_BINLOG
     */
    public boolean noWriteToBinlog() {
        return noWriteToBinlog;
    }

    /**
     * @param noWriteToBinlog LOCAL / NO_WRITE_TO_BINLOG
     */
    public void setNoWriteToBinlog(boolean noWriteToBinlog) {
        this.noWriteToBinlog = noWriteToBinlog;
    }

    /**
     * @return 选项名列表
     */
    public List<String> options() {
        return options;
    }

    /**
     * @return TABLES 后的表清单
     */
    public List<SqlIdentifier> tables() {
        return tables;
    }

    /**
     * @return WITH READ LOCK / FOR EXPORT，可空
     */
    public String tablesModifier() {
        return tablesModifier;
    }

    /**
     * @param tablesModifier 表修饰
     */
    public void setTablesModifier(String tablesModifier) {
        this.tablesModifier = tablesModifier;
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
