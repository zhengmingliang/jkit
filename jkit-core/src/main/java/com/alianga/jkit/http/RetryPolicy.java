package com.alianga.jkit.http;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.net.ConnectException;
import java.net.NoRouteToHostException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.util.Collections;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;

/**
 * HTTP 请求重试策略。默认不重试，避免对非幂等请求造成重复提交。
 * <p>
 * 常用预设见 {@link #defaults()}：3 次尝试、重试 IO 异常与 408/429/500/502/503/504、
 * 200ms 起指数退避并带 ±20% 抖动、遵循服务端的 {@code Retry-After}。
 */
public final class RetryPolicy {
    /** 默认参与重试的状态码。 */
    private static final int[] DEFAULT_RETRY_STATUS = {408, 429, 500, 502, 503, 504};

    private final int maxAttempts;
    private final long initialBackoffMs;
    private final long maxBackoffMs;
    private final boolean retryNonIdempotent;
    private final Set<Integer> retryStatusCodes;
    private final boolean retryIoExceptions;
    private final double jitterFactor;
    private final boolean respectRetryAfter;

    private RetryPolicy(Builder builder) {
        this.maxAttempts = Math.max(1, builder.maxAttempts);
        this.initialBackoffMs = Math.max(0L, builder.initialBackoffMs);
        this.maxBackoffMs = Math.max(this.initialBackoffMs, builder.maxBackoffMs);
        this.retryNonIdempotent = builder.retryNonIdempotent;
        this.retryIoExceptions = builder.retryIoExceptions;
        this.retryStatusCodes = Collections.unmodifiableSet(new HashSet<Integer>(builder.retryStatusCodes));
        this.jitterFactor = Math.min(1.0, Math.max(0.0, builder.jitterFactor));
        this.respectRetryAfter = builder.respectRetryAfter;
    }

    /**
     * @return 不重试策略
     */
    public static RetryPolicy none() {
        return builder().maxAttempts(1).build();
    }

    /**
     * 生产环境常用预设：3 次尝试、重试 IO 异常与 408/429/500/502/503/504、
     * 200ms 起指数退避（上限 10s）、±20% 抖动、遵循 {@code Retry-After}。
     * 不重试 POST/PATCH（非幂等）。
     *
     * @return 默认重试策略
     */
    public static RetryPolicy defaults() {
        return builder()
                .maxAttempts(3)
                .retryOnIOException()
                .retryStatus(DEFAULT_RETRY_STATUS)
                .initialBackoffMs(200L)
                .maxBackoffMs(10_000L)
                .jitterFactor(0.2)
                .respectRetryAfter(true)
                .build();
    }

    /**
     * 创建策略构造器。
     *
     * @return 构造器
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * @return 最大尝试次数（包含首次请求）
     */
    public int getMaxAttempts() {
        return maxAttempts;
    }

    /**
     * @return 退避抖动系数，0 表示无抖动
     */
    public double getJitterFactor() {
        return jitterFactor;
    }

    /**
     * @return 是否遵循响应中的 {@code Retry-After}
     */
    public boolean isRespectRetryAfter() {
        return respectRetryAfter;
    }

    /**
     * 判断异常是否允许重试。
     *
     * @param request 请求
     * @param error 异常
     * @return 是否重试
     */
    public boolean shouldRetry(HttpRequest request, IOException error) {
        return retryIoExceptions && error != null && canRetryRequest(request, error);
    }

    /**
     * 判断状态码是否允许重试。
     *
     * @param request 请求
     * @param statusCode 状态码
     * @return 是否重试
     */
    public boolean shouldRetry(HttpRequest request, int statusCode) {
        return retryStatusCodes.contains(statusCode) && canRetryRequest(request, null);
    }

    /**
     * 返回第 {@code attempt} 次重试前等待的毫秒数（指数退避 + 抖动）。
     *
     * @param attempt 已完成的尝试次数，从 1 开始
     * @return 等待时间
     */
    public long backoffMs(int attempt) {
        return applyJitter(baseBackoffMs(attempt));
    }

