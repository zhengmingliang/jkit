package com.alianga.jkit.sql.ast;

import com.alianga.jkit.sql.visitor.SqlVisitor;

import java.util.ArrayList;
import java.util.List;

/**
 * USE / CALL / TRUNCATE / GRANT / REVOKE 等相对扁平的语句；{@link SqlStatementType#OTHER} 用于过程块与维护语句。
 * （SET → {@link SqlSetStatement}；EXPLAIN → 见对应结构化节点；SHOW → {@link SqlShowStatement}。）
 *
 * @author 郑明亮
 * @since 2.0.1
 */
public final class SqlSimpleStatement extends SqlStatement {
    private SqlStatementType statementType = SqlStatementType.OTHER;
    private SqlStatement inner;
    private SqlIdentifier name;
    private SqlExpr value;
    private String text;
    private final List<SqlExpr> arguments = new ArrayList<SqlExpr>(2);
    private boolean withArguments;
    private String parseError;
    private String privileges;

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
     * @return CALL 实参列表
     * @since 2.0.1
     */
    public List<SqlExpr> arguments() {
        return arguments;
    }

    /**
     * @return CALL 是否带括号（区分 {@code CALL p} 与 {@code CALL p()}）
     * @since 2.0.1
     */
    public boolean withArguments() {
        return withArguments;
    }

    /**
     * @param withArguments 是否带括号
     * @since 2.0.1
     */
    public void setWithArguments(boolean withArguments) {
        this.withArguments = withArguments;
    }

    /**
     * 容错 {@code parseAll(..., true)} 时单条失败的错误信息；成功解析时为 null。
     *
     * @return 错误信息，无则 null
     * @since 2.0.1
     */
    public String parseError() {
        return parseError;
    }

    /**
     * @param parseError 解析错误信息
     * @since 2.0.1
     */
    public void setParseError(String parseError) {
        this.parseError = parseError;
    }

    /**
     * @return 是否为容错解析留下的失败占位
     * @since 2.0.1
     */
    public boolean hasParseError() {
        return parseError != null;
    }

    /**
     * @return GRANT/REVOKE 权限列表原文，如 {@code SELECT, INSERT} / {@code ALL PRIVILEGES}
     * @since 2.0.1
     */
    public String privileges() {
        return privileges;
    }

    /**
     * @param privileges 权限原文
     * @since 2.0.1
     */
    public void setPrivileges(String privileges) {
        this.privileges = privileges;
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
        children(visitor, arguments);
    }
}
