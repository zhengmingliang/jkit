/**
 * Created by 郑明亮 on 2022/3/13 09:27.
 */
package com.alianga.jkit.collection;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * <p> 可以映射多个key到一个value的HashMap实现(获取值时可忽略key的大小写，优先获取匹配大小写的值)</p>
 * <p> 忽略大小写只对通过 {@link #put(Object, Object)} 及其衍生方法写入的 key 生效，
 *     写入与删除都会同步维护内部的 {@code keyMap}，保证读写两侧语义一致。</p>
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

    /**
     * 计算忽略大小写匹配用的规范化键。
     * <p>固定使用 {@link Locale#ROOT}：默认的 {@link String#toLowerCase()} 依赖运行时语言环境，
     * 在土耳其语环境下 {@code "ID"} 会被转成 {@code "ıd"}（无点 i），导致同一 key 写入与读取的
     * 规范化结果不一致，忽略大小写查找整体失效。</p>
     *
     * @param key 原始键，允许为 {@code null}
     * @return 规范化后的小写键
     */
    private static String lowerKey(Object key) {
        return String.valueOf(key).toLowerCase(Locale.ROOT);
    }

    @Override
    public V get(Object key) {
        V v = super.get(key);
        if (v != null) {
            return v;
        }
        K k = keyMap.get(lowerKey(key));
        if (k != null) {
            return super.get(k);
        }

        return null;
    }

    @Override
    public boolean containsKey(Object key) {
        if (super.containsKey(key)) {
            return true;
        }
        K k = keyMap.get(lowerKey(key));
        return k != null && super.containsKey(k);
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
        keyMap.put(lowerKey(key), key);
        return super.put(key, value);
    }

    /**
     * 批量写入，逐条走 {@link #put(Object, Object)} 以登记忽略大小写用的 {@code keyMap}。
     * <p>直接复用 {@code HashMap#putAll} 会漏掉 keyMap 登记，使得批量写入的条目无法被
     * 忽略大小写地读取到。</p>
     *
     * @param m 待写入的映射，不得为 {@code null}
     * @throws NullPointerException m 为 {@code null} 时抛出
     */
    @Override
    public void putAll(Map<? extends K, ? extends V> m) {
        if (m == null || m.isEmpty()) {
            super.putAll(m);
            return;
        }
        for (Map.Entry<? extends K, ? extends V> entry : m.entrySet()) {
            put(entry.getKey(), entry.getValue());
        }
    }

    /**
     * 忽略大小写地删除条目，并同步清理 {@code keyMap} 中的登记。
     * <p>未覆写时 {@code remove} 只按精确 key 删除：写入 {@code "UserName"} 后调用
     * {@code remove("username")} 会静默失败，而 {@code get("username")} 仍能取到值，
     * 读写语义自相矛盾。</p>
     *
     * @param key 待删除的键
     * @return 被删除的值，键不存在时返回 {@code null}
     */
    @Override
    public V remove(Object key) {
        String lower = lowerKey(key);
        K real = keyMap.get(lower);
        V removed = super.remove(key);
        if (removed == null && real != null) {
            removed = super.remove(real);
        }
        // 无论走哪条路径，登记都要失效，避免 keyMap 长期持有已删除 key 的引用
        keyMap.remove(lower);
        return removed;
    }

    /**
     * 清空所有条目，同时清空 {@code keyMap}，避免残留 key 引用阻止其被回收。
     */
    @Override
    public void clear() {
        super.clear();
        keyMap.clear();
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
