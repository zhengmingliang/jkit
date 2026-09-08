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

    /**
     * @return 默认选项（不保留普通注释；MySQL {@code ||} 仍为 OR）
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
}
