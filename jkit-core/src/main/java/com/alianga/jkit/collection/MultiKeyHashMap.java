/**
 * Created by 郑明亮 on 2022/3/13 09:27.
 */
package com.alianga.jkit.collection;

import java.util.HashMap;
import java.util.Map;

/**
 * <p> 可以映射多个key到一个value的HashMap实现(获取值时可忽略key的大小写，优先获取匹配大小写的值)</p>
 *
 * @author 郑明亮
 * @time 2022/3/13 09:27
 * @since 1.3.11
 */
public class MultiKeyHashMap<K, V> extends HashMap<K, V> {
    /**
     * key 小写形式到原始 key 的映射，用于忽略大小写查找。
     */
    Map<String, K> keyMap = new HashMap<String, K>();

    @Override
    public V get(Object key) {
        V v = super.get(key);
        if (v != null) {
            return v;
        }
        K k = keyMap.get(String.valueOf(key).toLowerCase());
        if (k != null) {
            return super.get(k);
        }

        return null;
    }

    @Override
    public V getOrDefault(Object key, V defaultValue) {
        V v = get(key);
        if (v != null) {
            return v;
        }
        return defaultValue;
    }

    /**
     * 通过多个key来获取值，返回不为空的key值
     *
     * @param key1 首先尝试的键
     * @param key2 key1 未匹配到值时尝试的键
     * @return 首个匹配到的非空值，两个键都未匹配到时返回 {@code null}
     */
    public V get(Object key1, Object key2) {
        V v = get(key1);
        if (v != null) {
            return v;
        }
        return get(key2);
    }

    /**
     * 通过多个key来获取值，返回不为空的key值
     *
     * @param key1 key1
     * @param key2 key2
     * @param keys 键
     * @return {@link V}
     */
    public V get(Object key1, Object key2, Object... keys) {
        V v = this.get(key1);
        if (v != null) {
            return v;
        }
        v = this.get(key2);
        if (v != null) {
            return v;
        }
        if (keys != null) {
            for (Object key : keys) {
                v = this.get(key);
                if (v != null) {
                    return v;
                }
            }
        }

        return null;
    }

    @Override
    public V put(K key, V value) {
        keyMap.put(String.valueOf(key).toLowerCase(), key);
        return super.put(key, value);
    }

    /**
     * 将同一个值同时映射到两个 key 上。
     *
     * @param key   第一个键
     * @param key2  第二个键
     * @param value 需要映射的值
     * @return key2 原先关联的值，不存在则返回 {@code null}
     */
    public V put(K key, K key2, V value) {
        put(key, value);
        return put(key2, value);
    }

    /**
     * 将同一个值映射到多个 key 上，每个 key 都会登记进忽略大小写用的 keyMap。
     *
     * @param value 需要映射的值
     * @param key   第一个键
     * @param keys  其余的键，可为 {@code null}
     * @return key 原先关联的值，不存在则返回 {@code null}
     */
    public V putValue(V value, K key, K... keys) {
        V v = put(key, value);
        if (keys != null) {
            for (K k : keys) {
                put(k, value);
            }
        }
        return v;
    }

}
