package com.alianga.jkit.json.internal.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 标记优先通过 getter/setter 方法调用来访问属性，而不是直接读写字段。
 *
 * <p>可标注在类型上对该类所有属性生效，也可标注在单个方法上仅对该属性生效。
 */
@Target({ElementType.METHOD, ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
public @interface MethodInvokePriority {
}
