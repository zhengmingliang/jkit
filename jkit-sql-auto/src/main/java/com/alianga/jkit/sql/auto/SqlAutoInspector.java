package com.alianga.jkit.sql.auto;

import com.alianga.jkit.sql.SqlDialect;

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
        this.catalog = options == null ? null : emptyToNull(options.catalog());
        this.schema = options == null ? null : emptyToNull(options.schema());
        this.dialect = dialect == null ? SqlDialect.MYSQL : dialect;
    }

    /**
     * @param tableName 实体表名
     * @return 活表；不存在则 null
     */
    public SqlAutoLiveTable inspect(String tableName) {
        if (tableName == null || tableName.isEmpty()) {
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
        if (dialect == SqlDialect.ORACLE || dialect == SqlDialect.ORACLE12) {
            String user = meta.getUserName();
            return user == null ? null : user.toUpperCase(Locale.ROOT);
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
}
