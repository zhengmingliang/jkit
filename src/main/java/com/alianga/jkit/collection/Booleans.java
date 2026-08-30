/**
 * Created by 郑明亮 on 2022/2/28 19:25.
 */
package com.alianga.jkit.collection;

import com.alianga.jkit.DataUtils;

/**
 * <p> 布尔值处理工具类</p>
 *
 * @author 郑明亮
 * @time 2022/2/28 19:25
 * @since 1.3.9
 */
public class Booleans {
    private static final String[] TRUE_FLAGS = {"ok", "y", "yes", "t", "true", "1"};
    private static final String[] BOOLEAN_FLAGS = {"ok", "y", "yes", "t", "true", "false", "f", "no", "n", "1", "0"};

    /**
     * 转为布尔类型
     *
     * @param str 待转换的字符串
     * @return 字符串忽略大小写等于 ok、y、yes、t、true、1 之一时返回 {@code true}，为空或其他值时返回 {@code false}
     */
    public static boolean valueOf(String str) {
        if (str == null || str.length() == 0) {
            return false;
        }
        return DataUtils.orEqualsIgnoreCase(str, (Object[]) TRUE_FLAGS);
    }

    /**
     * 是否为布尔类型
     *
     * @param str 待判断的字符串
     * @return 字符串忽略大小写等于 ok、y、yes、t、true、false、f、no、n、1、0 之一时返回 {@code true}，
     *         否则返回 {@code false}
     */
    public static Boolean isBoolean(String str) {
        return DataUtils.orEqualsIgnoreCase(str, (Object[]) BOOLEAN_FLAGS);
    }

    /**
     * 是否为true
     *
     * @param bool 待判断的布尔值，允许为 {@code null}
     * @return 值为 {@link Boolean#TRUE} 时返回 {@code true}，为 {@code null} 或 {@code false} 时返回 {@code false}
     */
    public static boolean isTrue(Boolean bool) {
        if (bool == null) {
            return false;
        }
        return bool.booleanValue();
    }

    /**
     * 是否不是true
     * <pre>
     * Booleans.isNotTrue(Boolean.TRUE) = false
     * Booleans.isNotTrue(Boolean.FALSE) = true
     * Booleans.isNotTrue(null) = true
     * </pre>
     *
     * @param bool 布尔
     * @return boolean
     */
    public static boolean isNotTrue(Boolean bool) {
        return !isTrue(bool);
    }

    /**
     * 是否是false
     * <pre>
     * Booleans.isFalse(Boolean.TRUE) = false
     * Booleans.isFalse(Boolean.FALSE) = true
     * Booleans.isFalse(null) = false
     * </pre>
     *
     * @param bool 待判断的布尔值，允许为 {@code null}
     * @return 值为 {@link Boolean#FALSE} 时返回 {@code true}，为 {@code null} 或 {@code true} 时返回 {@code false}
     */
    public static boolean isFalse(Boolean bool) {
        if (bool == null) {
            return false;
        }
        return !bool.booleanValue();
    }

    /**
     * 是否不是false
     * <pre>
     * Booleans.isNotFalse(Boolean.TRUE) = true
     * Booleans.isNotFalse(Boolean.FALSE) = false
     * Booleans.isNotFalse(null) = true
     * </pre>
     *
     * @param bool 待判断的布尔值，允许为 {@code null}
     * @return 值为 {@code null} 或 {@link Boolean#TRUE} 时返回 {@code true}，为 {@code false} 时返回 {@code false}
     */
    public static boolean isNotFalse(Boolean bool) {
        return !isFalse(bool);
    }

    /**
     * 将布尔值转换为处理 null 的布尔值。
     * <pre>
     * Booleans.toBooleanDefaultIfNull(Boolean.TRUE, false) = true
     * Booleans.toBooleanDefaultIfNull(Boolean.FALSE, true) = false
     * Booleans.toBooleanDefaultIfNull(null, true) = true
     * </pre>
     *
     * @param bool 布尔值
     * @param valueIfNull 如果是null则返回什么布尔值
     * @return 布尔值不为 {@code null} 时返回其原始值，为 {@code null} 时返回 {@code valueIfNull}
     */
    public static boolean toBooleanDefaultIfNull(Boolean bool, boolean valueIfNull) {
        if (bool == null) {
            return valueIfNull;
        }
        return bool.booleanValue();
    }

}
