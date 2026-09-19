package com.alianga.jkit.sql;

import java.util.Collections;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiConsumer;

/**
 * 各方言的保留字注册表。实体表名 / 列名 / 索引名默认不加引号（靠数据库大小写折叠），
 * 但名字撞上目标库的保留字时会直接 SQL 报错；调用方在生成标识符前用
 * {@link #isKeyword(SqlDialect, String)} 探测，命中时自动加方言引号并回调告警。
 *
 * <p>识别范围取各方言「不加引号会报错」的保留字（SQL 标准核心 + 方言扩展），
 * 非保留关键字不收录，避免把本可折叠的普通名字错误变成大小写敏感。
 * 本类不打日志（jkit-sql 不依赖 core），告警由调用方通过 {@code listener} 完成，
 * 每个标识符（同方言）只回调一次。</p>
 *
 * @author 郑明亮
 * @since 2.0.3
 */
public final class SqlReservedWords {
    /** 同一标识符（同方言）只回调一次，避免多语句重复刷屏。 */
    private static final Set<String> WARNED =
            Collections.newSetFromMap(new ConcurrentHashMap<String, Boolean>());

    /** SQL 标准 / 各方言通用的保留字核心集。 */
    private static final String[] BASE = {
            "ADD", "ALL", "AND", "ANY", "ARRAY", "AS", "ASC", "BETWEEN", "BOTH", "BY",
            "CASE", "CAST", "CHECK", "COLUMN", "CONSTRAINT", "CREATE", "CROSS", "CURRENT",
            "CURRENT_DATE", "CURRENT_TIME", "CURRENT_TIMESTAMP", "CURRENT_USER", "DEFAULT",
            "DELETE", "DESC", "DISTINCT", "DROP", "ELSE", "END", "ESCAPE", "EXCEPT", "EXISTS",
            "FALSE", "FETCH", "FOR", "FOREIGN", "FROM", "FULL", "GRANT", "GROUP", "HAVING",
            "IN", "INNER", "INSERT", "INTERSECT", "INTO", "IS", "JOIN", "KEY", "LEFT",
            "LEADING", "LIKE", "NATURAL", "NOT", "NULL", "OF", "ON", "ONLY", "OR", "ORDER",
            "OUTER", "OVER", "PARTITION", "PRECISION", "PRIMARY", "REFERENCES", "RESTRICT",
            "RIGHT", "SELECT", "SET", "SOME", "TABLE", "THEN", "TO", "TRAILING", "TRUE",
            "TRUNCATE", "UNION", "UNIQUE", "UNKNOWN", "UPDATE", "USER", "USING", "VALUES",
            "VARYING", "WHEN", "WHERE", "WINDOW", "WITH",
    };

