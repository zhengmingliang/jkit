package com.alianga.jkit.sql.auto;

import com.alianga.jkit.sql.SqlDialect;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.SQLException;
import java.util.Locale;

/**
 * 从 JDBC URL / {@link DatabaseMetaData} 推断 {@link SqlDialect}。
 *
 * @author 郑明亮
 * @since 2.0.1
 */
public final class SqlAutoDialects {
    private SqlAutoDialects() {
    }

    /**
     * 优先用选项里的方言，否则 URL，再否则连接元数据。
     *
     * @param options 选项
     * @param connection 已打开连接，可空
     * @return 方言，无法判断时 MySQL
     */
    public static SqlDialect resolve(SqlAutoOptions options, Connection connection) {
        if (options != null && options.dialect() != null) {
            return options.dialect();
        }
        String url = options == null ? null : options.url();
        SqlDialect fromUrl = fromUrl(url);
        if (fromUrl != null) {
            return fromUrl;
        }
        if (connection != null) {
            try {
                return fromMeta(connection.getMetaData());
            } catch (SQLException ignored) {
                // 回落
            }
        }
        return SqlDialect.MYSQL;
    }

    /**
     * @param url JDBC URL
     * @return 方言，无法判断时 null
     */
    public static SqlDialect fromUrl(String url) {
        if (url == null) {
            return null;
        }
        String u = url.toLowerCase(Locale.ROOT);
        if (!u.startsWith("jdbc:")) {
            return null;
        }
        if (u.startsWith("jdbc:mysql:") || u.startsWith("jdbc:mariadb:") || u.startsWith("jdbc:tidb:")
                || u.startsWith("jdbc:gbase:") || u.startsWith("jdbc:gbase8a:")) {
            return SqlDialect.MYSQL;
        }
        if (u.startsWith("jdbc:postgresql:") || u.startsWith("jdbc:pgsql:")
                || u.startsWith("jdbc:gaussdb:") || u.startsWith("jdbc:opengauss:")
                || u.startsWith("jdbc:kingbase:")) {
            return SqlDialect.POSTGRES;
        }
        if (u.startsWith("jdbc:oracle:")) {
            return SqlDialect.ORACLE;
        }
        if (u.startsWith("jdbc:sqlserver:") || u.startsWith("jdbc:microsoft:sqlserver:")
                || u.startsWith("jdbc:jtds:")) {
            return SqlDialect.SQLSERVER;
        }
        if (u.startsWith("jdbc:h2:")) {
            return SqlDialect.H2;
        }
        if (u.startsWith("jdbc:sqlite:")) {
            return SqlDialect.SQLITE;
        }
        if (u.startsWith("jdbc:db2:")) {
            return SqlDialect.DB2;
        }
        if (u.startsWith("jdbc:hive2:") || u.startsWith("jdbc:hive:")) {
            return SqlDialect.HIVE;
        }
        if (u.startsWith("jdbc:clickhouse:")) {
            return SqlDialect.CLICKHOUSE;
        }
        if (u.startsWith("jdbc:presto:") || u.startsWith("jdbc:trino:")) {
            return SqlDialect.PRESTO;
        }
        if (u.startsWith("jdbc:dm:")) {
            return SqlDialect.DAMENG;
        }
        if (u.startsWith("jdbc:oscar:")) {
            return SqlDialect.ORACLE;
        }
        return null;
    }

    /**
     * @param meta 元数据
     * @return 方言
     */
    public static SqlDialect fromMeta(DatabaseMetaData meta) {
        if (meta == null) {
            return SqlDialect.MYSQL;
        }
        try {
            String product = meta.getDatabaseProductName();
            if (product != null && product.length() > 0) {
                SqlDialect d = fromProduct(product);
                if (d == SqlDialect.ORACLE && meta.getDatabaseMajorVersion() >= 12) {
                    return SqlDialect.ORACLE12;
                }
                return d;
            }
        } catch (SQLException ignored) {
            // 回落
        }
        return SqlDialect.MYSQL;
    }

