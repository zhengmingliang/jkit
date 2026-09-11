package com.alianga.jkit.sql.schema.spi;

import com.alianga.jkit.sql.SqlDialect;
import com.alianga.jkit.sql.schema.model.CanonicalType;
import com.alianga.jkit.sql.schema.registry.SqlDataTypeRegistry;

/**
 * SPI 扩展示例：补齐 icell {@code FieldConstruct} / {@code FieldTypeConverter}
 * 里当作<strong>源类型名</strong>使用、但内置表未登记的别名。
 *
 * <p>对照来源：{@code com.dtsz.cm.utils.FieldConstruct}、
 * {@code com.dtsz.cm.common.sql.type.*TypeConverter}。
 * 只追加反向别名，不改正向写法（PG 仍输出 {@code INTEGER} 而非 {@code int4}）。</p>
 *
 * <p>复制本类 + {@code META-INF/services/} 一行即可做第三方插件；
 * {@link #priority()} 大于 0，晚于内置表、可覆盖。</p>
 *
 * @author 郑明亮
 * @since 2.0.1
 */
public final class CommonModelTypeAliasesProvider implements SqlSchemaConverterProvider {
    /**
     * {@inheritDoc}
     */
    @Override
    public int priority() {
        return 50;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void registerTypes(SqlDataTypeRegistry registry) {
        // PG / Gauss：bpchar 是 CHAR 的内部名；float4/float8 是 real/double 的内部名
        registry.registerAliasAll("BPCHAR", CanonicalType.CHAR);
        registry.registerAliasAll("FLOAT4", CanonicalType.FLOAT);
        registry.registerAliasAll("FLOAT8", CanonicalType.DOUBLE);
        // FieldConstruct 把 LONG 当 64 位整数（达梦/神通/PG），不是 Oracle 历史 LONG 大文本
        registry.registerAliasAll("LONG", CanonicalType.BIGINT);
        // BIT：SQL Server 上已是 BOOLEAN 的正向字面量；其它方言 FieldConstruct 也把 BIT 当布尔
        registry.registerAliasAll("BIT", CanonicalType.BOOLEAN);
        // INT8：PG 是 bigint；ClickHouse 的 Int8 是 TINYINT，不能全局覆盖
        SqlDialect[] dialects = SqlDialect.values();
        for (int i = 0; i < dialects.length; i++) {
            if (dialects[i] != SqlDialect.CLICKHOUSE) {
                registry.registerAlias(dialects[i], "INT8", CanonicalType.BIGINT);
            }
        }
    }
}
