package com.alianga.jkit.sql;

/**
 * 格式化关键字大小写策略。默认 {@link #AS_IS} 保持回写原样（与既有行为一致）。
 *
 * @author 郑明亮
 * @since 2.0.1
 */
public enum SqlKeywordCase {
    /** 保持原样（默认）。 */
    AS_IS,
    /** 关键字统一大写。 */
    UPPER,
    /** 关键字统一小写。 */
    LOWER
}