    /**
     * 计算重试等待时间，优先采用服务端 {@code Retry-After}（若开启且取值合法）。
     *
     * @param attempt 已完成的尝试次数，从 1 开始
     * @param response 上一次的响应，可为 {@code null}（异常重试时）
     * @return 等待时间毫秒
     */
    public long retryDelayMs(int attempt, HttpResponse response) {
        long retryAfter = respectRetryAfter ? parseRetryAfterMs(response) : -1L;
        if (retryAfter >= 0L) {
            // Retry-After 是服务端的明确要求，按它执行，但仍受 maxBackoffMs 约束
            return Math.min(retryAfter, maxBackoffMs <= 0 ? retryAfter : maxBackoffMs);
        }
        return backoffMs(attempt);
    }

    /**
     * 解析 {@code Retry-After}：支持"秒数"与 HTTP-date 两种形式。
     *
     * @param response 响应，可为 {@code null}
     * @return 毫秒数；无该头或无法解析时返回 {@code -1}
     */
    static long parseRetryAfterMs(HttpResponse response) {
        if (response == null) {
            return -1L;
        }
        String raw = response.header("Retry-After");
        if (raw == null) {
            return -1L;
        }
        String value = raw.trim();
        if (value.isEmpty()) {
            return -1L;
        }
        try {
            long seconds = Long.parseLong(value);
            return seconds < 0L ? -1L : seconds * 1000L;
        } catch (NumberFormatException ignore) {
            // 继续尝试 HTTP-date
        }
        try {
            long epochMs = java.time.format.DateTimeFormatter.RFC_1123_DATE_TIME
                    .parse(value, java.time.ZonedDateTime::from)
                    .toInstant().toEpochMilli();
            long delta = epochMs - System.currentTimeMillis();
            return delta < 0L ? 0L : delta;
        } catch (RuntimeException ignore) {
            return -1L;
        }
    }

    private long baseBackoffMs(int attempt) {
        if (initialBackoffMs <= 0 || attempt <= 0) {
            return 0L;
        }
        long value = initialBackoffMs;
        for (int i = 1; i < attempt && value < maxBackoffMs; i++) {
            value = Math.min(maxBackoffMs, value > Long.MAX_VALUE / 2 ? maxBackoffMs : value * 2);
        }
        return value;
    }

    /**
     * 加入 ±jitterFactor 的随机抖动，避免多个客户端在服务端恢复后同时重试（惊群）。
     */
    private long applyJitter(long base) {
        if (base <= 0L || jitterFactor <= 0.0) {
            return base;
        }
        double span = base * jitterFactor;
        double offset = ThreadLocalRandom.current().nextDouble(-span, span);
        long jittered = (long) (base + offset);
        return jittered < 0L ? 0L : jittered;
    }

    /**
     * 幂等性判断：
     * <ul>
     *   <li>GET / HEAD / OPTIONS / TRACE / DELETE / PUT 视为幂等，可重试；</li>
     *   <li>POST / PATCH 非幂等，仅当 {@code retryNonIdempotent} 或请求带
     *       {@code Idempotency-Key} 时才重试；</li>
     *   <li>连接建立阶段的失败（{@link ConnectException}、{@link UnknownHostException}、
     *       {@link NoRouteToHostException}）说明请求根本没发出去，
     *       即使是 POST 也可以安全重试。</li>
     * </ul>
     */
    private boolean canRetryRequest(HttpRequest request, IOException error) {
        if (request == null) {
            return false;
        }
        if (!isNonIdempotent(request.getMethod())) {
            return true;
        }
        if (retryNonIdempotent) {
            return true;
        }
        if (request.getHeader("Idempotency-Key") != null) {
            return true;
        }
        return isConnectPhaseFailure(error);
    }

    private static boolean isNonIdempotent(String method) {
        if (method == null) {
            return false;
        }
        String m = method.toUpperCase(Locale.ROOT);
        return HttpRequest.POST.equals(m) || HttpRequest.PATCH.equals(m);
    }

    /**
     * 请求是否在"还没送达服务端"的阶段就失败了。这类失败重试不会造成重复提交。
     */
    static boolean isConnectPhaseFailure(IOException error) {
        if (error == null) {
            return false;
        }
        if (error instanceof ConnectException
                || error instanceof UnknownHostException
                || error instanceof NoRouteToHostException) {
            return true;
        }
        // SocketTimeoutException 既可能是连接超时也可能是读超时，无法区分，保守地不算
        return false;
    }

