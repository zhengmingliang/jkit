package com.alianga.jkit.sql;

/**
 * 方言能力规约：{@link SqlDialect} 枚举实现的接口，也是自定义方言的扩展点。
 *
 * <p>所有方言相关的判断都收敛为能力方法，解析 / 改写 / 格式化 / 构建全链路只依赖本接口。
 * 内置 {@link SqlDialect} 枚举覆盖了全部能力；{@link SqlDialectWrapper} 基于某个内置方言
 * 微调个别能力，无需改动枚举：</p>
 *
 * <pre>{@code
 * // MySQL + ANSI_QUOTES：双引号从字符串变回标识符
 * SqlDialectSpec ansiQuotesMysql = new SqlDialectWrapper(SqlDialect.MYSQL) {
 *     @Override
 *     public boolean doubleQuoteIsString() {
 *         return false;
 *     }
 * };
 * SQL.parseAll("SELECT \"id\" FROM t", ansiQuotesMysql);
 * }</pre>
 *
 * <p>未覆写的方法按 {@link SqlDialectWrapper} 所委托的基方言返回；直接实现本接口时，
 * 默认值为 ANSI 基线（双引号标识符、{@code ||} 拼接、LIMIT/OFFSET 与 FETCH、
 * 无反斜杠转义、无 {@code #} 注释、无 {@code ~} 正则）。</p>
 *
 * @author 郑明亮
 * @since 2.0.1
 */
public interface SqlDialectSpec {
    /**
     * @return 标识符左引号（默认 {@code "}）
     */
    default char identQuoteOpen() {
        return '"';
    }

    /**
     * @return 标识符右引号（默认与左引号相同，方括号时为 {@code ]}）
     */
    default char identQuoteClose() {
        char open = identQuoteOpen();
        return open == '[' ? ']' : open;
    }

    /**
     * 用本方言引号包裹标识符（右引号在字面量内加倍转义）。
     *
     * @param name 裸标识符，null 返回 null
     * @return 带引号文本
     */
    default String quoteIdent(String name) {
        if (name == null) {
            return null;
        }
        char open = identQuoteOpen();
        char close = identQuoteClose();
        String escaped = name.replace(String.valueOf(close), String.valueOf(close) + close);
        return open + escaped + close;
    }

    /**
     * MySQL 默认把 {@code ||} 当 OR；其余方言当拼接（默认拼接）。
     *
     * @return 是否把 {@code ||} 解析为逻辑或
     */
    default boolean pipesAsOr() {
        return false;
    }

    /**
     * {@link #pipesAsOr()} 的反义：{@code ||} 是否表示字符串拼接。
     *
     * @return 是否拼接
     */
    default boolean pipesAreConcat() {
        return !pipesAsOr();
    }

    /**
     * @return 双引号是否当作字符串（默认 false，即双引号是标识符）
     */
    default boolean doubleQuoteIsString() {
        return false;
    }

    /**
     * @return 是否识别 {@code #} 行注释（默认 false）
     */
    default boolean hashLineComment() {
        return false;
    }

    /**
     * @return 字符串字面量内 {@code \} 是否为转义前缀（默认 false）
     */
    default boolean backslashEscapes() {
        return false;
    }

    /**
     * @return 方括号 {@code [name]} 是否为标识符引号（SQL Server 风格，默认 false）
     */
    default boolean bracketIdentifiers() {
        return false;
    }

    /**
     * @return 裸 {@code ~} 是否为正则匹配操作符（PostgreSQL / H2，默认 false）
     */
    default boolean supportsTildeRegex() {
        return false;
    }

    /**
     * 是否原生支持 {@code LIMIT … [OFFSET …]} / {@code LIMIT offset, count}。
     *
     * @return true 表示改写宜写 LIMIT（默认 true）
     */
    default boolean supportsLimitOffset() {
        return true;
    }

    /**
     * 是否用 {@code SELECT TOP n} 表达行数上限（SQL Server）。
     *
     * @return true 表示改写宜写 TOP（默认 false）
     */
    default boolean supportsTop() {
        return false;
    }

    /**
     * 是否支持 {@code OFFSET … FETCH FIRST/NEXT … ROWS ONLY}。
     *
     * @return true 时分页可用 FETCH 风格（默认 true）
     */
    default boolean supportsFetchFirst() {
        return true;
    }

    /**
     * 是否习惯用 Oracle {@code ROWNUM} 伪列分页。
     *
     * @return true 表示 Oracle 族（含 12c+），默认 false
     */
    default boolean supportsRownum() {
        return false;
    }

    /**
     * 带 offset 的分页是否写 {@code LIMIT offset, count} 逗号风格（MySQL）。
     *
     * @return true 时改写优先逗号风格，默认 false
     */
    default boolean supportsCommaLimitOffset() {
        return false;
    }

    /**
     * 「只补行数」的首选形态：SQL Server → {@code TOP}；经典 Oracle → {@code ROWNUM}；其余 → {@code LIMIT}。
     * 带 offset 的分页见 {@link #supportsFetchFirst()} / {@link #supportsLimitOffset()}。
     *
     * @return {@code "TOP"}、{@code "ROWNUM"} 或 {@code "LIMIT"}
     */
    default String preferredLimitStyle() {
        if (supportsTop()) {
            return "TOP";
        }
        if (supportsRownum() && !supportsFetchFirst()) {
            return "ROWNUM";
        }
        return "LIMIT";
    }
}
