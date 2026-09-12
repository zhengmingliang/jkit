package com.alianga.jkit.sql.entity;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 数据库自增列。对应 JPA {@code @GeneratedValue}（IDENTITY/AUTO/SEQUENCE）。
 * 仅整数列会生成 {@code AUTO_INCREMENT}/{@code IDENTITY}；UUID / 字符串主键由应用赋值。
 *
 * @author 郑明亮
 * @since 2.0.1
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.FIELD)
public @interface SqlGenerated {
}
