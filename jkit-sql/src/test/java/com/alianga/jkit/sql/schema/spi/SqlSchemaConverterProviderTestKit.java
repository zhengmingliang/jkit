package com.alianga.jkit.sql.schema.spi;

import com.alianga.jkit.sql.SqlDialect;
import com.alianga.jkit.sql.schema.model.CanonicalType;
import com.alianga.jkit.sql.schema.registry.RegistryValidator;
import com.alianga.jkit.sql.schema.registry.SqlDataTypeRegistry;

import org.junit.Test;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * 新增方言插件的回归套件：覆盖率 + 未声明 reverse 碰撞。
 * 插件测试类继承本类并实现两个抽象方法即可。
 *
 * @author 郑明亮
 */
public abstract class SqlSchemaConverterProviderTestKit {
    /**
     * @return 被测插件
     */
    protected abstract SqlSchemaConverterProvider providerUnderTest();

    /**
     * @return 被测方言
     */
    protected abstract SqlDialect dialectUnderTest();

    /**
     * 每个 canonical 类型都已声明该方言写法。
     */
    @Test
    public void everyCanonicalTypeIsDeclared() {
        SqlDataTypeRegistry registry = SqlDataTypeRegistry.builtins();
        SqlDialect dialect = dialectUnderTest();
        CanonicalType[] types = CanonicalType.values();
        for (int i = 0; i < types.length; i++) {
            if (types[i] == CanonicalType.UNKNOWN) {
                continue;
            }
            assertNotNull(types[i] + " missing form for " + dialect,
                    registry.form(types[i], dialect));
        }
        assertNotNull(providerUnderTest());
    }

    /**
     * 内置表（含 SPI）无未声明碰撞。
     */
    @Test
    public void noUndeclaredReverseCollision() {
        RegistryValidator.ValidationResult result =
                RegistryValidator.validate(SqlDataTypeRegistry.builtins());
        if (!result.ok()) {
            fail(result.errors().toString());
        }
        assertTrue(result.errors().isEmpty());
    }
}
