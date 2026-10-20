package com.alianga.jkit.mock.server;

/**
 * 网络层故障注入配置（路由级）：模拟「状态码之外」的真实故障，用于测试客户端容错。
 * 在 {@link MockHttpServer} 中于「延迟之后、写出之前」生效；SSE / WS 路由忽略。
 *
 * <ul>
 *   <li>{@link Mode#CONNECTION_CLOSE}：收到请求后不返回任何响应直接关闭连接。
 *       JDK HttpServer 做不到精确的 TCP RST——handler 直接 close 会让交换未提交响应，
 *       连接被回收时客户端通常读到 EOF / empty reply / reset，效果近似「17 端口被拔」；</li>
 *   <li>{@link Mode#EMPTY_RESPONSE}：返回状态码与响应头，响应体为空；</li>
 *   <li>{@link Mode#TRUNCATED_BODY}：声明完整 Content-Length，只写出一部分响应体后断开
 *       （{@link #truncateBytes} 按字节数，或 {@link #truncatePercent} 按比例）；</li>
 *   <li>{@link Mode#SLOW_BODY}：响应体分块输出，{@link #chunkBytes} 每块字节数、
 *       {@link #chunkDelayMs} 块间隔毫秒；</li>
 *   <li>{@link Mode#INVALID_JSON}：返回刻意损坏的 JSON（去掉结尾大括号），Content-Type 仍为
 *       application/json（解析必失败，客户端容错直接暴露）。</li>
 * </ul>
 *
 * <p>不可变；用 {@link Builder} 组装。构造层对非法参数（负数、越界）直接抛出带字面的
 * {@link IllegalArgumentException}，不静默回落——配置是布尔性的，悄悄改值最骗人。</p>
 *
 * @author 郑明亮
 * @since 2.0.5
 */
public final class MockFault {
    /** 故障模式。 */
    public enum Mode {
        /** 不注入故障（缺省，历史行为）。 */
        NONE,
        /** 连接关闭（近似：JDK HttpServer 无法主动 TCP RST）。 */
        CONNECTION_CLOSE,
        /** 空响应体（状态码与响应头正常）。 */
        EMPTY_RESPONSE,
        /** 声明完整 Content-Length 只写一部分就断开。 */
        TRUNCATED_BODY,
        /** 分块慢输出。 */
        SLOW_BODY,
        /** 刻意损坏的 JSON。 */
        INVALID_JSON
    }

    /** 不注入故障的常量配置。 */
    public static MockFault none() {
        return NONE_INSTANCE;
    }

    private static final MockFault NONE_INSTANCE = new MockFault(Mode.NONE, 100, 0, 0, 0, 0L);

    public final Mode mode;
    /**
     * 命中该路由时触发故障的概率（0–100 的百分比）；默认 100（每次必发），取 0 视为关闭。
     */
    public final int probabilityPercent;
    /**
     * {@link Mode#TRUNCATED_BODY} 按字节数写出；&gt;=0 有效，&gt;0 时优先于比例。
     */
    public final int truncateBytes;
    /**
     * {@link Mode#TRUNCATED_BODY} 按比例写出（1–99）；truncateBytes &gt; 0 时忽略。
     */
    public final int truncatePercent;
    /** {@link Mode#SLOW_BODY} 每块写出的字节数（&gt;0 有效）。 */
    public final int chunkBytes;
    /** {@link Mode#SLOW_BODY} 相邻块之间的延迟毫秒（&gt;=0 有效）。 */
    public final long chunkDelayMs;

    private MockFault(Mode mode, int probabilityPercent, int truncateBytes,
                      int truncatePercent, int chunkBytes, long chunkDelayMs) {
        this.mode = mode;
        this.probabilityPercent = probabilityPercent;
        this.truncateBytes = truncateBytes;
        this.truncatePercent = truncatePercent;
        this.chunkBytes = chunkBytes;
        this.chunkDelayMs = chunkDelayMs;
    }

