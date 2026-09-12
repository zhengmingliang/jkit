package org.hibernate.annotations;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 测试桩：Hibernate {@code Comment}。
 *
 * @author 郑明亮
 */
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.FIELD, ElementType.METHOD})
public @interface Comment {
    /**
     * @return 注释
     */
    String value();

    /**
     * @return 注释对象（Hibernate 6.2+），可空
     */
    String on() default "";
}
