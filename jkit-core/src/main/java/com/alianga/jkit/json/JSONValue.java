package com.alianga.jkit.json;

import java.util.List;
import java.util.Map;

/**
 * JSON字符串中value可选类型约定为6种: String, Number, boolean, Map, List,null， 除了null外都可以使用valueOf进行显示类型的构造；
 *
 * @time 2024/8/2 23:46
 */
public final class JSONValue<T> {
    final T value;

    JSONValue(T value) {
        this.value = value;
    }

    /**
     * 获取被包装的原始值。
     *
     * @return 构造时传入的值
     */
    public T get() {
        return value;
    }

    /**
     * 包装一个 boolean 值。
     *
     * @param value 布尔值
     * @return 承载该布尔值的 JSONValue
     */
    public static JSONValue<Boolean> of(boolean value) {
        return new JSONValue<Boolean>(value);
    }

    /**
     * 包装一个数字值。
     *
     * @param value 数字值
     * @return 承载该数字的 JSONValue
     */
    public static JSONValue<Number> of(Number value) {
        return new JSONValue<Number>(value);
    }

    /**
     * 包装一个字符串值。
     *
     * @param value 字符串值
     * @return 承载该字符串的 JSONValue
     */
    public static JSONValue<String> of(String value) {
        return new JSONValue<String>(value);
    }

    /**
     * 包装一个 Map（JSON 对象）值。
     *
     * @param value Map 值
     * @return 承载该 Map 的 JSONValue
     */
    public static JSONValue<Map> of(Map value) {
        return new JSONValue<Map>(value);
    }

    /**
     * 包装一个 List（JSON 数组）值。
     *
     * @param value List 值
     * @return 承载该 List 的 JSONValue
     */
    public static JSONValue<List> of(List value) {
        return new JSONValue<List>(value);
    }
}
