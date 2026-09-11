package com.alianga.jkit.sql.schema.spi;

import com.alianga.jkit.sql.schema.registry.SqlDataTypeRegistry;
import com.alianga.jkit.sql.schema.rewrite.SqlFunctionRegistry;

/**
 * 跨方言转换 SPI：追加类型写法 / 别名，或登记函数改写规则。
 *
 * <p>通过 {@code META-INF/services/com.alianga.jkit.sql.schema.spi.SqlSchemaConverterProvider}
 * 注册。类型表在 freeze 前调用 {@link #registerTypes}；函数表在登记内置规则并
 * {@link SqlFunctionRegistry#snapshotBuiltins()} 之后调用 {@link #registerFunctions}。
 * 两次 {@code ServiceLoader.load} 会 new 出不同实例，不要靠实例字段在两个方法之间传状态。
 * {@link #priority()} 越大越晚，可覆盖先前声明（含内置）。内置优先级为 0。</p>
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
