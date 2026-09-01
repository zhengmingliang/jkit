package com.alianga.jkit.http.codegen;

/**
 * 各语言字符串字面量转义。
 *
 * @author 郑明亮
 * @since 2.0.1
 */
public final class CodeQuote {
    private CodeQuote() {
    }

    /**
     * @param value 原始值
     * @return JavaScript / TypeScript 单引号字面量
     */
    public static String js(String value) {
        return "'" + escape(value, '\'') + "'";
    }

    /**
     * @param value 原始值
     * @return Python 单引号字面量
     */
    public static String py(String value) {
        return "'" + escape(value, '\'') + "'";
    }

    /**
     * @param value 原始值
     * @return Go 双引号字面量
     */
    public static String go(String value) {
        return "\"" + escape(value, '"') + "\"";
    }

    /**
     * @param value 原始值
     * @return C# 双引号字面量
     */
    public static String csharp(String value) {
        return "\"" + escape(value, '"') + "\"";
    }

    /**
     * @param value 原始值
     * @return PHP 单引号字面量
     */
    public static String php(String value) {
        if (value == null) {
            return "''";
        }
        return "'" + value.replace("\\", "\\\\").replace("'", "\\'") + "'";
    }

    private static String escape(String value, char quote) {
        if (value == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder(value.length() + 8);
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == '\\' || c == quote) {
                sb.append('\\').append(c);
            } else if (c == '\n') {
                sb.append("\\n");
            } else if (c == '\r') {
                sb.append("\\r");
            } else if (c == '\t') {
                sb.append("\\t");
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }
}
