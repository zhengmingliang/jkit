package com.alianga.jkit.http.lb;

/**
 * 分桶滑动窗口计数器：统计最近一段时间内的请求数与失败数，用于按失败率熔断。
 * <p>
 * "连续失败 N 次"在混合流量下很难触发（成功请求会不断清零），而失败率能真实反映
 * 端点状态。窗口按固定时长分桶，过期桶惰性清零，无需后台线程。
 * <p>
 * 线程安全：所有方法都在实例锁内完成。桶数很少（默认 10），竞争可忽略。
 *
 * @author 郑明亮
 */
final class SlidingWindowCounter {
    private final int bucketCount;
    private final long bucketMs;
    private final int[] totals;
    private final int[] failures;
    private final long[] bucketStartMs;

    SlidingWindowCounter(int bucketCount, long bucketMs) {
        this.bucketCount = Math.max(1, bucketCount);
        this.bucketMs = Math.max(1L, bucketMs);
        this.totals = new int[this.bucketCount];
        this.failures = new int[this.bucketCount];
        this.bucketStartMs = new long[this.bucketCount];
    }

    /**
     * @return 窗口总时长（毫秒）
     */
    long windowMs() {
        return bucketCount * bucketMs;
    }

    synchronized void record(long nowMs, boolean failure) {
        int index = bucketIndex(nowMs);
        rollIfStale(index, nowMs);
        totals[index]++;
        if (failure) {
            failures[index]++;
        }
    }

    synchronized int total(long nowMs) {
        int sum = 0;
        long oldest = nowMs - windowMs();
        for (int i = 0; i < bucketCount; i++) {
            if (bucketStartMs[i] > oldest) {
                sum += totals[i];
            }
        }
        return sum;
    }

    synchronized int failures(long nowMs) {
        int sum = 0;
        long oldest = nowMs - windowMs();
        for (int i = 0; i < bucketCount; i++) {
            if (bucketStartMs[i] > oldest) {
                sum += failures[i];
            }
        }
        return sum;
    }

    synchronized void reset() {
        for (int i = 0; i < bucketCount; i++) {
            totals[i] = 0;
            failures[i] = 0;
            bucketStartMs[i] = 0L;
        }
    }

    synchronized void copyFrom(SlidingWindowCounter other) {
        if (other == null || other.bucketCount != bucketCount) {
            return;
        }
        synchronized (other) {
            System.arraycopy(other.totals, 0, totals, 0, bucketCount);
            System.arraycopy(other.failures, 0, failures, 0, bucketCount);
            System.arraycopy(other.bucketStartMs, 0, bucketStartMs, 0, bucketCount);
        }
    }

    private int bucketIndex(long nowMs) {
        return (int) Math.abs((nowMs / bucketMs) % bucketCount);
    }

    /**
     * 复用到同一个槽位但已跨过一整轮窗口时，先把旧数据清掉。
     */
    private void rollIfStale(int index, long nowMs) {
        long slotStart = (nowMs / bucketMs) * bucketMs;
        if (bucketStartMs[index] != slotStart) {
            bucketStartMs[index] = slotStart;
            totals[index] = 0;
            failures[index] = 0;
        }
    }
}
