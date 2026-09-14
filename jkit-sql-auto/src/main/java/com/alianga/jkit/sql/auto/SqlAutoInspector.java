package com.alianga.jkit.sql.auto;

import com.alianga.jkit.sql.SqlDialect;
import com.alianga.jkit.sql.jdbc.JdbcUrlUtils;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 用 {@link DatabaseMetaData} 读出现有表 / 列 / 索引。
 *
 * @author 郑明亮
 * @since 2.0.1
 */
public final class SqlAutoInspector {
    private final Connection connection;
    private final String catalog;
    private final String schema;
    private final SqlDialect dialect;

    /**
     * @param connection 连接
     * @param options 选项
     * @param dialect 方言
     */
    public SqlAutoInspector(Connection connection, SqlAutoOptions options, SqlDialect dialect) {
        this.connection = connection;
        this.dialect = dialect == null ? SqlDialect.MYSQL : dialect;
        this.catalog = resolveCatalog(options);
        this.schema = resolveSchema(options);
    }

    /**
     * 只查数据源指定的库：优先用配置，没配就从 JDBC 连接当前 catalog 取。
     *
     * <p>catalog 传 null 时，部分驱动（MySQL 等）会跨库扫描，
     * 把别的库下的同名表误判为"已存在"，进而对本库的缺失表发出 ALTER 而不是 CREATE。
     *
     * @param options 选项
     * @return catalog，无法获取时为 null
     */
    private String resolveCatalog(SqlAutoOptions options) {
        String configured = options == null ? null : emptyToNull(options.catalog());
        if (configured != null) {
            return configured;
        }
        if (connection == null) {
            return null;
        }
        try {
            return emptyToNull(connection.getCatalog());
        } catch (SQLException e) {
            // 少数驱动不支持 getCatalog，回退到驱动默认行为
            return null;
        }
    }

    /**
     * 只查当前 schema：优先用配置，没配就从 JDBC 连接 {@link Connection#getSchema()} 取。
     *
     * <p>PostgreSQL / openGauss / SQL Server 等用 schema 做命名空间。schema 传 null 时
     * 驱动会扫全部 schema，把别的 schema 下的同名表误判为已存在；部分 PostgreSQL
     * 驱动在只传 catalog、不传 schema 时甚至查不到当前 schema 的表。
     *
     * <p>无连接时（dry-run）从 JDBC URL 解析 {@code currentSchema} / {@code schema} 等参数。
     *
     * @param options 选项
     * @return schema，无法获取时为 null（再由 {@link #schemaPattern} 按方言补默认值）
     */
    private String resolveSchema(SqlAutoOptions options) {
        String configured = options == null ? null : emptyToNull(options.schema());
        if (configured != null) {
            return configured;
        }
        if (connection != null) {
            try {
                String current = emptyToNull(connection.getSchema());
                if (current != null) {
                    return current;
                }
            } catch (SQLException e) {
                // 少数驱动不支持 getSchema，回落到 URL
            } catch (AbstractMethodError e) {
                // JDBC 4.0 及更老的驱动没有 Connection#getSchema
            }
        }
        return emptyToNull(JdbcUrlUtils.schema(options == null ? null : options.url()));
    }

    /**
     * @param tableName 实体表名
     * @return 活表；不存在则 null
     */
    public SqlAutoLiveTable inspect(String tableName) {
        if (connection == null || tableName == null || tableName.isEmpty()) {
            return null;
        }
        try {
            DatabaseMetaData meta = connection.getMetaData();
            String catalogName = catalog;
            String schemaName = schemaPattern(meta);
            String lookup = tableLookupName(meta, tableName);
            if (!tableExists(meta, catalogName, schemaName, lookup, tableName)) {
                return null;
            }
            Map<String, SqlAutoLiveColumn> columns = readColumns(meta, catalogName, schemaName, lookup);
            if (columns.isEmpty() && !lookup.equals(tableName)) {
                columns = readColumns(meta, catalogName, schemaName, tableName);
            }
            Map<String, SqlAutoLiveIndex> indexes = readIndexes(meta, catalogName, schemaName, lookup);
            if (indexes.isEmpty() && !lookup.equals(tableName)) {
                indexes = readIndexes(meta, catalogName, schemaName, tableName);
            }
            return new SqlAutoLiveTable(lookup, columns, indexes);
        } catch (SQLException e) {
            throw new SqlAutoException("inspect table failed: " + tableName, e);
        }
    }

