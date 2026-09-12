package javax.persistence;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 测试桩：JPA {@code Column}。
 *
 * @author 郑明亮
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.FIELD)
public @interface Column {
    /**
     * @return 列名
     */
    String name() default "";

    /**
     * @return 长度
     */
    int length() default 255;

    /**
     * @return 精度
     */
    int precision() default 0;

    /**
     * @return 标度
     */
    int scale() default 0;

    /**
     * @return 是否可空
     */
    boolean nullable() default true;

    /**
     * @return 是否唯一
     */
    boolean unique() default false;

    /**
     * @return 原始类型
     */
    String columnDefinition() default "";
}
