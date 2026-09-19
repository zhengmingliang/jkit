package com.alianga.jkit.html;

/**
 * 解析期字符串驻留缓存：把内容相同的文本、属性值合并为同一实例。
 *
 * <p>真实页面里 class 值、模板化的文本（列表项标签、占位文案）高度重复，逐节点各建一份
 * {@code String} 会让常驻内存显著膨胀。缓存用开放寻址哈希表实现，命中即返回首个登记的
 * 实例，语义与原字符串完全等价（内容不可变），只减少重复实例。
 *
 * <p>容量与装载率有上限，装满后不再收录（只做查重），保证单次解析的内存与时间开销有界；
 * 缓存随解析上下文一次性使用，不跨文档共享，也无需失效。
 *
 * @author 郑明亮
 * @since 2.0.2
 */
final class StringCache {
    /** 槽位上限：真实页面的去重收益在几千个字符串内就饱和了，避免按输入长度放大大数组。 */
    private static final int MAX_CAPACITY = 8192;
    /** 超过该长度的字符串不再收录：长文本（script / style 正文、长段落）几乎不重复，
     *  却要付出 O(len) 的哈希成本。 */
    private static final int MAX_CACHE_LENGTH = 128;

    private final String[] table;
    private final int mask;
    private final int growLimit;
    private int size;
    private boolean full;

    /**
     * @param capacity 期望容量（会向上取整到 2 的幂，并夹在 [64, {@value #MAX_CAPACITY}] 内）
     */
    StringCache(int capacity) {
        int cap = Integer.highestOneBit(Math.max(63, Math.min(capacity, MAX_CAPACITY) - 1)) << 1;
        this.table = new String[cap];
        this.mask = cap - 1;
        this.growLimit = cap - (cap >> 2);
    }

    /**
     * 返回与 {@code s} 内容相同的驻留实例：首次遇到返回 {@code s} 本身，之后命中返回登记实例。
     *
     * @param s 原字符串
     * @return 驻留后的等价实例（绝不会为 {@code null}）
     */
    String dedupe(String s) {
        if (s.isEmpty() || full || s.length() > MAX_CACHE_LENGTH) {
            return s;
        }
        int i = s.hashCode() & mask;
        while (true) {
            String k = table[i];
            if (k == null) {
                if (size >= growLimit) {
                    full = true;
                } else {
                    table[i] = s;
                    size++;
                }
                return s;
            }
            if (k.length() == s.length() && k.equals(s)) {
                return k;
            }
            i = (i + 1) & mask;
        }
    }
}
