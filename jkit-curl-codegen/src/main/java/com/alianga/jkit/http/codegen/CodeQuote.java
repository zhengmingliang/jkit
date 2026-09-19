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
     * Kotlin 字符串支持 {@code ${...}} 模板插值：只做 Java 转义会让形如 {@code ${nope}} 的值
     * 被目标编译器求值（未定义符号直接编译失败，已定义符号则被替换成别的值）。
     *
     * @param value 原始值
     * @return Kotlin 双引号字面量（额外转义 {@code $}）
     */
    public static String kotlin(String value) {
        if (value == null) {
            return "\"\"";
        }
        return "\"" + escape(value, '"').replace("$", "\\$") + "\"";
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
     * R 的反引号名（{@code `X-Name` = "v"}）：反引号内同样要转义反斜杠与反引号，
     * 否则一个含反引号的头名就能闭合名字、注入任意 R 表达式。
     *
     * @param value 原始名
     * @return 带反引号的 R 名字面量
     */
    public static String rName(String value) {
        if (value == null) {
            return "``";
        }
        return "`" + escape(value, '`', "\\u%04x") + "`";
    }

    /**
     * @param value 原始值
     * @return Rust 双引号字面量
     */
    public static String rust(String value) {
        if (value == null) {
            return "\"\"";
        }
        return "\"" + escape(value, '"', "\\u{%04x}") + "\"";
    }

    /**
     * @param value 原始值
     * @return Swift 双引号字面量
     */
    public static String swift(String value) {
        if (value == null) {
            return "\"\"";
        }
        return "\"" + escape(value, '"', "\\u{%04x}") + "\"";
    }

    /**
     * Swift 字符串内容转义（不含外层引号），用于把值嵌进已经写好的 Swift 字面量中间。
     * 反斜杠被转义后，{@code \\(...)} 插值也会被还原成普通字符。
     *
     * @param value 原始值
     * @return 转义后的 Swift 字符串内容
     */
    public static String swiftEscape(String value) {
        if (value == null) {
            return "";
        }
        return escape(value, '"', "\\u{%04x}");
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
     * @return Lua 双引号字面量（优先双引号；若含双引号且不含单引号则用单引号）
     */
    public static String lua(String value) {
        if (value == null) {
            return "\"\"";
        }
        if (value.indexOf('"') >= 0 && value.indexOf('\'') < 0) {
            return "'" + escapeLua(value, '\'') + "'";
        }
        return "\"" + escapeLua(value, '"') + "\"";
    }

    /**
     * Lua 5.1 不支持 {@code \\uXXXX}：控制字符用三位十进制转义 {@code \\ddd}；
     * Lua 字符串是字节序列，U+2028/U+2029 原样保留即可。
     */
    private static String escapeLua(String value, char quote) {
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
            } else if (c < 0x20) {
                sb.append(String.format("\\%03d", (int) c));
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    /**
     * JSON 字符串内容转义（不含外层引号）。
     *
     * @param value 原始值
     * @return 转义后的 JSON 字符串内容
     */
    public static String jsonEscape(String value) {
        if (value == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder(value.length() + 8);
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '\\':
                    sb.append("\\\\");
                    break;
                case '"':
                    sb.append("\\\"");
                    break;
                case '\n':
                    sb.append("\\n");
                    break;
                case '\r':
                    sb.append("\\r");
                    break;
                case '\t':
                    sb.append("\\t");
                    break;
                default:
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
            }
        }
        return sb.toString();
    }

    /**
     * @param value 原始值
     * @return 带双引号的 JSON 字符串字面量
     */
    public static String json(String value) {
        return "\"" + jsonEscape(value) + "\"";
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
        return escape(value, quote, "\\u%04x");
    }

    /**
     * 除引号与 {@code \\n\\r\\t} 外，其余控制字符（&lt; 0x20）和 U+2028/U+2029 也转义：
     * U+2028/U+2029 在 JS 字符串字面量里是非法字符，控制字符会让生成的源码不可读甚至非法。
     *
     * @param value 原始值
     * @param quote 外层引号字符
     * @param unicodeFmt Unicode 转义格式（JS/Python/Go/C#/Ruby/R 用 {@code \\uXXXX}，Rust/Swift 用 {@code \\u{XXXX}}）
     * @return 转义后的字符串内容（不含外层引号）
     */
    private static String escape(String value, char quote, String unicodeFmt) {
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
            } else if (c < 0x20 || c == '\u2028' || c == '\u2029') {
                sb.append(String.format(unicodeFmt, (int) c));
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }
}
