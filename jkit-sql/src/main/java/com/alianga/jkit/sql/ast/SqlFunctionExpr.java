package com.alianga.jkit.sql.ast;

import com.alianga.jkit.sql.visitor.SqlVisitor;

import java.util.ArrayList;
import java.util.List;

/**
 * 函数调用，含 {@code COUNT(*)}、聚合 DISTINCT、OVER。
 *
 * @author 郑明亮
 * @since 2.1.0
 */
public final class SqlFunctionExpr extends SqlExpr {
    private SqlIdentifier name;
    private List<SqlExpr> arguments;
    private boolean distinct;
    private SqlExpr over;
    private SqlExpr filter;
    private String aggOption;

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
     * @return 聚合后缀，如 WITHIN GROUP
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
     * {@inheritDoc}
     */
    @Override
    protected void acceptChildren(SqlVisitor visitor) {
        child(visitor, name);
        children(visitor, arguments);
        child(visitor, filter);
        child(visitor, over);
    }
}
