package javax.persistence;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 测试桩：JPA {@code Table}。
 *
 * @author 郑明亮
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface Table {
    /**
     * @return 表名
     */
    String name() default "";

    /**
     * @return 索引
     */
    Index[] indexes() default {};
}