    /**
     * 该异常是否属于"上游端点不可用"，用于负载均衡的失败计数与熔断。
     * 读超时是否计入由 {@code countReadTimeout} 决定：对单向推送 / 长连接接口，
     * 读超时不代表节点故障。
     *
     * @param error 异常
     * @param countReadTimeout 读超时是否算作端点故障
     * @return 是否算作端点故障
     */
    public static boolean isEndpointFailure(IOException error, boolean countReadTimeout) {
        if (error == null) {
            return false;
        }
        if (isConnectPhaseFailure(error)) {
            return true;
        }
        if (error instanceof SocketTimeoutException) {
            return countReadTimeout;
        }
        if (error instanceof InterruptedIOException) {
            return false;
        }
        return true;
    }

    /**
     * 该状态码是否属于"上游端点不可用"。业务类 4xx（400/401/403/404 等）是请求本身的问题，
     * 不能算作节点故障，否则一批业务 404 会把整个池熔断掉。
     *
     * @param statusCode 状态码
     * @return 是否算作端点故障
     */
    public static boolean isEndpointFailure(int statusCode) {
        return statusCode == 429 || statusCode >= 500;
    }

    /**
     * RetryPolicy builder.
     */
    public static final class Builder {
        private int maxAttempts = 1;
        private long initialBackoffMs;
        private long maxBackoffMs = 30_000L;
        private boolean retryNonIdempotent;
        private boolean retryIoExceptions;
        private double jitterFactor;
        private boolean respectRetryAfter = true;
        private final Set<Integer> retryStatusCodes = new HashSet<Integer>();

        /**
         * @param maxAttempts 最大尝试次数
         * @return this
         */
        public Builder maxAttempts(int maxAttempts) {
            this.maxAttempts = maxAttempts;
            return this;
        }

        /**
         * @param initialBackoffMs 初始退避时间
         * @return this
         */
        public Builder initialBackoffMs(long initialBackoffMs) {
            this.initialBackoffMs = initialBackoffMs;
            return this;
        }

        /**
         * @param maxBackoffMs 最大退避时间
         * @return this
         */
        public Builder maxBackoffMs(long maxBackoffMs) {
            this.maxBackoffMs = maxBackoffMs;
            return this;
        }

        /**
         * 退避抖动系数：实际等待时间在 {@code base × (1 ± factor)} 之间随机。
         * 0 表示不抖动；建议 0.1–0.3，避免服务端恢复瞬间被所有客户端同时打爆。
         *
         * @param jitterFactor 抖动系数，取值 [0, 1]
         * @return this
         */
        public Builder jitterFactor(double jitterFactor) {
            this.jitterFactor = jitterFactor;
            return this;
        }

        /**
         * 是否遵循服务端返回的 {@code Retry-After}（秒数或 HTTP-date），默认 {@code true}。
         *
         * @param respect 是否遵循
         * @return this
         */
        public Builder respectRetryAfter(boolean respect) {
            this.respectRetryAfter = respect;
            return this;
        }

        /**
         * 允许 POST/PATCH 等非幂等请求重试。
         * <p>
         * 不开启时，POST/PATCH 只在两种情况下重试：请求带 {@code Idempotency-Key} 头，
         * 或失败发生在连接建立阶段（请求确定没送达服务端）。
         *
         * @param retry 是否允许
         * @return this
         */
        public Builder retryNonIdempotent(boolean retry) {
            this.retryNonIdempotent = retry;
            return this;
        }

        /**
         * 添加可触发重试的状态码。
         *
         * @param statusCodes 状态码
         * @return this
         */
        public Builder retryStatus(int... statusCodes) {
            if (statusCodes != null) {
                for (int statusCode : statusCodes) {
                    retryStatusCodes.add(statusCode);
                }
            }
            return this;
        }

        /**
         * 开启 IO 异常重试。
         *
         * @return this
         */
        public Builder retryOnIOException() {
            this.retryIoExceptions = true;
            return this;
        }

        /**
         * @return 策略
         */
        public RetryPolicy build() {
            return new RetryPolicy(this);
        }
    }
}
