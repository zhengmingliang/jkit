package com.alianga.jkit.sql.schema.registry;

import com.alianga.jkit.sql.SqlDialect;
import com.alianga.jkit.sql.schema.model.CanonicalType;
import com.alianga.jkit.sql.schema.spi.SqlSchemaConverterProvider;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;
import java.util.ServiceLoader;

/**
 * 内置 12 种方言的 canonical 类型声明、别名与有损映射。
 *
 * @author 郑明亮
 * @since 2.0.1
 */
final class SqlDataTypeRegistryBuiltins {
    private SqlDataTypeRegistryBuiltins() {
    }

    static SqlDataTypeRegistry create() {
        SqlDataTypeRegistry r = new SqlDataTypeRegistry();
        registerTypes(r);
        registerAliases(r);
        registerLossy(r);
        loadProviders(r);
        r.freeze();
        RegistryValidator.validate(r).throwIfInvalid();
        return r;
    }

    private static void loadProviders(SqlDataTypeRegistry registry) {
        List<SqlSchemaConverterProvider> providers = new ArrayList<SqlSchemaConverterProvider>();
        for (SqlSchemaConverterProvider p : ServiceLoader.load(SqlSchemaConverterProvider.class)) {
            if (p != null) {
                providers.add(p);
            }
        }
        Collections.sort(providers, new Comparator<SqlSchemaConverterProvider>() {
            /**
             * {@inheritDoc}
             */
            @Override
            public int compare(SqlSchemaConverterProvider a, SqlSchemaConverterProvider b) {
                return Integer.compare(a.priority(), b.priority());
            }
        });
        for (int i = 0; i < providers.size(); i++) {
            providers.get(i).registerTypes(registry);
        }
    }

    private static void registerTypes(SqlDataTypeRegistry r) {
        // mysql, postgres, oracle, sqlserver, ansi/h2, db2, sqlite, hive, clickhouse, presto
        put(r, CanonicalType.TINYINT,
                "TINYINT", "SMALLINT", "NUMBER(3)", "TINYINT",
                "SMALLINT", "SMALLINT", "INTEGER", "TINYINT", "Int8", "TINYINT");
        put(r, CanonicalType.SMALLINT,
                "SMALLINT", "SMALLINT", "NUMBER(5)", "SMALLINT",
                "SMALLINT", "SMALLINT", "INTEGER", "SMALLINT", "Int16", "SMALLINT");
        put(r, CanonicalType.MEDIUMINT,
                "MEDIUMINT", "INTEGER", "NUMBER(8)", "INT",
                "INTEGER", "INTEGER", "INTEGER", "INT", "Int32", "INTEGER");
        put(r, CanonicalType.INT,
                "INT", "INTEGER", "NUMBER(10)", "INT",
                "INTEGER", "INTEGER", "INTEGER", "INT", "Int32", "INTEGER");
        put(r, CanonicalType.BIGINT,
                "BIGINT", "BIGINT", "NUMBER(20)", "BIGINT",
                "BIGINT", "BIGINT", "INTEGER", "BIGINT", "Int64", "BIGINT");
        put(r, CanonicalType.FLOAT,
                "FLOAT", "REAL", "BINARY_FLOAT", "REAL",
                "REAL", "REAL", "REAL", "FLOAT", "Float32", "REAL");
        put(r, CanonicalType.DOUBLE,
                "DOUBLE", "DOUBLE PRECISION", "BINARY_DOUBLE", "FLOAT",
                "DOUBLE PRECISION", "DOUBLE", "REAL", "DOUBLE", "Float64", "DOUBLE");
        put(r, CanonicalType.DECIMAL,
                "DECIMAL(%d,%d)", "NUMERIC(%d,%d)", "NUMBER(%d,%d)", "DECIMAL(%d,%d)",
                "DECIMAL(%d,%d)", "DECIMAL(%d,%d)", "DECIMAL(%d,%d)", "DECIMAL(%d,%d)",
                "Decimal(%d,%d)", "DECIMAL(%d,%d)");
        put(r, CanonicalType.CHAR,
                "CHAR(%d)", "CHAR(%d)", "CHAR(%d)", "CHAR(%d)",
                "CHAR(%d)", "CHAR(%d)", "CHAR(%d)", "CHAR(%d)", "FixedString(%d)", "CHAR(%d)");
        put(r, CanonicalType.VARCHAR,
                "VARCHAR(%d)", "VARCHAR(%d)", "VARCHAR2(%d)", "VARCHAR(%d)",
                "VARCHAR(%d)", "VARCHAR(%d)", "TEXT", "VARCHAR(%d)", "String", "VARCHAR(%d)");
        put(r, CanonicalType.TEXT,
                "TEXT", "TEXT", "CLOB", "VARCHAR(MAX)",
                "CLOB", "CLOB", "TEXT", "STRING", "String", "VARCHAR");
        put(r, CanonicalType.DATE,
                "DATE", "DATE", "DATE", "DATE",
                "DATE", "DATE", "DATE", "DATE", "Date", "DATE");
        put(r, CanonicalType.DATETIME,
                "DATETIME", "TIMESTAMP", "TIMESTAMP", "DATETIME",
                "TIMESTAMP", "TIMESTAMP", "DATETIME", "TIMESTAMP", "DateTime", "TIMESTAMP");
        put(r, CanonicalType.TIMESTAMP,
                "TIMESTAMP", "TIMESTAMP", "TIMESTAMP", "DATETIME2",
                "TIMESTAMP", "TIMESTAMP", "DATETIME", "TIMESTAMP", "DateTime", "TIMESTAMP");
        put(r, CanonicalType.TIME,
                "TIME", "TIME", "TIMESTAMP", "TIME",
                "TIME", "TIME", "TEXT", "STRING", "String", "TIME");
        put(r, CanonicalType.BINARY,
                "BINARY(%d)", "BYTEA", "RAW(%d)", "BINARY(%d)",
                "BINARY(%d)", "CHAR(%d) FOR BIT DATA", "BLOB", "BINARY",
                "FixedString(%d)", "VARBINARY");
        put(r, CanonicalType.BLOB,
                "BLOB", "BYTEA", "BLOB", "VARBINARY(MAX)",
                "BLOB", "BLOB", "BLOB", "BINARY", "String", "VARBINARY");
        put(r, CanonicalType.BOOLEAN,
                "TINYINT(1)", "BOOLEAN", "NUMBER(1)", "BIT",
                "BOOLEAN", "SMALLINT", "INTEGER", "BOOLEAN", "Bool", "BOOLEAN");
        put(r, CanonicalType.JSON,
                "JSON", "JSONB", "CLOB", "NVARCHAR(MAX)",
                "CLOB", "CLOB", "TEXT", "STRING", "String", "JSON");
        put(r, CanonicalType.YEAR,
                "YEAR", "SMALLINT", "NUMBER(4)", "SMALLINT",
                "SMALLINT", "SMALLINT", "INTEGER", "INT", "Int16", "SMALLINT");
    }

