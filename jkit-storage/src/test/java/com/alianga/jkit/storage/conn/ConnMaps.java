package com.alianga.jkit.storage.conn;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 连接库单测的 Java 8 兼容小工具：jkit-storage 以 Java 8 为目标，
 * 测试代码不能用 {@code Map.of} / {@code List.of} / {@code Set.of}（Java 9+）。
 *
 * <p>泛型参数由赋值目标或方法参数类型推断，语义与 {@code Map.of} 一致。</p>
 *
 * @author 郑明亮
 */
final class ConnMaps {
    private ConnMaps() {
    }

    /**
     * 按「键、值」交替的可变参数构造有序 Map（替代 {@code Map.of}）。
     *
     * @param kv 键值对（偶数个）
     * @param <K> 键类型
     * @param <V> 值类型
     * @return 有序 Map
     */
    @SafeVarargs
    static <K, V> Map<K, V> map(Object... kv) {
        Map<K, V> out = new LinkedHashMap<K, V>();
        for (int i = 0; i + 1 < kv.length; i += 2) {
            @SuppressWarnings("unchecked")
            K key = (K) kv[i];
            @SuppressWarnings("unchecked")
            V value = (V) kv[i + 1];
            out.put(key, value);
        }
        return out;
    }

    /**
     * 构造 List（替代 {@code List.of}）。
     *
     * @param items 元素
     * @param <T> 元素类型
     * @return List
     */
    @SafeVarargs
    static <T> List<T> list(Object... items) {
        List<T> out = new ArrayList<T>(items.length);
        for (Object item : items) {
            @SuppressWarnings("unchecked")
            T value = (T) item;
            out.add(value);
        }
        return out;
    }

    /**
     * 构造 Set（替代 {@code Set.of}）。
     *
     * @param items 元素
     * @param <T> 元素类型
     * @return Set
     */
    @SafeVarargs
    static <T> Set<T> set(Object... items) {
        Set<T> out = new LinkedHashSet<T>(items.length);
        for (Object item : items) {
            @SuppressWarnings("unchecked")
            T value = (T) item;
            out.add(value);
        }
        return out;
    }
}
