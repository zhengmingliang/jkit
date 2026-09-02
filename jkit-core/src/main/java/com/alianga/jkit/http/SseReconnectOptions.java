package com.alianga.jkit.http;

/**
 * SSE 自动重连选项。
 *
 * @author 郑明亮
 */
public final class SseReconnectOptions {
    private int maxRetries = -1;
    private long initialBackoffMs = 1000L;
    private long maxBackoffMs = 30_000L;
    private double multiplier = 2.0D;
    private boolean reconnectOnStreamEnd = true;
    private boolean honorServerRetry = true;
    private boolean sendLastEventId = true;

    private SseReconnectOptions() {
    }

    /**
     * @return 选项构建器
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * @return 最大重连次数，{@code -1} 表示不限制
     */
    public int getMaxRetries() {
        return maxRetries;
    }

    /**
     * @return 首次重连退避毫秒数
     */
    public long getInitialBackoffMs() {
        return initialBackoffMs;
    }

    /**
     * @return 退避上限毫秒数
     */
    public long getMaxBackoffMs() {
        return maxBackoffMs;
    }

    /**
     * @return 退避倍增系数
     */
    public double getMultiplier() {
        return multiplier;
    }

    /**
     * @return 服务端正常关闭流时是否重连
     */
    public boolean isReconnectOnStreamEnd() {
        return reconnectOnStreamEnd;
    }

    /**
     * @return 是否尊重服务端 {@code retry:} 字段的重连间隔
     */
    public boolean isHonorServerRetry() {
        return honorServerRetry;
    }

    /**
     * @return 重连时是否携带 {@code Last-Event-ID} 头
     */
    public boolean isSendLastEventId() {
        return sendLastEventId;
    }

    /**
     * SSE 重连选项构建器。
     */
    public static final class Builder {
        private final SseReconnectOptions options = new SseReconnectOptions();

        /**
         * 最大重连次数，{@code -1} 表示不限制（默认）。
         *
         * @param maxRetries 次数
         * @return this
         */
        public Builder maxRetries(int maxRetries) {
            options.maxRetries = maxRetries;
            return this;
        }

        /**
         * 首次重连退避毫秒数（默认 1000）。
         *
         * @param initialBackoffMs 毫秒
         * @return this
         */
        public Builder initialBackoffMs(long initialBackoffMs) {
            options.initialBackoffMs = Math.max(0, initialBackoffMs);
            return this;
        }

        /**
         * 退避上限毫秒数（默认 30 秒）。
         *
         * @param maxBackoffMs 毫秒
         * @return this
         */
        public Builder maxBackoffMs(long maxBackoffMs) {
            options.maxBackoffMs = Math.max(0, maxBackoffMs);
            return this;
        }

        /**
         * 退避倍增系数（默认 2.0）。
         *
         * @param multiplier 系数
         * @return this
         */
        public Builder multiplier(double multiplier) {
            options.multiplier = multiplier <= 1.0D ? 1.0D : multiplier;
            return this;
        }

        /**
         * 服务端正常关闭流时是否重连（SSE 规范行为，默认开启；
         * LLM 一次性输出等场景可关闭）。
         *
         * @param reconnectOnStreamEnd 是否重连
         * @return this
         */
        public Builder reconnectOnStreamEnd(boolean reconnectOnStreamEnd) {
            options.reconnectOnStreamEnd = reconnectOnStreamEnd;
            return this;
        }

        /**
         * 是否尊重服务端 {@code retry:} 字段的重连间隔（默认开启）。
         *
         * @param honorServerRetry 是否尊重
         * @return this
         */
        public Builder honorServerRetry(boolean honorServerRetry) {
            options.honorServerRetry = honorServerRetry;
            return this;
        }

        /**
         * 重连时是否携带 {@code Last-Event-ID} 头（默认开启）。
         *
         * @param sendLastEventId 是否携带
         * @return this
         */
        public Builder sendLastEventId(boolean sendLastEventId) {
            options.sendLastEventId = sendLastEventId;
            return this;
        }

        /**
         * @return 构建好的选项
         */
        public SseReconnectOptions build() {
            return options;
        }
    }
}
