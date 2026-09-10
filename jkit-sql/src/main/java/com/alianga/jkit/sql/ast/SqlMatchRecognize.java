package com.alianga.jkit.sql.ast;

import com.alianga.jkit.sql.visitor.SqlVisitor;

import java.util.ArrayList;
import java.util.List;

/**
 * Oracle / 标准 {@code MATCH_RECOGNIZE (...)} 结构化节点。
 *
 * <p>常见子句尽量结构化（含 {@code PATTERN} <b>原文字符串</b>（完整 PATTERN DSL 不展开）、
 * {@code DEFINE}、{@code SUBSET}、{@code ROWS PER MATCH}/{@code AFTER MATCH}/{@code WITHIN}）；
 * 未识别片段进 {@link #optionsRaw()} / {@link #raw()}。</p>
 *
 * @author 郑明亮
 * @since 2.0.1
 */
public final class SqlMatchRecognize extends SqlNode {
    private final List<SqlExpr> partitionBy = new ArrayList<SqlExpr>(2);
    private final List<SqlOrderByItem> orderBy = new ArrayList<SqlOrderByItem>(2);
    private final List<SqlNamedExpr> measures = new ArrayList<SqlNamedExpr>(4);
    private String rowsPerMatch;
    private String afterMatch;
    /** {@code WITHIN …}（常跟 PATTERN），可空。 */
    private String within;
    private String pattern;
    private final List<SqlNamedExpr> define = new ArrayList<SqlNamedExpr>(4);
    private final List<SqlSubset> subsets = new ArrayList<SqlSubset>(2);
    private String optionsRaw;
    /** 括号内全文（不含外层括号），便于往返。 */
    private String raw;

    /**
     * @return PARTITION BY 表达式
     */
    public List<SqlExpr> partitionBy() {
        return partitionBy;
    }

    /**
     * @return ORDER BY
     */
    public List<SqlOrderByItem> orderBy() {
        return orderBy;
    }

    /**
     * @return MEASURES 列表
     */
    public List<SqlNamedExpr> measures() {
        return measures;
    }

    /**
     * @return {@code ONE ROW PER MATCH} / {@code ALL ROWS PER MATCH} 等，可空
     */
    public String rowsPerMatch() {
        return rowsPerMatch;
    }

    /**
     * @param rowsPerMatch 行模式子句
     */
    public void setRowsPerMatch(String rowsPerMatch) {
        this.rowsPerMatch = rowsPerMatch;
    }

    /**
     * @return {@code AFTER MATCH …} 原文，可空
     */
    public String afterMatch() {
        return afterMatch;
    }

    /**
     * @param afterMatch AFTER MATCH 子句
     */
    public void setAfterMatch(String afterMatch) {
        this.afterMatch = afterMatch;
    }

    /**
     * @return {@code WITHIN …} 原文，可空
     * @since 2.0.1
     */
    public String within() {
        return within;
    }

    /**
     * @param within WITHIN 子句
     * @since 2.0.1
     */
    public void setWithin(String within) {
        this.within = within;
    }

    /**
     * @return {@code PATTERN (...)} 括号内原文，可空；保持字符串，不解析正则/量词 DSL
     */
    public String pattern() {
        return pattern;
    }

    /**
     * @param pattern PATTERN 内容
     */
    public void setPattern(String pattern) {
        this.pattern = pattern;
    }

    /**
     * @return DEFINE 列表
     */
    public List<SqlNamedExpr> define() {
        return define;
    }

    /**
     * @return SUBSET 列表
     * @since 2.0.1
     */
    public List<SqlSubset> subsets() {
        return subsets;
    }

    /**
     * @return 未结构化尾部 / 其它选项原文，可空
     */
    public String optionsRaw() {
        return optionsRaw;
    }

    /**
     * @param optionsRaw 其它选项
     */
    public void setOptionsRaw(String optionsRaw) {
        this.optionsRaw = optionsRaw;
    }

    /**
     * @return 括号内全文
     */
    public String raw() {
        return raw;
    }

    /**
     * @param raw 括号内全文
     */
    public void setRaw(String raw) {
        this.raw = raw;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    protected void acceptChildren(SqlVisitor visitor) {
        children(visitor, partitionBy);
        children(visitor, orderBy);
        children(visitor, measures);
        children(visitor, define);
        children(visitor, subsets);
    }
}
