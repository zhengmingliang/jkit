package com.alianga.jkit.sql.schema;

import com.alianga.jkit.sql.SqlDialect;
import com.alianga.jkit.sql.SqlDialectSpec;
import com.alianga.jkit.sql.ast.SqlExpr;
import com.alianga.jkit.sql.ast.SqlFunctionExpr;
import com.alianga.jkit.sql.ast.SqlIdentifier;
import com.alianga.jkit.sql.schema.convert.ConversionReport;
import com.alianga.jkit.sql.schema.model.CanonicalType;
import com.alianga.jkit.sql.schema.registry.SqlDataTypeRegistry;
import com.alianga.jkit.sql.schema.rewrite.FunctionRewriteRule;
import com.alianga.jkit.sql.schema.rewrite.SqlFunctionRegistry;
import com.alianga.jkit.sql.schema.spi.SqlSchemaConverterProvider;

/**
 * 测试用 SPI：MySQL {@code MIDINT} → MEDIUMINT；函数 {@code JKIT_SPI_FN} → {@code SPI_OK}；
 * {@code DATE_FORMAT} 返回 null 以验证回落到内置规则。
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
    public void registerFunctions(SqlFunctionRegistry registry) {
        registry.register("JKIT_SPI_FN", new FunctionRewriteRule() {
            /**
             * {@inheritDoc}
             */
            @Override
            public SqlExpr rewrite(SqlFunctionExpr fn, SqlDialectSpec source, SqlDialectSpec target,
                                   ConversionReport.Builder report) {
                fn.setName(SqlIdentifier.of("SPI_OK"));
                return fn;
            }
        });
        // 返回 null：walker 必须回落到内置 DATE_FORMAT → TO_CHAR，不能把函数吃掉
        registry.register("DATE_FORMAT", new FunctionRewriteRule() {
            /**
             * {@inheritDoc}
             */
            @Override
            public SqlExpr rewrite(SqlFunctionExpr fn, SqlDialectSpec source, SqlDialectSpec target,
                                   ConversionReport.Builder report) {
                return null;
            }
        });
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public int priority() {
        return 200;
    }
}
