package com.alianga.jkit.expression;

/**
 * <p> 按java语法的操作符号优先级仅供参考，值越小优先级越高
 * <p> 支持科学计数法e+n，16进制0x，8进制0n
 * <p> 支持∈和∉
 *
 * <pre>{@code
 * 符号 优先级
 * () 1
 * ** 9 (指数运算，平方（根），立方（根）)
 * /*% 10
 * +- 100
 * << >> 200
 * ><== 300 其中==优先级设置为301（最低）
 * &|^ 500+ &(500) < ^(501) < |(502)
 * && || 600
 * ? : 700(? 701, : 700) 三目运算符优先级放最低,其中:优先级高于?
 * }</pre>
 *
 * @time 2024/8/24 22:35
 */
public enum ElOperator {
    /**
     * 原子操作符，表示不含运算符的单个值（常量或变量）
     */
    ATOM("", 0, 0),
    /**
     * 括号 {@code ()}，用于提升子表达式的优先级
     */
    BRACKET("()", 1, 2),
    /**
     * 三目运算符的冒号部分 {@code ?:}，用于分隔条件成立与不成立时的两个分支
     */
    QUESTION_COLON("?:", 2, 0),
    /**
     * 逻辑非 {@code !}
     */
    NOT("!", 5, 43),
    /**
     * 指数运算 {@code **}，用于平方（根）、立方（根）等幂运算
     */
    EXP("**", 9, 4),
    /**
     * 乘法 {@code *}
     */
    MULTI("*", 10, 1),
    /**
     * 除法 {@code /}
     */
    DIVISION("/", 10, 2),
    /**
     * 取模 {@code %}
     */
    MOD("%", 10, 3),
    /**
     * 加法 {@code +}
     */
    PLUS("+", 100, 11),
    /**
     * 减法 {@code -}
     */
    MINUS("-", 100, 12),
    /**
     * 右移 {@code >>}
     */
    BIT_RIGHT(">>", 200, 21),
    /**
     * 左移 {@code <<}
     */
    BIT_LEFT("<<", 200, 22),
    /**
     * 大于 {@code >}
     */
    GT(">", 300, 31),
    /**
     * 小于 {@code <}
     */
    LT("<", 300, 32),
    /**
     * 等于 {@code ==}
     */
    EQ("==", 301, 33),
    /**
     * 大于或等于 {@code >=}
     */
    GE(">=", 300, 34),
    /**
     * 小于或等于 {@code <=}
     */
    LE("<=", 300, 35),
    /**
     * 不等于 {@code !=}
     */
    NE("!=", 301, 36),

    // & > ^ > |
    /**
     * 按位与 {@code &}
     */
    AND("&", 500, 51),
    /**
     * 按位异或 {@code ^}
     */
    XOR("^", 501, 52),
    /**
     * 按位或 {@code |}
     */
    OR("|", 502, 53),
    /**
     * 逻辑与 {@code &&}
     */
    LOGICAL_AND("&&", 600, 61),
    /**
     * 逻辑或 {@code ||}
     */
    LOGICAL_OR("||", 600, 62),
    /**
     * 属于 {@code ∈}，判断左值是否包含在右侧集合中
     */
    IN("∈", 600, 63),
    /**
     * 不属于 {@code ∉}，判断左值是否不包含在右侧集合中
     */
    OUT("∉", 600, 64),
    //    COLON("?:", 700, 70),
    /**
     * 三目运算符的问号部分 {@code ??}，用于标记条件表达式的起始
     */
    QUESTION("??", 701, 71),

    // TODO 拓展类型
    /**
     * 拓展占位操作符，使用不可见字符 {@code \uffff} 作为符号，预留给后续扩展的运算类型
     */
    EXPAND("\uffff", 800, 80);

    static final ElOperator[] INDEXS_OPERATORS = new ElOperator[128];

    static {
        ElOperator[] values = ElOperator.values();
        for (ElOperator value : values) {
            String symbol = value.symbol;
            if (symbol.length() == 1) {
                char c = symbol.charAt(0);
                if (c < INDEXS_OPERATORS.length) {
                    INDEXS_OPERATORS[c] = value;
                }
            }
        }
        INDEXS_OPERATORS['='] = EQ;
    }

    final String symbol;
    final int level;
    final int type;

    ElOperator(String symbol, int level, int type) {
        this.symbol = symbol;
        this.level = level;
        this.type = type;
    }
}
