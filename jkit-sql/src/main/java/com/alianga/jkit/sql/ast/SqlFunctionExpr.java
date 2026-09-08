package com.alianga.jkit.sql.ast;

import com.alianga.jkit.sql.visitor.SqlVisitor;

import java.util.ArrayList;
import java.util.List;

/**
 * 函数调用，含 {@code COUNT(*)}、聚合 DISTINCT、OVER、GROUP_CONCAT ORDER BY/SEPARATOR、MATCH AGAINST。
 *
 * @author 郑明亮
 * @since 2.0.1
 */
public final class SqlFunctionExpr extends SqlExpr {
    private SqlIdentifier name;
    private List<SqlExpr> arguments;
    private boolean distinct;
    private SqlExpr over;
    private SqlExpr filter;
    private String aggOption;
    private List<SqlOrderByItem> orderBy;
    private boolean withinGroup;
    private SqlExpr separator;
    private boolean usingCharset;
    private SqlExpr against;
    private String againstModifier;

    /**
     * @return 函数名
     */
    public SqlIdentifier name() {
        return name;
    }

    /**
     * @param name 函数名
     */
    public void setName(SqlIdentifier name) {
        this.name = name;
    }

    /**
     * @return 参数
     */
    public List<SqlExpr> arguments() {
        if (arguments == null) {
            arguments = new ArrayList<SqlExpr>(2);
        }
        return arguments;
    }

    /**
     * @param arguments 参数
     */
    public void setArguments(List<SqlExpr> arguments) {
        this.arguments = arguments;
    }

    /**
     * @param arg 追加参数
     */
    public void addArgument(SqlExpr arg) {
        arguments().add(arg);
    }

    /**
     * @return COUNT(DISTINCT ...)
     */
    public boolean distinct() {
        return distinct;
    }

    /**
     * @param distinct DISTINCT
     */
    public void setDistinct(boolean distinct) {
        this.distinct = distinct;
    }

    /**
     * @return OVER 子句表达式，可空
     */
    public SqlExpr over() {
        return over;
    }

    /**
     * @param over OVER
     */
    public void setOver(SqlExpr over) {
        this.over = over;
    }

    /**
     * @return FILTER (WHERE ...) 谓词
     */
    public SqlExpr filter() {
        return filter;
    }

    /**
     * @param filter FILTER 谓词
     */
    public void setFilter(SqlExpr filter) {
        this.filter = filter;
    }

    /**
     * @return 聚合后缀，如未结构化的 WITHIN GROUP 原文
     */
    public String aggOption() {
        return aggOption;
    }

    /**
     * @param aggOption 聚合后缀
     */
    public void setAggOption(String aggOption) {
        this.aggOption = aggOption;
    }

    /**
     * @return 函数内或 WITHIN GROUP 的 ORDER BY
     */
    public List<SqlOrderByItem> orderBy() {
        if (orderBy == null) {
            orderBy = new ArrayList<SqlOrderByItem>(2);
        }
        return orderBy;
    }

    /**
     * @param orderBy ORDER BY 项
     */
    public void setOrderBy(List<SqlOrderByItem> orderBy) {
        this.orderBy = orderBy;
    }

    /**
     * @return 是否 WITHIN GROUP (ORDER BY ...)
     */
    public boolean withinGroup() {
        return withinGroup;
    }

    /**
     * @param withinGroup WITHIN GROUP
     */
    public void setWithinGroup(boolean withinGroup) {
        this.withinGroup = withinGroup;
    }

    /**
     * @return GROUP_CONCAT SEPARATOR 表达式
     */
    public SqlExpr separator() {
        return separator;
    }

    /**
     * @param separator SEPARATOR
     */
    public void setSeparator(SqlExpr separator) {
        this.separator = separator;
    }

    /**
     * @return CONVERT(expr USING charset)
     */
    public boolean usingCharset() {
        return usingCharset;
    }

    /**
     * @param usingCharset USING charset 形态
     */
    public void setUsingCharset(boolean usingCharset) {
        this.usingCharset = usingCharset;
    }

    /**
     * @return MATCH (...) AGAINST 表达式
     */
    public SqlExpr against() {
        return against;
    }

    /**
     * @param against AGAINST 内容
     */
    public void setAgainst(SqlExpr against) {
        this.against = against;
    }

    /**
     * @return AGAINST 修饰，如 IN BOOLEAN MODE
     */
    public String againstModifier() {
        return againstModifier;
    }

    /**
     * @param againstModifier AGAINST 修饰
     */
    public void setAgainstModifier(String againstModifier) {
        this.againstModifier = againstModifier;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    protected void acceptChildren(SqlVisitor visitor) {
        child(visitor, name);
        children(visitor, arguments);
        children(visitor, orderBy);
        child(visitor, separator);
        child(visitor, against);
        child(visitor, filter);
        child(visitor, over);
    }
}