    /** TRUNCATED_BODY 按比例写出时实际应写出的字节数（按最小 1 字节兜底，保证「真少一块」）。 */
    int truncatedBytesFor(int totalBytes) {
        if (truncateBytes > 0) {
            return Math.min(truncateBytes, totalBytes);
        }
        int written = totalBytes * truncatePercent / 100;
        return Math.min(totalBytes, Math.max(1, written));
    }

    public static Builder builder() {
        return new Builder();
    }

    /** {@link MockFault} 的 builder；build() 对非法参数抛异常而非静默回落。 */
    public static final class Builder {
        private Mode mode = Mode.NONE;
        private int probabilityPercent = 100;
        private int truncateBytes;
        /** 比例缺省 50：开了 TRUNCATED 又没配参数时也「真截一半」，不是全量回写。 */
        private int truncatePercent = 50;
        private int chunkBytes;
        private long chunkDelayMs;

        public Builder mode(Mode value) {
            this.mode = value == null ? Mode.NONE : value;
            return this;
        }

        public Builder probabilityPercent(int value) {
            this.probabilityPercent = value;
            return this;
        }

        public Builder truncateBytes(int value) {
            this.truncateBytes = value;
            return this;
        }

        public Builder truncatePercent(int value) {
            this.truncatePercent = value;
            return this;
        }

        public Builder chunkBytes(int value) {
            this.chunkBytes = value;
            return this;
        }

        public Builder chunkDelayMs(long value) {
            this.chunkDelayMs = value;
            return this;
        }

        /**
         * 构造不可变实例；非法参数（负数、越界）抛出带字面的异常，不静默回落。
         *
         * @throws IllegalArgumentException 概率超出 0–100、字节数为负、比例超出 1–99、延迟为负
         */
        public MockFault build() {
            if (probabilityPercent < 0 || probabilityPercent > 100) {
                throw new IllegalArgumentException("故障概率需在 0–100 之间: " + probabilityPercent);
            }
            if (truncateBytes < 0) {
                throw new IllegalArgumentException("截断字节数不能为负: " + truncateBytes);
            }
            if (truncatePercent < 1 || truncatePercent > 99) {
                // 0/100 意味着「没截断」，不是失败注入，属配置错误
                throw new IllegalArgumentException("截断比例需在 1–99 之间: " + truncatePercent);
            }
            if (chunkBytes < 0) {
                throw new IllegalArgumentException("分块字节数不能为负: " + chunkBytes);
            }
            if (chunkDelayMs < 0) {
                throw new IllegalArgumentException("分块间隔不能为负: " + chunkDelayMs);
            }
            if (truncateBytes > 0) {
                // 按字节截断时比例不再参与，锁定为一个无害值，便于 equals 语义稳定
                truncatePercent = 50;
            }
            if (mode == Mode.NONE) {
                return none();
            }
            return new MockFault(mode, probabilityPercent,
                    truncateBytes, truncatePercent, chunkBytes, chunkDelayMs);
        }
    }

    /** 日志与提示用的一句话标识，如 {@code SLOW_BODY(256B/200ms)}。 */
    @Override
    public String toString() {
        switch (mode) {
            case TRUNCATED_BODY:
                return truncateBytes > 0
                        ? "TRUNCATED_BODY(" + truncateBytes + "B)"
                        : "TRUNCATED_BODY(" + truncatePercent + "%)";
            case SLOW_BODY:
                return "SLOW_BODY(" + chunkBytes + "B/" + chunkDelayMs + "ms)";
            default:
                return mode.name();
        }
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof MockFault)) {
            return false;
        }
        MockFault other = (MockFault) o;
        return mode == other.mode
                && probabilityPercent == other.probabilityPercent
                && truncateBytes == other.truncateBytes
                && truncatePercent == other.truncatePercent
                && chunkBytes == other.chunkBytes
                && chunkDelayMs == other.chunkDelayMs;
    }

    @Override
    public int hashCode() {
        int result = mode.hashCode();
        result = 31 * result + probabilityPercent;
        result = 31 * result + truncateBytes;
        result = 31 * result + truncatePercent;
        result = 31 * result + chunkBytes;
        result = 31 * result + (int) (chunkDelayMs ^ (chunkDelayMs >>> 32));
        return result;
    }
}
