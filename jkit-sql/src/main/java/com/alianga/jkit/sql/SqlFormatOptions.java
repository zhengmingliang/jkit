package com.alianga.jkit.sql;

/**
 * SQL 格式化 / 回写选项。默认全部关闭，保证热路径与旧行为一致。
 *
 * @author 郑明亮
 * @since 2.0.1
 */
public final class SqlFormatOptions {
    private boolean quoteIdentifiers;

    /**
     * @return 默认选项（不强制给标识符加方言引号；仅 {@link com.alianga.jkit.sql.ast.SqlIdentifier#quoted()} 为 true 时加引号）
     */
    public static SqlFormatOptions defaults() {
        return new SqlFormatOptions();
    }

    /**
     * @return 是否强制用方言引号包裹每个标识符段（表/列等经 {@code writeIdentifier} 输出的名字）
     */
    public boolean quoteIdentifiers() {
        return quoteIdentifiers;
    }

    /**
     * 开启后，格式化时对每个标识符段强制加方言引号（MySQL {@code `}、PG/Oracle {@code "}、SQL Server {@code []}）；
     * 关闭则恢复仅按 {@link com.alianga.jkit.sql.ast.SqlIdentifier#quoted()} 决定是否加引号。
     * 不影响字符串字面量、关键字与函数名、{@code *}。
     *
     * @param quoteIdentifiers 是否强制引用标识符
     * @return this
     */
    public SqlFormatOptions quoteIdentifiers(boolean quoteIdentifiers) {
        this.quoteIdentifiers = quoteIdentifiers;
        return this;
    }
}
