package org.apache.ibatis.type;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 测试桩：MyBatis {@code Alias}，仅作表名回退。
 *
 * @author 郑明亮
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface Alias {
    /**
     * @return 别名
     */
    String value();
}
