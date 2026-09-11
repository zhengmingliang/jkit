package com.alianga.jkit.sql.schema;

import com.alianga.jkit.sql.SqlDialect;
import com.alianga.jkit.sql.schema.model.CanonicalType;
import com.alianga.jkit.sql.schema.registry.SqlDataTypeRegistry;
import com.alianga.jkit.sql.schema.spi.SqlSchemaConverterProvider;

/**
 * 测试用 SPI：为 MySQL 追加 {@code MIDINT} → MEDIUMINT 别名。
 *
 * @author 郑明亮
 */
public final class TestAliasProvider implements SqlSchemaConverterProvider {
    /**
     * {@inheritDoc}
     */
    @Override
    public void registerTypes(SqlDataTypeRegistry registry) {
        registry.registerAlias(SqlDialect.MYSQL, "MIDINT", CanonicalType.MEDIUMINT);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public int priority() {
        return 200;
    }
}
