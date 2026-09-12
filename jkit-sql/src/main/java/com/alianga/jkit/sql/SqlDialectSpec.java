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
     * 「只补行数」的首选形态（查询用，不驱动改写）：SQL Server → {@code TOP}；
     * 经典 Oracle → {@code ROWNUM}；其余 → {@code LIMIT}。
     * 分页改写读 {@link #supportsTop()} / {@link #supportsRownum()} /
     * {@link #supportsFetchFirst()} / {@link #supportsLimitOffset()} /
     * {@link #supportsCommaLimitOffset()}，覆写本方法不会改变 {@code setPage} 输出。
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

    /**
     * 未加引号标识符的长度上限（字符数）。ANSI 基线 128；经典 Oracle 30、MySQL 64、
     * PostgreSQL 63。生成索引名等自动标识符时应先 {@link #fitIdentifier(String)}。
     *
     * @return 上限
     */
    default int maxIdentifierLength() {
        return 128;
    }

    /**
     * 把标识符压进 {@link #maxIdentifierLength()}：未超长原样返回；超长则保留前缀并追加
     * 4 位十六进制散列。派生方法，覆写 {@link #maxIdentifierLength()} 即可改变行为。
     *
     * @param name 裸标识符，null 原样返回
     * @return 长度不超过上限的标识符
     */
    default String fitIdentifier(String name) {
        return SqlDialect.fitIdentifier(name, maxIdentifierLength());
    }

    /**
     * 方言稳定 id，类型表 / SPI 按此登记。内置枚举为 {@link SqlDialect#name()}。
     *
     * <p>自定义方言请返回自己的 id（如 {@code gauss-lite}）；若只想复用某内置类型表、
     * 不单独登记写法，保持与 {@link #typeFamily()}{@code .name()} 相同即可。</p>
     *
     * @return 非空 id
     */
    default String dialectId() {
        return typeFamily().name();
    }

    /**
     * 类型 / 自增 / 函数改写所复用的内置方言。
     *
     * <p>新增产品不必改 {@link SqlDialect} 枚举：实现本接口（或包一层
     * {@link SqlDialectWrapper}），这里返回最接近的内置方言（如达梦→{@link SqlDialect#ORACLE}）。
     * 仅当类型写法和内置都不一样时，再用 SPI 按 {@link #dialectId()} 覆盖个别 canonical。</p>
     *
     * @return 内置类型族，默认 {@link SqlDialect#ANSI}
     */
    default SqlDialect typeFamily() {
        return SqlDialect.ANSI;
    }
}
