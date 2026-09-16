package com.alianga.jkit.sql.entity;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 表 / 字段注释。供 jkit-sql-model 等模块解析字段注释时作为本模块自有选择
 *（与 Hibernate {@code @Comment}、{@code @SqlColumn(comment=...)} / {@code @SqlTable(comment=...)} 并列）。
 * 扫描侧按简单名 {@code Comment} 识别，读取 {@code value}。
 *
 * @author 郑明亮
 * @since 2.0.2
 */
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.FIELD})
public @interface Comment {
    /**
     * @return 注释；空则视为未标注
     */
    String value() default "";
}
