package com.alianga.jkit.sql.ast;

import com.alianga.jkit.sql.visitor.SqlVisitor;

import java.util.ArrayList;
import java.util.List;

/**
 * 物理表或视图。
 *
 * @author 郑明亮
 * @since 2.0.1
 */
public final class SqlTable extends SqlTableSource {
    private SqlIdentifier name;
    private String indexHint;
    private String optimizerHint;
    private String sampleClause;
    /** Oracle {@code AS OF TIMESTAMP|SCN …} / SQL Server {@code FOR SYSTEM_TIME …} 原文。 */
    private String temporalClause;
    /** Oracle {@code MATCH_RECOGNIZE (...)} 括号内原文（不含关键字与外层括号）。 */
    private String matchRecognize;
    private final List<SqlIdentifier> partitions = new ArrayList<SqlIdentifier>(2);

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
     * @return 表级优化器提示原文（slash-star-plus），可空
     * @since 2.0.1
     */
    public String optimizerHint() {
        return optimizerHint;
    }

    /**
     * @param optimizerHint 表级优化器提示
     * @since 2.0.1
     */
    public void setOptimizerHint(String optimizerHint) {
        this.optimizerHint = optimizerHint;
    }

    /**
     * @return {@code TABLESAMPLE …} / Oracle {@code SAMPLE(…)} 原文，可空
     * @since 2.0.1
     */
    public String sampleClause() {
        return sampleClause;
    }

    /**
     * @param sampleClause 采样子句原文
     * @since 2.0.1
     */
    public void setSampleClause(String sampleClause) {
        this.sampleClause = sampleClause;
    }

    /**
     * @return 闪回 / 时态表查询子句原文，可空
     * @since 2.0.1
     */
    public String temporalClause() {
        return temporalClause;
    }

    /**
     * @param temporalClause {@code AS OF …} / {@code FOR SYSTEM_TIME …}
     * @since 2.0.1
     */
    public void setTemporalClause(String temporalClause) {
        this.temporalClause = temporalClause;
    }

    /**
     * @return {@code MATCH_RECOGNIZE} 括号内原文，可空
     * @since 2.0.1
     */
    public String matchRecognize() {
        return matchRecognize;
    }

    /**
     * @param matchRecognize 括号内原文
     * @since 2.0.1
     */
    public void setMatchRecognize(String matchRecognize) {
        this.matchRecognize = matchRecognize;
    }

    /**
     * @return MySQL {@code PARTITION (p0, p1)} 分区名列表，可空
     * @since 2.0.1
     */
    public List<SqlIdentifier> partitions() {
        return partitions;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    protected void acceptChildren(SqlVisitor visitor) {
        child(visitor, name);
        children(visitor, partitions);
        children(visitor, columnAliases());
    }
}