    private boolean tableExists(DatabaseMetaData meta, String catalogName, String schemaName,
                                String lookup, String original) throws SQLException {
        if (hasTable(meta, catalogName, schemaName, lookup)) {
            return true;
        }
        return !lookup.equals(original) && hasTable(meta, catalogName, schemaName, original);
    }

    private boolean hasTable(DatabaseMetaData meta, String catalogName, String schemaName,
                             String table) throws SQLException {
        ResultSet rs = meta.getTables(catalogName, schemaName, table, new String[] {"TABLE", "BASE TABLE"});
        try {
            return rs.next();
        } finally {
            rs.close();
        }
    }

    private Map<String, SqlAutoLiveColumn> readColumns(DatabaseMetaData meta, String catalogName,
                                                       String schemaName, String table) throws SQLException {
        Map<String, SqlAutoLiveColumn> out = new LinkedHashMap<String, SqlAutoLiveColumn>(8);
        ResultSet rs = meta.getColumns(catalogName, schemaName, table, null);
        try {
            while (rs.next()) {
                String name = rs.getString("COLUMN_NAME");
                if (name == null) {
                    continue;
                }
                String typeName = rs.getString("TYPE_NAME");
                int dataType = rs.getInt("DATA_TYPE");
                int size = rs.getInt("COLUMN_SIZE");
                int digits = rs.getInt("DECIMAL_DIGITS");
                int nullable = rs.getInt("NULLABLE");
                out.put(name.toLowerCase(Locale.ROOT),
                        new SqlAutoLiveColumn(name, typeName, dataType, size, digits, nullable));
            }
        } finally {
            rs.close();
        }
        return out;
    }

    private Map<String, SqlAutoLiveIndex> readIndexes(DatabaseMetaData meta, String catalogName,
                                                      String schemaName, String table) throws SQLException {
        Map<String, List<String>> cols = new LinkedHashMap<String, List<String>>(4);
        Map<String, Boolean> unique = new LinkedHashMap<String, Boolean>(4);
        ResultSet rs = meta.getIndexInfo(catalogName, schemaName, table, false, true);
        try {
            while (rs.next()) {
                String name = rs.getString("INDEX_NAME");
                if (name == null || name.isEmpty()) {
                    continue;
                }
                String col = rs.getString("COLUMN_NAME");
                if (col == null) {
                    continue;
                }
                String key = name.toLowerCase(Locale.ROOT);
                List<String> list = cols.get(key);
                if (list == null) {
                    list = new ArrayList<String>(2);
                    cols.put(key, list);
                    unique.put(key, Boolean.valueOf(!rs.getBoolean("NON_UNIQUE")));
                }
                list.add(col);
            }
        } finally {
            rs.close();
        }
        Map<String, SqlAutoLiveIndex> out = new LinkedHashMap<String, SqlAutoLiveIndex>(cols.size());
        for (Map.Entry<String, List<String>> e : cols.entrySet()) {
            Boolean u = unique.get(e.getKey());
            out.put(e.getKey(), new SqlAutoLiveIndex(e.getKey(), u != null && u.booleanValue(), e.getValue()));
        }
        return out;
    }

    private String schemaPattern(DatabaseMetaData meta) throws SQLException {
        if (schema != null) {
            return schema;
        }
        if (dialect == SqlDialect.ORACLE || dialect == SqlDialect.ORACLE12
                || dialect == SqlDialect.DAMENG) {
            String user = meta.getUserName();
            return user == null ? null : user.toUpperCase(Locale.ROOT);
        }
        if (dialect == SqlDialect.POSTGRES) {
            // PG / Gauss / openGauss / Kingbase：连接 getSchema 拿不到时默认 public
            return "public";
        }
        if (dialect == SqlDialect.SQLSERVER) {
            return "dbo";
        }
        if (dialect == SqlDialect.DB2) {
            return emptyToNull(meta.getUserName());
        }
        return null;
    }

    private String tableLookupName(DatabaseMetaData meta, String tableName) throws SQLException {
        if (meta.storesUpperCaseIdentifiers()) {
            return tableName.toUpperCase(Locale.ROOT);
        }
        if (meta.storesLowerCaseIdentifiers()) {
            return tableName.toLowerCase(Locale.ROOT);
        }
        return tableName;
    }

    private static String emptyToNull(String s) {
        return s == null || s.isEmpty() ? null : s;
    }

    /**
     * @return 已解析的 schema，测试用
     */
    String resolvedSchema() {
        return schema;
    }
}
