package javax.persistence;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 测试桩：JPA {@code Index}。
 *
 * @author 郑明亮
 */
@Retention(RetentionPolicy.RUNTIME)
@Target({})
public @interface Index {
    /**
     * @return 索引名
     */
    String name() default "";

    /**
     * @return 列清单
     */
    String columnList();

    /**
     * @return 是否唯一
     */
    boolean unique() default false;
}
