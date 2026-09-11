package com.alianga.jkit.sql;

/**
 * SQL 格式化 / 回写选项。默认全部关闭，保证热路径与旧行为一致。
 *
 * @author 郑明亮
 * @since 2.0.1
 */
public final class SqlFormatOptions {
    private boolean quoteIdentifiers;
    private SqlKeywordCase keywordCase = SqlKeywordCase.AS_IS;

    /**
     * 热路径只读默认实例（{@code options == null} 时 Formatter 复用）。
     * 勿调用 {@link #keywordCase}/{@link #quoteIdentifiers} 改它；需要定制请用 {@link #defaults()}。
     */
    static final SqlFormatOptions DEFAULTS = new SqlFormatOptions();

    /**
     * @return 新的默认选项（可再链式改；不强制给标识符加方言引号；仅 {@link com.alianga.jkit.sql.ast.SqlIdentifier#quoted()} 为 true 时加引号）
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

    /**
     * @return 关键字大小写策略（默认 {@link SqlKeywordCase#AS_IS}）
     * @since 2.0.1
     */
    public SqlKeywordCase keywordCase() {
        return keywordCase == null ? SqlKeywordCase.AS_IS : keywordCase;
    }

    /**
     * 关键字大小写策略：SELECT / FROM / WHERE 等经 {@code kw(...)} 输出的关键字统一转大写或小写；
     * 不影响标识符、字符串字面量与函数名。
     *
     * @param keywordCase 策略，null 视为 {@link SqlKeywordCase#AS_IS}
     * @return this
     * @since 2.0.1
     */
    public SqlFormatOptions keywordCase(SqlKeywordCase keywordCase) {
        this.keywordCase = keywordCase == null ? SqlKeywordCase.AS_IS : keywordCase;
        return this;
    }
}
