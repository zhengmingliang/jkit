package com.alianga.jkit.http.lb;

import com.alianga.jkit.http.HttpIo;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 负载均衡池中的一个上游端点：不变的身份（基址、权重、元数据）+ 可变的健康状态
 * （在途请求数、EWMA 延迟、滑动窗口失败率、熔断冷却、半开探测令牌）。
 * <p>
 * 身份用规范化后的基址表示：{@code scheme://host[:port]}，其中默认端口会被省略、
 * host 转小写、userinfo 被剥离（凭据不应出现在端点标识与日志里）。
 * 因此 {@code https://api.example.com:443} 与 {@code https://API.example.com}
 * 是同一个端点。
 * <p>
 * 线程安全：所有可变状态都是原子字段或受自身锁保护。
 *
 * @author 郑明亮
 */
public final class Endpoint {
    /** 权重上限，避免权重求和溢出导致选择逻辑崩溃。 */
    public static final int MAX_WEIGHT = 1_000_000;

    private final String baseUrl;
    private final int weight;
    private final Map<String, String> metadata;

    private final AtomicInteger inFlight = new AtomicInteger();
    private final AtomicLong lastUsedMs = new AtomicLong();
    private final AtomicLong cooldownUntilMs = new AtomicLong();
    private final AtomicInteger consecutiveFailures = new AtomicInteger();
    private final AtomicInteger breakerTrips = new AtomicInteger();
    /** 半开探测令牌：冷却结束后只允许一个请求去试探。 */
    private final AtomicBoolean probeToken = new AtomicBoolean();
    /** 运维手动摘流开关。 */
    private final AtomicBoolean disabled = new AtomicBoolean();
    /** 平滑加权轮询的当前权重（nginx current_weight），由策略维护。 */
    private final AtomicInteger currentWeight = new AtomicInteger();
    /** EWMA 延迟，单位微秒；0 表示还没有样本。 */
    private final AtomicLong ewmaLatencyMicros = new AtomicLong();
    private final SlidingWindowCounter window;

    /**
     * @param baseUrl 端点基址，如 {@code https://api1.example.com} 或 {@code http://10.0.0.7:8080}
     * @param weight 调度权重，会被收敛到 {@code [1, MAX_WEIGHT]}
     * @param metadata 端点元数据（如 Nacos 的 cluster、region），可为 {@code null}
     */
    public Endpoint(String baseUrl, int weight, Map<String, String> metadata) {
        this.baseUrl = normalize(baseUrl);
        this.weight = Math.min(MAX_WEIGHT, Math.max(1, weight));
        Map<String, String> copy = new LinkedHashMap<String, String>();
        if (metadata != null) {
            for (Map.Entry<String, String> entry : metadata.entrySet()) {
                if (entry.getKey() != null) {
                    copy.put(entry.getKey(), entry.getValue());
                }
            }
        }
        this.metadata = Collections.unmodifiableMap(copy);
        this.window = new SlidingWindowCounter(10, 1000L);
    }

    /**
     * @param baseUrl 端点基址
     * @param weight 调度权重
     */
    public Endpoint(String baseUrl, int weight) {
        this(baseUrl, weight, null);
    }

    /**
     * @param baseUrl 端点基址，权重 1
     */
    public Endpoint(String baseUrl) {
        this(baseUrl, 1, null);
    }

    /**
     * 规范化端点基址为 origin：{@code scheme://host[:port]}。
     * <ul>
     *   <li>缺省 scheme 时：带端口或形如 IP 的按 {@code http} 处理（内网服务的常见形态），
     *       否则按 {@code https}；</li>
     *   <li>省略默认端口（http:80 / https:443），使 {@code a.com} 与 {@code a.com:80} 归为一个端点；</li>
     *   <li>剥离 userinfo，避免凭据进入端点标识与日志；</li>
     *   <li>保留 IPv6 的方括号。</li>
     * </ul>
     *
     * @param url 原始基址或完整 URL
     * @return 规范化 origin
     * @throws IllegalArgumentException 基址为空或缺少 host
     */
    public static String normalize(String url) {
        if (url == null || url.trim().isEmpty()) {
            throw new IllegalArgumentException("endpoint baseUrl required");
        }
        String remaining = url.trim();
        String scheme = null;
        int schemeIdx = remaining.indexOf("://");
        if (schemeIdx > 0) {
            scheme = remaining.substring(0, schemeIdx).toLowerCase(Locale.ROOT);
            remaining = remaining.substring(schemeIdx + 3);
        } else if (remaining.startsWith("//")) {
            remaining = remaining.substring(2);
        }
        // 去掉路径 / 查询 / 片段，只保留 authority
        int cut = indexOfAny(remaining, '/', '?', '#');
        String authority = cut >= 0 ? remaining.substring(0, cut) : remaining;
        int at = authority.lastIndexOf('@');
        if (at >= 0) {
            authority = authority.substring(at + 1);
        }
        if (authority.isEmpty()) {
            throw new IllegalArgumentException("endpoint baseUrl requires a host: " + url);
        }
        String host;
        int port = -1;
        if (authority.startsWith("[")) {
            int close = authority.indexOf(']');
            if (close < 0) {
                throw new IllegalArgumentException("malformed IPv6 endpoint: " + url);
            }
            host = authority.substring(0, close + 1);
            String rest = authority.substring(close + 1);
            if (rest.startsWith(":")) {
                port = parsePort(rest.substring(1), url);
            }
        } else if (authority.indexOf(':') != authority.lastIndexOf(':')) {
            // 多个冒号说明是没加方括号的裸 IPv6，先补括号再判断，
            // 否则会被当成 host:port 而把 "fe80::1" 的 ":1" 当端口解析失败
            host = "[" + authority + "]";
        } else {
            int colon = authority.indexOf(':');
            if (colon >= 0) {
                host = authority.substring(0, colon);
                port = parsePort(authority.substring(colon + 1), url);
            } else {
                host = authority;
            }
        }
        if (host.isEmpty()) {
            throw new IllegalArgumentException("endpoint baseUrl requires a host: " + url);
        }
        host = host.toLowerCase(Locale.ROOT);
        if (scheme == null) {
            // 明确写了端口或是 IP 形态的，按内网明文服务处理；纯域名默认 https
            scheme = port >= 0 || HttpIo.isIpLiteral(host) ? "http" : "https";
        }
        boolean defaultPort = ("http".equals(scheme) && port == 80)
                || ("https".equals(scheme) && port == 443);
        if (port < 0 || defaultPort) {
            return scheme + "://" + host;
        }
        return scheme + "://" + host + ":" + port;
    }

    private static int indexOfAny(String value, char... chars) {
        int best = -1;
        for (char c : chars) {
            int idx = value.indexOf(c);
            if (idx >= 0 && (best < 0 || idx < best)) {
                best = idx;
            }
        }
        return best;
    }

    private static int parsePort(String raw, String url) {
        if (raw.isEmpty()) {
            return -1;
        }
        try {
            int port = Integer.parseInt(raw);
            if (port < 1 || port > 65535) {
                throw new IllegalArgumentException("invalid endpoint port: " + url);
            }
            return port;
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("invalid endpoint port: " + url);
        }
    }

    /**
     * @return 规范化后的端点基址（origin）
     */
    public String getBaseUrl() {
        return baseUrl;
    }

    /**
     * @return 调度权重
     */
    public int getWeight() {
        return weight;
    }

    /**
     * @return 端点元数据（只读）
     */
    public Map<String, String> getMetadata() {
        return metadata;
    }

    /**
     * @return 当前在途请求数
     */
    public int getInFlight() {
        return inFlight.get();
    }

    /**
     * @return 连续失败次数
     */
    public int getConsecutiveFailures() {
        return consecutiveFailures.get();
    }

    /**
     * @return 熔断累计触发次数
     */
    public int getBreakerTrips() {
        return breakerTrips.get();
    }

    /**
     * 是否处于熔断冷却中（拒绝一切流量）。
     *
     * @param nowMs 当前时间戳
     * @return 是否冷却中
     */
    public boolean isCoolingDown(long nowMs) {
        long until = cooldownUntilMs.get();
        return until != 0L && nowMs < until;
    }

    /**
     * 是否处于半开状态：熔断过、冷却已到期，但还没有一次成功来证明它恢复了。
     * <p>
     * 半开端点不参与正常调度，只接受一个探测请求；探测成功才回到正常池，
     * 探测失败会重新进入（更长的）冷却。
     *
     * @param nowMs 当前时间戳
     * @return 是否半开
     */
    public boolean isHalfOpen(long nowMs) {
        long until = cooldownUntilMs.get();
        return until != 0L && nowMs >= until;
    }

    /**
     * @return 冷却截止时间戳（毫秒），0 表示未熔断
     */
    public long getCooldownUntilMs() {
        return cooldownUntilMs.get();
    }

    /**
     * @return 最近一次被选中的时间戳（毫秒）
     */
    public long getLastUsedMs() {
        return lastUsedMs.get();
    }

    /**
     * @return EWMA 延迟（毫秒），无样本时为 0
     */
    public double getEwmaLatencyMs() {
        return ewmaLatencyMicros.get() / 1000.0d;
    }

    /**
     * @return 窗口内请求数
     */
    public int getWindowTotal() {
        return window.total(System.currentTimeMillis());
    }

    /**
     * @return 窗口内失败数
     */
    public int getWindowFailures() {
        return window.failures(System.currentTimeMillis());
    }

    /**
     * @return 是否被运维手动摘流
     */
    public boolean isDisabled() {
        return disabled.get();
    }

    /**
     * 手动摘流 / 恢复。摘流后该端点不再参与调度，但保留统计。
     *
     * @param value {@code true} 摘流
     */
    public void setDisabled(boolean value) {
        disabled.set(value);
    }

    /**
     * 端点是否可以承接普通流量：未摘流，且既不在冷却中也不在半开中。
     *
     * @param nowMs 当前时间戳
     * @return 是否可用
     */
    public boolean isAvailable(long nowMs) {
        return !disabled.get() && cooldownUntilMs.get() == 0L;
    }

    /**
     * 尝试领取半开探测令牌。冷却期结束后，只有拿到令牌的那一个请求会去试探，
     * 避免所有并发请求一起打向一个已知故障的节点。
     *
     * @return 是否成功领取
     */
    public boolean tryAcquireProbe() {
        return probeToken.compareAndSet(false, true);
    }

    /**
     * 归还探测令牌。
     */
    public void releaseProbe() {
        probeToken.set(false);
    }

    /**
     * @return 探测令牌是否已被占用
     */
    public boolean isProbing() {
        return probeToken.get();
    }

    // ---------- 以下由 EndpointPool / 策略调用 ----------

    void markSelected(long nowMs) {
        inFlight.incrementAndGet();
        lastUsedMs.set(nowMs);
    }

    void release() {
        int value = inFlight.decrementAndGet();
        if (value < 0) {
            // 防御：重复 release 不应把计数带负，否则该端点会被永久优先选中
            inFlight.compareAndSet(value, 0);
        }
    }

    /**
     * 记录一次成功。
     * <p>
     * 冷却<b>只有</b>在这是半开探测（或冷却已到期）时才解除：否则一个在熔断之前发出、
     * 熔断之后才返回成功的在途请求，会把刚打开的熔断立刻关掉，
     * 让熔断在混合流量下形同虚设。
     *
     * @param latencyMicros 本次耗时（微秒）
     * @param nowMs 当前时间戳
     * @param probe 本次是否为半开探测
     */
    void recordSuccess(long latencyMicros, long nowMs, boolean probe) {
        consecutiveFailures.set(0);
        window.record(nowMs, false);
        updateEwma(latencyMicros);
        if (probe || cooldownUntilMs.get() == 0L || isHalfOpen(nowMs)) {
            cooldownUntilMs.set(0L);
            breakerTrips.set(0);
        }
        releaseProbe();
    }

    /**
     * 记录一次失败，并按 {@code options} 判断是否触发熔断。
     *
     * @return 本次失败是否使端点进入冷却
     */
    boolean recordFailure(long latencyMicros, long nowMs, BreakerOptions options) {
        int failures = consecutiveFailures.incrementAndGet();
        window.record(nowMs, true);
        updateEwma(latencyMicros);
        releaseProbe();
        if (options == null || !options.isEnabled()) {
            return false;
        }
        boolean trip = failures >= options.getConsecutiveFailureThreshold();
        if (!trip) {
            int total = window.total(nowMs);
            if (total >= options.getMinimumRequests()) {
                double rate = window.failures(nowMs) / (double) total;
                trip = rate >= options.getFailureRateThreshold();
            }
        }
        if (!trip) {
            return false;
        }
        int trips = breakerTrips.incrementAndGet();
        cooldownUntilMs.set(nowMs + options.cooldownMsFor(trips));
        return true;
    }

    /**
     * 记录一次"不归因于端点"的调用（例如业务 4xx）：只更新窗口总数与延迟，
     * 不增加失败计数，避免业务错误把健康节点熔断掉。
     */
    void recordNeutral(long latencyMicros, long nowMs) {
        window.record(nowMs, false);
        updateEwma(latencyMicros);
        releaseProbe();
    }

    /**
     * 手动解除熔断并清零失败统计。
     */
    public void recover() {
        consecutiveFailures.set(0);
        cooldownUntilMs.set(0);
        breakerTrips.set(0);
        window.reset();
        releaseProbe();
    }

    /**
     * 从另一个同基址端点继承健康状态，用于服务发现刷新时不丢失熔断/统计。
     *
     * @param other 旧端点
     */
    void inheritHealthFrom(Endpoint other) {
        if (other == null || !baseUrl.equals(other.baseUrl)) {
            return;
        }
        inFlight.set(other.inFlight.get());
        lastUsedMs.set(other.lastUsedMs.get());
        cooldownUntilMs.set(other.cooldownUntilMs.get());
        consecutiveFailures.set(other.consecutiveFailures.get());
        breakerTrips.set(other.breakerTrips.get());
        ewmaLatencyMicros.set(other.ewmaLatencyMicros.get());
        disabled.set(other.disabled.get());
        window.copyFrom(other.window);
    }

    AtomicInteger currentWeightRef() {
        return currentWeight;
    }

    /**
     * EWMA：新值权重 0.2。延迟用于 P2C 的代价比较，不需要很精确。
     */
    private void updateEwma(long latencyMicros) {
        if (latencyMicros < 0) {
            return;
        }
        while (true) {
            long previous = ewmaLatencyMicros.get();
            long next = previous == 0L
                    ? latencyMicros
                    : (long) (previous * 0.8d + latencyMicros * 0.2d);
            if (ewmaLatencyMicros.compareAndSet(previous, next)) {
                return;
            }
        }
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof Endpoint)) {
            return false;
        }
        return baseUrl.equals(((Endpoint) o).baseUrl);
    }

    @Override
    public int hashCode() {
        return baseUrl.hashCode();
    }

    @Override
    public String toString() {
        return baseUrl + "(w=" + weight + ",inflight=" + inFlight.get()
                + ",fail=" + consecutiveFailures.get()
                + (isCoolingDown(System.currentTimeMillis()) ? ",COOLING" : "")
                + (disabled.get() ? ",DISABLED" : "") + ")";
    }
}
