package com.alianga.jkit.sql;

/**
 * SQL 方言。决定标识符引号、字符串引号、{@code ||} 语义，以及分页能力
 *（{@link #supportsLimitOffset()} / {@link #supportsTop()} /
 * {@link #supportsFetchFirst()} / {@link #supportsRownum()}）。
 *
 * <p>能力摘要（改写 / 格式化 / 构建以本枚举为单一事实来源）：</p>
 * <ul>
 *   <li>{@link #MYSQL}：反引号、{@code ||}=OR、LIMIT/OFFSET；别名含 MariaDB/GBase/TiDB 等</li>
 *   <li>{@link #POSTGRES}：双引号、{@code ||}=拼接、LIMIT/OFFSET 与 FETCH；别名含 Gauss/Greenplum</li>
 *   <li>{@link #ORACLE}：双引号、拼接、FETCH FIRST 与 ROWNUM；别名含达梦/Oscar</li>
 *   <li>{@link #SQLSERVER}：方括号、TOP 与 OFFSET FETCH；别名含 mssql/tsql</li>
 *   <li>{@link #ANSI} / {@link #H2}：双引号、拼接、LIMIT/OFFSET（H2 兼认 {@code #} 注释）</li>
 * </ul>
 *
 * <p>名称别名见 {@link #fromName(String)}：无法识别时默认 {@link #MYSQL}。</p>
 *
 * @author 郑明亮
 * @since 2.1.0
 */
public enum SqlDialect {
    /**
     * SQL-92 / ANSI：双引号是标识符，{@code ||} 是拼接。
     */
    ANSI,
    /**
     * MySQL / MariaDB / GBase / TiDB：反引号标识符，默认双引号是字符串，{@code ||} 是 OR。
     */
    MYSQL,
    /**
     * PostgreSQL / GaussDB / Greenplum：双引号标识符，{@code ||} 拼接，LIMIT/OFFSET。
     */
    POSTGRES,
    /**
     * Oracle / 达梦：双引号标识符，{@code ||} 拼接，ROWNUM / FETCH。
     */
    ORACLE,
    /**
     * SQL Server：方括号标识符，TOP / OFFSET FETCH。
     */
    SQLSERVER,
    /**
     * H2：接近 ANSI，兼有 MySQL 与 PG 的常见写法。
     */
    H2;

    /**
     * 按名称解析方言，无法识别时返回 {@link #MYSQL}。
     *
     * @param name 方言名，null 或空白视为 mysql
     * @return 方言
     */
    public static SqlDialect fromName(String name) {
        if (name == null) {
            return MYSQL;
        }
        String n = name.trim().toLowerCase();
        if (n.isEmpty() || "mysql".equals(n) || "mysql8".equals(n) || "mariadb".equals(n)
                || "maria".equals(n) || "gbase".equals(n) || "tidb".equals(n)
                || "oceanbase".equals(n) || "polardb".equals(n) || "starrocks".equals(n)
                || "doris".equals(n) || "percona".equals(n) || "singlestore".equals(n)
                || "memsql".equals(n) || "tdsql".equals(n) || "greatsql".equals(n)) {
            return MYSQL;
        }
        if ("postgres".equals(n) || "postgresql".equals(n) || "pgsql".equals(n)
                || "gauss".equals(n) || "gaussdb".equals(n) || "greenplum".equals(n)
                || "kingbase".equals(n) || "opengauss".equals(n) || "cockroach".equals(n)
                || "cockroachdb".equals(n) || "redshift".equals(n)) {
            return POSTGRES;
        }
        if ("oracle".equals(n) || "dm".equals(n) || "dameng".equals(n) || "oscar".equals(n)
                || "oceanbase_oracle".equals(n)) {
            return ORACLE;
        }
        if ("sqlserver".equals(n) || "mssql".equals(n) || "sqlserver2012".equals(n)
                || "tsql".equals(n) || "sybase".equals(n) || "azure".equals(n)
                || "azuresql".equals(n)) {
            return SQLSERVER;
        }
        if ("ansi".equals(n) || "sql92".equals(n) || "standard".equals(n)
                || "sqlite".equals(n) || "db2".equals(n) || "snowflake".equals(n)) {
            return ANSI;
        }
        if ("h2".equals(n)) {
            return H2;
        }
        return MYSQL;
    }

