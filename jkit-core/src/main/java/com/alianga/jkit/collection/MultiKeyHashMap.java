/**
 * Created by 郑明亮 on 2022/3/13 09:27.
 */
package com.alianga.jkit.collection;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.function.BiFunction;
import java.util.function.Function;

/**
 * <p> 可以映射多个key到一个value的HashMap实现(获取值时可忽略key的大小写，优先获取匹配大小写的值)</p>
 * <p> 忽略大小写只对通过 {@link #put(Object, Object)} 及 {@link #putIfAbsent} /
 *     {@link #compute}/{@link #computeIfAbsent}/{@link #computeIfPresent}/{@link #merge}
 *     等写入方法写入的 key 生效，写入与删除都会同步维护内部的 {@code keyMap}，
 *     保证读写两侧语义一致。</p>
 * <p> 注意：通过 {@code keySet()}/{@code entrySet()} 的迭代器删除条目时，{@code HashMap}
 *     直接摘除节点、子类无法拦截，对应别名不会同步清理——残留别名不影响 {@code get} 的
 *     正确性（会回退精确查找），但「视图删除后再 {@code put} 同形 key」的别名归属以
 *     后写者为准。需要严格一致时请使用 {@link #remove(Object)} 删除。</p>
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
        if (key == null) {
            // null key 不参与忽略大小写匹配，避免 lowerKey(null) 塌缩成 "null"
            // 后误命中键为字面量 "null" 的条目
            return null;
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
        if (key == null) {
            return false;
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
        if (key != null) {
            // null key 不登记：lowerKey(null) 会塌缩成 "null"，
            // 覆盖键为字面量 "null" 条目的别名
            keyMap.put(lowerKey(key), key);
        }
        return super.put(key, value);
    }

    @Override
    public V putIfAbsent(K key, V value) {
        V existing = super.putIfAbsent(key, value);
        if (existing == null && key != null) {
            // 返回 null 表示插入了新条目（HashMap.putIfAbsent 不走子类 put，需自行登记）
            keyMap.put(lowerKey(key), key);
        }
        return existing;
    }

    @Override
    public V computeIfAbsent(K key, Function<? super K, ? extends V> mappingFunction) {
        V v = super.computeIfAbsent(key, mappingFunction);
        if (v != null && key != null) {
            keyMap.put(lowerKey(key), key);
        }
        return v;
    }

    @Override
    public V computeIfPresent(K key, BiFunction<? super K, ? super V, ? extends V> remappingFunction) {
        V v = super.computeIfPresent(key, remappingFunction);
        if (key == null) {
            return v;
        }
        if (v != null) {
            keyMap.put(lowerKey(key), key);
        } else {
            refreshAliasAfterRemove(key);
        }
        return v;
    }

    @Override
    public V compute(K key, BiFunction<? super K, ? super V, ? extends V> remappingFunction) {
        V v = super.compute(key, remappingFunction);
        if (key == null) {
            return v;
        }
        if (v != null) {
            keyMap.put(lowerKey(key), key);
        } else {
            refreshAliasAfterRemove(key);
        }
        return v;
    }

    @Override
    public V merge(K key, V value, BiFunction<? super V, ? super V, ? extends V> remappingFunction) {
        V v = super.merge(key, value, remappingFunction);
        if (key == null) {
            return v;
        }
        if (v != null) {
            keyMap.put(lowerKey(key), key);
        } else {
            refreshAliasAfterRemove(key);
        }
        return v;
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
     * 忽略大小写地删除条目，并同步维护 {@code keyMap} 中的登记。
     * <p>未覆写时 {@code remove} 只按精确 key 删除：写入 {@code "UserName"} 后调用
     * {@code remove("username")} 会静默失败，而 {@code get("username")} 仍能取到值，
     * 读写语义自相矛盾。</p>
     * <p>删除后按现存条目重建别名：map 中可能同时存在仅大小写不同的多个条目
     * （如 {@code "A"} 与 {@code "a"}），删除其一后别名必须指向幸存条目，
     * 否则幸存条目的忽略大小写查找会失效。</p>
     *
     * @param key 待删除的键
     * @return 被删除的值，键不存在时返回 {@code null}
     */
    @Override
    public V remove(Object key) {
        V removed = super.remove(key);
        if (key == null) {
            // null key 不参与忽略大小写匹配，避免误删键为字面量 "null" 的条目
            return removed;
        }
        if (removed == null) {
            K real = keyMap.get(lowerKey(key));
            if (real != null) {
                removed = super.remove(real);
            }
        }
        refreshAliasAfterRemove(key);
        return removed;
    }

    /**
     * 删除条目后重建 {@code lower} 形态的别名：存在同形幸存条目则指向之，否则移除别名，
     * 避免 keyMap 长期持有已删除 key 的引用。
     *
     * @param key 刚被（尝试）删除的原始键
     */
    private void refreshAliasAfterRemove(Object key) {
        String lower = lowerKey(key);
        K alias = null;
        for (K k : this.keySet()) {
            if (k != null && lowerKey(k).equals(lower)) {
                alias = k;
                break;
            }
        }
        if (alias != null) {
            keyMap.put(lower, alias);
        } else {
            keyMap.remove(lower);
        }
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
