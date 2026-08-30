package com.alianga.jkit.json.internal.beans;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * <p> 固定长度的队列Map,先进先出
 *
 * @time 2024/10/22 18:29
 */
public final class ArrayQueueMap<K, V> extends LinkedHashMap<K, V> {
    /**
     * 允许保留的最大元素个数，超出后移除最久未使用的元素
     */
    final int limit;

    @Override
    protected boolean removeEldestEntry(Map.Entry<K, V> eldest) {
        return size() > limit;
    }

    /**
     * 创建指定容量上限的队列 Map，按访问顺序排序，元素个数超过上限时自动移除最旧的元素
     *
     * @param limit 容量上限，同时作为底层哈希表的初始容量
     */
    public ArrayQueueMap(int limit) {
        super(limit, 0.75F, true);
        this.limit = limit;
    }
}