    /**
     * @return 标识符左引号
     */
    public char identQuoteOpen() {
        switch (this) {
            case MYSQL:
                return '`';
            case SQLSERVER:
                return '[';
            case ANSI:
            case POSTGRES:
            case ORACLE:
            case H2:
            default:
                return '"';
        }
    }

    /**
     * @return 标识符右引号
     */
    public char identQuoteClose() {
        return this == SQLSERVER ? ']' : identQuoteOpen();
    }

    /**
     * 用本方言引号包裹标识符（右引号在字面量内加倍转义）。
     *
     * @param name 裸标识符，null 返回 null
     * @return 带引号文本
     */
    public String quoteIdent(String name) {
        if (name == null) {
            return null;
        }
        char open = identQuoteOpen();
        char close = identQuoteClose();
        String escaped = name.replace(String.valueOf(close), String.valueOf(close) + close);
        return open + escaped + close;
    }

    /**
     * MySQL 默认把 {@code ||} 当 OR；其余方言当拼接。
     *
     * @return 是否把 {@code ||} 解析为逻辑或
     */
    public boolean pipesAsOr() {
        return this == MYSQL;
    }

    /**
     * {@link #pipesAsOr()} 的反义：{@code ||} 是否表示字符串拼接。
     *
     * @return 是否拼接
     */
    public boolean pipesAreConcat() {
        return !pipesAsOr();
    }

    /**
     * @return 双引号是否当作字符串（MySQL 默认开启；ANSI_QUOTES 关闭）
     */
    public boolean doubleQuoteIsString() {
        return this == MYSQL;
    }

    /**
     * @return 是否识别 {@code #} 行注释
     */
    public boolean hashLineComment() {
        return this == MYSQL || this == H2;
    }

    /**
     * 是否原生支持 {@code LIMIT … [OFFSET …]} / {@code LIMIT offset, count}。
     *
     * @return true 表示改写宜写 LIMIT
     */
    public boolean supportsLimitOffset() {
        return this == MYSQL || this == POSTGRES || this == H2 || this == ANSI;
    }

    /**
     * 是否用 {@code SELECT TOP n} 表达行数上限（SQL Server）。
     *
     * @return true 表示改写宜写 TOP
     */
    public boolean supportsTop() {
        return this == SQLSERVER;
    }

    /**
     * 是否支持 {@code OFFSET … FETCH FIRST/NEXT … ROWS ONLY}。
     *
     * @return true 时分页可用 FETCH 风格
     */
    public boolean supportsFetchFirst() {
        return this == ORACLE || this == POSTGRES || this == SQLSERVER
                || this == ANSI || this == H2;
    }

    /**
     * 是否习惯用 Oracle {@code ROWNUM} 伪列分页（解析器仍接受，改写优先 FETCH）。
     *
     * @return true 表示 Oracle 族
     */
    public boolean supportsRownum() {
        return this == ORACLE;
    }

    /**
     * {@link com.alianga.jkit.sql.SqlRewriter#addLimit} 一类「只补行数」的首选形态。
     * SQL Server → {@code TOP}；其余（含 Oracle）→ {@code LIMIT}。带 offset 的分页见
     * {@link #supportsFetchFirst()} / {@link #supportsLimitOffset()}。
     *
     * @return {@code "TOP"} 或 {@code "LIMIT"}
     */
    public String preferredLimitStyle() {
        return supportsTop() ? "TOP" : "LIMIT";
    }
}