    private static final String[] MYSQL_EXTRA = {
            // LEVEL / SORT 不是 MySQL 保留字，但 GBase 8a（jkit 内归并为 MYSQL 方言）保留它们，
            // 实体列名 level / sort 在 GBase 8a 上不加引号直接语法错误（多库实测确认）
            "LEVEL", "SORT",
            "ANALYZE", "ASENSITIVE", "AUTO_INCREMENT", "BEFORE", "BIGINT", "BINARY", "BLOB",
            "CALL", "CASCADE", "CHANGE", "CHAR", "CHARACTER", "COLLATE", "CONDITION",
            "CUME_DIST", "DATABASE", "DATABASES", "DAY_HOUR", "DAY_MICROSECOND", "DAY_MINUTE",
            "DAY_SECOND", "DEC", "DECIMAL", "DELAYED", "DENSE_RANK", "DESCRIBE",
            "DETERMINISTIC", "DIAGNOSTICS", "DISTINCTROW", "DIV", "DOUBLE", "DUAL", "EACH",
            "ELSEIF", "EMPTY", "ENCLOSED", "ESCAPED", "EXIT", "EXPLAIN", "FIRST_VALUE",
            "FLOAT", "FLOAT4", "FLOAT8", "FORCE", "FULLTEXT", "GENERATED", "GEOMETRY",
            "GEOMETRYCOLLECTION", "GROUPING", "GROUPS", "HIGH_PRIORITY", "HOUR_MICROSECOND",
            "HOUR_MINUTE", "HOUR_SECOND", "IF", "IGNORE", "INFILE", "INOUT", "INSENSITIVE",
            "INT", "INT4", "INT8", "INTEGER", "INTERVAL", "ITERATE", "JSON", "JSON_TABLE",
            "JSON_VALUE", "KEYS", "KILL", "LAG", "LAST_VALUE", "LATERAL", "LEAD", "LEAVE",
            "LIMIT", "LINEAR", "LINES", "LINESTRING", "LOAD", "LOCAL", "LOCALTIME",
            "LOCALTIMESTAMP", "LOCK", "LONG", "LONGBLOB", "LONGTEXT", "LOOP", "LOW_PRIORITY",
            "MASTER_BIND", "MAXVALUE", "MEDIUMBLOB", "MEDIUMINT", "MEDIUMTEXT", "MIDDLEINT",
            "MINUTE_MICROSECOND", "MINUTE_SECOND", "MOD", "MODE", "MODIFIES", "MODIFY",
            "MULTILINESTRING", "MULTIPOINT", "MULTIPOLYGON", "NATIONAL", "NCHAR", "NEW",
            "NEXT", "NO", "NOWAIT", "NVARCHAR", "NO_WRITE_TO_BINLOG", "OFFSET", "OJ", "OLD",
            "ONE", "OPTIMIZE", "OPTIMIZER_COSTS", "OPTION", "OPTIONALLY", "OUT", "OUTFILE",
            "PIVOT", "POINT", "POLYGON", "PROCEDURE", "PURGE", "RANGE", "RANK", "READ",
            "READS", "READ_WRITE", "REAL", "RECURSIVE", "REGEXP", "RELEASE", "RENAME",
            "REPEAT", "REPLACE", "REQUIRE", "RESIGNAL", "RETURN", "REVOKE", "RLIKE",
            "ROLLUP", "ROW", "ROWS", "ROW_COUNT", "ROW_FORMAT", "ROW_NUMBER", "SCHEMA",
            "SCHEMAS", "SECOND_MICROSECOND", "SENSITIVE", "SEPARATOR", "SERIAL", "SESSION",
            "SHARE", "SHOW", "SIGNAL", "SIGNED", "SMALLINT", "SPATIAL", "SPECIFIC", "SQL",
            "SQL_BIG_RESULT", "SQL_CALC_FOUND_ROWS", "SQL_NO_CACHE", "SQL_SMALL_RESULT",
            "SQL_TSI_DAY", "SQL_TSI_HOUR", "SQL_TSI_MINUTE", "SQL_TSI_MONTH", "SQL_TSI_QUARTER",
            "SQL_TSI_SECOND", "SQL_TSI_WEEK", "SQL_TSI_YEAR", "SQLEXCEPTION", "SQLSTATE",
            "SQLWARNING", "STARTING", "STORED", "STRAIGHT_JOIN", "SYSTEM", "TERMINATED",
            "TIES", "TINYBLOB", "TINYINT", "TINYTEXT", "TRANSACTION", "TRIGGER", "TRIGGERS",
            "UNDO", "UNINSTALL", "UNLOCK", "UNSIGNED", "UPGRADE", "USAGE", "USE",
            "UTC_DATE", "UTC_TIME", "UTC_TIMESTAMP", "VACUUM", "VARBINARY", "VARCHAR",
            "VARCHARACTER", "VARIANCE", "VIEW", "VIRTUAL", "VISIBLE", "WARNINGS", "WRITE",
            "XOR", "YEAR_MONTH", "ZEROFILL",
    };

    private static final String[] POSTGRES_EXTRA = {
            "ANALYSE", "ANALYZE", "ASYMMETRIC", "AUTHORIZATION", "COLLATE",
            "CONCURRENTLY", "CURRENT_CATALOG", "CURRENT_PATH", "CURRENT_ROLE",
            "CURRENT_SCHEMA", "DAY", "DEFERRABLE", "DO", "HOUR", "ILIKE", "INITIALLY",
            "ISNULL", "LATERAL", "LIMIT", "LOCALTIME", "LOCALTIMESTAMP", "MINUTE", "MONTH",
            "NOTNULL", "NULLS", "OFFSET", "PLACING", "RETURNING", "SECOND", "SESSION_USER",
            "SYMMETRIC", "TABLESAMPLE", "VARIADIC", "VERBOSE", "YEAR",
    };

