package com.alianga.jkit.json.internal.beans;

/**
 * @time 2024/12/12 23:07
 */
public final class TimeCounter {
    private long nanoTime;

    /**
     * 构造计时器并立即记录起始时间
     */
    public TimeCounter() {
        mark();
    }

    /**
     * 重新记录起始时间，后续的间隔计算以此刻为基准
     */
    public void mark() {
        nanoTime = System.nanoTime();
    }

    /**
     * 计算距离上次标记的耗时
     *
     * @return 距离上次标记经过的纳秒数
     */
    public long intervalNanoTime() {
        return System.nanoTime() - nanoTime;
    }

    /**
     * 计算距离上次标记的耗时
     *
     * @return 距离上次标记经过的毫秒数（纳秒数除以 1000000 取整）
     */
    public long intervalMillis() {
        return (System.nanoTime() - nanoTime) / 1000000;
    }
}