    /**
     * @param mysql 同时用于 MYSQL
     * @param postgres POSTGRES
     * @param oracle ORACLE 与 ORACLE12
     * @param sqlserver SQLSERVER
     * @param ansi ANSI 与 H2
     * @param db2 DB2
     * @param sqlite SQLITE
     * @param hive HIVE
     * @param clickhouse CLICKHOUSE
     * @param presto PRESTO
     */
    private static void put(SqlDataTypeRegistry r, CanonicalType type,
                            String mysql, String postgres, String oracle, String sqlserver,
                            String ansi, String db2, String sqlite, String hive,
                            String clickhouse, String presto) {
        r.register(type, SqlDialect.MYSQL, DialectTypeForm.of(mysql));
        r.register(type, SqlDialect.POSTGRES, DialectTypeForm.of(postgres));
        r.register(type, SqlDialect.ORACLE, DialectTypeForm.of(oracle));
        r.register(type, SqlDialect.ORACLE12, DialectTypeForm.of(oracle));
        r.register(type, SqlDialect.SQLSERVER, DialectTypeForm.of(sqlserver));
        r.register(type, SqlDialect.ANSI, DialectTypeForm.of(ansi));
        r.register(type, SqlDialect.H2, DialectTypeForm.of(ansi));
        r.register(type, SqlDialect.DB2, DialectTypeForm.of(db2));
        r.register(type, SqlDialect.SQLITE, DialectTypeForm.of(sqlite));
        r.register(type, SqlDialect.HIVE, DialectTypeForm.of(hive));
        r.register(type, SqlDialect.CLICKHOUSE, DialectTypeForm.of(clickhouse));
        r.register(type, SqlDialect.PRESTO, DialectTypeForm.of(presto));
    }

