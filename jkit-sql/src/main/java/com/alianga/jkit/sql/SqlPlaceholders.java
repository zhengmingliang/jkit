package com.alianga.jkit.sql;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 模板占位符配置。默认空（关闭）；显式 {@link #add} 或内置预设后，词法器把匹配片段
 * 当作 {@link SqlTokenType#IDENT}，从而可出现在表名/列名/别名/值表达式位置。
 *
 * <pre>{@code
 * SqlParseOptions opt = SqlParseOptions.defaults()
 *         .placeholders(SqlPlaceholders.create()
 *                 .atWrapped()   // @age@
 *                 .printf()      // %s / %d …
 *                 .angle()       // <sheet>
 *                 .arrowAngle()  // <-sheet->
 *                 .add("{{*}}")); // 自定义
 * SQL.parse(sql, SqlDialect.MYSQL, opt);
 * }</pre>
 *
 * @author 郑明亮
 * @since 2.0.1
 */
public final class SqlPlaceholders {
    private final List<SqlPlaceholderPattern> patterns = new ArrayList<SqlPlaceholderPattern>(4);

    /**
     * @return 空配置
     */
    public static SqlPlaceholders create() {
        return new SqlPlaceholders();
    }

    /**
     * @return 空配置（同 {@link #create()}）
     */
    public static SqlPlaceholders none() {
        return new SqlPlaceholders();
    }

    /**
     * 添加模式。支持：
     * <ul>
     *   <li>恰好一个 {@code *}：前后缀包裹，如 {@code @*@}、{@code <*>}、{@code <-*->}、{@code {{*}}}</li>
     *   <li>无 {@code *}：精确字面量，如 {@code %s}</li>
     * </ul>
     *
     * @param pattern 模式串
     * @return this
     */
    public SqlPlaceholders add(String pattern) {
        if (pattern == null || pattern.isEmpty()) {
            throw new IllegalArgumentException("placeholder pattern required");
        }
        int star = pattern.indexOf('*');
        if (star < 0) {
            patterns.add(SqlPlaceholderPattern.exact(pattern));
            return this;
        }
        if (pattern.indexOf('*', star + 1) >= 0) {
            throw new IllegalArgumentException(
                    "placeholder pattern allows at most one '*': " + pattern);
        }
        String prefix = pattern.substring(0, star);
        String suffix = pattern.substring(star + 1);
        if (prefix.isEmpty() || suffix.isEmpty()) {
            throw new IllegalArgumentException(
                    "wrapped placeholder needs non-empty prefix and suffix: " + pattern);
        }
        SqlPlaceholderPattern.BodyClass body = inferBodyClass(prefix, suffix);
        patterns.add(SqlPlaceholderPattern.wrapped(prefix, suffix, body, pattern));
        return this;
    }

    /**
     * 内置：{@code @name@}（common-model 模板绑定）。
     *
     * @return this
     */
    public SqlPlaceholders atWrapped() {
        return add("@*@");
    }

    /**
     * 内置：printf 转换符 {@code %s}/{@code %d}/{@code %f}…（{@code %} + 字母）。
     *
     * @return this
     */
    public SqlPlaceholders printf() {
        patterns.add(SqlPlaceholderPattern.printfSpec());
        return this;
    }

    /**
     * 内置：{@code <sheet>} 表名占位。
     *
     * @return this
     */
    public SqlPlaceholders angle() {
        return add("<*>");
    }

    /**
     * 内置：{@code <-sheet->} 箭头角括号表名占位。
     *
     * @return this
     */
    public SqlPlaceholders arrowAngle() {
        return add("<-*->");
    }

    /**
     * common-model 审计 B 类常用组合：{@code @*@} + printf + {@code <*>} + {@code <-*->}。
     *
     * @return this
     */
    public SqlPlaceholders commonModelTemplates() {
        return atWrapped().printf().angle().arrowAngle();
    }

    /**
     * @return 是否未配置任何模式
     */
    public boolean isEmpty() {
        return patterns.isEmpty();
    }

    /**
     * @return 已配置模式（只读）
     */
    public List<SqlPlaceholderPattern> patterns() {
        return Collections.unmodifiableList(patterns);
    }

    private static SqlPlaceholderPattern.BodyClass inferBodyClass(String prefix, String suffix) {
        if ("@".equals(prefix) && "@".equals(suffix)) {
            return SqlPlaceholderPattern.BodyClass.IDENT;
        }
        if ("<".equals(prefix) && ">".equals(suffix)) {
            return SqlPlaceholderPattern.BodyClass.SHEET;
        }
        if ("<-".equals(prefix) && "->".equals(suffix)) {
            return SqlPlaceholderPattern.BodyClass.SHEET;
        }
        return SqlPlaceholderPattern.BodyClass.ANY_NON_WS;
    }
}
