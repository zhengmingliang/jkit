package com.alianga.jkit.http.codegen;

/**
 * Java / Kotlin 源码字符串转义。
 *
 * @author 郑明亮
 * @since 2.0.1
 */
public final class JavaEmit {
    private JavaEmit() {
    }

    /**
     * @param value 原始字符串
     * @return 带双引号的 Java 字面量
     */
    public static String quote(String value) {
        return "\"" + escape(value) + "\"";
    }

    /**
     * @param value 原始字符串
     * @return 转义后的内容（不含引号）
     */
    public static String escape(String value) {
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
                    sb.append(c);
            }
        }
        return sb.toString();
    }
}
