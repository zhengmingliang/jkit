package com.alianga.jkit.sql.entity;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 不映射为列。对应 JPA {@code @Transient}。
 *
 * @author 郑明亮
 * @since 2.0.1
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.FIELD)
public @interface SqlTransient {
}
