package com.alianga.jkit.sql.schema;

import com.alianga.jkit.sql.SQL;
import com.alianga.jkit.sql.SqlDialect;
import com.alianga.jkit.sql.schema.model.CanonicalType;
import com.alianga.jkit.sql.schema.registry.SqlDataTypeRegistry;
import com.alianga.jkit.sql.schema.rewrite.SqlFunctionRegistry;
import com.alianga.jkit.sql.schema.spi.SqlSchemaConverterProvider;
import com.alianga.jkit.sql.schema.spi.SqlSchemaConverterProviderTestKit;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

/**
 * SPI TestKit dogfooding：用 SQL Server 验证内置方言覆盖；并用测试 Provider 追加别名。
 *
 * @author 郑明亮
 */
public class SqlSchemaConverterProviderTest extends SqlSchemaConverterProviderTestKit {
    /**
     * {@inheritDoc}
     */
    @Override
    protected SqlSchemaConverterProvider providerUnderTest() {
        return new SqlSchemaConverterProvider() {
            /**
             * {@inheritDoc}
             */
            @Override
            public int priority() {
                return 0;
            }
        };
    }

    /**
     * {@inheritDoc}
     */
    @Override
    protected SqlDialect dialectUnderTest() {
        return SqlDialect.SQLSERVER;
    }

    @Test
    public void testClasspathProviderAliasIsLoaded() {
        assertEquals(CanonicalType.MEDIUMINT,
                SqlDataTypeRegistry.builtins().fromDialect("MIDINT", SqlDialect.MYSQL));
    }

    @Test
    public void registerFunctionsSpiRewritesUnknownFunction() {
        String sql = SQL.convert("SELECT JKIT_SPI_FN(1) FROM t", SqlDialect.MYSQL, SqlDialect.POSTGRES);
        assertTrue(sql, sql.toUpperCase().contains("SPI_OK"));
        assertFalse(sql, sql.toUpperCase().contains("JKIT_SPI_FN"));
    }

    @Test
    public void registerFunctionsSpiNullFallsBackToBuiltinDateFormat() {
        assertNotNull(SqlFunctionRegistry.builtins().find("DATE_FORMAT"));
        assertNotSame(SqlFunctionRegistry.builtins().find("DATE_FORMAT"),
                SqlFunctionRegistry.builtins().findBuiltin("DATE_FORMAT"));
        String sql = SQL.convert("SELECT DATE_FORMAT(ts, '%Y-%m-%d') FROM t",
                SqlDialect.MYSQL, SqlDialect.POSTGRES);
        assertTrue(sql, sql.toUpperCase().contains("TO_CHAR"));
        assertFalse(sql, sql.toUpperCase().contains("DATE_FORMAT"));
    }

    @Test
    public void registerTypesAndFunctionsShareProviderInstance() {
        SqlDataTypeRegistry.builtins();
        SqlFunctionRegistry.builtins();
        assertNotNull(TestAliasProvider.typesInstance);
        assertNotNull(TestAliasProvider.functionsInstance);
        assertSame(TestAliasProvider.typesInstance, TestAliasProvider.functionsInstance);
    }
}
