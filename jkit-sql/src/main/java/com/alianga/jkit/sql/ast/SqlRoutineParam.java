package com.alianga.jkit.sql.ast;

import com.alianga.jkit.sql.visitor.SqlVisitor;

/**
 * 存储过程 / 函数参数：{@code [IN|OUT|INOUT] name type…}。
 *
 * @author 郑明亮
 * @since 2.0.1
 */
public final class SqlRoutineParam extends SqlNode {
    private String mode;
    private SqlIdentifier name;
    private String typeRaw;

    /**
     * @return IN / OUT / INOUT，可空
     */
    public String mode() {
        return mode;
    }

    /**
     * @param mode 参数模式
     */
    public void setMode(String mode) {
        this.mode = mode;
    }

    /**
     * @return 参数名
     */
    public SqlIdentifier name() {
        return name;
    }

    /**
     * @param name 参数名
     */
    public void setName(SqlIdentifier name) {
        this.name = name;
    }

    /**
     * @return 类型与属性原文，可空
     */
    public String typeRaw() {
        return typeRaw;
    }

    /**
     * @param typeRaw 类型原文
     */
    public void setTypeRaw(String typeRaw) {
        this.typeRaw = typeRaw;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    protected void acceptChildren(SqlVisitor visitor) {
        child(visitor, name);
    }
}
