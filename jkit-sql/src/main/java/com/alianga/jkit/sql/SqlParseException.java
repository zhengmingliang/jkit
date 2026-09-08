package com.alianga.jkit.sql;

/**
 * SQL 解析失败。带行号、列号和出错附近的原文片段，不返回半棵树。
 *
 * @author 郑明亮
 * @since 2.1.0
 */
public class SqlParseException extends RuntimeException {
    private final int line;
    private final int column;
    private final String snippet;

    /**
     * @param message 说明
     * @param line 1-based 行号
     * @param column 1-based 列号
     * @param snippet 附近原文
     */
    public SqlParseException(String message, int line, int column, String snippet) {
        super(format(message, line, column, snippet));
        this.line = line;
        this.column = column;
        this.snippet = snippet == null ? "" : snippet;
    }

    /**
     * @param message 说明
     * @param line 行号
     * @param column 列号
     * @param snippet 附近原文
     * @param cause 原因
     */
    public SqlParseException(String message, int line, int column, String snippet, Throwable cause) {
        super(format(message, line, column, snippet), cause);
        this.line = line;
        this.column = column;
        this.snippet = snippet == null ? "" : snippet;
    }

    private static String format(String message, int line, int column, String snippet) {
        StringBuilder sb = new StringBuilder();
        sb.append(message == null ? "SQL parse error" : message);
        sb.append(" at ").append(line).append(':').append(column);
        if (snippet != null && !snippet.isEmpty()) {
            sb.append(" near \"").append(snippet).append('"');
        }
        return sb.toString();
    }

    /**
     * @return 1-based 行号
     */
    public int line() {
        return line;
    }

    /**
     * @return 1-based 列号
     */
    public int column() {
        return column;
    }

    /**
     * @return 出错附近原文
     */
    public String snippet() {
        return snippet;
    }
}
