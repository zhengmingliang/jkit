package com.baomidou.mybatisplus.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 测试桩：MyBatis-Plus {@code TableField}。
 *
 * @author 郑明亮
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.FIELD)
public @interface TableField {
    /**
     * @return 列名
     */
    String value() default "";

    /**
     * @return false 表示非表字段
     */
    boolean exist() default true;
}
