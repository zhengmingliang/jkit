package com.alianga.jkit.http.lb;

/**
 * 熔断参数。
 * <p>
 * 两个触发条件取"或"：
 * <ul>
 *   <li><b>连续失败</b>达到 {@link #getConsecutiveFailureThreshold()}——低流量场景下唯一可靠的信号；</li>
 *   <li>窗口内请求数达到 {@link #getMinimumRequests()} 且<b>失败率</b>达到
 *       {@link #getFailureRateThreshold()}——高流量场景下比连续计数灵敏得多，
 *       因为成功请求会不断把连续计数清零。</li>
 * </ul>
 * 冷却时长随连续熔断次数指数增长（上限 {@link #getMaxCooldownMs()}），
 * 避免对一个长期故障的节点每 30 秒放一次流量。
 *
 * @author 郑明亮
 */
public final class BreakerOptions {
    /** 默认连续失败阈值。 */
    public static final int DEFAULT_CONSECUTIVE_FAILURES = 5;
    /** 默认失败率阈值。 */
    public static final double DEFAULT_FAILURE_RATE = 0.5d;
    /** 默认最小样本数。 */
    public static final int DEFAULT_MINIMUM_REQUESTS = 10;
    /** 默认冷却时长（毫秒）。 */
    public static final long DEFAULT_COOLDOWN_MS = 10_000L;
    /** 默认最大冷却时长（毫秒）。 */
    public static final long DEFAULT_MAX_COOLDOWN_MS = 120_000L;

    private final boolean enabled;
    private final int consecutiveFailureThreshold;
    private final double failureRateThreshold;
    private final int minimumRequests;
    private final long cooldownMs;
    private final long maxCooldownMs;

    private BreakerOptions(Builder builder) {
        this.enabled = builder.enabled;
        this.consecutiveFailureThreshold = Math.max(1, builder.consecutiveFailureThreshold);
        this.failureRateThreshold = Math.min(1.0d, Math.max(0.0d, builder.failureRateThreshold));
        this.minimumRequests = Math.max(1, builder.minimumRequests);
        this.cooldownMs = Math.max(1L, builder.cooldownMs);
        this.maxCooldownMs = Math.max(this.cooldownMs, builder.maxCooldownMs);
    }

    /**
     * @return 默认熔断参数
     */
    public static BreakerOptions defaults() {
        return builder().build();
    }

    /**
     * @return 关闭熔断
     */
    public static BreakerOptions disabled() {
        return builder().enabled(false).build();
    }

    /**
     * @return 构造器
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * @return 是否启用熔断
     */
    public boolean isEnabled() {
        return enabled;
    }

    /**
     * @return 连续失败阈值
     */
    public int getConsecutiveFailureThreshold() {
        return consecutiveFailureThreshold;
    }

    /**
     * @return 失败率阈值（0–1）
     */
    public double getFailureRateThreshold() {
        return failureRateThreshold;
    }

    /**
     * @return 触发失败率判定所需的最小窗口样本数
     */
    public int getMinimumRequests() {
        return minimumRequests;
    }

    /**
     * @return 基础冷却时长（毫秒）
     */
    public long getCooldownMs() {
        return cooldownMs;
    }

    /**
     * @return 最大冷却时长（毫秒）
     */
    public long getMaxCooldownMs() {
        return maxCooldownMs;
    }

    /**
     * 第 {@code trips} 次熔断的冷却时长：{@code cooldown × 2^(trips-1)}，上限 maxCooldown。
     *
     * @param trips 累计熔断次数，从 1 开始
     * @return 冷却毫秒数
     */
    public long cooldownMsFor(int trips) {
        long value = cooldownMs;
        for (int i = 1; i < trips && value < maxCooldownMs; i++) {
            value = value > maxCooldownMs / 2 ? maxCooldownMs : value * 2;
        }
        return Math.min(value, maxCooldownMs);
    }

    /**
     * BreakerOptions 构造器。
     */
    public static final class Builder {
        private boolean enabled = true;
        private int consecutiveFailureThreshold = DEFAULT_CONSECUTIVE_FAILURES;
        private double failureRateThreshold = DEFAULT_FAILURE_RATE;
        private int minimumRequests = DEFAULT_MINIMUM_REQUESTS;
        private long cooldownMs = DEFAULT_COOLDOWN_MS;
        private long maxCooldownMs = DEFAULT_MAX_COOLDOWN_MS;

        /**
         * @param enabled 是否启用
         * @return this
         */
        public Builder enabled(boolean enabled) {
            this.enabled = enabled;
            return this;
        }

        /**
         * @param threshold 连续失败阈值
         * @return this
         */
        public Builder consecutiveFailureThreshold(int threshold) {
            this.consecutiveFailureThreshold = threshold;
            return this;
        }

        /**
         * @param threshold 失败率阈值（0–1）
         * @return this
         */
        public Builder failureRateThreshold(double threshold) {
            this.failureRateThreshold = threshold;
            return this;
        }

        /**
         * @param minimumRequests 最小样本数
         * @return this
         */
        public Builder minimumRequests(int minimumRequests) {
            this.minimumRequests = minimumRequests;
            return this;
        }

        /**
         * @param cooldownMs 基础冷却时长
         * @return this
         */
        public Builder cooldownMs(long cooldownMs) {
            this.cooldownMs = cooldownMs;
            return this;
        }

        /**
         * @param maxCooldownMs 最大冷却时长
         * @return this
         */
        public Builder maxCooldownMs(long maxCooldownMs) {
            this.maxCooldownMs = maxCooldownMs;
            return this;
        }

        /**
         * @return 熔断参数
         */
        public BreakerOptions build() {
            return new BreakerOptions(this);
        }
    }
}
