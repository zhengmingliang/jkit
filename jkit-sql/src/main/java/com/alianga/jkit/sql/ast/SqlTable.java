package com.alianga.jkit.sql.ast;

import com.alianga.jkit.sql.visitor.SqlVisitor;

/**
 * 物理表或视图。
 *
 * @author 郑明亮
 * @since 2.1.0
 */
public final class SqlTable extends SqlTableSource {
    private SqlIdentifier name;
    private String indexHint;

    /**
     * @param name 表名
     * @return 表
     */
    public static SqlTable of(SqlIdentifier name) {
        SqlTable t = new SqlTable();
        t.name = name;
        return t;
    }

    /**
     * @return 表名
     */
    public SqlIdentifier name() {
        return name;
    }

    /**
     * @param name 表名
     */
    public void setName(SqlIdentifier name) {
        this.name = name;
    }

    /**
     * @return USE/FORCE/IGNORE INDEX 原文，可空
     */
    public String indexHint() {
        return indexHint;
    }

    /**
     * @param indexHint 索引提示
     */
    public void setIndexHint(String indexHint) {
        this.indexHint = indexHint;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    protected void acceptChildren(SqlVisitor visitor) {
        child(visitor, name);
        children(visitor, columnAliases());
    }
}