    private static void registerAliases(SqlDataTypeRegistry r) {
        r.registerAliasAll("INTEGER", CanonicalType.INT);
        r.registerAliasAll("INT4", CanonicalType.INT);
        r.registerAliasAll("INT2", CanonicalType.SMALLINT);
        r.registerAliasAll("SMALLINT", CanonicalType.SMALLINT);
        r.registerAliasAll("BIGINT", CanonicalType.BIGINT);
        r.registerAliasAll("NUMERIC", CanonicalType.DECIMAL);
        r.registerAliasAll("DEC", CanonicalType.DECIMAL);
        r.registerAliasAll("NUMBER", CanonicalType.DECIMAL);
        r.registerAliasAll("CHARACTER", CanonicalType.CHAR);
        r.registerAliasAll("CHARACTER VARYING", CanonicalType.VARCHAR);
        r.registerAliasAll("VARCHAR2", CanonicalType.VARCHAR);
        r.registerAliasAll("NVARCHAR", CanonicalType.VARCHAR);
        r.registerAliasAll("NVARCHAR2", CanonicalType.VARCHAR);
        r.registerAliasAll("NCHAR", CanonicalType.CHAR);
        r.registerAliasAll("BOOL", CanonicalType.BOOLEAN);
        r.registerAliasAll("BOOLEAN", CanonicalType.BOOLEAN);
        r.registerAliasAll("TINYTEXT", CanonicalType.TEXT);
        r.registerAliasAll("MEDIUMTEXT", CanonicalType.TEXT);
        r.registerAliasAll("LONGTEXT", CanonicalType.TEXT);
        r.registerAliasAll("CLOB", CanonicalType.TEXT);
        r.registerAliasAll("NCLOB", CanonicalType.TEXT);
        r.registerAliasAll("TINYBLOB", CanonicalType.BLOB);
        r.registerAliasAll("MEDIUMBLOB", CanonicalType.BLOB);
        r.registerAliasAll("LONGBLOB", CanonicalType.BLOB);
        r.registerAliasAll("BYTEA", CanonicalType.BLOB);
        r.registerAliasAll("VARBINARY", CanonicalType.BLOB);
        r.registerAliasAll("IMAGE", CanonicalType.BLOB);
        r.registerAliasAll("JSONB", CanonicalType.JSON);
        r.registerAliasAll("JSON", CanonicalType.JSON);
        r.registerAliasAll("DATETIME2", CanonicalType.TIMESTAMP);
        r.registerAliasAll("SMALLDATETIME", CanonicalType.DATETIME);
        r.registerAliasAll("BINARY_FLOAT", CanonicalType.FLOAT);
        r.registerAliasAll("BINARY_DOUBLE", CanonicalType.DOUBLE);
        r.registerAliasAll("REAL", CanonicalType.FLOAT);
        r.registerAliasAll("DOUBLE PRECISION", CanonicalType.DOUBLE);
        r.registerAliasAll("TIMESTAMPTZ", CanonicalType.TIMESTAMP);
        r.registerAliasAll("STRING", CanonicalType.TEXT);
        r.registerAlias(SqlDialect.POSTGRES, "INT8", CanonicalType.BIGINT);
        r.registerAlias(SqlDialect.POSTGRES, "SERIAL", CanonicalType.INT);
        r.registerAlias(SqlDialect.POSTGRES, "BIGSERIAL", CanonicalType.BIGINT);
        r.registerAlias(SqlDialect.POSTGRES, "SMALLSERIAL", CanonicalType.SMALLINT);
        r.registerAlias(SqlDialect.SQLSERVER, "VARCHAR(MAX)", CanonicalType.TEXT);
        r.registerAlias(SqlDialect.SQLSERVER, "NVARCHAR(MAX)", CanonicalType.JSON);
        r.registerAlias(SqlDialect.SQLSERVER, "VARBINARY(MAX)", CanonicalType.BLOB);
    }

