package com.alianga.jkit.json;

import java.util.function.Function;
import java.util.function.ToIntFunction;

/**
 * JSON 类型拓展注册辅助工具类，用于快速构建常见的序列化器与反序列化器
 */
public class JSONTypeRegistorUtils {
    /**
     * 对象调用toString序列化器
     */
    public static final JSONTypeSerializer TO_STRING = JSONTypeSerializer.TO_STRING;

    /**
     * 构建一个从已经解析完成的字符串转化为指定类型的反序列化器
     *
     * @param <E> 转化后的目标类型
     * @param function 将字符串转化为目标类型的转换函数
     * @return 调用 {@code function} 完成转化的字符串反序列化器
     */
    public static <E> JSONTypeDeserializer fromString(final Function<String, E> function) {
        return new JSONTypeDeserializer.FromStringImpl() {
            @Override
            public Object of(String value) throws Exception {
                return function.apply(value);
            }
        };
    }

    /**
     * 构建一个从已经解析完成的Integer转化为指定类型的反序列化器
     *
     * @param <E> 转化后的目标类型
     * @param function 将 Integer 转化为目标类型的转换函数
     * @return 调用 {@code function} 完成转化的 Integer 反序列化器
     */
    public static <E> JSONTypeDeserializer fromInteger(final Function<Integer, E> function) {
        return new JSONTypeDeserializer.FromIntegerImpl() {
            @Override
            public Object of(Integer value) throws Exception {
                return function.apply(value);
            }
        };
    }

    /**
     * 构建一个将指定类型序列化为 int 数值的序列化器
     *
     * @param <E> 待序列化的对象类型
     * @param function 将对象取值为 int 的转换函数
     * @return 调用 {@code function} 取值并按数值输出的序列化器
     */
    public static <E> JSONTypeSerializer toInt(final ToIntFunction<E> function) {
        return new JSONTypeSerializer.ToIntegerImpl<E>() {
            @Override
            public int intValue(E target) throws Exception {
                return function.applyAsInt(target);
            }
        };
    }

}
