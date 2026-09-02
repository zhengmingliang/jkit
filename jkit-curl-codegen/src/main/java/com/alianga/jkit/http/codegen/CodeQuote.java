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

    /**
     * @param value 原始值
     * @return Ruby 双引号字面量
     */
    public static String ruby(String value) {
        if (value == null) {
            return "\"\"";
        }
        return dquote(value.replace("#{", "\\#{"));
    }

    /**
     * @param value 原始值
     * @return R 双引号字面量
     */
    public static String r(String value) {
        return dquote(value);
    }

    /**
     * @param value 原始值
     * @return Rust 双引号字面量
     */
    public static String rust(String value) {
        return dquote(value);
    }

    /**
     * @param value 原始值
     * @return Swift 双引号字面量
     */
    public static String swift(String value) {
        return dquote(value);
    }

    /**
     * @param value 原始值
     * @return PowerShell 单引号字面量
     */
    public static String ps(String value) {
        if (value == null) {
            return "''";
        }
        return "'" + value.replace("'", "''") + "'";
    }

    /**
     * @param value 原始值
     * @return POSIX shell（bash / wget）单引号字面量
     */
    public static String sh(String value) {
        if (value == null) {
            return "''";
        }
        return "'" + value.replace("'", "'\\''") + "'";
    }

    /**
     * Windows 的 cmd.exe 没有多行字符串，正文里的换行会退化成字面量 {@code \n}。
     *
     * @param value 原始值
     * @return Windows cmd 下 curl.exe 可用的双引号字面量
     */
    public static String cmd(String value) {
        if (value == null) {
            return "\"\"";
        }
        String flat = value.replace("\r\n", "\n").replace('\r', '\n');
        StringBuilder sb = new StringBuilder(flat.length() + 8);
        for (int i = 0; i < flat.length(); i++) {
            char c = flat.charAt(i);
            if (c == '\\') {
                sb.append("\\\\");
            } else if (c == '"') {
                sb.append("\\\"");
            } else if (c == '\n') {
                sb.append("\\n");
            } else if (c == '\t') {
                sb.append("\\t");
            } else {
                sb.append(c);
            }
        }
        return "\"" + sb.toString() + "\"";
    }

    /**
     * @param value 原始值
     * @return 通用双引号字面量（C / Rust / Swift / Ruby 系转义规则）
     */
    public static String dquote(String value) {
        if (value == null) {
            return "\"\"";
        }
        return "\"" + escape(value, '"') + "\"";
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