    private static void registerLossy(SqlDataTypeRegistry r) {
        SqlDialect[] pgLike = {SqlDialect.POSTGRES};
        SqlDialect[] oracle = {SqlDialect.ORACLE, SqlDialect.ORACLE12};
        SqlDialect[] ansiH2 = {SqlDialect.ANSI, SqlDialect.H2};
        lossy(r, pgLike, "SMALLINT", CanonicalType.SMALLINT,
                CanonicalType.TINYINT, CanonicalType.YEAR);
        lossy(r, pgLike, "INTEGER", CanonicalType.INT, CanonicalType.MEDIUMINT);
        lossy(r, pgLike, "TIMESTAMP", CanonicalType.TIMESTAMP, CanonicalType.DATETIME);
        lossy(r, pgLike, "BYTEA", CanonicalType.BLOB, CanonicalType.BINARY);

        lossy(r, oracle, "TIMESTAMP", CanonicalType.TIMESTAMP,
                CanonicalType.DATETIME, CanonicalType.TIME);
        lossy(r, oracle, "CLOB", CanonicalType.TEXT, CanonicalType.JSON);

        lossy(r, new SqlDialect[] {SqlDialect.SQLSERVER}, "INT", CanonicalType.INT,
                CanonicalType.MEDIUMINT);
        lossy(r, new SqlDialect[] {SqlDialect.SQLSERVER}, "SMALLINT", CanonicalType.SMALLINT,
                CanonicalType.YEAR);

        lossy(r, ansiH2, "SMALLINT", CanonicalType.SMALLINT,
                CanonicalType.TINYINT, CanonicalType.YEAR);
        lossy(r, ansiH2, "INTEGER", CanonicalType.INT, CanonicalType.MEDIUMINT);
        lossy(r, ansiH2, "TIMESTAMP", CanonicalType.TIMESTAMP, CanonicalType.DATETIME);
        lossy(r, ansiH2, "CLOB", CanonicalType.TEXT, CanonicalType.JSON);

        lossy(r, new SqlDialect[] {SqlDialect.DB2}, "SMALLINT", CanonicalType.SMALLINT,
                CanonicalType.TINYINT, CanonicalType.YEAR, CanonicalType.BOOLEAN);
        lossy(r, new SqlDialect[] {SqlDialect.DB2}, "INTEGER", CanonicalType.INT,
                CanonicalType.MEDIUMINT);
        lossy(r, new SqlDialect[] {SqlDialect.DB2}, "TIMESTAMP", CanonicalType.TIMESTAMP,
                CanonicalType.DATETIME);
        lossy(r, new SqlDialect[] {SqlDialect.DB2}, "CLOB", CanonicalType.TEXT, CanonicalType.JSON);

        lossy(r, new SqlDialect[] {SqlDialect.SQLITE}, "INTEGER", CanonicalType.INT,
                CanonicalType.TINYINT, CanonicalType.SMALLINT, CanonicalType.MEDIUMINT,
                CanonicalType.BIGINT, CanonicalType.BOOLEAN, CanonicalType.YEAR);
        lossy(r, new SqlDialect[] {SqlDialect.SQLITE}, "REAL", CanonicalType.FLOAT,
                CanonicalType.DOUBLE);
        lossy(r, new SqlDialect[] {SqlDialect.SQLITE}, "TEXT", CanonicalType.TEXT,
                CanonicalType.VARCHAR, CanonicalType.TIME, CanonicalType.JSON);
        lossy(r, new SqlDialect[] {SqlDialect.SQLITE}, "DATETIME", CanonicalType.DATETIME,
                CanonicalType.TIMESTAMP);
        lossy(r, new SqlDialect[] {SqlDialect.SQLITE}, "BLOB", CanonicalType.BLOB,
                CanonicalType.BINARY);

        lossy(r, new SqlDialect[] {SqlDialect.HIVE}, "INT", CanonicalType.INT,
                CanonicalType.MEDIUMINT, CanonicalType.YEAR);
        lossy(r, new SqlDialect[] {SqlDialect.HIVE}, "TIMESTAMP", CanonicalType.TIMESTAMP,
                CanonicalType.DATETIME);
        lossy(r, new SqlDialect[] {SqlDialect.HIVE}, "STRING", CanonicalType.TEXT,
                CanonicalType.TIME, CanonicalType.JSON);
        lossy(r, new SqlDialect[] {SqlDialect.HIVE}, "BINARY", CanonicalType.BLOB,
                CanonicalType.BINARY);

        lossy(r, new SqlDialect[] {SqlDialect.CLICKHOUSE}, "Int32", CanonicalType.INT,
                CanonicalType.MEDIUMINT);
        lossy(r, new SqlDialect[] {SqlDialect.CLICKHOUSE}, "Int16", CanonicalType.SMALLINT,
                CanonicalType.YEAR);
        lossy(r, new SqlDialect[] {SqlDialect.CLICKHOUSE}, "DateTime", CanonicalType.TIMESTAMP,
                CanonicalType.DATETIME);
        lossy(r, new SqlDialect[] {SqlDialect.CLICKHOUSE}, "String", CanonicalType.TEXT,
                CanonicalType.VARCHAR, CanonicalType.TIME, CanonicalType.JSON, CanonicalType.BLOB);
        lossy(r, new SqlDialect[] {SqlDialect.CLICKHOUSE}, "FixedString(10)", CanonicalType.CHAR,
                CanonicalType.BINARY);

        lossy(r, new SqlDialect[] {SqlDialect.PRESTO}, "INTEGER", CanonicalType.INT,
                CanonicalType.MEDIUMINT);
        lossy(r, new SqlDialect[] {SqlDialect.PRESTO}, "SMALLINT", CanonicalType.SMALLINT,
                CanonicalType.YEAR);
        lossy(r, new SqlDialect[] {SqlDialect.PRESTO}, "TIMESTAMP", CanonicalType.TIMESTAMP,
                CanonicalType.DATETIME);
        lossy(r, new SqlDialect[] {SqlDialect.PRESTO}, "VARBINARY", CanonicalType.BLOB,
                CanonicalType.BINARY);
    }

    private static void lossy(SqlDataTypeRegistry r, SqlDialect[] dialects, String form,
                              CanonicalType primary, CanonicalType... rest) {
        EnumSet<CanonicalType> set = EnumSet.of(primary);
        for (int i = 0; i < rest.length; i++) {
            set.add(rest[i]);
        }
        for (int i = 0; i < dialects.length; i++) {
            r.registerLossyMapping(new LossyMapping(dialects[i], form, primary, set));
        }
    }
}
