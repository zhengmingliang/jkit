package com.alianga.jkit.sql.jdbc;

import com.alianga.jkit.sql.SqlDialect;

import java.util.Locale;

/**
 * JDBC URL 工具：解析主机/库/schema、推断方言与驱动类名。
 *
 * @author 郑明亮
 * @since 2.0.2
 */
public final class JdbcUrlUtils {
    private JdbcUrlUtils() {
    }

    /**
     * 解析 JDBC URL。无法识别协议时抛 {@link IllegalArgumentException}。
     *
     * @param url JDBC URL
     * @return 解析结果
     */
    public static JdbcUrlInfo parse(String url) {
        if (url == null || url.trim().isEmpty()) {
            throw new IllegalArgumentException("JDBC URL cannot be null or empty");
        }
        String trimmed = url.trim();
        if (!startsWithIgnoreCase(trimmed, "jdbc:")) {
            throw new IllegalArgumentException("not a JDBC URL: " + url);
        }
        JdbcUrlInfo info = JdbcUrlParsers.parse(trimmed);
        fillSchema(info);
        return info;
    }

    /**
     * 解析失败时返回 null，不抛错。
     *
     * @param url JDBC URL，可空
     * @return 解析结果，无法解析时 null
     */
    public static JdbcUrlInfo tryParse(String url) {
        if (url == null || url.trim().isEmpty()) {
            return null;
        }
        try {
            return parse(url);
        } catch (RuntimeException e) {
            return null;
        }
    }

    /**
     * 从 URL 取 schema。PostgreSQL 系读 {@code currentSchema}（缺省 {@code public}），
     * SQL Server 缺省 {@code dbo}，达梦读 {@code schema} 参数。
     *
     * @param url JDBC URL，可空
     * @return schema，无法取得时 null
     */
    public static String schema(String url) {
        JdbcUrlInfo info = tryParse(url);
        if (info == null) {
            return null;
        }
        String s = info.getSchema();
        return s == null || s.isEmpty() ? null : s;
    }

    /**
     * 从 URL 取库名 / SID / Service Name。
     *
     * @param url JDBC URL，可空
     * @return 库名，无法取得时 null
     */
    public static String databaseName(String url) {
        JdbcUrlInfo info = tryParse(url);
        if (info == null) {
            return null;
        }
        String n = info.getDatabaseName();
        return n == null || n.isEmpty() ? null : n;
    }

    /**
     * 按 JDBC URL 推断 {@link SqlDialect}。无法识别时返回 null（与
     * {@link SqlDialect#fromName(String)} 把未知名回落 MySQL 不同）。
     *
     * @param url JDBC URL，可空
     * @return 方言，无法判断时 null
     */
    public static SqlDialect fromUrl(String url) {
        String type = getDbType(url);
        if (type == null || !hasDialect(type)) {
            return null;
        }
        return SqlDialect.fromName(type);
    }

