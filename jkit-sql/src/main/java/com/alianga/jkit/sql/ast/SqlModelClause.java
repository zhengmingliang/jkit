package com.alianga.jkit.sql.ast;

import com.alianga.jkit.sql.visitor.SqlVisitor;

import java.util.ArrayList;
import java.util.List;

/**
 * Oracle {@code MODEL …} 结构化节点。
 *
 * <p>PARTITION / DIMENSION / MEASURES / RULES 尽量结构化；前缀选项与未识别片段进
 * {@link #options()} / {@link #tail()}；全文（含 MODEL）进 {@link #raw()}。</p>
 *
 * @author 郑明亮
 * @since 2.0.1
 */
public final class SqlModelClause extends SqlNode {
    /** {@code RETURN UPDATED ROWS} / {@code IGNORE NAV} 等前缀选项原文。 */
    private String options;
    private final List<SqlExpr> partitionBy = new ArrayList<SqlExpr>(2);
    private final List<SqlExpr> dimensionBy = new ArrayList<SqlExpr>(2);
    private final List<SqlExpr> measures = new ArrayList<SqlExpr>(4);
    /** {@code RULES (...)} 括号内原文（不含 RULES 与外层括号）。 */
    private String rules;
    private String rulesModifiers;
    private String tail;
    /** 含 {@code MODEL} 关键字的全文。 */
    private String raw;

    /**
     * @return 前缀选项原文，可空
     */
    public String options() {
        return options;
    }

    /**
     * @param options 前缀选项
     */
    public void setOptions(String options) {
        this.options = options;
    }

    /**
     * @return PARTITION BY
     */
    public List<SqlExpr> partitionBy() {
        return partitionBy;
    }

    /**
     * @return DIMENSION BY
     */
    public List<SqlExpr> dimensionBy() {
        return dimensionBy;
    }

    /**
     * @return MEASURES 表达式列表
     */
    public List<SqlExpr> measures() {
        return measures;
    }

    /**
     * @return RULES 括号内原文，可空
     */
    public String rules() {
        return rules;
    }

    /**
     * @param rules RULES 内容
     */
    public void setRules(String rules) {
        this.rules = rules;
    }

    /**
     * @return {@code RULES UPSERT} / {@code RULES UPDATE} 等修饰，可空
     */
    public String rulesModifiers() {
        return rulesModifiers;
    }

    /**
     * @param rulesModifiers RULES 修饰
     */
    public void setRulesModifiers(String rulesModifiers) {
        this.rulesModifiers = rulesModifiers;
    }

    /**
     * @return 未识别尾部，可空
     */
    public String tail() {
        return tail;
    }

    /**
     * @param tail 尾部
     */
    public void setTail(String tail) {
        this.tail = tail;
    }

    /**
     * @return 含 MODEL 的全文
     */
    public String raw() {
        return raw;
    }

    /**
     * @param raw 全文
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
        children(visitor, dimensionBy);
        children(visitor, measures);
    }
}
