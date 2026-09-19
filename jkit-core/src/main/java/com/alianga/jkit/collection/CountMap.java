/**
 * Created by 郑明亮 on 2022/2/18 15:29.
 */
package com.alianga.jkit.collection;

import java.util.HashMap;

/**
 * <p> 计数map</p>
 * <p> 只覆写了 {@link #get(Object)}：未计数的 key 返回 {@code 0} 而不是 {@code null}。
 *     继承而来的 {@link #containsKey(Object)} 与 {@link #getOrDefault(Object, Object)}
 *     仍按 HashMap 原语义工作，因此 {@code get(k) != 0} 与 {@code containsKey(k)} 可能给出
 *     不同答案——判断"是否发生过计数"请统一使用 {@code containsKey}。</p>
 *
 * @author 郑明亮
 * @version 1.0.0
 * @time 2022/2/19 15:29
 */
public class CountMap<K> extends HashMap<K, Integer> {
    /**
     * 将指定 key 的计数值加 1，key 不存在时按 0 起算。
     *
     * @param key 计数的键
     */
    public void increment(K key) {
        put(key, get(key) + 1);
    }

    /**
     * 将指定 key 的计数值增加给定步长，key 不存在时按 0 起算。
     *
     * @param key  计数的键
     * @param step 累加的步长，可为负数以实现减少计数
     */
    public void increment(K key, int step) {
        put(key, get(key) + step);
    }

    @Override
    public Integer get(Object key) {
        Integer integer = super.get(key);
        return integer == null ? 0 : integer;
    }
}
