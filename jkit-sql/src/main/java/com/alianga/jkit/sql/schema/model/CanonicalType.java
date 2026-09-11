package com.alianga.jkit.sql.schema.model;

/**
 * 方言无关的 canonical 类型。插件只能为已有枚举追加方言写法，不能发明新的 canonical。
 *
 * @author 郑明亮
 * @since 2.0.1
 */
public enum CanonicalType {
    /** 无法归一的方言专属类型，转换时按策略保留原文或告警。 */
    UNKNOWN(false, false),
    TINYINT(false, false),
    SMALLINT(false, false),
    MEDIUMINT(false, false),
    INT(false, false),
    BIGINT(false, false),
    FLOAT(false, false),
    DOUBLE(false, false),
    DECIMAL(true, true),
    CHAR(true, false),
    VARCHAR(true, false),
    TEXT(false, false),
    DATE(false, false),
    DATETIME(false, false),
    TIMESTAMP(false, false),
    TIME(false, false),
    BINARY(true, false),
    BLOB(false, false),
    BOOLEAN(false, false),
    JSON(false, false),
    YEAR(false, false);

    private final boolean precision;
    private final boolean scale;

    CanonicalType(boolean precision, boolean scale) {
        this.precision = precision;
        this.scale = scale;
    }

    /**
     * @return 该类型在目标方言通常需要精度参数（如 {@code VARCHAR(n)}）
     */
    public boolean requiresPrecision() {
        return precision;
    }

    /**
     * @return 该类型通常需要标度参数（如 {@code DECIMAL(p,s)}）
     */
    public boolean requiresScale() {
        return scale;
    }

    /**
     * @return 是否整数族（UNSIGNED 升档时使用）
     */
    public boolean integerFamily() {
        return this == TINYINT || this == SMALLINT || this == MEDIUMINT
                || this == INT || this == BIGINT || this == YEAR;
    }
}
