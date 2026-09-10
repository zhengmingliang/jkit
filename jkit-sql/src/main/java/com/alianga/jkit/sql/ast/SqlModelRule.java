package com.alianga.jkit.sql.ast;

import com.alianga.jkit.sql.visitor.SqlVisitor;

import java.util.ArrayList;
import java.util.List;

/**
 * Oracle {@code MODEL … RULES (…)} 中的单条规则：{@code [UPSERT|UPDATE] cell[…] = expr}。
 *
 * <p>左侧单元格引用以原文保留；多维下标可拆进 {@link #cellDims()}；若均为简单标识符则同时填 {@link #cellDimExprs()}；右侧尽量结构化为表达式；整条失败时仅 {@link #raw()}。</p>
 *
 * @author 郑明亮
 * @since 2.0.1
 */
public final class SqlModelRule extends SqlNode {
    /** {@code UPSERT} / {@code UPSERT ALL} / {@code UPDATE} 等规则级修饰，可空。 */
    private String modifiers;
    /** 左侧单元格原文，如 {@code y2024[ANY]} / {@code sales[product='X',year=2024]}。 */
    private String cell;
    /** 多维下标原文列表（括号内按顶层逗号拆分），可空。 */
    private final List<String> cellDims = new ArrayList<String>(2);
    /** 当各维均为简单标识符时填充的表达式列表（与 {@link #cellDims()} 对齐），否则为空。 */
    private final List<SqlExpr> cellDimExprs = new ArrayList<SqlExpr>(2);
    private SqlExpr value;
    /** 未能拆分时的整条原文。 */
    private String raw;

    /**
     * @return 规则级修饰，可空
     */
    public String modifiers() {
        return modifiers;
    }

    /**
     * @param modifiers 修饰
     */
    public void setModifiers(String modifiers) {
        this.modifiers = modifiers;
    }

    /**
     * @return 左侧单元格原文，可空
     */
    public String cell() {
        return cell;
    }

    /**
     * @param cell 单元格原文
     */
    public void setCell(String cell) {
        this.cell = cell;
    }

    /**
     * @return 多维下标原文列表
     * @since 2.0.1
     */
    public List<String> cellDims() {
        return cellDims;
    }

    /**
     * @return 简单标识符维的表达式列表（非简单维时为空）
     * @since 2.0.1
     */
    public List<SqlExpr> cellDimExprs() {
        return cellDimExprs;
    }

    /**
     * @return 右侧表达式
     */
    public SqlExpr value() {
        return value;
    }

    /**
     * @param value 右侧表达式
     */
    public void setValue(SqlExpr value) {
        this.value = value;
    }

    /**
     * @return 整条原文兜底，可空
     */
    public String raw() {
        return raw;
    }

    /**
     * @param raw 原文
     */
    public void setRaw(String raw) {
        this.raw = raw;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    protected void acceptChildren(SqlVisitor visitor) {
        children(visitor, cellDimExprs);
        child(visitor, value);
    }
}
