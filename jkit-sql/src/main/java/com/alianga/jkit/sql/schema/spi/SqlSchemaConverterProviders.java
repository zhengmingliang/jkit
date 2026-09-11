package com.alianga.jkit.sql.schema.spi;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.ServiceLoader;

/**
 * 加载 {@link SqlSchemaConverterProvider}：只 {@code ServiceLoader.load} 一次，
 * 类型表与函数表共用同一批实例（{@code registerTypes} 里写的字段 {@code registerFunctions} 能看见）。
 *
 * <p>实现类构造器不要调用 {@code SqlDataTypeRegistry.builtins()} /
 * {@code SqlFunctionRegistry.builtins()}，以免类初始化死锁。</p>
 *
 * @author 郑明亮
 * @since 2.0.1
 */
public final class SqlSchemaConverterProviders {
    private static final Object LOCK = new Object();
    private static List<SqlSchemaConverterProvider> sorted;

    private SqlSchemaConverterProviders() {
    }

    /**
     * @return 按 {@link SqlSchemaConverterProvider#priority()} 升序的不可变列表
     */
    public static List<SqlSchemaConverterProvider> loadSorted() {
        synchronized (LOCK) {
            if (sorted != null) {
                return sorted;
            }
            List<SqlSchemaConverterProvider> providers = new ArrayList<SqlSchemaConverterProvider>(4);
            for (SqlSchemaConverterProvider p : ServiceLoader.load(SqlSchemaConverterProvider.class)) {
                if (p != null) {
                    providers.add(p);
                }
            }
            Collections.sort(providers, new Comparator<SqlSchemaConverterProvider>() {
                /**
                 * {@inheritDoc}
                 */
                @Override
                public int compare(SqlSchemaConverterProvider a, SqlSchemaConverterProvider b) {
                    return Integer.compare(a.priority(), b.priority());
                }
            });
            sorted = Collections.unmodifiableList(providers);
            return sorted;
        }
    }
}