    /**
     * @param product {@link DatabaseMetaData#getDatabaseProductName()}
     * @return 方言
     */
    public static SqlDialect fromProduct(String product) {
        if (product == null || product.isEmpty()) {
            return SqlDialect.MYSQL;
        }
        String n = product.toLowerCase(Locale.ROOT);
        if (n.contains("dm dbms") || n.contains("dameng") || n.contains("dm database")) {
            return SqlDialect.DAMENG;
        }
        // 产品名是多词串（如 "Microsoft SQL Server"），给 fromName 做短名精确匹配会失败并回落 MySQL
        if (n.contains("sql server") || n.contains("sqlserver")) {
            return SqlDialect.SQLSERVER;
        }
        if (n.contains("db2")) {
            return SqlDialect.DB2;
        }
        return SqlDialect.fromName(product);
    }

    /**
     * 按 URL 猜测驱动类名。
     *
     * @param url JDBC URL
     * @return 类名，未知则 null
     */
    public static String driverForUrl(String url) {
        if (url == null) {
            return null;
        }
        String u = url.toLowerCase(Locale.ROOT);
        if (u.startsWith("jdbc:mysql:")) {
            return "com.mysql.cj.jdbc.Driver";
        }
        if (u.startsWith("jdbc:mariadb:")) {
            return "org.mariadb.jdbc.Driver";
        }
        if (u.startsWith("jdbc:postgresql:") || u.startsWith("jdbc:pgsql:")) {
            return "org.postgresql.Driver";
        }
        if (u.startsWith("jdbc:oracle:")) {
            return "oracle.jdbc.OracleDriver";
        }
        if (u.startsWith("jdbc:sqlserver:")) {
            return "com.microsoft.sqlserver.jdbc.SQLServerDriver";
        }
        if (u.startsWith("jdbc:h2:")) {
            return "org.h2.Driver";
        }
        if (u.startsWith("jdbc:sqlite:")) {
            return "org.sqlite.JDBC";
        }
        if (u.startsWith("jdbc:db2:")) {
            return "com.ibm.db2.jcc.DB2Driver";
        }
        if (u.startsWith("jdbc:dm:")) {
            return "dm.jdbc.driver.DmDriver";
        }
        if (u.startsWith("jdbc:gbase:") || u.startsWith("jdbc:gbase8a:")) {
            return "com.gbase.jdbc.Driver";
        }
        if (u.startsWith("jdbc:gaussdb:") || u.startsWith("jdbc:opengauss:")) {
            return "org.opengauss.Driver";
        }
        if (u.startsWith("jdbc:duckdb:")) {
            return "org.duckdb.DuckDBDriver";
        }
        return null;
    }

    /**
     * 是否 GBase 8a。GBase 8a 是分析型 MPP 引擎，其默认存储引擎不支持二级索引，
     * 在 jkit 内方言归并为 {@link SqlDialect#MYSQL}，故单列此方法识别，供 DDL 规划跳过二级索引。
     *
     * <p>识别依据：JDBC URL 含 {@code gbase}（如 {@code jdbc:gbase:} / {@code jdbc:gbase8a:}）。
     * GBase 8a 必须使用 {@code com.gbase.jdbc.Driver}，URL 必带此前缀，因此可稳定区分 MySQL。</p>
     *
     * @param options 选项
     * @return 是否 GBase 8a
     */
    public static boolean isGbase8a(SqlAutoOptions options) {
        if (options == null) {
            return false;
        }
        if (options.gbase8a()) {
            return true;
        }
        String url = options.url();
        return url != null && url.toLowerCase(Locale.ROOT).contains("gbase");
    }

    /**
     * 是否 GBase 8a，依据连接元数据产品名识别。Spring Boot 集成里选项 URL 可能为空
     * （仅注入了 {@code DataSource}），但连接元数据可稳定取到产品名。
     *
     * @param connection 连接，可空
     * @return 是否 GBase 8a
     */
    public static boolean isGbase8a(Connection connection) {
        if (connection == null) {
            return false;
        }
        try {
            String product = connection.getMetaData().getDatabaseProductName();
            return product != null && product.toLowerCase(Locale.ROOT).contains("gbase");
        } catch (SQLException ignored) {
            return false;
        }
    }
}
