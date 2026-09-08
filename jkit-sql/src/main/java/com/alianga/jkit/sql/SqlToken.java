package com.alianga.jkit.sql;

/**
 * 可复用词法记号。文本在首次 {@link #text()} 时从源缓冲切片。
 *
 * @author 郑明亮
 * @since 2.1.0
 */
public final class SqlToken {
    SqlTokenType type;
    int start;
    int end;
    int line;
    int column;
    char[] src;
    String text;

    /**
     * 供解析器复用。
     */
    public SqlToken() {
    }

    void set(SqlTokenType type, char[] src, int start, int end, int line, int column) {
        this.type = type;
        this.src = src;
        this.start = start;
        this.end = end;
        this.line = line;
        this.column = column;
        this.text = null;
    }

    void copyFrom(SqlToken other) {
        this.type = other.type;
        this.src = other.src;
        this.start = other.start;
        this.end = other.end;
        this.line = other.line;
        this.column = other.column;
        this.text = other.text;
    }

    /**
     * @return 记号类型
     */
    public SqlTokenType type() {
        return type;
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
     * @return 源起始下标
     */
    public int start() {
        return start;
    }

    /**
     * @return 源结束下标（不含）
     */
    public int end() {
        return end;
    }

    /**
     * @return 文本长度
     */
    public int length() {
        return end - start;
    }

    /**
     * @return 原文；关键字返回大写名以外的原始切片
     */
    public String text() {
        if (text == null) {
            if (src == null || end <= start) {
                text = "";
            } else {
                text = new String(src, start, end - start);
            }
        }
        return text;
    }

    /**
     * 与关键字字面量忽略大小写比较。
     *
     * @param word 大写或任意大小写单词
     * @return 是否相同
     */
    public boolean textEqualsIgnoreCase(String word) {
        if (word == null || word.length() != length()) {
            return false;
        }
        for (int i = 0; i < word.length(); i++) {
            char c = src[start + i];
            if (c >= 'a' && c <= 'z') {
                c = (char) (c - 32);
            }
            char w = word.charAt(i);
            if (w >= 'a' && w <= 'z') {
                w = (char) (w - 32);
            }
            if (c != w) {
                return false;
            }
        }
        return true;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public String toString() {
        return type + "(" + text() + ")";
    }
}
