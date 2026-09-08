package com.alianga.jkit.sql;

/**
 * SQL 方言。决定标识符引号、字符串引号、{@code ||} 语义、LIMIT/TOP/ROWNUM 写法。
 *
 * <p>名称别名见 {@link #fromName(String)}：GBase / MariaDB / TiDB 走 {@link #MYSQL}，
 * 高斯 / Greenplum 走 {@link #POSTGRES}，达梦走 {@link #ORACLE}。</p>
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
        if (n.isEmpty() || "mysql".equals(n) || "mariadb".equals(n) || "maria".equals(n)
                || "gbase".equals(n) || "tidb".equals(n) || "oceanbase".equals(n)
                || "polardb".equals(n) || "starrocks".equals(n) || "doris".equals(n)) {
            return MYSQL;
        }
        if ("postgres".equals(n) || "postgresql".equals(n) || "pgsql".equals(n)
                || "gauss".equals(n) || "gaussdb".equals(n) || "greenplum".equals(n)
                || "kingbase".equals(n) || "opengauss".equals(n)) {
            return POSTGRES;
        }
        if ("oracle".equals(n) || "dm".equals(n) || "dameng".equals(n) || "oscar".equals(n)) {
            return ORACLE;
        }
        if ("sqlserver".equals(n) || "mssql".equals(n) || "sqlserver2012".equals(n)
                || "tsql".equals(n)) {
            return SQLSERVER;
        }
        if ("ansi".equals(n) || "sql92".equals(n) || "standard".equals(n)) {
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
     * MySQL 默认把 {@code ||} 当 OR；其余方言当拼接。
     *
     * @return 是否把 {@code ||} 解析为逻辑或
     */
    public boolean pipesAsOr() {
        return this == MYSQL;
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
}
