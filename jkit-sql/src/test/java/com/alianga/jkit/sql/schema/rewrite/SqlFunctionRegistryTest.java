package com.alianga.jkit.sql.schema.rewrite;

import com.alianga.jkit.sql.SqlDialect;
import com.alianga.jkit.sql.SqlDialectSpec;
import com.alianga.jkit.sql.ast.SqlExpr;
import com.alianga.jkit.sql.ast.SqlFunctionExpr;
import com.alianga.jkit.sql.ast.SqlIdentifier;
import com.alianga.jkit.sql.schema.convert.ConversionReport;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.fail;

/**
 * {@link SqlFunctionRegistry}：后注册覆盖、快照回落、freeze。
 *
 * @author 郑明亮
 */
public class SqlFunctionRegistryTest {

    @Test
    public void laterRegisterOverridesEarlier() {
        SqlFunctionRegistry registry = new SqlFunctionRegistry();
        registry.register("FOO", renameTo("A"));
        registry.register("FOO", renameTo("B"));
        SqlFunctionExpr fn = named("FOO");
        SqlExpr out = registry.find("FOO").rewrite(fn, SqlDialect.MYSQL, SqlDialect.POSTGRES,
                new ConversionReport.Builder());
        assertEquals("B", ((SqlFunctionExpr) out).name().simpleName());
    }

    @Test
    public void snapshotKeepsBuiltinWhenOverlayReturnsNull() {
        SqlFunctionRegistry registry = new SqlFunctionRegistry();
        FunctionRewriteRule builtin = renameTo("BUILTIN");
        registry.register("FOO", builtin);
        registry.snapshotBuiltins();
        registry.register("FOO", new FunctionRewriteRule() {
            /**
             * {@inheritDoc}
             */
            @Override
            public SqlExpr rewrite(SqlFunctionExpr fn, SqlDialectSpec source, SqlDialectSpec target,
                                   ConversionReport.Builder report) {
                return null;
            }
        });
        assertSame(builtin, registry.findBuiltin("FOO"));
        assertNull(registry.find("FOO").rewrite(named("FOO"), SqlDialect.MYSQL,
                SqlDialect.POSTGRES, new ConversionReport.Builder()));
    }

    @Test
    public void builtinsIncludeDateFormatAndIf() {
        assertNotNull(SqlFunctionRegistry.builtins().findBuiltin("DATE_FORMAT"));
        assertNotNull(SqlFunctionRegistry.builtins().findBuiltin("IF"));
        assertNotNull(SqlFunctionRegistry.builtins().find("JKIT_SPI_FN"));
        assertNull(SqlFunctionRegistry.builtins().findBuiltin("JKIT_SPI_FN"));
    }

    @Test
    public void freezeRejectsRegister() {
        SqlFunctionRegistry registry = new SqlFunctionRegistry();
        registry.freeze();
        try {
            registry.register("FOO", renameTo("X"));
            fail("expected IllegalStateException");
        } catch (IllegalStateException expected) {
            assertEquals("SqlFunctionRegistry is frozen", expected.getMessage());
        }
    }

    private static FunctionRewriteRule renameTo(final String name) {
        return new FunctionRewriteRule() {
            /**
             * {@inheritDoc}
             */
            @Override
            public SqlExpr rewrite(SqlFunctionExpr fn, SqlDialectSpec source, SqlDialectSpec target,
                                   ConversionReport.Builder report) {
                fn.setName(SqlIdentifier.of(name));
                return fn;
            }
        };
    }

    private static SqlFunctionExpr named(String name) {
        SqlFunctionExpr fn = new SqlFunctionExpr();
        fn.setName(SqlIdentifier.of(name));
        return fn;
    }
}