    /**
     * 按 JDBC URL 识别数据库类型短名，如 {@code mysql}、{@code postgresql}、{@code gaussdb}。
     *
     * @param url JDBC URL，可空
     * @return 类型名，无法识别时 null
     */
    public static String getDbType(String url) {
        if (url == null) {
            return null;
        }
        String u = url.trim().toLowerCase(Locale.ROOT);
        if (!u.startsWith("jdbc:")) {
            return null;
        }
        if (u.startsWith("jdbc:log4jdbc:")) {
            u = "jdbc:" + u.substring("jdbc:log4jdbc:".length());
        }
        if (u.startsWith("jdbc:mysql:") || u.startsWith("jdbc:cobar:") || u.startsWith("jdbc:goldendb:")
                || u.startsWith("jdbc:mariadb:") || u.startsWith("jdbc:tidb:")
                || u.startsWith("jdbc:gbase:") || u.startsWith("jdbc:gbase8a:")
                || (u.startsWith("jdbc:oceanbase:") && !u.startsWith("jdbc:oceanbase:oracle:"))
                || u.startsWith("jdbc:polardb") || u.startsWith("jdbc:starrocks:")
                || u.startsWith("jdbc:doris:")) {
            return "mysql";
        }
        if (u.startsWith("jdbc:oceanbase:oracle:")) {
            return "oracle";
        }
        if (u.startsWith("jdbc:postgresql:") || u.startsWith("jdbc:pgsql:") || u.startsWith("jdbc:edb:")) {
            return "postgresql";
        }
        if (u.startsWith("jdbc:gaussdb:") || u.startsWith("jdbc:opengauss:")
                || u.startsWith("jdbc:dws:iam:")) {
            return "gaussdb";
        }
        if (u.startsWith("jdbc:kingbase:") || u.startsWith("jdbc:kingbase8:")) {
            return "kingbase";
        }
        if (u.startsWith("jdbc:highgo:")) {
            return "highgo";
        }
        if (u.startsWith("jdbc:pivotal:greenplum:") || u.startsWith("jdbc:datadirect:greenplum:")) {
            return "greenplum";
        }
        if (u.startsWith("jdbc:oracle:") || u.startsWith("jdbc:alibaba:oracle:")
                || u.startsWith("jdbc:oscar")) {
            return "oracle";
        }
        if (u.startsWith("jdbc:sqlserver:") || u.startsWith("jdbc:microsoft:")
                || u.startsWith("jdbc:jtds:") || u.startsWith("jdbc:sybase:")) {
            return "sqlserver";
        }
        if (u.startsWith("jdbc:h2:")) {
            return "h2";
        }
        if (u.startsWith("jdbc:sqlite:")) {
            return "sqlite";
        }
        if (u.startsWith("jdbc:db2:")) {
            return "db2";
        }
        if (u.startsWith("jdbc:hive:") || u.startsWith("jdbc:hive2:") || u.startsWith("jdbc:odps:")
                || u.startsWith("jdbc:transwarp2:")) {
            return "hive";
        }
        if (u.startsWith("jdbc:clickhouse:")) {
            return "clickhouse";
        }
        if (u.startsWith("jdbc:presto:")) {
            return "presto";
        }
        if (u.startsWith("jdbc:trino:")) {
            return "trino";
        }
        if (u.startsWith("jdbc:dm:")) {
            return "dm";
        }
        if (u.startsWith("jdbc:derby:")) {
            return "derby";
        }
        if (u.startsWith("jdbc:hsqldb:")) {
            return "hsqldb";
        }
        if (u.startsWith("jdbc:duckdb:")) {
            return "duckdb";
        }
        return null;
    }

