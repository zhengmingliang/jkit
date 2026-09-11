package javax.persistence;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 测试桩：JPA {@code Enumerated}。
 *
 * @author 郑明亮
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.FIELD)
public @interface Enumerated {
    /**
     * @return ORDINAL / STRING
     */
    EnumType value() default EnumType.ORDINAL;
}
