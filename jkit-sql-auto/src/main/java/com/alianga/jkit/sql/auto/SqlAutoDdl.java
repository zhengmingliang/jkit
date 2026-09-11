package com.alianga.jkit.sql.auto;

import com.alianga.jkit.sql.SqlDialect;
import com.alianga.jkit.sql.entity.SqlEntities;
import com.alianga.jkit.sql.entity.SqlEntityColumn;
import com.alianga.jkit.sql.entity.SqlEntityModel;
import com.alianga.jkit.sql.schema.convert.SqlSchemaConvertOptions;
import com.alianga.jkit.sql.schema.model.CanonicalType;
import com.alianga.jkit.sql.schema.registry.SqlDataTypeRegistry;

import java.sql.Types;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 实体模型对照活表，生成 CREATE / ALTER / DROP / INDEX。
 *
 * @author 郑明亮
 * @since 2.0.1
 */
public final class SqlAutoDdl {
    private SqlAutoDdl() {
    }

    /**
     * 规划一张表。
     *
     * @param model 实体
     * @param live 活表，null 表示不存在
     * @param dialect 方言
     * @param options 选项
     * @return 变更（可能为空）
     */
    public static List<SqlAutoChange> planTable(SqlEntityModel model, SqlAutoLiveTable live,
                                                SqlDialect dialect, SqlAutoOptions options) {
        List<SqlAutoChange> out = new ArrayList<SqlAutoChange>(4);
        if (model == null) {
            return out;
        }
        SqlDialect d = dialect == null ? SqlDialect.MYSQL : dialect;
        SqlAutoOptions opt = options == null ? SqlAutoOptions.defaults() : options;
        SqlAutoMode mode = opt.mode();
        if (mode == SqlAutoMode.NONE) {
            return out;
        }
        String table = model.tableName();
        if (mode == SqlAutoMode.CREATE || mode == SqlAutoMode.CREATE_DROP) {
            // DROP 由 SqlAuto.plan 按外键逆序统一发出，这里只建表。
            out.add(createTableChange(model, d, opt));
            addExtraChanges(out, model, d, opt);
            addIndexChanges(out, model, null, d, opt);
            return out;
        }
        if (live == null) {
            if (mode == SqlAutoMode.VALIDATE) {
                out.add(new SqlAutoChange(SqlAutoChange.Kind.VALIDATE, table, "missing table", ""));
                return out;
            }
            out.add(createTableChange(model, d, opt));
            addExtraChanges(out, model, d, opt);
            addIndexChanges(out, model, null, d, opt);
            return out;
        }
        List<SqlEntityColumn> cols = model.columns();
        Set<String> wanted = new HashSet<String>(cols.size());
        for (int i = 0; i < cols.size(); i++) {
            SqlEntityColumn col = cols.get(i);
            wanted.add(col.columnName().toLowerCase(Locale.ROOT));
            SqlAutoLiveColumn existing = live.column(col.columnName());
            if (existing == null) {
                if (mode == SqlAutoMode.VALIDATE) {
                    out.add(new SqlAutoChange(SqlAutoChange.Kind.VALIDATE, table,
                            "missing column " + col.columnName(), ""));
                } else {
                    out.add(new SqlAutoChange(SqlAutoChange.Kind.ADD_COLUMN, table, col.columnName(),
                            addColumnSql(table, col, d, opt)));
                    addColumnExtras(out, model, col, d, opt);
                }
                continue;
            }
            if (!compatible(col, existing, d)) {
                String msg = "column type mismatch " + col.columnName()
                        + " expected " + SqlEntities.columnTypeSql(col, d)
                        + " actual " + existing.typeName();
                if (mode == SqlAutoMode.VALIDATE) {
                    out.add(new SqlAutoChange(SqlAutoChange.Kind.VALIDATE, table, msg, ""));
                } else if (opt.alterColumn()) {
                    out.add(new SqlAutoChange(SqlAutoChange.Kind.ALTER_COLUMN, table, col.columnName(),
                            alterColumnSql(table, col, d, opt)));
                }
            }
        }
        if (opt.dropExtraColumns() && mode != SqlAutoMode.VALIDATE) {
            for (SqlAutoLiveColumn extra : live.columns().values()) {
                if (!wanted.contains(extra.name().toLowerCase(Locale.ROOT))) {
                    out.add(new SqlAutoChange(SqlAutoChange.Kind.DROP_COLUMN, table, extra.name(),
                            dropColumnSql(table, extra.name(), d, opt)));
                }
            }
        }
        addIndexChanges(out, model, live, d, opt);
        return out;
    }

