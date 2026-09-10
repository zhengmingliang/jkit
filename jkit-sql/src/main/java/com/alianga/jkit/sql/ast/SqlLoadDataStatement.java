package com.alianga.jkit.sql.ast;

import com.alianga.jkit.sql.visitor.SqlVisitor;

import java.util.ArrayList;
import java.util.List;

/**
 * MySQL {@code LOAD DATA [LOCAL] INFILE … INTO TABLE …}。
 *
 * <p>抽文件、目标表、可选列清单；{@code FIELDS}/{@code LINES}/{@code IGNORE}/其余尾部
 * 以原文段保留（完整文法过重）。语句种类仍为 {@link SqlStatementType#OTHER}。</p>
 *
 * @author 郑明亮
 * @since 2.0.1
 */
public final class SqlLoadDataStatement extends SqlStatement {
    private boolean local;
    /** LOW_PRIORITY / CONCURRENT，可空。 */
    private String priority;
    /** REPLACE / IGNORE（INTO 前），可空。 */
    private String duplicateMode;
    private SqlExpr fileName;
    private SqlIdentifier table;
    private final List<SqlIdentifier> columns = new ArrayList<SqlIdentifier>(4);
    /** CHARACTER SET 名，可空。 */
    private String characterSet;
    /** FIELDS/COLUMNS … 原文，可空。 */
    private String fieldsClause;
    /** LINES … 原文，可空。 */
    private String linesClause;
    /** IGNORE n LINES/ROWS 原文，可空。 */
    private String ignoreClause;
    /** SET … 等残余尾部，可空。 */
    private String tail;
    private String raw;

    /**
     * {@inheritDoc}
     */
    @Override
    public SqlStatementType type() {
        return SqlStatementType.OTHER;
    }

    /**
     * @return 是否 LOCAL
     */
    public boolean local() {
        return local;
    }

    /**
     * @param local LOCAL
     */
    public void setLocal(boolean local) {
        this.local = local;
    }

    /**
     * @return LOW_PRIORITY / CONCURRENT，可空
     */
    public String priority() {
        return priority;
    }

    /**
     * @param priority 优先级修饰
     */
    public void setPriority(String priority) {
        this.priority = priority;
    }

    /**
     * @return REPLACE / IGNORE，可空
     */
    public String duplicateMode() {
        return duplicateMode;
    }

    /**
     * @param duplicateMode REPLACE / IGNORE
     */
    public void setDuplicateMode(String duplicateMode) {
        this.duplicateMode = duplicateMode;
    }

    /**
     * @return INFILE 路径表达式，可空
     */
    public SqlExpr fileName() {
        return fileName;
    }

    /**
     * @param fileName 文件名
     */
    public void setFileName(SqlExpr fileName) {
        this.fileName = fileName;
    }

    /**
     * @return 目标表，可空
     */
    public SqlIdentifier table() {
        return table;
    }

    /**
     * @param table 目标表
     */
    public void setTable(SqlIdentifier table) {
        this.table = table;
    }

    /**
     * @return 列清单（可含用户变量名作标识）
     */
    public List<SqlIdentifier> columns() {
        return columns;
    }

    /**
     * @return CHARACTER SET 名，可空
     */
    public String characterSet() {
        return characterSet;
    }

    /**
     * @param characterSet 字符集
     */
    public void setCharacterSet(String characterSet) {
        this.characterSet = characterSet;
    }

    /**
     * @return FIELDS/COLUMNS 子句原文，可空
     */
    public String fieldsClause() {
        return fieldsClause;
    }

    /**
     * @param fieldsClause FIELDS 原文
     */
    public void setFieldsClause(String fieldsClause) {
        this.fieldsClause = fieldsClause;
    }

    /**
     * @return LINES 子句原文，可空
     */
    public String linesClause() {
        return linesClause;
    }

    /**
     * @param linesClause LINES 原文
     */
    public void setLinesClause(String linesClause) {
        this.linesClause = linesClause;
    }

    /**
     * @return IGNORE 子句原文，可空
     */
    public String ignoreClause() {
        return ignoreClause;
    }

    /**
     * @param ignoreClause IGNORE 原文
     */
    public void setIgnoreClause(String ignoreClause) {
        this.ignoreClause = ignoreClause;
    }

    /**
     * @return SET 等残余尾部，可空
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
     * @return 整段原文（失败回退），可空
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
        super.acceptChildren(visitor);
        child(visitor, fileName);
        child(visitor, table);
        children(visitor, columns);
    }
}
