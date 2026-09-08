package com.alianga.jkit.sql.ast;

/**
 * 二元运算符。
 *
 * @author 郑明亮
 * @since 2.1.0
 */
public enum SqlBinaryOp {
    EQ("="),
    NE("<>"),
    LT("<"),
    GT(">"),
    LE("<="),
    GE(">="),
    IS("IS"),
    IS_NOT("IS NOT"),
    LIKE("LIKE"),
    NOT_LIKE("NOT LIKE"),
    ILIKE("ILIKE"),
    REGEXP("REGEXP"),
    AND("AND"),
    OR("OR"),
    XOR("XOR"),
    PLUS("+"),
    MINUS("-"),
    MUL("*"),
    DIV("/"),
    MOD("%"),
    INT_DIV("DIV"),
    CONCAT("||"),
    BIT_AND("&"),
    BIT_OR("|"),
    BIT_XOR("^"),
    SHIFT_LEFT("<<"),
    SHIFT_RIGHT(">>"),
    JSON("->"),
    ASSIGN(":="),
    ESCAPE("ESCAPE"),
    COLLATE("COLLATE"),
    CAST("::");

    private final String symbol;

    SqlBinaryOp(String symbol) {
        this.symbol = symbol;
    }

    /**
     * @return 打印用符号
     */
    public String symbol() {
        return symbol;
    }
}
