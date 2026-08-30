/**
 * Created by 郑明亮 on 2022/2/18 15:29.
 */
package com.alianga.jkit.collection;

import java.util.HashMap;

/**
 * <p> 计数map</p>
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
