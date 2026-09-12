package com.alianga.jkit.sql.entity;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 把类标成可扫描实体。无 JPA 依赖时用本注解；有 {@code javax/jakarta.persistence.Entity} 同样会被扫描到。
 *
 * @author 郑明亮
 * @since 2.0.1
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface SqlTable {
    /**
     * @return 表名；空则用类名转下划线
     */
    String name() default "";

    /**
     * @return 表注释，可空
     */
    String comment() default "";

    /**
     * 索引，元素为 {@code col} 或 {@code name:col1,col2}。未写名字时生成 {@code {table}_{col}_idx}。
     *
     * @return 索引定义
     */
    String[] indexes() default {};
}
