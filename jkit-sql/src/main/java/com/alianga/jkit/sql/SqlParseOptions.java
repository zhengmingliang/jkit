package com.alianga.jkit.sql;

/**
 * SQL 解析选项。默认全部关闭，保证热路径与旧行为一致。
 *
 * @author 郑明亮
 * @since 2.0.1
 */
public final class SqlParseOptions {
    private boolean keepComments;
    private boolean pipesAsConcat;
    private SqlPlaceholders placeholders = SqlPlaceholders.none();
    private SqlStatementParsers statementParsers = SqlStatementParsers.none();

    /**
     * @return 默认选项（不保留普通注释；MySQL {@code ||} 仍为 OR；模板占位符关闭）
     */
    public static SqlParseOptions defaults() {
        return new SqlParseOptions();
    }

    /**
     * @return 是否把普通行/块注释作为 {@link SqlTokenType#SQL_COMMENT} 交给解析器
     *         （可执行注释与优化器 hint 不受此开关影响）
     */
    public boolean keepComments() {
        return keepComments;
    }

    /**
     * @param keepComments 保留普通注释
     * @return this
     */
    public SqlParseOptions keepComments(boolean keepComments) {
        this.keepComments = keepComments;
        return this;
    }

    /**
     * MySQL 默认 {@code ||} 为逻辑 OR。打开后按 ANSI/PG 把 {@code ||} 解析为字符串拼接
     * （等价于会话 {@code PIPES_AS_CONCAT}）。
     *
     * @return 是否把 {@code ||} 当拼接
     */
    public boolean pipesAsConcat() {
        return pipesAsConcat;
    }

    /**
     * @param pipesAsConcat 把 {@code ||} 解析为拼接
     * @return this
     */
    public SqlParseOptions pipesAsConcat(boolean pipesAsConcat) {
        this.pipesAsConcat = pipesAsConcat;
        return this;
    }

    /**
     * 模板占位符配置；默认空（关闭）。未配置时 {@code @age@} / {@code %s} / {@code <sheet>}
     * 仍按原严格词法失败或拆成运算符。
     *
     * @return 占位符配置（可直接链式 {@code options.placeholders().atWrapped()}）
     * @since 2.0.1
     */
    public SqlPlaceholders placeholders() {
        if (placeholders == null) {
            placeholders = SqlPlaceholders.none();
        }
        return placeholders;
    }

    /**
     * 替换整份占位符配置。
     *
     * @param placeholders 配置，null 视为关闭
     * @return this
     * @since 2.0.1
     */
    public SqlParseOptions placeholders(SqlPlaceholders placeholders) {
        this.placeholders = placeholders == null ? SqlPlaceholders.none() : placeholders;
        return this;
    }

    /**
     * 自定义语句解析器注册表；默认空（关闭）。注册后，内建分派未覆盖的前导关键字
     * 交给 {@link SqlStatementParser} 处理，如 {@code BACKUP …} / {@code SIGNAL …}。
     *
     * @return 注册表（可直接链式 {@code options.statementParsers().add("BACKUP", fn)}）
     * @since 2.0.1
     */
    public SqlStatementParsers statementParsers() {
        if (statementParsers == null) {
            statementParsers = SqlStatementParsers.none();
        }
        return statementParsers;
    }

    /**
     * 替换整份语句解析器注册表。
     *
     * @param statementParsers 注册表，null 视为关闭
     * @return this
     * @since 2.0.1
     */
    public SqlParseOptions statementParsers(SqlStatementParsers statementParsers) {
        this.statementParsers = statementParsers == null ? SqlStatementParsers.none() : statementParsers;
        return this;
    }
}
