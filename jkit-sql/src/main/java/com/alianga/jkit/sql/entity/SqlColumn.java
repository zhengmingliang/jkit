package com.alianga.jkit.sql.entity;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 字段 → 列。未标注时仍按 Java 类型映射，列名用字段名转下划线。
 *
 * @author 郑明亮
 * @since 2.0.1
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.FIELD)
public @interface SqlColumn {
    /**
     * @return 列名；空则用字段名转下划线
     */
    String name() default "";

    /**
     * @return 长度（VARCHAR/CHAR/BINARY）
     */
    int length() default 255;

    /**
     * @return DECIMAL 精度
     */
    int precision() default 0;

    /**
     * @return DECIMAL 标度
     */
    int scale() default 0;

    /**
     * @return 是否可空
     */
    boolean nullable() default true;

    /**
     * @return 是否唯一
     */
    boolean unique() default false;
}
