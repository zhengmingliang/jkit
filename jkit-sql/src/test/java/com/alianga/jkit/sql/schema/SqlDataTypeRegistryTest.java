package com.alianga.jkit.sql.schema;

import com.alianga.jkit.sql.SqlDialect;
import com.alianga.jkit.sql.schema.model.CanonicalType;
import com.alianga.jkit.sql.schema.model.ColumnDefinition;
import com.alianga.jkit.sql.schema.model.SqlDataType;
import com.alianga.jkit.sql.schema.parse.SqlColumnDefinitionParser;
import com.alianga.jkit.sql.schema.registry.LossyMapping;
import com.alianga.jkit.sql.schema.registry.SqlDataTypeRegistry;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * canonical 类型注册表：正向、反向、有损映射、冻结。
 *
 * @author 郑明亮
 */
public class SqlDataTypeRegistryTest {

    private final SqlDataTypeRegistry registry = SqlDataTypeRegistry.builtins();

    @Test
    public void mysqlIntToPostgresInteger() {
        assertEquals("INTEGER", registry.convert("INT", SqlDialect.MYSQL, SqlDialect.POSTGRES));
        assertEquals("INTEGER", registry.convert("INT(11)", SqlDialect.MYSQL, SqlDialect.POSTGRES));
        assertEquals("INT", registry.convert("INTEGER", SqlDialect.POSTGRES, SqlDialect.MYSQL));
    }

    @Test
    public void mysqlVarcharKeepsPrecision() {
        assertEquals("VARCHAR(100)",
                registry.convert("VARCHAR(100)", SqlDialect.MYSQL, SqlDialect.POSTGRES));
        assertEquals("VARCHAR2(100)",
                registry.convert("VARCHAR(100)", SqlDialect.MYSQL, SqlDialect.ORACLE));
    }

    @Test
    public void mysqlDecimalToOracleNumber() {
        assertEquals("NUMBER(10,2)",
                registry.convert("DECIMAL(10,2)", SqlDialect.MYSQL, SqlDialect.ORACLE));
        assertEquals("NUMERIC(10,2)",
                registry.convert("DECIMAL(10,2)", SqlDialect.MYSQL, SqlDialect.POSTGRES));
    }

    @Test
    public void mysqlDatetimeToPostgresTimestamp() {
        assertEquals("TIMESTAMP",
                registry.convert("DATETIME", SqlDialect.MYSQL, SqlDialect.POSTGRES));
        assertEquals(CanonicalType.DATETIME,
                registry.fromDialect("DATETIME", SqlDialect.MYSQL));
        assertEquals(CanonicalType.TIMESTAMP,
                registry.fromDialect("TIMESTAMP", SqlDialect.POSTGRES));
    }

    @Test
    public void postgresTimestampReverseUsesPrimary() {
        assertEquals(CanonicalType.TIMESTAMP,
                registry.fromDialect("TIMESTAMP", SqlDialect.POSTGRES));
        LossyMapping mapping = registry.findLossy(SqlDialect.POSTGRES, "TIMESTAMP");
        assertNotNull(mapping);
        assertEquals(CanonicalType.TIMESTAMP, mapping.primary());
        assertTrue(mapping.collapsedFrom().contains(CanonicalType.DATETIME));
    }

    @Test
    public void mysqlTinyint1IsBoolean() {
        assertEquals(CanonicalType.BOOLEAN, registry.fromDialect("TINYINT(1)", SqlDialect.MYSQL));
        assertEquals(CanonicalType.TINYINT, registry.fromDialect("TINYINT", SqlDialect.MYSQL));
        assertEquals(CanonicalType.TINYINT, registry.fromDialect("TINYINT(4)", SqlDialect.MYSQL));
        assertEquals("BOOLEAN", registry.convert("TINYINT(1)", SqlDialect.MYSQL, SqlDialect.POSTGRES));
    }

