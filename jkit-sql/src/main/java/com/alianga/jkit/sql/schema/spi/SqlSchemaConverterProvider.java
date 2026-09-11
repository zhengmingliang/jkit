package com.alianga.jkit.sql.schema.spi;

import com.alianga.jkit.sql.schema.registry.SqlDataTypeRegistry;
import com.alianga.jkit.sql.schema.rewrite.SqlFunctionRegistry;

/**
 * 跨方言转换 SPI：追加类型写法 / 别名，或登记函数改写规则。
 *
 * <p>通过 {@code META-INF/services/com.alianga.jkit.sql.schema.spi.SqlSchemaConverterProvider}
 * 注册。类型表在 freeze 前调用 {@link #registerTypes}；函数表在登记内置规则并
 * {@link SqlFunctionRegistry#snapshotBuiltins()} 之后调用 {@link #registerFunctions}。
 * 类型表与函数表共用 {@link SqlSchemaConverterProviders#loadSorted()} 的同一批实例，
 * {@link #registerTypes} 写的字段 {@link #registerFunctions} 能看见（先类型后函数）。
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