    private static final String[] ORACLE_EXTRA = {
            "ACCESS", "AUDIT", "CLUSTER", "COLUMN_VALUE", "COMMENT", "COMPRESS", "CONNECT",
            "DATE", "EXCLUSIVE", "FILE", "IDENTIFIED", "IMMEDIATE", "INCREMENT", "INDEX",
            "INITIAL", "INTEGER", "LEVEL", "LONG", "MAXEXTENTS", "MINUS", "MLSLABEL",
            "MODE", "NOCOMPRESS", "NOWAIT", "NUMBER", "OFFLINE", "ONLINE", "OPTION",
            "PCTFREE", "PRIOR", "PUBLIC", "RAW", "RENAME", "RESOURCE", "ROWID", "ROWNUM",
            "ROWS", "SESSION", "SHARE", "SIZE", "SMALLINT", "START", "SUCCESSFUL",
            "SYNONYM", "SYSDATE", "TRIGGER", "UID", "VALIDATE", "VARCHAR", "VARCHAR2",
            "VIEW", "WHENEVER",
    };

    private static final String[] SQLSERVER_EXTRA = {
            "BACKUP", "BEGIN", "BREAK", "BROWSE", "BULK", "CHECKPOINT", "CLUSTERED",
            "COALESCE", "COMPUTE", "CONTAINS", "CONTAINSTABLE", "CURSOR", "DATABASE",
            "DBCC", "DEALLOCATE", "DECLARE", "DENY", "DISK", "DISTRIBUTED", "DOUBLE",
            "DUMP", "ERRLVL", "EXEC", "EXECUTE", "EXIT", "EXTERNAL", "FILLFACTOR",
            "FREETEXT", "FREETEXTTABLE", "FUNCTION", "GOTO", "HOLDLOCK", "IDENTITY",
            "IDENTITY_INSERT", "IDENTITYCOL", "IF", "INDEX", "KILL", "LINENO", "LOAD",
            "MERGE", "NATIONAL", "NOCHECK", "NONCLUSTERED", "NULLIF", "OFF", "OFFSETS",
            "OPEN", "OPENDATASOURCE", "OPENQUERY", "OPENROWSET", "OPENXML", "PERCENT",
            "PIVOT", "PLAN", "PRINT", "PROC", "PROCEDURE", "PUBLIC", "RAISERROR", "READ",
            "READTEXT", "RECONFIGURE", "REPLICATION", "RESTORE", "RETURN", "REVERT",
            "ROWCOUNT", "ROWGUIDCOL", "RULE", "SAVE", "SCHEMA", "SECURITYAUDIT",
            "SETUSER", "SHUTDOWN", "STATISTICS", "SYSTEM_USER", "TABLESAMPLE", "TEXTSIZE",
            "TOP", "TRAN", "TRANSACTION", "TRY_CONVERT", "TSEQUAL", "UNPIVOT",
            "UPDATETEXT", "USE", "VIEW", "WAITFOR", "WHILE", "WITHIN", "WRITETEXT",
    };

    private static final String[] H2_EXTRA = {
            "CURRENT_CATALOG", "CURRENT_PATH", "CURRENT_ROLE", "CURRENT_SCHEMA", "DAY",
            "FILTER", "GROUPS", "HOUR", "IF", "ILIKE", "INTERVAL", "LOCALTIME",
            "LOCALTIMESTAMP", "MINUS", "MINUTE", "MONTH", "QUALIFY", "RANGE", "REGEXP",
            "ROWNUM", "ROWS", "SECOND", "SESSION_USER", "SYMMETRIC", "TOP", "UESCAPE",
            "VALUE", "YEAR", "_ROWID_",
    };

    private static final String[] DB2_EXTRA = {
            "CURRENT_PATH", "CURRENT_SCHEMA", "DAY", "DIAGNOSTICS", "HOUR", "IF", "MINUTE",
            "MONTH", "SECOND", "SESSION_USER", "SYSTEM_USER", "VALUE", "YEAR",
    };

    private static final String[] HIVE_EXTRA = {
            "BIGINT", "BOOLEAN", "DOUBLE", "EXTENDED", "FLOAT", "INT", "SMALLINT",
            "STRING", "TIMESTAMP", "TINYINT",
    };

    private static final String[] CLICKHOUSE_EXTRA = {
            "BIGINT", "BOOLEAN", "DATE", "DATETIME", "DECIMAL", "DOUBLE", "FLOAT", "INT",
            "SMALLINT", "STRING", "TINYINT", "UUID",
    };

    private static final Map<SqlDialect, Set<String>> WORDS = build();

    private SqlReservedWords() {
    }

