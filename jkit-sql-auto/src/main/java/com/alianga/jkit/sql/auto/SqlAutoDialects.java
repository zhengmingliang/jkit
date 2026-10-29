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
     * 按 {@link DatabaseMetaData#getDatabaseProductName()} 的产品名推断方言。
     *
     * <p>产品名通常是<b>展示名</b>而非短名：{@code "PostgreSQL"}、{@code "Microsoft SQL Server"}、
     * {@code "KingbaseES"}、{@code "OceanBase"}、{@code "Oracle"}、{@code "H2"} 等。与
     * {@link SqlDialect#fromName(String)}（只认精确短名）不同，这里会先做归一化
     * （小写、折叠空白与下划线），再做包含匹配，避免把 {@code "KingbaseES"} 这类合法产品名
     * 误判成 MySQL 后生成 {@code AUTO_INCREMENT}/{@code ENGINE=InnoDB} 等 MySQL 专有 DDL。</p>
     *
     * <p>无法识别时按历史行为回落 {@link SqlDialect#MYSQL}。需要"无法识别即失败"的调用方请用
     * {@link #fromProductStrict(String)}。</p>
     *
     * @param product 产品名
     * @return 方言，无法识别时 MySQL
     */
    public static SqlDialect fromProduct(String product) {
        SqlDialect d = matchProduct(product);
        return d == null ? SqlDialect.MYSQL : d;
    }

    /**
     * 与 {@link #fromProduct(String)} 识别逻辑相同，但产品名无法识别时返回 {@code null}
     * 而不是静默回落 MySQL。
     *
     * <p>用于 DDL 规划/执行这类"猜错方言会写出不可执行 SQL"的场景：调用方应据此提示
     * 用户显式指定 {@code options.dialect(...)}。</p>
     *
     * @param product 产品名
     * @return 方言，无法识别时 null
     */
    public static SqlDialect fromProductStrict(String product) {
        return matchProduct(product);
    }

    /**
     * 产品名 → 方言的匹配主体。返回 null 表示无法识别（不发散到默认值，交由调用方决定）。
     *
     * @param product 产品名
     * @return 方言或 null
     */
    private static SqlDialect matchProduct(String product) {
        if (product == null || product.trim().isEmpty()) {
            return null;
        }
        // 归一化：小写 + 折叠连续空白，便于 "Microsoft  SQL Server" 之类也能命中。
        String raw = product.trim().toLowerCase(Locale.ROOT).replaceAll("\\s+", " ");
        // 紧凑形式：去掉空格与下划线，处理 "kingbase es" / "open gauss" /
        // "oceanbase_oracle" / "sql_server" 等写法差异。下划线必须一并折叠，
        // 否则 compact.contains("oceanbaseoracle") 这类分支永远匹配不到。
        String compact = raw.replace(" ", "").replace("_", "");

        if (compact.contains("dameng") || raw.contains("dm dbms") || raw.contains("dm database")) {
            return SqlDialect.DAMENG;
        }
        // 先判 Oracle 专有分支，避免 "oceanbase_oracle" 被 MYSQL 分支的 oceanbase 抢走。
        if (compact.contains("oceanbaseoracle")) {
            return SqlDialect.ORACLE;
        }
        if (compact.contains("kingbase") || compact.contains("opengauss")
                || compact.contains("gaussdb") || compact.contains("greenplum")
                || compact.contains("highgo") || compact.contains("vastbase")
                || compact.contains("mogdb") || compact.contains("uxdb")
                || compact.contains("cockroach") || compact.contains("redshift")
                || compact.contains("postgres") || compact.contains("pgsql")
                || compact.contains("ivorysql") || compact.contains("antdb")) {
            return SqlDialect.POSTGRES;
        }
        if (compact.contains("sqlserver") || raw.contains("sql server")
                || compact.contains("mssql") || compact.contains("sybase")) {
            return SqlDialect.SQLSERVER;
        }
        if (compact.contains("db2")) {
            return SqlDialect.DB2;
        }
        if (compact.contains("clickhouse")) {
            return SqlDialect.CLICKHOUSE;
        }
        if (compact.contains("presto") || compact.contains("trino")) {
            return SqlDialect.PRESTO;
        }
        if (compact.contains("sqlite")) {
            return SqlDialect.SQLITE;
        }
        if (compact.contains("hive") || compact.contains("maxcompute") || compact.contains("odps")) {
            return SqlDialect.HIVE;
        }
        if (compact.contains("oracle")) {
            return SqlDialect.ORACLE;   // 版本号升级到 ORACLE12 由 fromMeta 负责
        }
        // MySQL 家族最后判（"oceanbase"/"polardb"/"gbase"/"tidb" 等都很宽松）。
        if (compact.contains("mysql") || compact.contains("mariadb")
                || compact.contains("oceanbase") || compact.contains("polardb")
                || compact.contains("gbase") || compact.contains("tidb")
                || compact.contains("starrocks") || compact.contains("doris")
                || compact.contains("greatsql") || compact.contains("tdsql")
                || compact.contains("goldendb")) {
            return SqlDialect.MYSQL;
        }
        if (compact.equals("h2")) {
            return SqlDialect.H2;
        }
        if (compact.contains("snowflake") || compact.contains("ansi")) {
            return SqlDialect.ANSI;
        }
        // 兜底：交给短名精确匹配（覆盖 "H2"、"Presto" 等单字产品名）。
        SqlDialect byName = SqlDialect.fromName(product);
        // fromName 无法识别时也返回 MYSQL，无法区分"真的是 MySQL"和"未知"，
        // 因此这里的判断用紧凑名再排除一次 MySQL 家族。
        if (byName == SqlDialect.MYSQL && !compact.contains("mysql")) {
            return null;
        }
        return byName;
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
