package com.alianga.jkit.sql;

/**
 * 编译后的模板占位符匹配器（词法阶段用）。支持 {@code @*@} / {@code <*>} /
 * {@code <-*->} 一类前后缀包裹，精确字面量（如 {@code %s}），以及 printf 风格
 * {@code %}{@code + 字母}。
 *
 * <p>默认解析不启用任何占位符；由 {@link SqlPlaceholders} 配置后交给词法器。
 * 匹配成功时词法器发出 {@link SqlTokenType#IDENT}，可作表名/列名/别名/表达式原子。</p>
 *
 * @author 郑明亮
 * @since 2.0.1
 */
public final class SqlPlaceholderPattern {
    enum Kind {
        /** 前缀 + 正文 + 后缀，正文至少一字且不含空白。 */
        WRAPPED,
        /** 完整字面量精确匹配（无通配）。 */
        EXACT,
        /** {@code %} 后跟一个 ASCII 字母（printf 转换符）。 */
        PRINTF
    }

    enum BodyClass {
        /** {@code @name@}：字母/数字/下划线/$/非 ASCII。 */
        IDENT,
        /** {@code <sheet>} / {@code <-…->}：IDENT 体 + {@code .} {@code -}。 */
        SHEET,
        /** 自定义包裹：非空白即可（遇后缀结束）。 */
        ANY_NON_WS
    }

    private final Kind kind;
    private final char[] prefix;
    private final char[] suffix;
    private final BodyClass bodyClass;
    private final String display;

    private SqlPlaceholderPattern(Kind kind, String prefix, String suffix,
            BodyClass bodyClass, String display) {
        this.kind = kind;
        this.prefix = prefix == null ? new char[0] : prefix.toCharArray();
        this.suffix = suffix == null ? new char[0] : suffix.toCharArray();
        this.bodyClass = bodyClass == null ? BodyClass.ANY_NON_WS : bodyClass;
        this.display = display;
    }

    static SqlPlaceholderPattern wrapped(String prefix, String suffix,
            BodyClass bodyClass, String display) {
        if (prefix == null || prefix.isEmpty() || suffix == null || suffix.isEmpty()) {
            throw new IllegalArgumentException("placeholder prefix/suffix required");
        }
        return new SqlPlaceholderPattern(Kind.WRAPPED, prefix, suffix, bodyClass, display);
    }

    static SqlPlaceholderPattern exact(String literal) {
        if (literal == null || literal.isEmpty()) {
            throw new IllegalArgumentException("placeholder literal required");
        }
        return new SqlPlaceholderPattern(Kind.EXACT, literal, "", null, literal);
    }

    static SqlPlaceholderPattern printfSpec() {
        return new SqlPlaceholderPattern(Kind.PRINTF, "%", "", null, "%*");
    }

    /**
     * @return 配置时的模式展示串
     */
    public String pattern() {
        return display;
    }

    /**
     * 若从 {@code pos} 起匹配成功，返回结束下标（不含）；否则 -1。
     *
     * @param src 源
     * @param pos 当前位置
     * @param limit 结束
     * @return 匹配结束下标或 -1
     */
    int tryMatch(char[] src, int pos, int limit) {
        if (kind == Kind.PRINTF) {
            if (pos >= limit || src[pos] != '%') {
                return -1;
            }
            if (pos + 1 >= limit) {
                return -1;
            }
            char c = src[pos + 1];
            if ((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z')) {
                return pos + 2;
            }
            return -1;
        }
        if (kind == Kind.EXACT) {
            if (pos + prefix.length > limit) {
                return -1;
            }
            for (int i = 0; i < prefix.length; i++) {
                if (src[pos + i] != prefix[i]) {
                    return -1;
                }
            }
            return pos + prefix.length;
        }
        // WRAPPED
        if (pos + prefix.length + 1 + suffix.length > limit) {
            return -1;
        }
        for (int i = 0; i < prefix.length; i++) {
            if (src[pos + i] != prefix[i]) {
                return -1;
            }
        }
        int i = pos + prefix.length;
        int bodyStart = i;
        if (suffix.length == 1) {
            char end = suffix[0];
            while (i < limit && src[i] != end) {
                if (!allowedBody(src[i])) {
                    return -1;
                }
                i++;
            }
            if (i >= limit || i == bodyStart) {
                return -1;
            }
            return i + 1;
        }
        while (i + suffix.length <= limit) {
            if (regionMatches(src, i, suffix)) {
                if (i == bodyStart) {
                    return -1;
                }
                return i + suffix.length;
            }
            if (!allowedBody(src[i])) {
                return -1;
            }
            i++;
        }
        return -1;
    }

    private boolean allowedBody(char c) {
        if (c <= ' ') {
            return false;
        }
        switch (bodyClass) {
            case IDENT:
                return isIdentBody(c);
            case SHEET:
                return isIdentBody(c) || c == '.' || c == '-';
            case ANY_NON_WS:
            default:
                return true;
        }
    }

    private static boolean isIdentBody(char c) {
        return (c >= 'a' && c <= 'z')
                || (c >= 'A' && c <= 'Z')
                || (c >= '0' && c <= '9')
                || c == '_'
                || c == '$'
                || c > 0x7f;
    }

    private static boolean regionMatches(char[] src, int pos, char[] needle) {
        for (int i = 0; i < needle.length; i++) {
            if (src[pos + i] != needle[i]) {
                return false;
            }
        }
        return true;
    }
}