    @Test
    public void oracleNumberByPrecision() {
        assertEquals(CanonicalType.INT, registry.fromDialect("NUMBER(10)", SqlDialect.ORACLE));
        assertEquals(CanonicalType.TINYINT, registry.fromDialect("NUMBER(3)", SqlDialect.ORACLE));
        assertEquals(CanonicalType.DECIMAL, registry.fromDialect("NUMBER(10,2)", SqlDialect.ORACLE));
        assertEquals(CanonicalType.DECIMAL, registry.fromDialect("NUMBER", SqlDialect.ORACLE));
        assertEquals(CanonicalType.BOOLEAN, registry.fromDialect("NUMBER(1)", SqlDialect.ORACLE));
    }

    @Test
    public void completeLiteralIgnoresPrecision() {
        assertEquals("NUMBER(10)",
                registry.toDialect(CanonicalType.INT, SqlDialect.ORACLE, Integer.valueOf(20), null));
        assertEquals("TINYINT(1)",
                registry.toDialect(CanonicalType.BOOLEAN, SqlDialect.MYSQL, Integer.valueOf(4), null));
    }

    @Test
    public void varcharWithoutPrecisionStripsParens() {
        assertEquals("VARCHAR",
                registry.toDialect(CanonicalType.VARCHAR, SqlDialect.MYSQL, null, null));
        assertEquals("DECIMAL",
                registry.toDialect(CanonicalType.DECIMAL, SqlDialect.MYSQL, null, null));
    }

    @Test
    public void unknownTypeReturnedAsOriginal() {
        assertEquals("GEOMETRY",
                registry.convert("GEOMETRY", SqlDialect.MYSQL, SqlDialect.POSTGRES));
        assertEquals(CanonicalType.UNKNOWN, registry.fromDialect("GEOMETRY", SqlDialect.MYSQL));
    }

    @Test
    public void aliases() {
        assertEquals(CanonicalType.INT, registry.fromDialect("INTEGER", SqlDialect.MYSQL));
        assertEquals(CanonicalType.VARCHAR, registry.fromDialect("VARCHAR2(50)", SqlDialect.ORACLE));
        assertEquals(CanonicalType.DECIMAL, registry.fromDialect("NUMERIC(8,2)", SqlDialect.POSTGRES));
        assertEquals(CanonicalType.JSON, registry.fromDialect("JSONB", SqlDialect.POSTGRES));
        assertEquals(CanonicalType.TEXT, registry.fromDialect("LONGTEXT", SqlDialect.MYSQL));
    }

    @Test
    public void structuredTypeFromParser() {
        ColumnDefinition col = SqlColumnDefinitionParser.parse(
                "name VARCHAR(64) NOT NULL", SqlDialect.MYSQL);
        CanonicalType type = registry.fromDialect(col.dataType(), SqlDialect.MYSQL);
        assertEquals(CanonicalType.VARCHAR, type);
        assertEquals("VARCHAR2(64)",
                registry.toDialect(type, SqlDialect.ORACLE, col.dataType().precision(), null));
    }

    @Test
    public void sqlserverVarcharMax() {
        assertEquals("VARCHAR(MAX)",
                registry.toDialect(CanonicalType.TEXT, SqlDialect.SQLSERVER, null, null));
        SqlDataType parsed = new SqlDataType("VARCHAR(MAX)", null, null, null);
        assertEquals(CanonicalType.TEXT, registry.fromDialect(parsed, SqlDialect.SQLSERVER));
    }

    @Test
    public void oracle12SharesOracleTypes() {
        assertEquals("VARCHAR2(20)",
                registry.convert("VARCHAR(20)", SqlDialect.MYSQL, SqlDialect.ORACLE12));
        assertEquals("NUMBER(10)",
                registry.convert("INT", SqlDialect.MYSQL, SqlDialect.ORACLE12));
    }

    @Test
    public void clickhouseAndHiveAndSqlite() {
        assertEquals("Int32", registry.convert("INT", SqlDialect.MYSQL, SqlDialect.CLICKHOUSE));
        assertEquals("INT", registry.convert("INT", SqlDialect.MYSQL, SqlDialect.HIVE));
        assertEquals("INTEGER", registry.convert("INT", SqlDialect.MYSQL, SqlDialect.SQLITE));
        assertEquals("STRING", registry.convert("TEXT", SqlDialect.MYSQL, SqlDialect.HIVE));
    }

