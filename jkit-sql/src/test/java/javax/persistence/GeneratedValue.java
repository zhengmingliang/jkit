package javax.persistence;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 测试桩：JPA {@code GeneratedValue}。
 *
 * @author 郑明亮
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.FIELD)
public @interface GeneratedValue {
    /**
     * @return 策略
     */
    GenerationType strategy() default GenerationType.AUTO;

    /**
     * @return 生成器名
     */
    String generator() default "";
}
