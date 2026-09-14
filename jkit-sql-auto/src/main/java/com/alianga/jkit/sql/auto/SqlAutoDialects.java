package com.alianga.jkit.sql.auto;

import com.alianga.jkit.sql.SqlDialect;
import com.alianga.jkit.sql.jdbc.JdbcUrlUtils;

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
        return JdbcUrlUtils.fromUrl(url);
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
        return JdbcUrlUtils.driverForUrl(url);
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