    /**
     * 删表前先删该表自增序列（Oracle 11g / 达梦等）。
     *
     * @param model 实体
     * @param dialect 方言
     * @return DROP SEQUENCE 列表
     */
    public static List<String> dropSequenceSql(SqlEntityModel model, SqlDialect dialect) {
        List<String> out = new ArrayList<String>(2);
        if (model == null) {
            return out;
        }
        List<SqlEntityColumn> cols = model.columns();
        for (int i = 0; i < cols.size(); i++) {
            SqlEntityColumn col = cols.get(i);
            if (SqlEntities.sequenceSql(model.tableName(), col, dialect) == null) {
                continue;
            }
            String name = SqlEntities.sequenceName(model.tableName(), col.columnName());
            if (dialect == SqlDialect.ORACLE || dialect == SqlDialect.ORACLE12) {
                out.add("DROP SEQUENCE " + name);
            } else {
                out.add("DROP SEQUENCE IF EXISTS " + name);
            }
        }
        return out;
    }

    /**
     * 删表语句（CREATE_DROP 关机用）。
     *
     * @param tableName 表名
     * @param dialect 方言
     * @param options 选项
     * @return DDL
     */

    public static String dropTableSql(String tableName, SqlDialect dialect, SqlAutoOptions options) {
        String name = ident(tableName, dialect, options);
        SqlDialect d = dialect == null ? SqlDialect.MYSQL : dialect;
        if (d == SqlDialect.ORACLE || d == SqlDialect.ORACLE12) {
            return "DROP TABLE " + name;
        }
        return "DROP TABLE IF EXISTS " + name;
    }

    static boolean compatible(SqlEntityColumn wanted, SqlAutoLiveColumn live, SqlDialect dialect) {
        if (wanted == null || live == null) {
            return true;
        }
        CanonicalType expected = wanted.canonical();
        CanonicalType actual = fromJdbc(live, dialect);
        if (actual == CanonicalType.UNKNOWN) {
            return familyCompatible(expected, live.typeName());
        }
        if (expected == actual) {
            return lengthCompatible(wanted, live, expected);
        }
        return sameFamily(expected, actual);
    }

    private static boolean lengthCompatible(SqlEntityColumn wanted, SqlAutoLiveColumn live,
                                            CanonicalType type) {
        if (type == CanonicalType.VARCHAR || type == CanonicalType.CHAR || type == CanonicalType.BINARY) {
            Integer p = wanted.precision();
            if (p != null && p.intValue() > 0 && live.size() > 0 && live.size() < p.intValue()) {
                return false;
            }
        }
        if (type == CanonicalType.DECIMAL) {
            Integer p = wanted.precision();
            Integer s = wanted.scale();
            if (p != null && p.intValue() > 0 && live.size() > 0 && live.size() < p.intValue()) {
                return false;
            }
            if (s != null && s.intValue() > 0 && live.decimalDigits() >= 0
                    && live.decimalDigits() < s.intValue()) {
                return false;
            }
        }
        return true;
    }

    private static boolean sameFamily(CanonicalType a, CanonicalType b) {
        if (a == b) {
            return true;
        }
        if (a.integerFamily() && b.integerFamily()) {
            return integerRank(b) >= integerRank(a);
        }
        if ((a == CanonicalType.FLOAT || a == CanonicalType.DOUBLE)
                && (b == CanonicalType.FLOAT || b == CanonicalType.DOUBLE)) {
            return true;
        }
        if ((a == CanonicalType.DATETIME || a == CanonicalType.TIMESTAMP)
                && (b == CanonicalType.DATETIME || b == CanonicalType.TIMESTAMP)) {
            return true;
        }
        if ((a == CanonicalType.TEXT || a == CanonicalType.VARCHAR)
                && (b == CanonicalType.TEXT || b == CanonicalType.VARCHAR || b == CanonicalType.CHAR
                || b == CanonicalType.JSON || b == CanonicalType.UUID)) {
            return true;
        }
        if (a == CanonicalType.BOOLEAN && (b == CanonicalType.BOOLEAN || b == CanonicalType.TINYINT
                || b == CanonicalType.INT || b == CanonicalType.SMALLINT)) {
            return true;
        }
        if (a == CanonicalType.BLOB && b == CanonicalType.BLOB) {
            return true;
        }
        return false;
    }

