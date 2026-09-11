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
        if (u.startsWith("jdbc:dm:") || u.startsWith("jdbc:oscar:")) {
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
}
