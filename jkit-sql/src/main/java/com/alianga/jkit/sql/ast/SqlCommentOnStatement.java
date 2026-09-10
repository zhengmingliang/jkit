package com.alianga.jkit.sql.ast;

import com.alianga.jkit.sql.visitor.SqlVisitor;

/**
 * PG/Oracle 风格 {@code COMMENT ON <objectKind> <name> IS <comment>}。
 *
 * <p>对象种类进 {@link #objectKind()}（TABLE/COLUMN/INDEX/VIEW/…）；
 * 对象名（COLUMN 时为 {@code table.column}）进 {@link #name()}；
 * 注释字面量进 {@link #comment()}；残余进 {@link #raw()}。
 * 语句种类为 {@link SqlStatementType#OTHER}。</p>
 *
 * @author 郑明亮
 * @since 2.0.1
 */
public final class SqlCommentOnStatement extends SqlStatement {
    /** TABLE / COLUMN / INDEX / VIEW / SCHEMA / DATABASE / …，可空。 */
    private String objectKind;
    /** 对象名（COLUMN 时含表.列），可空。 */
    private SqlIdentifier name;
    /** IS 后的注释表达式（通常为字符串字面量），可空。 */
    private SqlExpr comment;
    /** 残余原文，可空。 */
    private String raw;

    /**
     * {@inheritDoc}
     */
    @Override
    public SqlStatementType type() {
        return SqlStatementType.OTHER;
    }

    /**
     * @return TABLE / COLUMN / INDEX / …，可空
     */
    public String objectKind() {
        return objectKind;
    }

    /**
     * @param objectKind 对象种类
     */
    public void setObjectKind(String objectKind) {
        this.objectKind = objectKind;
    }

    /**
     * @return 对象名，可空
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
     * @return 注释表达式，可空
     */
    public SqlExpr comment() {
        return comment;
    }

    /**
     * @param comment 注释表达式
     */
    public void setComment(SqlExpr comment) {
        this.comment = comment;
    }

    /**
     * @return 残余原文，可空
     */
    public String raw() {
        return raw;
    }

    /**
     * @param raw 残余原文
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
        child(visitor, name);
        child(visitor, comment);
    }
}
