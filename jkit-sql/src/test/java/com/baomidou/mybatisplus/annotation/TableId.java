package com.baomidou.mybatisplus.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 测试桩：MyBatis-Plus {@code TableId}。
 *
 * @author 郑明亮
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.FIELD)
public @interface TableId {
    /**
     * @return 列名
     */
    String value() default "";

    /**
     * @return 主键策略
     */
    IdType type() default IdType.NONE;
}
