package com.alianga.jkit.csv;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * CSV 列映射注解，标注在字段或 setter 方法上，用于指定其对应的 CSV 列。
 */
@Target({ElementType.METHOD, ElementType.FIELD})
@Retention(RetentionPolicy.RUNTIME)
public @interface CSVColumn {
    /**
     * 映射名称
     *
     * @return 该字段在 CSV 表头中对应的列名
     */
    public String value();

    /**
     * 检查字段是否存在以及值是否为空，如果为空转化将抛出异常
     *
     * @return 需要做非空校验时返回 {@code true}，否则返回 {@code false}，默认 {@code false}
     */
    public boolean required() default false;

    /**
     * 类型转化handler
     *
     * @return 用于将 CSV 文本转换为目标类型的处理器类型，默认使用 {@code DefaultCSVTypeHandler}
     */
    public Class<? extends CSVTypeHandler> handler() default CSVTypeHandler.DefaultCSVTypeHandler.class;
}