    @Test
    public void frozenRegistryRejectsMutation() {
        try {
            registry.registerAlias(SqlDialect.MYSQL, "FOO", CanonicalType.INT);
            fail("expected IllegalStateException");
        } catch (IllegalStateException expected) {
            assertTrue(expected.getMessage().contains("frozen"));
        }
    }

    @Test
    public void builtinsIsSingleton() {
        assertSame(SqlDataTypeRegistry.builtins(), SqlDataTypeRegistry.builtins());
    }

    @Test
    public void lossyDatetimeRoundtripLandsInEquivalenceClass() {
        String pg = registry.convert("DATETIME", SqlDialect.MYSQL, SqlDialect.POSTGRES);
        assertEquals("TIMESTAMP", pg);
        CanonicalType back = registry.fromDialect(pg, SqlDialect.POSTGRES);
        assertEquals(CanonicalType.TIMESTAMP, back);
        LossyMapping mapping = registry.findLossy(SqlDialect.POSTGRES, "TIMESTAMP");
        assertNotNull(mapping);
        assertTrue(mapping.collapsedFrom().contains(CanonicalType.DATETIME));
        assertTrue(mapping.collapsedFrom().contains(back));
    }

    @Test
    public void lossyMediumintBecomesIntOnPostgres() {
        String pg = registry.convert("MEDIUMINT", SqlDialect.MYSQL, SqlDialect.POSTGRES);
        assertEquals("INTEGER", pg);
        assertEquals(CanonicalType.INT, registry.fromDialect(pg, SqlDialect.POSTGRES));
        assertEquals("INT", registry.convert(pg, SqlDialect.POSTGRES, SqlDialect.MYSQL));
    }

    @Test
    public void roundtripEveryCanonicalOnEveryDialect() {
        CanonicalType[] types = CanonicalType.values();
        SqlDialect[] dialects = SqlDialect.values();
        for (int ti = 0; ti < types.length; ti++) {
            CanonicalType t = types[ti];
            if (t == CanonicalType.UNKNOWN) {
                continue;
            }
            Integer p = t.requiresPrecision() ? Integer.valueOf(10) : null;
            Integer s = t.requiresScale() ? Integer.valueOf(2) : null;
            for (int di = 0; di < dialects.length; di++) {
                SqlDialect d = dialects[di];
                String form = registry.toDialect(t, d, p, s);
                CanonicalType back = registry.fromDialect(form, d);
                LossyMapping lossy = registry.findLossy(d, form);
                if (lossy != null) {
                    assertTrue(t + " on " + d + " form " + form + " not in " + lossy.collapsedFrom(),
                            lossy.collapsedFrom().contains(t));
                    assertEquals(t + " on " + d + " " + form, lossy.primary(), back);
                } else {
                    assertEquals(t + " on " + d + " rendered " + form, t, back);
                }
            }
        }
    }

    @Test
    public void roundtripNonLossyMysqlPostgres() {
        CanonicalType[] types = {
                CanonicalType.INT, CanonicalType.BIGINT, CanonicalType.VARCHAR,
                CanonicalType.DECIMAL, CanonicalType.DATE, CanonicalType.JSON
        };
        for (int i = 0; i < types.length; i++) {
            CanonicalType t = types[i];
            Integer p = t.requiresPrecision() ? Integer.valueOf(12) : null;
            Integer s = t.requiresScale() ? Integer.valueOf(3) : null;
            String mysql = registry.toDialect(t, SqlDialect.MYSQL, p, s);
            CanonicalType back = registry.fromDialect(mysql, SqlDialect.MYSQL);
            assertEquals(t.name(), t, back);
            String pg = registry.toDialect(t, SqlDialect.POSTGRES, p, s);
            CanonicalType fromPg = registry.fromDialect(pg, SqlDialect.POSTGRES);
            assertEquals(t.name() + " via PG " + pg, t, fromPg);
        }
    }
}
