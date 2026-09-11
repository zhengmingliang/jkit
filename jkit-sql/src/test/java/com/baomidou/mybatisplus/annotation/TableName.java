package com.baomidou.mybatisplus.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 测试桩：与 MyBatis-Plus {@code TableName} 同名，供反射识别。
 *
 * @author 郑明亮
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface TableName {
    /**
     * @return 表名
     */
    String value() default "";

    /**
     * @return schema
     */
    String schema() default "";
}