    private static int integerRank(CanonicalType t) {
        if (t == CanonicalType.TINYINT || t == CanonicalType.YEAR) {
            return 1;
        }
        if (t == CanonicalType.SMALLINT) {
            return 2;
        }
        if (t == CanonicalType.MEDIUMINT) {
            return 3;
        }
        if (t == CanonicalType.INT) {
            return 4;
        }
        if (t == CanonicalType.BIGINT) {
            return 5;
        }
        return 0;
    }

    private static boolean familyCompatible(CanonicalType expected, String typeName) {
        if (typeName == null || typeName.isEmpty()) {
            return true;
        }
        String n = typeName.toUpperCase(Locale.ROOT);
        int paren = n.indexOf('(');
        if (paren > 0) {
            n = n.substring(0, paren).trim();
        }
        if (n.endsWith(" IDENTITY")) {
            n = n.substring(0, n.length() - " IDENTITY".length()).trim();
        }
        CanonicalType guessed = SqlDataTypeRegistry.builtins().fromDialect(n, SqlDialect.MYSQL);
        if (guessed == CanonicalType.UNKNOWN) {
            guessed = SqlDataTypeRegistry.builtins().fromDialect(n, SqlDialect.POSTGRES);
        }
        if (guessed == CanonicalType.UNKNOWN) {
            guessed = SqlDataTypeRegistry.builtins().fromDialect(n, SqlDialect.H2);
        }
        if (guessed == CanonicalType.UNKNOWN) {
            return true;
        }
        return sameFamily(expected, guessed);
    }

    static CanonicalType fromJdbc(SqlAutoLiveColumn live, SqlDialect dialect) {
        String typeName = live.typeName();
        if (typeName != null && typeName.length() > 0) {
            CanonicalType fromName = SqlDataTypeRegistry.builtins().fromDialect(typeName, dialect);
            if (fromName != CanonicalType.UNKNOWN) {
                return fromName;
            }
            fromName = SqlDataTypeRegistry.builtins().fromDialect(stripTypeArgs(typeName), dialect);
            if (fromName != CanonicalType.UNKNOWN) {
                return fromName;
            }
        }
        return fromJdbcType(live.dataType());
    }

    private static String stripTypeArgs(String typeName) {
        String n = typeName.trim();
        int paren = n.indexOf('(');
        if (paren > 0) {
            n = n.substring(0, paren).trim();
        }
        String upper = n.toUpperCase(Locale.ROOT);
        if (upper.endsWith(" IDENTITY")) {
            n = n.substring(0, n.length() - " IDENTITY".length()).trim();
        }
        return n;
    }

    private static CanonicalType fromJdbcType(int jdbc) {
        switch (jdbc) {
            case Types.TINYINT:
                return CanonicalType.TINYINT;
            case Types.SMALLINT:
                return CanonicalType.SMALLINT;
            case Types.INTEGER:
                return CanonicalType.INT;
            case Types.BIGINT:
                return CanonicalType.BIGINT;
            case Types.FLOAT:
            case Types.REAL:
                return CanonicalType.FLOAT;
            case Types.DOUBLE:
                return CanonicalType.DOUBLE;
            case Types.NUMERIC:
            case Types.DECIMAL:
                return CanonicalType.DECIMAL;
            case Types.CHAR:
            case Types.NCHAR:
                return CanonicalType.CHAR;
            case Types.VARCHAR:
            case Types.NVARCHAR:
            case Types.LONGVARCHAR:
            case Types.LONGNVARCHAR:
                return CanonicalType.VARCHAR;
            case Types.CLOB:
            case Types.NCLOB:
                return CanonicalType.TEXT;
            case Types.DATE:
                return CanonicalType.DATE;
            case Types.TIMESTAMP:
            case Types.TIMESTAMP_WITH_TIMEZONE:
                return CanonicalType.DATETIME;
            case Types.TIME:
            case Types.TIME_WITH_TIMEZONE:
                return CanonicalType.TIME;
            case Types.BINARY:
            case Types.VARBINARY:
                return CanonicalType.BINARY;
            case Types.BLOB:
            case Types.LONGVARBINARY:
                return CanonicalType.BLOB;
            case Types.BOOLEAN:
            case Types.BIT:
                return CanonicalType.BOOLEAN;
            case Types.OTHER:
                return CanonicalType.UNKNOWN;
            default:
                return CanonicalType.UNKNOWN;
        }
    }

