package com.alianga.jkit.sql;

/**
 * SQL 解析选项。默认全部关闭，保证热路径与旧行为一致。
 *
 * @author 郑明亮
 * @since 2.1.0
 */
public final class SqlParseOptions {
    private boolean keepComments;

    /**
     * @return 默认选项（不保留普通注释）
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
}
