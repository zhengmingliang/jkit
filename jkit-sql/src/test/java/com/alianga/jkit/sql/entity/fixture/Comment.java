package com.alianga.jkit.sql.entity.fixture;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 测试桩：任意包下的 {@code Comment}，验证不绑定 Hibernate FQCN。
 *
 * @author 郑明亮
 */
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.FIELD})
public @interface Comment {
    /**
     * @return 注释
     */
    String value() default "";
}
