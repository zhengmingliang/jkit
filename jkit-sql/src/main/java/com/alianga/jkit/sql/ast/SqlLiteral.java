package com.alianga.jkit.sql.ast;

/**
 * 字面量：字符串、数字、NULL、TRUE/FALSE、绑定变量。
 *
 * @author 郑明亮
 * @since 2.1.0
 */
public final class SqlLiteral extends SqlExpr {
    /**
     * 字面量种类。
     */
    public enum Kind {
        STRING,
        NUMBER,
        NULL,
        BOOLEAN,
        HEX,
        BIT,
        BIND,
        NAMED_BIND,
        VARIABLE
    }

    private Kind kind;
    private String value;
    private String name;

    /**
     * @param kind 种类
     * @param value 原文或解码值
     * @return 字面量
     */
    public static SqlLiteral of(Kind kind, String value) {
        SqlLiteral lit = new SqlLiteral();
        lit.kind = kind;
        lit.value = value;
        return lit;
    }

    /**
     * @return 种类
     */
    public Kind kind() {
        return kind;
    }

    /**
     * @param kind 种类
     */
    public void setKind(Kind kind) {
        this.kind = kind;
    }

    /**
     * @return 值
     */
    public String value() {
        return value;
    }

    /**
     * @param value 值
     */
    public void setValue(String value) {
        this.value = value;
    }

    /**
     * @return 命名参数名（不含冒号）或变量名
     */
    public String name() {
        return name;
    }

    /**
     * @param name 名称
     */
    public void setName(String name) {
        this.name = name;
    }
}
