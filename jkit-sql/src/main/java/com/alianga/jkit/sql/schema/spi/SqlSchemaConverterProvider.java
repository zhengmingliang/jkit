package com.alianga.jkit.sql.schema.spi;

import com.alianga.jkit.sql.schema.registry.SqlDataTypeRegistry;
import com.alianga.jkit.sql.schema.rewrite.SqlFunctionRegistry;

/**
 * 跨方言转换 SPI：为已有 canonical 类型追加方言写法，或覆盖内置声明。
 *
 * <p>通过 {@code META-INF/services/com.alianga.jkit.sql.schema.spi.SqlSchemaConverterProvider}
 * 注册。{@link #priority()} 越大越晚执行，可覆盖先前声明。内置注册优先级为 0。</p>
 *
 * @author 郑明亮
 * @since 2.0.1
 */
public interface SqlSchemaConverterProvider {
    /**
     * 追加或覆盖类型写法 / 别名 / 有损映射。
     *
     * @param registry 可变注册表（尚未 freeze）
     */
    default void registerTypes(SqlDataTypeRegistry registry) {
        // 默认不追加
    }

    /**
     * 追加或覆盖函数改写规则。
     *
     * @param registry 可变函数表（尚未 freeze）
     */
    default void registerFunctions(SqlFunctionRegistry registry) {
        // 默认不追加
    }

    /**
     * @return 优先级，越大越晚、越能覆盖；内置为 0
     */
    default int priority() {
        return 100;
    }
}
