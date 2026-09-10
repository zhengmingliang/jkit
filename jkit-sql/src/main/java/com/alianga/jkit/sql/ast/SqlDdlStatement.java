package com.alianga.jkit.sql.ast;

import com.alianga.jkit.sql.visitor.SqlVisitor;

import java.util.ArrayList;
import java.util.List;

/**
 * CREATE / DROP / ALTER 等 DDL。抽取对象名；支持 {@code OR REPLACE}、VIEW / PROCEDURE 等；
 * CREATE TABLE 解析列定义原文、ENGINE/CHARSET/COMMENT 与表级 FOREIGN KEY 引用表；
 * ALTER 解析 ADD/DROP INDEX、RENAME TO、CHANGE/MODIFY 列定义、ADD CONSTRAINT；
 * 过程体等可留在 {@link #tail()}；FUNCTION {@code RETURNS}、TRIGGER 时机/事件/表/FOR EACH/FOLLOWS、
 * EVENT {@code ON SCHEDULE}/STARTS/ENDS/ENABLE/COMMENT、TRIGGER {@code UPDATE OF} 可结构化。
 *
 * @author 郑明亮
 * @since 2.0.1
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
    private String columnDefinition;
    /** CREATE TABLE 括号内各列/表约束的原文（含类型），按逗号分段。 */
    private final List<String> columnDefinitions = new ArrayList<String>(4);
    private SqlIdentifier constraintName;
    private String constraintType;
    private final List<SqlIdentifier> referencedTables = new ArrayList<SqlIdentifier>(1);
    private String tail;
    /** PROCEDURE/FUNCTION 参数列表。 */
    private final List<SqlRoutineParam> parameters = new ArrayList<SqlRoutineParam>(4);
    /** BEGIN…END 内语句列表（尽力解析）。 */
    private final List<SqlStatement> bodyStatements = new ArrayList<SqlStatement>(4);
    /** 过程体原文（含 BEGIN/END 或单语句），可空。 */
    private String bodyRaw;
    /** FUNCTION {@code RETURNS} 类型原文，可空。 */
    private String returnsType;
    /** TRIGGER {@code BEFORE}/{@code AFTER}，可空。 */
    private String triggerTiming;
    /** TRIGGER 事件 {@code INSERT}/{@code UPDATE}/{@code DELETE}，可空。 */
    private String triggerEvent;
    /** TRIGGER {@code ON} 表名，可空。 */
    private SqlIdentifier triggerTable;
    /** TRIGGER {@code FOR EACH ROW|STATEMENT}，可空。 */
    private String triggerForEach;
    /** TRIGGER {@code FOLLOWS}/{@code PRECEDES}，可空。 */
    private String triggerOrder;
    /** TRIGGER FOLLOWS/PRECEDES 目标触发器名，可空。 */
    private SqlIdentifier triggerOther;
    /** EVENT {@code ON SCHEDULE} 种类 {@code AT}/{@code EVERY}，可空。 */
    private String eventScheduleKind;
    /** EVENT 调度表达式/原文（AT/EVERY 之后至 DO 之前），可空。 */
    private String eventScheduleRaw;
    /** TRIGGER {@code UPDATE OF} 列名列表。 */
    private final List<SqlIdentifier> triggerUpdateColumns = new ArrayList<SqlIdentifier>(2);
    /** EVENT {@code STARTS} 原文，可空。 */
    private String eventStarts;
    /** EVENT {@code ENDS} 原文，可空。 */
    private String eventEnds;
    /** EVENT {@code ENABLE}/{@code DISABLE}；{@code true}=ENABLE，{@code false}=DISABLE，未写则为 {@code null}。 */
    private Boolean eventEnabled;
    /** EVENT {@code COMMENT} 原文（含引号），可空。 */
    private String eventComment;
    /** EVENT {@code ON COMPLETION}：{@code PRESERVE} / {@code NOT PRESERVE}，可空。 */
    private String eventOnCompletion;
    /** EVENT {@code DISABLE ON SLAVE}（相对普通 DISABLE）。 */
    private boolean eventDisableOnSlave;

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
     * @since 2.0.1
     */
    public boolean orReplace() {
        return orReplace;
    }

    /**
     * @param orReplace OR REPLACE
     * @since 2.0.1
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
     * @since 2.0.1
     */
    public String engine() {
        return engine;
    }

    /**
     * @param engine ENGINE 值
     * @since 2.0.1
     */
    public void setEngine(String engine) {
        this.engine = engine;
    }

    /**
     * @return CREATE TABLE {@code CHARSET} / {@code CHARACTER SET}
     * @since 2.0.1
     */
    public String charset() {
        return charset;
    }

    /**
     * @param charset 字符集
     * @since 2.0.1
     */
    public void setCharset(String charset) {
        this.charset = charset;
    }

    /**
     * @return COLLATE
     * @since 2.0.1
     */
    public String collate() {
        return collate;
    }

    /**
     * @param collate 排序规则
     * @since 2.0.1
     */
    public void setCollate(String collate) {
        this.collate = collate;
    }

    /**
     * @return 表 COMMENT 字面量（含引号）
     * @since 2.0.1
     */
    public String comment() {
        return comment;
    }

    /**
     * @param comment COMMENT 字面量
     * @since 2.0.1
     */
    public void setComment(String comment) {
        this.comment = comment;
    }

    /**
     * @return ALTER 动作，如 {@code ADD INDEX} / {@code DROP INDEX} / {@code RENAME TO}
     * @since 2.0.1
     */
    public String alterAction() {
        return alterAction;
    }

    /**
     * @param alterAction ALTER 动作
     * @since 2.0.1
     */
    public void setAlterAction(String alterAction) {
        this.alterAction = alterAction;
    }

    /**
     * @return ADD/DROP INDEX 的索引名
     * @since 2.0.1
     */
    public SqlIdentifier indexName() {
        return indexName;
    }

    /**
     * @param indexName 索引名
     * @since 2.0.1
     */
    public void setIndexName(SqlIdentifier indexName) {
        this.indexName = indexName;
    }

    /**
     * @return ADD INDEX 列清单
     * @since 2.0.1
     */
    public List<SqlIdentifier> indexColumns() {
        return indexColumns;
    }

    /**
     * @return {@code RENAME TO} 新表名
     * @since 2.0.1
     */
    public SqlIdentifier renameTo() {
        return renameTo;
    }

    /**
     * @param renameTo 新表名
     * @since 2.0.1
     */
    public void setRenameTo(SqlIdentifier renameTo) {
        this.renameTo = renameTo;
    }

    /**
     * @return CHANGE/MODIFY/ADD COLUMN 的类型与列属性原文（不含列名）
     * @since 2.0.1
     */
    public String columnDefinition() {
        return columnDefinition;
    }

    /**
     * @param columnDefinition 列定义原文
     * @since 2.0.1
     */
    public void setColumnDefinition(String columnDefinition) {
        this.columnDefinition = columnDefinition;
    }

    /**
     * @return CREATE TABLE 列定义/表约束原文列表（含类型与约束关键字）
     * @since 2.0.1
     */
    public List<String> columnDefinitions() {
        return columnDefinitions;
    }

    /**
     * @return ADD CONSTRAINT 约束名，可空
     * @since 2.0.1
     */
    public SqlIdentifier constraintName() {
        return constraintName;
    }

    /**
     * @param constraintName 约束名
     * @since 2.0.1
     */
    public void setConstraintName(SqlIdentifier constraintName) {
        this.constraintName = constraintName;
    }

    /**
     * @return 约束类型，如 {@code FOREIGN KEY} / {@code PRIMARY KEY} / {@code UNIQUE} / {@code CHECK}
     * @since 2.0.1
     */
    public String constraintType() {
        return constraintType;
    }

    /**
     * @param constraintType 约束类型
     * @since 2.0.1
     */
    public void setConstraintType(String constraintType) {
        this.constraintType = constraintType;
    }

    /**
     * @return 表级 / ALTER FOREIGN KEY 引用的外表名
     * @since 2.0.1
     */
    public List<SqlIdentifier> referencedTables() {
        return referencedTables;
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
     * @return PROCEDURE/FUNCTION 参数列表
     * @since 2.0.1
     */
    public List<SqlRoutineParam> parameters() {
        return parameters;
    }

    /**
     * @return BEGIN…END 内语句列表（可能为空；解析失败片段可不在此列）
     * @since 2.0.1
     */
    public List<SqlStatement> bodyStatements() {
        return bodyStatements;
    }

    /**
     * @return 过程体原文，可空
     * @since 2.0.1
     */
    public String bodyRaw() {
        return bodyRaw;
    }

    /**
     * @param bodyRaw 过程体原文
     * @since 2.0.1
     */
    public void setBodyRaw(String bodyRaw) {
        this.bodyRaw = bodyRaw;
    }

    /**
     * @return FUNCTION RETURNS 类型原文，可空
     * @since 2.0.1
     */
    public String returnsType() {
        return returnsType;
    }

    /**
     * @param returnsType RETURNS 类型
     * @since 2.0.1
     */
    public void setReturnsType(String returnsType) {
        this.returnsType = returnsType;
    }

    /**
     * @return TRIGGER BEFORE/AFTER，可空
     * @since 2.0.1
     */
    public String triggerTiming() {
        return triggerTiming;
    }

    /**
     * @param triggerTiming BEFORE/AFTER
     * @since 2.0.1
     */
    public void setTriggerTiming(String triggerTiming) {
        this.triggerTiming = triggerTiming;
    }

    /**
     * @return TRIGGER INSERT/UPDATE/DELETE，可空
     * @since 2.0.1
     */
    public String triggerEvent() {
        return triggerEvent;
    }

    /**
     * @param triggerEvent 事件
     * @since 2.0.1
     */
    public void setTriggerEvent(String triggerEvent) {
        this.triggerEvent = triggerEvent;
    }

    /**
     * @return TRIGGER ON 表，可空
     * @since 2.0.1
     */
    public SqlIdentifier triggerTable() {
        return triggerTable;
    }

    /**
     * @param triggerTable 表名
     * @since 2.0.1
     */
    public void setTriggerTable(SqlIdentifier triggerTable) {
        this.triggerTable = triggerTable;
    }

    /**
     * @return FOR EACH ROW/STATEMENT，可空
     * @since 2.0.1
     */
    public String triggerForEach() {
        return triggerForEach;
    }

    /**
     * @param triggerForEach ROW/STATEMENT
     * @since 2.0.1
     */
    public void setTriggerForEach(String triggerForEach) {
        this.triggerForEach = triggerForEach;
    }

    /**
     * @return FOLLOWS/PRECEDES，可空
     * @since 2.0.1
     */
    public String triggerOrder() {
        return triggerOrder;
    }

    /**
     * @param triggerOrder FOLLOWS/PRECEDES
     * @since 2.0.1
     */
    public void setTriggerOrder(String triggerOrder) {
        this.triggerOrder = triggerOrder;
    }

    /**
     * @return FOLLOWS/PRECEDES 目标触发器，可空
     * @since 2.0.1
     */
    public SqlIdentifier triggerOther() {
        return triggerOther;
    }

    /**
     * @param triggerOther 其它触发器名
     * @since 2.0.1
     */
    public void setTriggerOther(SqlIdentifier triggerOther) {
        this.triggerOther = triggerOther;
    }

    /**
     * @return EVENT 调度种类 AT/EVERY，可空
     * @since 2.0.1
     */
    public String eventScheduleKind() {
        return eventScheduleKind;
    }

    /**
     * @param eventScheduleKind AT/EVERY
     * @since 2.0.1
     */
    public void setEventScheduleKind(String eventScheduleKind) {
        this.eventScheduleKind = eventScheduleKind;
    }

    /**
     * @return EVENT 调度原文（AT/EVERY 之后），可空
     * @since 2.0.1
     */
    public String eventScheduleRaw() {
        return eventScheduleRaw;
    }

    /**
     * @param eventScheduleRaw 调度原文
     * @since 2.0.1
     */
    public void setEventScheduleRaw(String eventScheduleRaw) {
        this.eventScheduleRaw = eventScheduleRaw;
    }

    /**
     * @return TRIGGER UPDATE OF 列名
     * @since 2.0.1
     */
    public List<SqlIdentifier> triggerUpdateColumns() {
        return triggerUpdateColumns;
    }

    /**
     * @return EVENT STARTS 原文，可空
     * @since 2.0.1
     */
    public String eventStarts() {
        return eventStarts;
    }

    /**
     * @param eventStarts STARTS 原文
     * @since 2.0.1
     */
    public void setEventStarts(String eventStarts) {
        this.eventStarts = eventStarts;
    }

    /**
     * @return EVENT ENDS 原文，可空
     * @since 2.0.1
     */
    public String eventEnds() {
        return eventEnds;
    }

    /**
     * @param eventEnds ENDS 原文
     * @since 2.0.1
     */
    public void setEventEnds(String eventEnds) {
        this.eventEnds = eventEnds;
    }

    /**
     * @return {@code true}=ENABLE，{@code false}=DISABLE，未指定为 {@code null}
     * @since 2.0.1
     */
    public Boolean eventEnabled() {
        return eventEnabled;
    }

    /**
     * @param eventEnabled ENABLE/DISABLE
     * @since 2.0.1
     */
    public void setEventEnabled(Boolean eventEnabled) {
        this.eventEnabled = eventEnabled;
    }

    /**
     * @return EVENT COMMENT 原文，可空
     * @since 2.0.1
     */
    public String eventComment() {
        return eventComment;
    }

    /**
     * @param eventComment COMMENT 原文
     * @since 2.0.1
     */
    public void setEventComment(String eventComment) {
        this.eventComment = eventComment;
    }

    /**
     * @return {@code PRESERVE} / {@code NOT PRESERVE}，未指定为 {@code null}
     * @since 2.0.1
     */
    public String eventOnCompletion() {
        return eventOnCompletion;
    }

    /**
     * @param eventOnCompletion ON COMPLETION 值
     * @since 2.0.1
     */
    public void setEventOnCompletion(String eventOnCompletion) {
        this.eventOnCompletion = eventOnCompletion;
    }

    /**
     * @return 是否 {@code DISABLE ON SLAVE}
     * @since 2.0.1
     */
    public boolean eventDisableOnSlave() {
        return eventDisableOnSlave;
    }

    /**
     * @param eventDisableOnSlave DISABLE ON SLAVE
     * @since 2.0.1
     */
    public void setEventDisableOnSlave(boolean eventDisableOnSlave) {
        this.eventDisableOnSlave = eventDisableOnSlave;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    protected void acceptChildren(SqlVisitor visitor) {
        super.acceptChildren(visitor);
        children(visitor, names);
        children(visitor, columns);
        children(visitor, parameters);
        children(visitor, bodyStatements);
        child(visitor, triggerTable);
        children(visitor, triggerUpdateColumns);
        child(visitor, triggerOther);
        child(visitor, indexName);
        children(visitor, indexColumns);
        child(visitor, renameTo);
        child(visitor, constraintName);
        children(visitor, referencedTables);
        child(visitor, query);
    }
}
