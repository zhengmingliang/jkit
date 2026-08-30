package com.alianga.jkit.json.annotations;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * json type setting
 *
 * @time 2024/3/24 16:10
 * @since 1.0.0
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
public @interface JsonTypeSetting {
    /**
     * <p> running on Strict mode
     *
     * @return {@code true} if strict mode is enabled, otherwise {@code false}
     */
    boolean strict() default false;

    /**
     * Enable JIT optimization (currently only supported for beans of conventions)
     *
     * @return {@code true} if JIT optimization is enabled, otherwise {@code false}
     */
    boolean enableJIT() default false;
}
