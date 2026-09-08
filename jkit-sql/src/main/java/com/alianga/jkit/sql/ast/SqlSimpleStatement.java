package com.alianga.jkit.sql.ast;

import com.alianga.jkit.sql.visitor.SqlVisitor;

/**
 * EXPLAIN / SET / USE / SHOW / CALL / TRUNCATE / GRANT 等相对扁平的语句。
 *
 * @author 郑明亮
 * @since 2.1.0
 */
public final class SqlSimpleStatement extends SqlStatement {
    private SqlStatementType statementType = SqlStatementType.OTHER;
    private SqlStatement inner;
    private SqlIdentifier name;
    private SqlExpr value;
    private String text;

    /**
     * {@inheritDoc}
     */
    @Override
    public SqlStatementType type() {
        return statementType;
    }

    /**
     * @param statementType 种类
     */
    public void setStatementType(SqlStatementType statementType) {
        this.statementType = statementType;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean isReadOnly() {
        return statementType == SqlStatementType.SHOW
                || statementType == SqlStatementType.EXPLAIN
                || statementType == SqlStatementType.USE;
    }

    /**
     * @return EXPLAIN 包裹的语句
     */
    public SqlStatement inner() {
        return inner;
    }

    /**
     * @param inner 内层语句
     */
    public void setInner(SqlStatement inner) {
        this.inner = inner;
    }

    /**
     * @return 对象名（USE db、TRUNCATE t）
     */
    public SqlIdentifier name() {
        return name;
    }

    /**
     * @param name 对象名
     */
    public void setName(SqlIdentifier name) {
        this.name = name;
    }

    /**
     * @return SET 的值
     */
    public SqlExpr value() {
        return value;
    }

    /**
     * @param value 值
     */
    public void setValue(SqlExpr value) {
        this.value = value;
    }

    /**
     * @return 残余原文，供 SHOW / CALL 等
     */
    public String text() {
        return text;
    }

    /**
     * @param text 残余原文
     */
    public void setText(String text) {
        this.text = text;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    protected void acceptChildren(SqlVisitor visitor) {
        super.acceptChildren(visitor);
        child(visitor, inner);
        child(visitor, name);
        child(visitor, value);
    }
}
