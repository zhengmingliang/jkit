package com.alianga.jkit.json.temporal;

import com.alianga.jkit.json.JSONPropertyDefinition;
import com.alianga.jkit.reflect.GenericParameterizedType;

/**
 * 时间类型的序列化/反序列化配置，保存目标泛型类型与日期格式化模板。
 */
public class TemporalConfig {
    final GenericParameterizedType<?> genericParameterizedType;
    final String pattern;

    TemporalConfig(GenericParameterizedType<?> genericParameterizedType, JSONPropertyDefinition propertyDefinition) {
        this.genericParameterizedType = genericParameterizedType;
        String pattern = null;
        if (propertyDefinition != null) {
            pattern = propertyDefinition.pattern().trim();
            if (pattern.isEmpty()) {
                pattern = null;
            }
        }
        this.pattern = pattern;
    }

    /**
     * 根据泛型类型与属性定义创建时间配置。
     *
     * @param genericParameterizedType 目标时间类型的泛型信息
     * @param propertyDefinition       属性定义，为 {@code null} 或其 pattern 为空串时不使用格式化模板
     * @return 新创建的 TemporalConfig 实例
     */
    public static TemporalConfig of(GenericParameterizedType<?> genericParameterizedType,
                                    JSONPropertyDefinition propertyDefinition) {
        return new TemporalConfig(genericParameterizedType, propertyDefinition);
    }

    /**
     * 获取目标时间类型的泛型信息。
     *
     * @return 当前的泛型类型信息
     */
    public GenericParameterizedType<?> getGenericParameterizedType() {
        return genericParameterizedType;
    }

    /**
     * 获取日期格式化模板。
     *
     * @return 当前的日期格式化模板，未配置时返回 {@code null}
     */
    public String getDatePattern() {
        return pattern;
    }
}
