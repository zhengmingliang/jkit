package com.alianga.jkit.sql;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * 语句解析器注册表。按前导关键字（不区分大小写）注册 {@link SqlStatementParser}，
 * 经 {@link SqlParseOptions#statementParsers(SqlStatementParsers)} 生效；
 * 内建语句分派优先，注册表只兜内建 switch 未覆盖的关键字。
 *
 * <pre>{@code
 * SqlParseOptions opt = SqlParseOptions.defaults()
 *         .statementParsers(SqlStatementParsers.create()
 *                 .add("BACKUP", ctx -> { ... })
 *                 .add("RESTORE", ctx -> { ... }));
 * }</pre>
 *
 * @author 郑明亮
 * @since 2.0.1
 */
public final class SqlStatementParsers {
    private Map<String, SqlStatementParser> parsers;

    /**
     * @return 空注册表（可继续 {@link #add}）
     */
    public static SqlStatementParsers create() {
        return new SqlStatementParsers();
    }

    /**
     * @return 空注册表（同 {@link #create()}）
     */
    public static SqlStatementParsers none() {
        return new SqlStatementParsers();
    }

    private SqlStatementParsers() {
    }

    /**
     * 注册语句解析器；同关键字后注册覆盖先注册。
     *
     * @param keyword 前导关键字（不含空白，注册与匹配均不区分大小写）
     * @param parser 解析器
     * @return this
     */
    public SqlStatementParsers add(String keyword, SqlStatementParser parser) {
        if (keyword == null || keyword.trim().isEmpty()) {
            throw new IllegalArgumentException("statement keyword required");
        }
        if (parser == null) {
            throw new IllegalArgumentException("statement parser required");
        }
        if (parsers == null) {
            parsers = new LinkedHashMap<String, SqlStatementParser>();
        }
        parsers.put(keyword.trim().toUpperCase(Locale.ROOT), parser);
        return this;
    }

    /**
     * @return 是否为空
     */
    public boolean isEmpty() {
        return parsers == null || parsers.isEmpty();
    }

    /**
     * 按前导关键字查找（不区分大小写）。
     *
     * @param keyword 前导关键字
     * @return 解析器，未注册返回 {@code null}
     */
    public SqlStatementParser find(String keyword) {
        if (parsers == null || keyword == null) {
            return null;
        }
        return parsers.get(keyword.toUpperCase(Locale.ROOT));
    }

    /**
     * @return 只读视图（关键字已大写归一）
     */
    public Map<String, SqlStatementParser> parsers() {
        return parsers == null ? Collections.<String, SqlStatementParser>emptyMap()
                : Collections.unmodifiableMap(parsers);
    }
}
