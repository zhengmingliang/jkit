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
    IS_DISTINCT_FROM("IS DISTINCT FROM"),
    IS_NOT_DISTINCT_FROM("IS NOT DISTINCT FROM"),
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
    JSON_ARROW("->"),
    JSON_ARROW_TEXT("->>"),
    JSON_PATH("#>"),
    JSON_PATH_TEXT("#>>"),
    SUBSCRIPT("[]"),
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