    private static SqlAutoChange createTableChange(SqlEntityModel model, SqlDialect dialect,
                                                   SqlAutoOptions options) {
        SqlSchemaConvertOptions convert = convertOptions(options);
        String sql = SqlEntities.createTable(model, dialect, false, convert);
        if (options.quoteIdentifiers()) {
            sql = quoteCreateTable(sql, model, dialect);
        }
        return new SqlAutoChange(SqlAutoChange.Kind.CREATE_TABLE, model.tableName(), "", sql);
    }

    private static String quoteCreateTable(String sql, SqlEntityModel model, SqlDialect dialect) {
        // 简单路径：重新用带引号的标识符拼 CREATE TABLE 会更稳，这里只给表名加引号。
        String table = model.tableName();
        String quoted = dialect.quoteIdent(table);
        int at = sql.indexOf(table);
        if (at < 0) {
            return sql;
        }
        return sql.substring(0, at) + quoted + sql.substring(at + table.length());
    }

    private static String addColumnSql(String table, SqlEntityColumn col, SqlDialect dialect,
                                       SqlAutoOptions options) {
        String def = SqlEntities.columnSql(col, dialect, false, convertOptions(options));
        return "ALTER TABLE " + ident(table, dialect, options) + " ADD " + addColumnKeyword(dialect) + def;
    }

    private static String addColumnKeyword(SqlDialect dialect) {
        if (dialect == SqlDialect.POSTGRES || dialect == SqlDialect.H2 || dialect == SqlDialect.ANSI
                || dialect == SqlDialect.SQLITE) {
            return "COLUMN ";
        }
        return "";
    }

    private static String alterColumnSql(String table, SqlEntityColumn col, SqlDialect dialect,
                                         SqlAutoOptions options) {
        String type = SqlEntities.columnTypeSql(col, dialect);
        String t = ident(table, dialect, options);
        String c = ident(col.columnName(), dialect, options);
        if (dialect == SqlDialect.MYSQL || dialect == SqlDialect.H2) {
            return "ALTER TABLE " + t + " MODIFY " + SqlEntities.columnSql(col, dialect, false);
        }
        if (dialect == SqlDialect.SQLSERVER) {
            return "ALTER TABLE " + t + " ALTER COLUMN " + SqlEntities.columnSql(col, dialect, false);
        }
        if (dialect == SqlDialect.ORACLE || dialect == SqlDialect.ORACLE12) {
            return "ALTER TABLE " + t + " MODIFY (" + c + " " + type + ")";
        }
        return "ALTER TABLE " + t + " ALTER COLUMN " + c + " TYPE " + type;
    }

    private static String dropColumnSql(String table, String column, SqlDialect dialect,
                                        SqlAutoOptions options) {
        return "ALTER TABLE " + ident(table, dialect, options)
                + " DROP COLUMN " + ident(column, dialect, options);
    }

    private static void addExtraChanges(List<SqlAutoChange> out, SqlEntityModel model,
                                        SqlDialect dialect, SqlAutoOptions options) {
        List<String> extras = SqlEntities.extraSql(model, dialect, convertOptions(options));
        for (int i = 0; i < extras.size(); i++) {
            String sql = extras.get(i);
            SqlAutoChange.Kind kind = sql.toUpperCase(Locale.ROOT).contains("SEQUENCE")
                    || sql.toUpperCase(Locale.ROOT).contains("TRIGGER")
                    ? SqlAutoChange.Kind.SEQUENCE : SqlAutoChange.Kind.COMMENT;
            splitExtra(out, kind, model.tableName(), sql);
        }
    }

    private static void addColumnExtras(List<SqlAutoChange> out, SqlEntityModel model, SqlEntityColumn col,
                                        SqlDialect dialect, SqlAutoOptions options) {
        List<String> extras = SqlEntities.extraSql(
                new SqlEntityModel(model.type(), model.tableName(),
                        java.util.Collections.singletonList(col),
                        java.util.Collections.<String>emptyList(), null),
                dialect, convertOptions(options));
        for (int i = 0; i < extras.size(); i++) {
            String sql = extras.get(i);
            SqlAutoChange.Kind kind = sql.toUpperCase(Locale.ROOT).contains("SEQUENCE")
                    || sql.toUpperCase(Locale.ROOT).contains("TRIGGER")
                    ? SqlAutoChange.Kind.SEQUENCE : SqlAutoChange.Kind.COMMENT;
            splitExtra(out, kind, model.tableName(), sql);
        }
    }

