package com.alianga.jkit.sql.ast;

/**
 * 二元运算符。
 *
 * @author 郑明亮
 * @since 2.0.1
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
    /** PostgreSQL {@code NOT ILIKE} */
    NOT_ILIKE("NOT ILIKE"),
    REGEXP("REGEXP"),
    NOT_REGEXP("NOT REGEXP"),
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
    CONTAINS("@>"),
    CONTAINED_BY("<@"),
    REGEX_MATCH("~"),
    REGEX_MATCH_CI("~*"),
    REGEX_NOT_MATCH("!~"),
    REGEX_NOT_MATCH_CI("!~*"),
    NULL_SAFE_EQ("<=>"),
    SUBSCRIPT("[]"),
    /** 函数/表达式结果字段访问 {@code f(x).y} */
    MEMBER("."),
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
