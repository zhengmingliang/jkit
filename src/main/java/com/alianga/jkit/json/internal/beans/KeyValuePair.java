package com.alianga.jkit.json.internal.beans;

/**
 * @time 2022/11/13 10:23
 */
public class KeyValuePair<K, V> {
    final K key;
    final V value;

    /**
     * 构造一个键值对。
     *
     * @param k 键
     * @param v 值
     */
    public KeyValuePair(K k, V v) {
        this.key = k;
        this.value = v;
    }

    /**
     * 创建一个键值对。
     *
     * @param <K> 键类型
     * @param <V> 值类型
     * @param k   键
     * @param v   值
     * @return 持有给定键和值的新键值对
     */
    public static <K, V> KeyValuePair<K, V> of(K k, V v) {
        return new KeyValuePair<K, V>(k, v);
    }

    /**
     * 获取键。
     *
     * @return 构造时传入的键，可能为 {@code null}
     */
    public K getKey() {
        return key;
    }

    /**
     * 获取值。
     *
     * @return 构造时传入的值，可能为 {@code null}
     */
    public V getValue() {
        return value;
    }
}
