package com.alianga.jkit.sql.ast;

import com.alianga.jkit.sql.visitor.SqlVisitor;

/**
 * {@code SHOW …} 语句（含 {@code SHOW CREATE TABLE|VIEW|DATABASE|…}）。
 *
 * <p>{@link #showKind()} 为 {@code CREATE}/{@code COLUMNS}/{@code TABLES}/{@code INDEX} 等；
 * {@code SHOW CREATE} 时对象种类进 {@link #objectType()}（TABLE/VIEW/DATABASE/…）；
 * 对象名进 {@link #name()}；{@code FROM}/{@code IN} 进 {@link #fromOrIn()}；
 * {@code LIKE}/{@code WHERE} 等残余进 {@link #raw()}。
 * 语句种类为 {@link SqlStatementType#SHOW}。</p>
 *
 * @author 郑明亮
 * @since 2.0.1
 */
public final class SqlShowStatement extends SqlStatement {
    /** CREATE / COLUMNS / TABLES / DATABASES / INDEX / …，可空。 */
    private String showKind;
    /** SHOW CREATE 的对象种类（TABLE/VIEW/DATABASE/…），可空。 */
    private String objectType;
    /** 对象/表名，可空。 */
    private SqlIdentifier name;
    /** FROM / IN，可空。 */
    private String fromOrIn;
    /** LIKE/WHERE 等残余原文，可空。 */
    private String raw;

    /**
     * {@inheritDoc}
     */
    @Override
    public SqlStatementType type() {
        return SqlStatementType.SHOW;
    }

    /**
     * @return CREATE / COLUMNS / TABLES / …，可空
     */
    public String showKind() {
        return showKind;
    }

    /**
     * @param showKind SHOW 子命令
     */
    public void setShowKind(String showKind) {
        this.showKind = showKind;
    }

    /**
     * @return SHOW CREATE 的对象种类，可空
     */
    public String objectType() {
        return objectType;
    }

    /**
     * @param objectType TABLE/VIEW/DATABASE/…
     */
    public void setObjectType(String objectType) {
        this.objectType = objectType;
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
     * @return FROM / IN，可空
     */
    public String fromOrIn() {
        return fromOrIn;
    }

    /**
     * @param fromOrIn FROM 或 IN
     */
    public void setFromOrIn(String fromOrIn) {
        this.fromOrIn = fromOrIn;
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
    }
}