    private static Map<SqlDialect, Set<String>> build() {
        Map<SqlDialect, Set<String>> out = new EnumMap<SqlDialect, Set<String>>(SqlDialect.class);
        Set<String> ansi = words(BASE);
        Set<String> mysql = union(ansi, MYSQL_EXTRA);
        Set<String> postgres = union(ansi, POSTGRES_EXTRA);
        Set<String> oracle = union(ansi, ORACLE_EXTRA);
        Set<String> sqlserver = union(ansi, SQLSERVER_EXTRA);
        Set<String> h2 = union(ansi, H2_EXTRA);
        Set<String> db2 = union(ansi, DB2_EXTRA);
        Set<String> sqlite = ansi;
        Set<String> hive = union(ansi, HIVE_EXTRA);
        Set<String> clickhouse = union(ansi, CLICKHOUSE_EXTRA);
        Set<String> presto = ansi;
        put(out, SqlDialect.ANSI, ansi);
        put(out, SqlDialect.MYSQL, mysql);
        put(out, SqlDialect.POSTGRES, postgres);
        put(out, SqlDialect.ORACLE, oracle);
        put(out, SqlDialect.ORACLE12, oracle);
        put(out, SqlDialect.SQLSERVER, sqlserver);
        put(out, SqlDialect.H2, h2);
        put(out, SqlDialect.DB2, db2);
        put(out, SqlDialect.SQLITE, sqlite);
        put(out, SqlDialect.HIVE, hive);
        put(out, SqlDialect.CLICKHOUSE, clickhouse);
        put(out, SqlDialect.PRESTO, presto);
        put(out, SqlDialect.DAMENG, union(oracle, new String[] {"BYTES", "LOB", "TYPE"}));
        return out;
    }

    private static void put(Map<SqlDialect, Set<String>> out, SqlDialect dialect, Set<String> words) {
        out.put(dialect, Collections.unmodifiableSet(words));
    }

    private static Set<String> words(String[] list) {
        Set<String> out = new HashSet<String>(list.length * 2);
        for (int i = 0; i < list.length; i++) {
            out.add(list[i]);
        }
        return out;
    }

    private static Set<String> union(Set<String> base, String[] extra) {
        Set<String> out = new HashSet<String>(base.size() + extra.length * 2);
        out.addAll(base);
        for (int i = 0; i < extra.length; i++) {
            out.add(extra[i]);
        }
        return out;
    }

    /**
     * 判断标识符是否为目标方言的保留字（大小写不敏感）。
     *
     * @param dialect 方言，null 视为 ANSI 核心集
     * @param identifier 标识符（表名 / 列名 / 索引名等）
     * @return 是保留字返回 {@code true}
     */
    public static boolean isKeyword(SqlDialect dialect, String identifier) {
        if (identifier == null || identifier.isEmpty()) {
            return false;
        }
        Set<String> words = dialect == null ? WORDS.get(SqlDialect.ANSI) : WORDS.get(dialect);
        return words != null && words.contains(identifier.toUpperCase(Locale.ROOT));
    }

    /**
     * 生成标识符的最终文本，无告警回调的便捷重载。
     *
     * @param dialect 方言
     * @param identifier 标识符原文
     * @param quoteAll 是否全部加引号（quote-identifiers 语义）
     * @param keywordAware 是否启用保留字自动引号
     * @return 可直接拼进 SQL 的标识符文本
     */
    public static String protect(SqlDialect dialect, String identifier, boolean quoteAll,
                                 boolean keywordAware) {
        return protect(dialect, identifier, quoteAll, keywordAware, null);
    }

    /**
     * 生成标识符的最终文本：{@code quoteAll} 时无条件加方言引号；否则命中保留字时
     * 加引号，并通过 {@code listener} 告警一次（每标识符每方言只回调一次）。
     *
     * @param dialect 方言
     * @param identifier 标识符原文
     * @param quoteAll 是否全部加引号（quote-identifiers 语义）
     * @param keywordAware 是否启用保留字自动引号
     * @param listener 自动引号告警回调，可空；入参为 (方言, 标识符原文)
     * @return 可直接拼进 SQL 的标识符文本
     */
    public static String protect(SqlDialect dialect, String identifier, boolean quoteAll,
                                 boolean keywordAware, BiConsumer<SqlDialect, String> listener) {
        if (identifier == null || identifier.isEmpty()) {
            return identifier;
        }
        SqlDialect d = dialect == null ? SqlDialect.ANSI : dialect;
        if (quoteAll) {
            return d.quoteIdent(identifier);
        }
        if (keywordAware && isKeyword(d, identifier)) {
            if (WARNED.add(d.name() + ":" + identifier.toLowerCase(Locale.ROOT))
                    && listener != null) {
                listener.accept(d, identifier);
            }
            return d.quoteIdent(identifier);
        }
        return identifier;
    }
}
