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
 *   <li>{@link #ORACLE}：经典 Oracle ≤11g / 达梦 / Oscar — 双引号、拼接、仅 ROWNUM 分页（无 OFFSET/FETCH）</li>
 *   <li>{@link #ORACLE12}：Oracle 12c+ — 同引号/拼接，裸 SELECT 可用 OFFSET/FETCH，仍识别 ROWNUM 包装</li>
 *   <li>{@link #SQLSERVER}：方括号、TOP 与 OFFSET FETCH；别名含 mssql/tsql</li>
 *   <li>{@link #ANSI} / {@link #H2}：双引号、拼接、LIMIT/OFFSET（H2 兼认 {@code #} 注释）</li>
 *   <li>{@link #DB2}：双引号、拼接、仅 {@code FETCH FIRST} 分页（无 LIMIT/ROWNUM）</li>
 *   <li>{@link #SQLITE}：双引号、拼接、LIMIT/OFFSET，无 FETCH FIRST；别名含 presto/trino、gbase8s</li>
 *   <li>{@link #HIVE}：反引号、拼接、LIMIT（无 OFFSET）；别名含 maxcompute/odps、argo(argodb)</li>
 *   <li>{@link #CLICKHOUSE}：反引号、双引号也是标识符、拼接、LIMIT 含逗号风格</li>
 * </ul>
 *
 * <p>名称别名见 {@link #fromName(String)}：无法识别时默认 {@link #MYSQL}。</p>
 *
 * <p>本枚举实现 {@link SqlDialectSpec}；需要微调个别能力时用 {@link SqlDialectWrapper}
 * 包装，不要往枚举里加一次性变体。</p>
 *
 * @author 郑明亮
 * @since 2.0.1
 */
public enum SqlDialect implements SqlDialectSpec {
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
     * 经典 Oracle（11g 及以下）/ 达梦 / Oscar：双引号标识符，{@code ||} 拼接；
     * 分页一律 ROWNUM 包装，不生成 {@code OFFSET … FETCH}。
     */
    ORACLE,
    /**
     * Oracle 12c 及以上：引号与 {@code ||} 同 {@link #ORACLE}；
     * 裸 SELECT 分页可用 {@code OFFSET … FETCH FIRST … ROWS ONLY}，仍识别已有 ROWNUM 包装。
     */
    ORACLE12,
    /**
     * SQL Server：方括号标识符，TOP / OFFSET FETCH。
     */
    SQLSERVER,
    /**
     * H2：接近 ANSI，兼有 MySQL 与 PG 的常见写法。
     */
    H2,
    /**
     * DB2（LUW）：双引号标识符，{@code ||} 拼接；分页用 {@code FETCH FIRST n ROWS ONLY}，
     * 不用 LIMIT，也不生成 ROWNUM 包装。
     */
    DB2,
    /**
     * SQLite：双引号标识符（亦接受反引号），{@code ||} 拼接，LIMIT/OFFSET；
     * 不支持 {@code FETCH FIRST}。
     */
    SQLITE,
    /**
     * Hive / MaxCompute（ODPS）：反引号标识符，双引号是字符串，{@code ||} 拼接；
     * 分页仅 {@code LIMIT n}（无 OFFSET）。
     */
    HIVE,
    /**
     * ClickHouse：反引号标识符（双引号同），{@code ||} 拼接；
     * {@code LIMIT n} / {@code LIMIT offset, count} / {@code LIMIT n OFFSET m}。
     */
    CLICKHOUSE,
    /**
     * Presto / Trino：双引号标识符，{@code ||} 拼接，LIMIT；
     * 不支持 {@code FETCH FIRST}（Trino 部分版本有 OFFSET）。
     */
    PRESTO;

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
                || "memsql".equals(n) || "tdsql".equals(n) || "greatsql".equals(n)
                || "goldendb".equals(n) || "adb".equals(n) || "analyticdb".equals(n)
                || "ads".equals(n) || "selectdb".equals(n) || "matrixone".equals(n)
                || "stonedb".equals(n) || "gbase8a".equals(n)) {
            return MYSQL;
        }
        if ("postgres".equals(n) || "postgresql".equals(n) || "pgsql".equals(n)
                || "gauss".equals(n) || "gaussdb".equals(n) || "greenplum".equals(n)
                || "kingbase".equals(n) || "opengauss".equals(n) || "cockroach".equals(n)
                || "cockroachdb".equals(n) || "redshift".equals(n)
                || "highgo".equals(n) || "uxdb".equals(n) || "mogdb".equals(n)
                || "vastbase".equals(n) || "antdb".equals(n) || "ivorysql".equals(n)
                || "xcloud".equals(n)) {
            return POSTGRES;
        }
        if ("oracle12".equals(n) || "oracle12c".equals(n) || "12c".equals(n)
                || "oracle18".equals(n) || "oracle19".equals(n) || "oracle21".equals(n)
                || "19c".equals(n) || "21c".equals(n)) {
            return ORACLE12;
        }
        if ("oracle".equals(n) || "oracle11".equals(n) || "oracle10".equals(n) || "11g".equals(n)
                || "dm".equals(n) || "dameng".equals(n) || "oscar".equals(n)
                || "oceanbase_oracle".equals(n)) {
            return ORACLE;
        }
        if ("sqlserver".equals(n) || "mssql".equals(n) || "sqlserver2012".equals(n)
                || "tsql".equals(n) || "sybase".equals(n) || "azure".equals(n)
                || "azuresql".equals(n)) {
            return SQLSERVER;
        }
        if ("db2".equals(n) || "db2luw".equals(n)) {
            return DB2;
        }
        if ("sqlite".equals(n) || "sqlite3".equals(n) || "gbase8s".equals(n)) {
            return SQLITE;
        }
        if ("hive".equals(n) || "hive2".equals(n) || "hive3".equals(n)
                || "maxcompute".equals(n) || "odps".equals(n)
                || "argo".equals(n) || "argodb".equals(n)) {
            return HIVE;
        }
        if ("clickhouse".equals(n) || "ck".equals(n) || "ch".equals(n)) {
            return CLICKHOUSE;
        }
        if ("presto".equals(n) || "prestodb".equals(n) || "trino".equals(n)) {
            return PRESTO;
        }
        if ("ansi".equals(n) || "sql92".equals(n) || "standard".equals(n)
                || "snowflake".equals(n)) {
            return ANSI;
        }
        if ("h2".equals(n)) {
            return H2;
        }
        return MYSQL;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public String dialectId() {
        return name();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public SqlDialect typeFamily() {
        return this;
    }

    /**
     * @return 标识符左引号
     */
    public char identQuoteOpen() {
        switch (this) {
            case MYSQL:
            case HIVE:
            case CLICKHOUSE:
                return '`';
            case SQLSERVER:
                return '[';
            case ANSI:
            case POSTGRES:
            case ORACLE:
            case ORACLE12:
            case H2:
            case DB2:
            case SQLITE:
            case PRESTO:
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
        return this == MYSQL || this == HIVE;
    }

    /**
     * @return 字符串字面量内 {@code \} 是否为转义前缀（MySQL；NO_BACKSLASH_ESCAPES 时应为 false）
     */
    public boolean backslashEscapes() {
        return this == MYSQL || this == HIVE;
    }

    /**
     * @return 方括号 {@code [name]} 是否为标识符引号（SQL Server）
     */
    public boolean bracketIdentifiers() {
        return this == SQLSERVER;
    }

    /**
     * @return 裸 {@code ~} 是否为正则匹配操作符（PostgreSQL / H2）
     */
    public boolean supportsTildeRegex() {
        return this == POSTGRES || this == H2;
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
        return this == MYSQL || this == POSTGRES || this == H2 || this == ANSI
                || this == SQLITE || this == HIVE || this == CLICKHOUSE || this == PRESTO;
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
        return this == ORACLE12 || this == POSTGRES || this == SQLSERVER
                || this == ANSI || this == H2 || this == DB2;
    }

    /**
     * 是否习惯用 Oracle {@code ROWNUM} 伪列分页。
     * {@link #ORACLE} 改写裸 SELECT 时生成 ROWNUM 包装；{@link #ORACLE12} 仍识别/改写已有包装。
     *
     * @return true 表示 Oracle 族（含 12c+）
     */
    public boolean supportsRownum() {
        return this == ORACLE || this == ORACLE12;
    }

    /**
     * 带 offset 的分页是否写 {@code LIMIT offset, count} 逗号风格（MySQL）。
     *
     * @return true 时改写优先逗号风格
     */
    public boolean supportsCommaLimitOffset() {
        return this == MYSQL || this == CLICKHOUSE;
    }

    /**
     * {@link com.alianga.jkit.sql.SqlRewriter#addLimit} 一类「只补行数」的首选形态。
     * SQL Server → {@code TOP}；经典 Oracle → {@code ROWNUM}；其余 → {@code LIMIT}。
     * 带 offset 的分页见 {@link #supportsFetchFirst()} / {@link #supportsLimitOffset()}。
     *
     * @return {@code "TOP"}、{@code "ROWNUM"} 或 {@code "LIMIT"}
     */
    public String preferredLimitStyle() {
        if (supportsTop()) {
            return "TOP";
        }
        if (supportsRownum() && !supportsFetchFirst()) {
            return "ROWNUM";
        }
        return "LIMIT";
    }
}