    private static void splitExtra(List<SqlAutoChange> out, SqlAutoChange.Kind kind, String table, String sql) {
        if (sql == null || sql.isEmpty()) {
            return;
        }
        int from = 0;
        while (from < sql.length()) {
            int slash = sql.indexOf("\n/\n", from);
            String piece;
            if (slash < 0) {
                piece = sql.substring(from).trim();
                from = sql.length();
            } else {
                piece = sql.substring(from, slash).trim();
                from = slash + 3;
            }
            if (piece.endsWith(";") && piece.toUpperCase(Locale.ROOT).indexOf(" BEGIN ") < 0) {
                piece = piece.substring(0, piece.length() - 1).trim();
            }
            if (!piece.isEmpty()) {
                out.add(new SqlAutoChange(kind, table, "", piece));
            }
        }
    }

    private static void addIndexChanges(List<SqlAutoChange> out, SqlEntityModel model,
                                        SqlAutoLiveTable live, SqlDialect dialect, SqlAutoOptions options) {
        if (!options.createIndex()) {
            return;
        }
        List<String> indexes = model.indexes();
        for (int i = 0; i < indexes.size(); i++) {
            String spec = indexes.get(i);
            String name = indexName(model.tableName(), spec);
            if (coveredByUniqueColumn(model, spec)) {
                continue;
            }
            if (live != null && indexPresent(live, name, spec)) {
                continue;
            }
            String sql = SqlEntities.createIndex(model.tableName(), spec);
            if (options.quoteIdentifiers() && dialect != null) {
                sql = sql.replace(" ON " + model.tableName() + " ",
                        " ON " + dialect.quoteIdent(model.tableName()) + " ");
            }
            if (options.mode() == SqlAutoMode.VALIDATE) {
                out.add(new SqlAutoChange(SqlAutoChange.Kind.VALIDATE, model.tableName(),
                        "missing index " + name, ""));
            } else {
                out.add(new SqlAutoChange(SqlAutoChange.Kind.CREATE_INDEX, model.tableName(), name, sql));
            }
        }
    }

    private static boolean coveredByUniqueColumn(SqlEntityModel model, String spec) {
        String cols = indexColumns(spec).replace(" ", "");
        if (cols.indexOf(',') >= 0) {
            return false;
        }
        List<SqlEntityColumn> list = model.columns();
        for (int i = 0; i < list.size(); i++) {
            SqlEntityColumn c = list.get(i);
            if (c.columnName().equalsIgnoreCase(cols) && (c.unique() || c.primaryKey())) {
                return true;
            }
        }
        return false;
    }

    private static boolean indexPresent(SqlAutoLiveTable live, String name, String spec) {
        if (live.index(name) != null) {
            return true;
        }
        String cols = indexColumns(spec).replace(" ", "").toLowerCase(Locale.ROOT);
        for (SqlAutoLiveIndex idx : live.indexes().values()) {
            String joined = join(idx.columns()).replace(" ", "").toLowerCase(Locale.ROOT);
            if (joined.equals(cols)) {
                return true;
            }
        }
        return false;
    }

    private static String indexName(String table, String spec) {
        int colon = spec.indexOf(':');
        if (colon > 0) {
            return spec.substring(0, colon).trim();
        }
        return table + "_idx";
    }

    private static String indexColumns(String spec) {
        int colon = spec.indexOf(':');
        String cols = colon > 0 ? spec.substring(colon + 1).trim() : spec.trim();
        if (cols.startsWith("(") && cols.endsWith(")")) {
            cols = cols.substring(1, cols.length() - 1);
        }
        return cols;
    }

    private static String join(List<String> cols) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < cols.size(); i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(cols.get(i));
        }
        return sb.toString();
    }

    private static SqlSchemaConvertOptions convertOptions(SqlAutoOptions options) {
        SqlSchemaConvertOptions convert = SqlSchemaConvertOptions.defaults();
        if (options.postgresIdentityStyle() != null) {
            convert.postgresIdentityStyle(options.postgresIdentityStyle());
        }
        convert.includeForeignKeys(options.foreignKeys());
        convert.includeAutoIncrement(options.autoIncrement());
        return convert;
    }

    private static String ident(String name, SqlDialect dialect, SqlAutoOptions options) {
        if (options != null && options.quoteIdentifiers() && dialect != null) {
            return dialect.quoteIdent(name);
        }
        return name;
    }
}
