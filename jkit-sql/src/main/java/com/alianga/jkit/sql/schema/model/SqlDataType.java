package com.alianga.jkit.sql.schema.model;

import java.util.Collections;
import java.util.EnumSet;
import java.util.Set;

/**
 * 列类型节点，替代裸字符串类型名。
 *
 * @author 郑明亮
 * @since 2.0.1
 */
public final class SqlDataType {
    /**
     * 类型修饰符。
     */
    public enum TypeAttribute {
        /** MySQL {@code UNSIGNED}。 */
        UNSIGNED,
        /** MySQL {@code ZEROFILL}。 */
        ZEROFILL,
        /** MySQL {@code BINARY} 字符集修饰（跟在 CHAR/VARCHAR 后）。 */
        BINARY_CHARSET,
        /** {@code NCHAR} / {@code NVARCHAR} / {@code NATIONAL CHAR}。 */
        NATIONAL,
        /** {@code TIMESTAMP WITH TIME ZONE} / {@code TIMESTAMPTZ}。 */
        WITH_TIME_ZONE,
        /** {@code TIMESTAMP WITHOUT TIME ZONE}。 */
        WITHOUT_TIME_ZONE
    }

    private final String rawTypeName;
    private final Integer precision;
    private final Integer scale;
    private final Set<TypeAttribute> attributes;

    /**
     * @param rawTypeName 源方言原始类型名（不含括号参数），如 {@code TINYINT}
     * @param precision 精度，可空
     * @param scale 标度，可空
     * @param attributes 修饰符，可空
     */
    public SqlDataType(String rawTypeName, Integer precision, Integer scale,
                       Set<TypeAttribute> attributes) {
        this.rawTypeName = rawTypeName == null ? "" : rawTypeName;
        this.precision = precision;
        this.scale = scale;
        if (attributes == null || attributes.isEmpty()) {
            this.attributes = Collections.emptySet();
        } else {
            this.attributes = Collections.unmodifiableSet(EnumSet.copyOf(attributes));
        }
    }

    /**
     * 无法识别的类型，仅保留原文。
     *
     * @param rawTypeName 原文类型名
     * @return 类型节点
     */
    public static SqlDataType unknown(String rawTypeName) {
        return new SqlDataType(rawTypeName, null, null, Collections.<TypeAttribute>emptySet());
    }

    /**
     * @return 源方言原始类型名
     */
    public String rawTypeName() {
        return rawTypeName;
    }

    /**
     * @return 精度，可空
     */
    public Integer precision() {
        return precision;
    }

    /**
     * @return 标度，可空
     */
    public Integer scale() {
        return scale;
    }

    /**
     * @return 修饰符（不可变）
     */
    public Set<TypeAttribute> attributes() {
        return attributes;
    }

    /**
     * @param attribute 修饰符
     * @return 是否含该修饰符
     */
    public boolean has(TypeAttribute attribute) {
        return attributes.contains(attribute);
    }
}
