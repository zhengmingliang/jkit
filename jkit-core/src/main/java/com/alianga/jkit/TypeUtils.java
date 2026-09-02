/**
 * Created by 郑明亮 on 2023/8/13 16:02.
 */
package com.alianga.jkit;

import java.util.HashMap;
import java.util.Map;

/**
 * <p> 类型处理工具类</p>
 *
 * @author 郑明亮
 * @time 2023/8/13 16:02
 * @since 1.4.3
 */
public class TypeUtils {
    private static final Map<Class<?>, Class<?>> primWrapMap = new HashMap<Class<?>, Class<?>>();
    private static final Map<Class<?>, Class<?>> wrapPrimMap = new HashMap<Class<?>, Class<?>>();

    static {
        put(boolean.class, Boolean.class);
        put(byte.class, Byte.class);
        put(char.class, Character.class);
        put(double.class, Double.class);
        put(float.class, Float.class);
        put(int.class, Integer.class);
        put(long.class, Long.class);
        put(short.class, Short.class);
        put(void.class, Void.class);
    }

    private static void put(Class<?> primitive, Class<?> boxed) {
        primWrapMap.put(primitive, boxed);
        wrapPrimMap.put(boxed, primitive);
    }

    /**
     * 是否是装包类型
     *
     * @param clz 待判断的类型
     * @return 该类型是九种基本类型对应的包装类型之一时返回 {@code true}，否则返回 {@code false}
     */

    public static boolean isBoxed(Class clz) {
        return primWrapMap.containsValue(clz);
    }

    /**
     * 获取基本类型的装包类型,如果不是基本类型，则返回传入的类型
     *
     * @param clz 待转换的类型
     * @return 基本类型对应的包装类型；若不是基本类型则原样返回传入的类型
     */
    public static Class<?> getBoxedType(Class<?> clz) {
        if (clz.isPrimitive()) {
            return primWrapMap.get(clz);
        }
        return clz;
    }

    /**
     * 获取拆箱后的类型
     *
     * @param clz 待拆箱的类型
     * @return 包装类型对应的基本类型；传入的已是基本类型或不是九种包装类型之一时原样返回
     */
    public static Class<?> getUnBoxedType(Class<?> clz) {
        if (clz.isPrimitive()) {
            return clz;
        }
        Class<?> aClass = wrapPrimMap.get(clz);
        return aClass == null ? clz : aClass;
    }
}
