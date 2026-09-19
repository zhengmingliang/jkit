package com.alianga.jkit.sql.auto;

import com.alianga.jkit.log.Log;
import com.alianga.jkit.sql.SqlDialect;

import java.net.InetAddress;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * 把已应用的变更写入历史表（默认表名 {@code jkit_schema_history}），提供审计依据：
 * 什么时候、哪台机器、以什么模式、执行了哪条 DDL。空计划不产生任何行。
 *
 * <p>历史表不存在时自动创建，主键用应用侧生成的 UUID，避免方言相关的自增语法；
 * 时间列用 {@code TIMESTAMP}（Oracle / 达梦等无参即含日期时间）。
 * 历史表本身不会进入同步计划——它不在实体清单里，也不会被 {@code dropExtraColumns} 触碰。</p>
 *
 * @author 郑明亮
 * @since 2.0.2
 */
public final class SqlAutoHistory {
    private static final Log LOG = Log.get(SqlAutoHistory.class);

    private SqlAutoHistory() {
    }

    /**
     * 记录一批已应用的变更。历史表不存在时先创建；写入失败只记日志，不影响主流程。
     *
     * @param connection 连接
     * @param dialect 方言
     * @param historyTable 历史表名
     * @param mode 当前模式名
     * @param applied 实际执行成功的变更
     */
    public static void record(Connection connection, SqlDialect dialect, String historyTable,
                              String mode, List<SqlAutoChange> applied) {
        if (connection == null || applied == null || applied.isEmpty()) {
            return;
        }
        String table = historyTable == null || historyTable.isEmpty() ? "jkit_schema_history" : historyTable;
        try {
            ensureTable(connection, dialect, table);
            String host = host();
            for (int i = 0; i < applied.size(); i++) {
                SqlAutoChange c = applied.get(i);
                insert(connection, dialect, table, host, mode, c.kind().name(), c.table(), c.sql());
            }
        } catch (Throwable e) {
            // 老驱动可能抛 AbstractMethodError 等 Error，审计失败不影响主流程
            LOG.warn("record schema history failed: {}", String.valueOf(e));
        }
    }

    /**
     * 历史表不存在时创建。列为：id / installed_on / host / mode / change_type / table_name / sql_text。
     *
     * @param connection 连接
     * @param dialect 方言
     * @param table 历史表名
     * @throws SQLException 建表或探测失败时抛出，由调用方降级为日志
     */
    static void ensureTable(Connection connection, SqlDialect dialect, String table) throws SQLException {
        if (exists(connection, table)) {
            return;
        }
        String textType = textType(dialect);
        String sql = "CREATE TABLE " + table + " ("
                + "id " + varchar(dialect, 36) + " NOT NULL, "
                + "installed_on " + timestamp(dialect) + ", "
                + "host " + varchar(dialect, 128) + ", "
                + "sync_mode " + varchar(dialect, 32) + ", "
                + "change_type " + varchar(dialect, 32) + ", "
                + "table_name " + varchar(dialect, 128) + ", "
                + "sql_text " + textType + ", "
                + "PRIMARY KEY (id))";
        Statement st = connection.createStatement();
        try {
            st.execute(sql);
        } finally {
            st.close();
        }
    }

    private static void insert(Connection connection, SqlDialect dialect, String table, String host,
                               String mode, String kind, String tableName, String sql) throws SQLException {
        PreparedStatement ps = connection.prepareStatement(
                "INSERT INTO " + table
                        + " (id, installed_on, host, sync_mode, change_type, table_name, sql_text) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?)");
        try {
            ps.setString(1, UUID.randomUUID().toString());
            ps.setTimestamp(2, new Timestamp(System.currentTimeMillis()));
            ps.setString(3, host);
            ps.setString(4, mode);
            ps.setString(5, kind);
            ps.setString(6, tableName);
            ps.setString(7, sql);
            ps.executeUpdate();
        } finally {
            ps.close();
        }
    }

    private static boolean exists(Connection connection, String table) throws SQLException {
        DatabaseMetaData meta = connection.getMetaData();
        // Oracle / H2(UPPER) 等按大小写折叠，原始名与大小写变体都探测一遍
        String[] candidates = new String[] {table,
                table.toUpperCase(Locale.ROOT), table.toLowerCase(Locale.ROOT)};
        String schema = schema(connection);
        for (int i = 0; i < candidates.length; i++) {
            ResultSet rs = meta.getTables(connection.getCatalog(), schema,
                    candidates[i], new String[] {"TABLE", "BASE TABLE"});
            try {
                if (rs.next()) {
                    return true;
                }
            } finally {
                rs.close();
            }
        }
        return false;
    }

    /**
     * 连接当前 schema；老驱动没实现 {@code Connection#getSchema()} 时返回 {@code null}。
     *
     * @param connection 连接
     * @return schema，可空
     */
    private static String schema(Connection connection) {
        try {
            return connection.getSchema() == null ? null : connection.getSchema();
        } catch (AbstractMethodError | SQLException e) {
            // JDBC 4.0 及更老的驱动没有 Connection#getSchema
            return null;
        }
    }

    private static String host() {
        try {
            return InetAddress.getLocalHost().getHostName();
        } catch (Exception e) {
            return "unknown";
        }
    }

    private static String varchar(SqlDialect dialect, int size) {
        if (dialect == SqlDialect.ORACLE || dialect == SqlDialect.ORACLE12 || dialect == SqlDialect.DAMENG) {
            return "VARCHAR2(" + size + " CHAR)";
        }
        return "VARCHAR(" + size + ")";
    }

    private static String textType(SqlDialect dialect) {
        if (dialect == SqlDialect.ORACLE || dialect == SqlDialect.ORACLE12 || dialect == SqlDialect.DAMENG) {
            return "CLOB";
        }
        if (dialect == SqlDialect.SQLSERVER) {
            return "VARCHAR(MAX)";
        }
        return "TEXT";
    }

    private static String timestamp(SqlDialect dialect) {
        if (dialect == SqlDialect.SQLSERVER) {
            return "DATETIME";
        }
        return "TIMESTAMP";
    }

}
