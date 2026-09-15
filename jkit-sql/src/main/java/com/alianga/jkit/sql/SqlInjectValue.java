package com.alianga.jkit.sql;

/**
 * 行级注入的取值。切面里每次 {@link SQL#inject} 时再取，便于租户 ID / 当前用户随请求变化。
 *
 * @author 郑明亮
 * @since 2.0.2
 */
public interface SqlInjectValue {
    /**
     * @return 本次注入使用的 Java 值；经 {@link SqlInjectRewriter#literalValue} 收成字面量
     */
    Object get();
}
