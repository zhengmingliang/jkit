package com.alianga.jkit.sql.ast;

import com.alianga.jkit.sql.visitor.SqlVisitor;

import java.util.ArrayList;
import java.util.List;

/**
 * CREATE / DROP / ALTER 等 DDL。抽取对象名；支持 {@code OR REPLACE}、VIEW / PROCEDURE 等；
 * CREATE TABLE 解析 ENGINE/CHARSET/COMMENT，ALTER 解析 ADD/DROP INDEX 与 RENAME TO，
 * 过程体等可留在 {@link #tail()}。
 *
 * @author 郑明亮
 * @since 2.1.0
 */
public final class SqlDdlStatement extends SqlStatement {
    private SqlStatementType statementType = SqlStatementType.CREATE;
    private String objectType;
    private boolean orReplace;
    private final List<SqlIdentifier> names = new ArrayList<SqlIdentifier>(1);
    private boolean ifExists;
    private boolean ifNotExists;
    private SqlStatement query;
    private final List<SqlIdentifier> columns = new ArrayList<SqlIdentifier>(4);
    private String engine;
    private String charset;
    private String collate;
    private String comment;
    private String alterAction;
    private SqlIdentifier indexName;
    private final List<SqlIdentifier> indexColumns = new ArrayList<SqlIdentifier>(4);
    private SqlIdentifier renameTo;
    private String tail;

    /**
     * {@inheritDoc}
     */
    @Override
    public SqlStatementType type() {
        return statementType;
    }

    /**
     * @param statementType CREATE/DROP/ALTER
     */
    public void setStatementType(SqlStatementType statementType) {
        this.statementType = statementType;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean isReadOnly() {
        return false;
    }

    /**
     * @return TABLE / VIEW / INDEX / DATABASE ...
     */
    public String objectType() {
        return objectType;
    }

    /**
     * @param objectType 对象类型
     */
    public void setObjectType(String objectType) {
        this.objectType = objectType;
    }

    /**
     * @return {@code CREATE OR REPLACE}
     * @since 2.1.0
     */
    public boolean orReplace() {
        return orReplace;
    }

    /**
     * @param orReplace OR REPLACE
     * @since 2.1.0
     */
    public void setOrReplace(boolean orReplace) {
        this.orReplace = orReplace;
    }

    /**
     * @return 对象名
     */
    public List<SqlIdentifier> names() {
        return names;
    }

    /**
     * @return IF EXISTS
     */
    public boolean ifExists() {
        return ifExists;
    }

    /**
     * @param ifExists IF EXISTS
     */
    public void setIfExists(boolean ifExists) {
        this.ifExists = ifExists;
    }

    /**
     * @return IF NOT EXISTS
     */
    public boolean ifNotExists() {
        return ifNotExists;
    }

    /**
     * @param ifNotExists IF NOT EXISTS
     */
    public void setIfNotExists(boolean ifNotExists) {
        this.ifNotExists = ifNotExists;
    }

    /**
     * @return CTAS / CREATE VIEW AS
     */
    public SqlStatement query() {
        return query;
    }

    /**
     * @param query AS 查询
     */
    public void setQuery(SqlStatement query) {
        this.query = query;
    }

    /**
     * @return CREATE TABLE 列名
     */
    public List<SqlIdentifier> columns() {
        return columns;
    }

    /**
     * @return CREATE TABLE {@code ENGINE}
     * @since 2.1.0
     */
    public String engine() {
        return engine;
    }

    /**
     * @param engine ENGINE 值
     * @since 2.1.0
     */
    public void setEngine(String engine) {
        this.engine = engine;
    }

    /**
     * @return CREATE TABLE {@code CHARSET} / {@code CHARACTER SET}
     * @since 2.1.0
     */
    public String charset() {
        return charset;
    }

    /**
     * @param charset 字符集
     * @since 2.1.0
     */
    public void setCharset(String charset) {
        this.charset = charset;
    }

    /**
     * @return COLLATE
     * @since 2.1.0
     */
    public String collate() {
        return collate;
    }

    /**
     * @param collate 排序规则
     * @since 2.1.0
     */
    public void setCollate(String collate) {
        this.collate = collate;
    }

    /**
     * @return 表 COMMENT 字面量（含引号）
     * @since 2.1.0
     */
    public String comment() {
        return comment;
    }

    /**
     * @param comment COMMENT 字面量
     * @since 2.1.0
     */
    public void setComment(String comment) {
        this.comment = comment;
    }

    /**
     * @return ALTER 动作，如 {@code ADD INDEX} / {@code DROP INDEX} / {@code RENAME TO}
     * @since 2.1.0
     */
    public String alterAction() {
        return alterAction;
    }

    /**
     * @param alterAction ALTER 动作
     * @since 2.1.0
     */
    public void setAlterAction(String alterAction) {
        this.alterAction = alterAction;
    }

    /**
     * @return ADD/DROP INDEX 的索引名
     * @since 2.1.0
     */
    public SqlIdentifier indexName() {
        return indexName;
    }

    /**
     * @param indexName 索引名
     * @since 2.1.0
     */
    public void setIndexName(SqlIdentifier indexName) {
        this.indexName = indexName;
    }

    /**
     * @return ADD INDEX 列清单
     * @since 2.1.0
     */
    public List<SqlIdentifier> indexColumns() {
        return indexColumns;
    }

    /**
     * @return {@code RENAME TO} 新表名
     * @since 2.1.0
     */
    public SqlIdentifier renameTo() {
        return renameTo;
    }

    /**
     * @param renameTo 新表名
     * @since 2.1.0
     */
    public void setRenameTo(SqlIdentifier renameTo) {
        this.renameTo = renameTo;
    }

    /**
     * @return 未建模的尾部原文
     */
    public String tail() {
        return tail;
    }

    /**
     * @param tail 尾部原文
     */
    public void setTail(String tail) {
        this.tail = tail;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    protected void acceptChildren(SqlVisitor visitor) {
        super.acceptChildren(visitor);
        children(visitor, names);
        children(visitor, columns);
        child(visitor, indexName);
        children(visitor, indexColumns);
        child(visitor, renameTo);
        child(visitor, query);
    }
}