    /**
     * 按 JDBC URL 猜测驱动类名。未知协议返回 null。
     *
     * <p>MySQL 优先 {@code com.mysql.cj.jdbc.Driver}，类路径没有再回落 5.x。
     * ClickHouse 优先新包名 {@code com.clickhouse.jdbc.ClickHouseDriver}。</p>
     *
     * @param url JDBC URL，可空
     * @return 驱动类名，未知则 null
     */
    public static String driverForUrl(String url) {
        if (url == null) {
            return null;
        }
        String u = url.trim().toLowerCase(Locale.ROOT);
        if (u.startsWith("jdbc:mysql:") || u.startsWith("jdbc:tidb:") || u.startsWith("jdbc:polardb")) {
            return classPresent("com.mysql.cj.jdbc.Driver")
                    ? "com.mysql.cj.jdbc.Driver" : "com.mysql.jdbc.Driver";
        }
        if (u.startsWith("jdbc:mariadb:")) {
            return "org.mariadb.jdbc.Driver";
        }
        if (u.startsWith("jdbc:postgresql:") || u.startsWith("jdbc:pgsql:") || u.startsWith("jdbc:edb:")) {
            return "org.postgresql.Driver";
        }
        if (u.startsWith("jdbc:gaussdb:") || u.startsWith("jdbc:opengauss:")) {
            return "org.opengauss.Driver";
        }
        if (u.startsWith("jdbc:oracle:") || u.startsWith("jdbc:alibaba:oracle:")) {
            return "oracle.jdbc.OracleDriver";
        }
        if (u.startsWith("jdbc:sqlserver:")) {
            return "com.microsoft.sqlserver.jdbc.SQLServerDriver";
        }
        if (u.startsWith("jdbc:microsoft:")) {
            return "com.microsoft.jdbc.sqlserver.SQLServerDriver";
        }
        if (u.startsWith("jdbc:jtds:")) {
            return "net.sourceforge.jtds.jdbc.Driver";
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
        if (u.startsWith("jdbc:duckdb:")) {
            return "org.duckdb.DuckDBDriver";
        }
        if (u.startsWith("jdbc:derby:")) {
            return "org.apache.derby.jdbc.EmbeddedDriver";
        }
        if (u.startsWith("jdbc:hsqldb:")) {
            return "org.hsqldb.jdbcDriver";
        }
        if (u.startsWith("jdbc:hive:") || u.startsWith("jdbc:hive2:")) {
            return "org.apache.hive.jdbc.HiveDriver";
        }
        if (u.startsWith("jdbc:clickhouse:")) {
            return classPresent("com.clickhouse.jdbc.ClickHouseDriver")
                    ? "com.clickhouse.jdbc.ClickHouseDriver"
                    : "ru.yandex.clickhouse.ClickHouseDriver";
        }
        if (u.startsWith("jdbc:presto:")) {
            return "com.facebook.presto.jdbc.PrestoDriver";
        }
        if (u.startsWith("jdbc:trino:")) {
            return "io.trino.jdbc.TrinoDriver";
        }
        if (u.startsWith("jdbc:kingbase8:")) {
            return "com.kingbase8.Driver";
        }
        if (u.startsWith("jdbc:kingbase:")) {
            return "com.kingbase.Driver";
        }
        if (u.startsWith("jdbc:highgo:")) {
            return "com.highgo.jdbc.Driver";
        }
        if (u.startsWith("jdbc:oscar")) {
            return "com.oscar.Driver";
        }
        if (u.startsWith("jdbc:oceanbase:")) {
            return "com.oceanbase.jdbc.Driver";
        }
        if (u.startsWith("jdbc:odps:")) {
            return "com.aliyun.odps.jdbc.OdpsDriver";
        }
        return null;
    }

    /**
     * {@link #driverForUrl(String)} 的别名。
     *
     * @param url JDBC URL，可空
     * @return 驱动类名，未知则 null
     */
    public static String getDriverClassName(String url) {
        return driverForUrl(url);
    }

    static void fillSchema(JdbcUrlInfo info) {
        if (info.getSchema() != null && !info.getSchema().isEmpty()) {
            return;
        }
        String type = info.getDbType();
        if (type == null) {
            return;
        }
        String t = type.toLowerCase(Locale.ROOT);
        if (isPostgresFamily(t)) {
            String s = firstToken(firstParam(info, "currentSchema", "search_path", "searchpath"));
            info.setSchema(s != null ? s : "public");
            return;
        }
        if ("sqlserver".equals(t) || "jtds".equals(t)) {
            String s = firstParam(info, "schema", "currentSchema");
            info.setSchema(s != null ? s : "dbo");
            return;
        }
        if ("dm".equals(t) || "dameng".equals(t)) {
            info.setSchema(firstParam(info, "schema", "currentSchema"));
            return;
        }
        if ("h2".equals(t) || "hsqldb".equals(t) || "derby".equals(t)) {
            info.setSchema(firstParam(info, "SCHEMA", "schema"));
        }
    }

    private static boolean isPostgresFamily(String t) {
        return "postgresql".equals(t) || "pgsql".equals(t) || "postgres".equals(t)
                || "gaussdb".equals(t) || "opengauss".equals(t) || "gauss".equals(t)
                || "kingbase".equals(t) || "kingbase8".equals(t) || "highgo".equals(t)
                || "greenplum".equals(t) || "edb".equals(t);
    }

    private static boolean hasDialect(String type) {
        String n = type.toLowerCase(Locale.ROOT);
        return "mysql".equals(n) || "mariadb".equals(n) || "tidb".equals(n) || "gbase".equals(n)
                || "gbase8a".equals(n) || "oceanbase".equals(n) || "polardb".equals(n)
                || "postgresql".equals(n) || "pgsql".equals(n) || "gaussdb".equals(n)
                || "opengauss".equals(n) || "kingbase".equals(n) || "highgo".equals(n)
                || "greenplum".equals(n) || "oracle".equals(n) || "oscar".equals(n)
                || "sqlserver".equals(n) || "h2".equals(n) || "sqlite".equals(n)
                || "db2".equals(n) || "hive".equals(n) || "clickhouse".equals(n)
                || "presto".equals(n) || "trino".equals(n) || "dm".equals(n)
                || "dameng".equals(n);
    }

    private static String firstParam(JdbcUrlInfo info, String... keys) {
        for (int i = 0; i < keys.length; i++) {
            String v = info.parameterIgnoreCase(keys[i]);
            if (v != null && !v.isEmpty()) {
                return v;
            }
        }
        return null;
    }

    private static String firstToken(String s) {
        if (s == null) {
            return null;
        }
        int c = s.indexOf(',');
        String t = (c < 0 ? s : s.substring(0, c)).trim();
        return t.isEmpty() ? null : t;
    }

    private static boolean startsWithIgnoreCase(String s, String prefix) {
        return s.regionMatches(true, 0, prefix, 0, prefix.length());
    }

    private static boolean classPresent(String name) {
        ClassLoader cl = Thread.currentThread().getContextClassLoader();
        if (cl != null) {
            try {
                Class.forName(name, false, cl);
                return true;
            } catch (ClassNotFoundException e) {
                // 再试本类加载器
            }
        }
        try {
            Class.forName(name, false, JdbcUrlUtils.class.getClassLoader());
            return true;
        } catch (ClassNotFoundException e) {
            return false;
        }
    }
}
