package com.alianga.jkit.sql.ast;

import com.alianga.jkit.sql.visitor.SqlVisitor;

import java.util.ArrayList;
import java.util.List;

/**
 * CASE 表达式。
 *
 * @author 郑明亮
 * @since 2.0.1
 */
public final class SqlCaseExpr extends SqlExpr {
    private SqlExpr value;
    private final List<SqlExpr> whenList = new ArrayList<SqlExpr>(2);
    private final List<SqlExpr> thenList = new ArrayList<SqlExpr>(2);
    private SqlExpr elseExpr;

    /**
     * @return 简单 CASE 的被比较值，可空
     */
    public SqlExpr value() {
        return value;
    }

    /**
     * @param value 被比较值
     */
    public void setValue(SqlExpr value) {
        this.value = value;
    }

    /**
     * @return WHEN 列表
     */
    public List<SqlExpr> whenList() {
        return whenList;
    }

    /**
     * @return THEN 列表
     */
    public List<SqlExpr> thenList() {
        return thenList;
    }

    /**
     * @param when WHEN
     * @param then THEN
     */
    public void addWhenThen(SqlExpr when, SqlExpr then) {
        whenList.add(when);
        thenList.add(then);
    }

    /**
     * @return ELSE
     */
    public SqlExpr elseExpr() {
        return elseExpr;
    }

    /**
     * @param elseExpr ELSE
     */
    public void setElseExpr(SqlExpr elseExpr) {
        this.elseExpr = elseExpr;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    protected void acceptChildren(SqlVisitor visitor) {
        child(visitor, value);
        children(visitor, whenList);
        children(visitor, thenList);
        child(visitor, elseExpr);
    }
}
