package com.alianga.jkit.sql.schema;

import com.alianga.jkit.sql.SqlDialect;
import com.alianga.jkit.sql.schema.model.CanonicalType;
import com.alianga.jkit.sql.schema.registry.SqlDataTypeRegistry;
import com.alianga.jkit.sql.schema.spi.SqlSchemaConverterProvider;
import com.alianga.jkit.sql.schema.spi.SqlSchemaConverterProviderTestKit;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

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
}
