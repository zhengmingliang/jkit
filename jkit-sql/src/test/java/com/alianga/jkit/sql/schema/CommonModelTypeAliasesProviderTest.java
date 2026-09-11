package com.alianga.jkit.sql.schema;

import com.alianga.jkit.sql.SqlDialect;
import com.alianga.jkit.sql.schema.model.CanonicalType;
import com.alianga.jkit.sql.schema.registry.RegistryValidator;
import com.alianga.jkit.sql.schema.registry.SqlDataTypeRegistry;
import com.alianga.jkit.sql.schema.spi.CommonModelTypeAliasesProvider;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * 对照 common-model {@code FieldConstruct} / {@code FieldTypeConverter} 的源类型别名。
 *
 * @author 郑明亮
 */
public class CommonModelTypeAliasesProviderTest {
    private final SqlDataTypeRegistry registry = SqlDataTypeRegistry.builtins();

    @Test
    public void providerIsOnServiceLoader() {
        assertEquals(50, new CommonModelTypeAliasesProvider().priority());
        assertTrue(RegistryValidator.validate(registry).ok());
    }

    @Test
    public void bpcharIsChar() {
        assertEquals(CanonicalType.CHAR,
                registry.fromDialect("BPCHAR", SqlDialect.POSTGRES));
        assertEquals(CanonicalType.CHAR,
                registry.fromDialect("BPCHAR(10)", SqlDialect.POSTGRES));
        assertEquals("CHAR(10)",
                registry.convert("BPCHAR(10)", SqlDialect.POSTGRES, SqlDialect.MYSQL));
    }

    @Test
    public void float4AndFloat8() {
        assertEquals(CanonicalType.FLOAT, registry.fromDialect("FLOAT4", SqlDialect.POSTGRES));
        assertEquals(CanonicalType.DOUBLE, registry.fromDialect("FLOAT8", SqlDialect.POSTGRES));
        assertEquals("REAL", registry.convert("FLOAT4", SqlDialect.POSTGRES, SqlDialect.POSTGRES));
        assertEquals("DOUBLE PRECISION",
                registry.convert("FLOAT8", SqlDialect.MYSQL, SqlDialect.POSTGRES));
    }

    @Test
    public void longIsBigintLikeFieldConstruct() {
        assertEquals(CanonicalType.BIGINT, registry.fromDialect("LONG", SqlDialect.MYSQL));
        assertEquals("BIGINT", registry.convert("LONG", SqlDialect.MYSQL, SqlDialect.POSTGRES));
        assertEquals("NUMBER(20)", registry.convert("LONG", SqlDialect.MYSQL, SqlDialect.ORACLE));
    }

    @Test
    public void bitIsBooleanLikeFieldConstruct() {
        assertEquals(CanonicalType.BOOLEAN, registry.fromDialect("BIT", SqlDialect.MYSQL));
        assertEquals("BOOLEAN", registry.convert("BIT", SqlDialect.MYSQL, SqlDialect.POSTGRES));
        assertEquals("TINYINT(1)", registry.convert("BIT", SqlDialect.SQLSERVER, SqlDialect.MYSQL));
    }

    @Test
    public void int8IsBigintExceptClickhouseTinyint() {
        assertEquals(CanonicalType.BIGINT, registry.fromDialect("INT8", SqlDialect.POSTGRES));
        assertEquals(CanonicalType.BIGINT, registry.fromDialect("INT8", SqlDialect.MYSQL));
        assertEquals("BIGINT", registry.convert("INT8", SqlDialect.MYSQL, SqlDialect.POSTGRES));
        assertEquals(CanonicalType.TINYINT, registry.fromDialect("Int8", SqlDialect.CLICKHOUSE));
        assertEquals("TINYINT", registry.convert("Int8", SqlDialect.CLICKHOUSE, SqlDialect.MYSQL));
    }

    @Test
    public void fieldConstructNamesAlreadyInBuiltinsStillWork() {
        assertEquals(CanonicalType.INT, registry.fromDialect("INT4", SqlDialect.POSTGRES));
        assertEquals(CanonicalType.SMALLINT, registry.fromDialect("INT2", SqlDialect.POSTGRES));
        assertEquals(CanonicalType.BOOLEAN, registry.fromDialect("BOOL", SqlDialect.POSTGRES));
        assertEquals(CanonicalType.BLOB, registry.fromDialect("BYTEA", SqlDialect.POSTGRES));
        assertEquals(CanonicalType.TEXT, registry.fromDialect("STRING", SqlDialect.HIVE));
        assertEquals(CanonicalType.TEXT, registry.fromDialect("CLOB", SqlDialect.ORACLE));
        assertEquals(CanonicalType.BLOB, registry.fromDialect("IMAGE", SqlDialect.SQLSERVER));
        assertEquals(CanonicalType.VARCHAR, registry.fromDialect("NVARCHAR2", SqlDialect.ORACLE));
        assertEquals(CanonicalType.TEXT, registry.fromDialect("LONGTEXT", SqlDialect.MYSQL));
        assertEquals(CanonicalType.DATETIME, registry.fromDialect("SMALLDATETIME", SqlDialect.SQLSERVER));
    }
}
