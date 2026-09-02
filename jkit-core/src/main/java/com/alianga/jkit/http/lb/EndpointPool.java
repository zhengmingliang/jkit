package com.alianga.jkit.http.lb;

import com.alianga.jkit.log.Log;

import java.io.Closeable;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 上游端点负载均衡池：调度、健康统计、熔断与半开探测、服务发现刷新。
 * <p>
 * 用法：
 * <pre>{@code
 * // 静态端点
 * EndpointPool pool = EndpointPool.builder()
 *         .serviceName("orders")                       // 只对 http://orders/... 生效
 *         .add("http://10.0.0.7:8080", 3)
 *         .add("http://10.0.0.8:8080", 1)
 *         .strategy(LoadBalanceStrategies.p2cLeastLoaded())
 *         .build();
 *
 * // 服务发现驱动，10s 拉一次
 * EndpointPool pool = EndpointPool.builder()
 *         .serviceName("orders")
 *         .discovery(new NacosDiscovery.Builder("10.0.0.1:8848").build())
 *         .refreshIntervalMs(10_000)
 *         .build();
 *
 * HttpUtils.config().setEndpointPool(pool);
 * }</pre>
 * <p>
 * 取用端点必须走 {@link #acquire(Set, String)} 返回的 {@link Lease}，并在 finally 里
 * {@code close()}：在途计数由 Lease 维护，漏掉释放会让该端点被永久判定为"最忙"。
 * <p>
 * 线程安全。{@link #close()} 会停掉发现刷新线程。
 *
 * @author 郑明亮
 */
public final class EndpointPool implements Closeable {
    private static final Log log = Log.get(EndpointPool.class);

    private final String serviceName;
    private final LoadBalanceStrategy strategy;
    private final BreakerOptions breaker;
    private final ServiceDiscovery discovery;
    private final long refreshIntervalMs;
    private final boolean preserveHostHeader;
    private final List<EndpointPoolListener> listeners = new CopyOnWriteArrayList<EndpointPoolListener>();

    /** 端点快照：整体替换，读路径无锁。 */
    private volatile List<Endpoint> endpoints;
    private volatile Map<String, Endpoint> index;
    private final AtomicLong lastRefreshMs = new AtomicLong();
    private final AtomicBoolean closed = new AtomicBoolean();
    private final Object refreshLock = new Object();
    private ScheduledExecutorService refresher;
    private ScheduledFuture<?> refreshTask;

    private EndpointPool(Builder builder) {
        this.serviceName = builder.serviceName;
        this.strategy = builder.strategy == null
                ? LoadBalanceStrategies.p2cLeastLoaded() : builder.strategy;
        this.breaker = builder.breaker == null ? BreakerOptions.defaults() : builder.breaker;
        this.discovery = builder.discovery;
        this.refreshIntervalMs = builder.refreshIntervalMs;
        this.preserveHostHeader = builder.preserveHostHeader;
        this.listeners.addAll(builder.listeners);

        List<Endpoint> initial = new ArrayList<Endpoint>(builder.endpoints);
        if (initial.isEmpty() && discovery == null) {
            throw new IllegalArgumentException(
                    "endpoint pool requires at least one endpoint or a ServiceDiscovery");
        }
        if (discovery != null && discovery.requiresServiceName() && serviceName == null) {
            // 提前报错，而不是等第一次刷新时抛出 "requires a service name"
            throw new IllegalArgumentException(discovery
                    + " needs a serviceName: call EndpointPool.builder().serviceName(...)");
        }
        applySnapshot(dedupe(initial), false);
        if (discovery != null) {
            try {
                refreshNow();
            } catch (IOException e) {
                // 启动时发现失败不致命：如果有静态兜底端点就继续用，否则由首次 acquire 报错
                notifyDiscoveryFailure(e);
                if (endpoints.isEmpty()) {
                    log.warn("service discovery failed on startup for service {}: {}",
                            serviceName, e.getMessage());
                }
            }
            startRefresher();
        }
    }

    /**
     * @return 构造器
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * 单端点便捷池（仍保留熔断与统计）。
     *
     * @param baseUrl 端点基址
     * @return 端点池
     */
    public static EndpointPool of(String baseUrl) {
        return builder().add(baseUrl).build();
    }

    /**
     * 逻辑服务名。非 {@code null} 时，只有 URL 主机名等于该值的请求才会被负载均衡改写，
     * 例如 {@code http://orders/api/v1/x}；写了具体主机/端口的请求会原样直连。
     * 这样同一个进程里访问多个上游不会互相干扰。
     *
     * @return 服务名，{@code null} 表示对所有请求生效
     */
    public String getServiceName() {
        return serviceName;
    }

    /**
     * @return 调度策略
     */
    public LoadBalanceStrategy getStrategy() {
        return strategy;
    }

    /**
     * @return 熔断参数
     */
    public BreakerOptions getBreakerOptions() {
        return breaker;
    }

    /**
     * 是否在改写 origin 后保留原始 {@code Host} 头。
     * <p>
     * 服务发现给出的是 IP，直接改写 URL 会让上游收到 {@code Host: 10.0.0.7:8080}，
     * 基于域名路由的网关 / 虚拟主机会 404。开启后会把原始 authority 写回 Host 头。
     * <p>
     * 注意 JDK 默认禁止应用设置 Host 头，需要加启动参数才会真正生效：
     * JDK 8 用 {@code -Dsun.net.http.allowRestrictedHeaders=true}，
     * JDK 11+ 用 {@code -Djdk.httpclient.allowRestrictedHeaders=host}。
     *
     * @return 是否保留原始 Host
     */
    public boolean isPreserveHostHeader() {
        return preserveHostHeader;
    }

    /**
     * @return 当前端点快照（不可变）
     */
    public List<Endpoint> endpoints() {
        return endpoints;
    }

    /**
     * 按 origin 查找端点。
     *
     * @param origin 端点基址或任意同源 URL
     * @return 端点，未命中为 {@code null}
     */
    public Endpoint byOrigin(String origin) {
        if (origin == null) {
            return null;
        }
        try {
            return index.get(Endpoint.normalize(origin));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /**
     * 注册事件监听。
     *
     * @param listener 监听器
     * @return this
     */
    public EndpointPool addListener(EndpointPoolListener listener) {
        if (listener != null) {
            listeners.add(listener);
        }
        return this;
    }

    /**
     * 移除事件监听。
     *
     * @param listener 监听器
     * @return 是否移除成功
     */
    public boolean removeListener(EndpointPoolListener listener) {
        return listeners.remove(listener);
    }

    /**
     * 取用一个端点。返回的 {@link Lease} 已经把在途计数加一，
     * <b>必须</b>在 finally 中 {@code close()}。
     *
     * @param exclude 本次调用已经试过的端点基址（故障转移时排除），可为 {@code null}
     * @param hashKey 会话亲和键（一致性哈希用），可为 {@code null}
     * @return 租约
     * @throws IOException 池内没有任何端点
     */
    public Lease acquire(Set<String> exclude, String hashKey) throws IOException {
        List<Endpoint> snapshot = endpoints;
        if (snapshot.isEmpty()) {
            throw new IOException("no endpoint available for service "
                    + (serviceName == null ? "<any>" : serviceName));
        }
        long now = System.currentTimeMillis();
        List<Endpoint> candidates = new ArrayList<Endpoint>(snapshot.size());
        for (Endpoint endpoint : snapshot) {
            if (exclude != null && exclude.contains(endpoint.getBaseUrl())) {
                continue;
            }
            if (endpoint.isAvailable(now)) {
                candidates.add(endpoint);
            }
        }
        if (!candidates.isEmpty()) {
            Endpoint selected = strategy.select(candidates, hashKey);
            if (selected == null) {
                selected = candidates.get(0);
            }
            return newLease(selected, false, now);
        }
        // 没有健康端点：只在"半开"端点上放行一个探测请求。
        // 半开 = 熔断过、冷却已到期、还没有一次成功来证明它恢复了。
        Endpoint probe = pickProbe(snapshot, exclude, now);
        if (probe != null && probe.tryAcquireProbe()) {
            notifyAllDown(probe);
            return newLease(probe, true, now);
        }
        // 熔断的本义就是快速失败：仍在冷却中、或探测名额已被别人占用时，
        // 不要再把流量灌向已知故障的节点，直接报错让调用方尽快感知。
        throw new IOException("all " + snapshot.size() + " endpoints are unavailable for service "
                + (serviceName == null ? "<any>" : serviceName)
                + ", circuit is open: " + snapshot);
    }

    /**
     * 选出可用于半开探测的端点：冷却已到期、未被摘流、探测令牌空闲；
     * 有多个时取冷却最早结束的那个。
     */
    private Endpoint pickProbe(List<Endpoint> snapshot, Set<String> exclude, long now) {
        Endpoint best = null;
        for (Endpoint endpoint : snapshot) {
            if (exclude != null && exclude.contains(endpoint.getBaseUrl())) {
                continue;
            }
            if (endpoint.isDisabled() || endpoint.isProbing() || !endpoint.isHalfOpen(now)) {
                continue;
            }
            if (best == null || endpoint.getCooldownUntilMs() < best.getCooldownUntilMs()) {
                best = endpoint;
            }
        }
        return best;
    }

    private Lease newLease(Endpoint endpoint, boolean probe, long now) {
        endpoint.markSelected(now);
        return new Lease(this, endpoint, probe);
    }

    /**
     * 立即执行一次服务发现刷新（增量合并，保留已有端点的健康状态）。
     *
     * @return 刷新后的端点数
     * @throws IOException 发现失败（此时保留上一次快照）
     */
    public int refreshNow() throws IOException {
        if (discovery == null) {
            return endpoints.size();
        }
        List<Endpoint> resolved;
        try {
            resolved = discovery.resolve(serviceName);
        } catch (IOException e) {
            notifyDiscoveryFailure(e);
            throw e;
        } catch (RuntimeException e) {
            notifyDiscoveryFailure(e);
            throw new IOException("service discovery failed for " + serviceName, e);
        }
        if (resolved == null || resolved.isEmpty()) {
            // fail-static：发现返回空一律视为异常，宁可继续用旧端点，也不要把池清空
            IOException error = new IOException("service discovery returned no endpoints for "
                    + serviceName);
            notifyDiscoveryFailure(error);
            throw error;
        }
        applySnapshot(dedupe(resolved), true);
        lastRefreshMs.set(System.currentTimeMillis());
        return endpoints.size();
    }

    /**
     * @return 最近一次成功刷新的时间戳，未刷新过为 0
     */
    public long getLastRefreshMs() {
        return lastRefreshMs.get();
    }

    /**
     * 用新端点列表替换快照：同基址的端点<b>继承</b>原有健康状态（熔断、在途、统计），
     * 这样扩缩容不会把熔断信息清零，也不会让刚摘掉的坏节点立刻恢复接流。
     */
    private void applySnapshot(List<Endpoint> incoming, boolean notify) {
        synchronized (refreshLock) {
            Map<String, Endpoint> previous = index == null
                    ? Collections.<String, Endpoint>emptyMap() : index;
            List<Endpoint> merged = new ArrayList<Endpoint>(incoming.size());
            Map<String, Endpoint> newIndex = new LinkedHashMap<String, Endpoint>();
            int added = 0;
            for (Endpoint endpoint : incoming) {
                Endpoint existing = previous.get(endpoint.getBaseUrl());
                Endpoint effective = endpoint;
                if (existing != null) {
                    if (existing.getWeight() == endpoint.getWeight()) {
                        // 权重没变，直接复用旧对象，连 EWMA / 滑窗都不用搬
                        effective = existing;
                    } else {
                        effective.inheritHealthFrom(existing);
                    }
                } else {
                    added++;
                }
                merged.add(effective);
                newIndex.put(effective.getBaseUrl(), effective);
            }
            int removed = 0;
            for (String key : previous.keySet()) {
                if (!newIndex.containsKey(key)) {
                    removed++;
                }
            }
            this.endpoints = Collections.unmodifiableList(merged);
            this.index = Collections.unmodifiableMap(newIndex);
            if (notify && (added > 0 || removed > 0)) {
                notifyEndpointsChanged(added, removed, merged.size());
            }
        }
    }

    /**
     * 同基址去重：保留第一次出现的定义。重复端点会把健康状态劈成两份，
     * 熔断与统计都会失效。
     */
    private static List<Endpoint> dedupe(List<Endpoint> input) {
        Map<String, Endpoint> unique = new LinkedHashMap<String, Endpoint>();
        for (Endpoint endpoint : input) {
            if (endpoint == null) {
                continue;
            }
            if (!unique.containsKey(endpoint.getBaseUrl())) {
                unique.put(endpoint.getBaseUrl(), endpoint);
            }
        }
        return new ArrayList<Endpoint>(unique.values());
    }

    private void startRefresher() {
        if (refreshIntervalMs <= 0L) {
            return;
        }
        synchronized (refreshLock) {
            if (refresher != null) {
                return;
            }
            refresher = Executors.newSingleThreadScheduledExecutor(r -> {
                Thread thread = new Thread(r, "jkit-lb-discovery-"
                        + (serviceName == null ? "default" : serviceName));
                thread.setDaemon(true);
                return thread;
            });
            refreshTask = refresher.scheduleWithFixedDelay(() -> {
                try {
                    refreshNow();
                } catch (Exception e) {
                    // 已经通知过监听器，这里只留一行日志，绝不让异常打断调度
                    log.debug("service discovery refresh failed for {}: {}", serviceName, e.getMessage());
                }
            }, refreshIntervalMs, refreshIntervalMs, TimeUnit.MILLISECONDS);
        }
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        synchronized (refreshLock) {
            if (refreshTask != null) {
                refreshTask.cancel(true);
                refreshTask = null;
            }
            if (refresher != null) {
                refresher.shutdownNow();
                refresher = null;
            }
        }
    }

    /**
     * @return 是否已关闭
     */
    public boolean isClosed() {
        return closed.get();
    }

    // ---------- Lease 回调 ----------

    void onLeaseSuccess(Endpoint endpoint, boolean probe, long latencyMicros) {
        boolean wasTripped = endpoint.getCooldownUntilMs() != 0L;
        endpoint.recordSuccess(latencyMicros, System.currentTimeMillis(), probe);
        if (wasTripped && endpoint.getCooldownUntilMs() == 0L) {
            notifyBreakerClose(endpoint);
        }
    }

    void onLeaseNeutral(Endpoint endpoint, long latencyMicros) {
        endpoint.recordNeutral(latencyMicros, System.currentTimeMillis());
    }

    void onLeaseFailure(Endpoint endpoint, long latencyMicros, String cause) {
        boolean tripped = endpoint.recordFailure(latencyMicros, System.currentTimeMillis(), breaker);
        if (tripped) {
            notifyBreakerOpen(endpoint, endpoint.getCooldownUntilMs() - System.currentTimeMillis(), cause);
        }
    }

    /**
     * 通知一次故障转移。由发送链路在换端点重试时调用。
     *
     * @param failed 失败的端点
     * @param next 接替的端点
     * @param cause 失败原因描述
     */
    public void notifyFailover(Endpoint failed, Endpoint next, String cause) {
        for (EndpointPoolListener listener : listeners) {
            try {
                listener.onFailover(this, failed, next, cause);
            } catch (RuntimeException e) {
                log.debug("endpoint pool listener failed: {}", e.getMessage());
            }
        }
    }

    private void notifyBreakerOpen(Endpoint endpoint, long cooldownMs, String cause) {
        log.warn("endpoint {} circuit opened for {}ms, cause: {}",
                endpoint.getBaseUrl(), cooldownMs, cause);
        for (EndpointPoolListener listener : listeners) {
            try {
                listener.onBreakerOpen(this, endpoint, cooldownMs);
            } catch (RuntimeException e) {
                log.debug("endpoint pool listener failed: {}", e.getMessage());
            }
        }
    }

    private void notifyBreakerClose(Endpoint endpoint) {
        for (EndpointPoolListener listener : listeners) {
            try {
                listener.onBreakerClose(this, endpoint);
            } catch (RuntimeException e) {
                log.debug("endpoint pool listener failed: {}", e.getMessage());
            }
        }
    }

    private void notifyEndpointsChanged(int added, int removed, int total) {
        for (EndpointPoolListener listener : listeners) {
            try {
                listener.onEndpointsChanged(this, added, removed, total);
            } catch (RuntimeException e) {
                log.debug("endpoint pool listener failed: {}", e.getMessage());
            }
        }
    }

    private void notifyDiscoveryFailure(Exception error) {
        for (EndpointPoolListener listener : listeners) {
            try {
                listener.onDiscoveryFailure(this, error);
            } catch (RuntimeException e) {
                log.debug("endpoint pool listener failed: {}", e.getMessage());
            }
        }
    }

    private void notifyAllDown(Endpoint probe) {
        for (EndpointPoolListener listener : listeners) {
            try {
                listener.onAllEndpointsDown(this, probe);
            } catch (RuntimeException e) {
                log.debug("endpoint pool listener failed: {}", e.getMessage());
            }
        }
    }

    @Override
    public String toString() {
        return "EndpointPool{service=" + serviceName + ", strategy=" + strategy.name()
                + ", endpoints=" + endpoints + "}";
    }

    /**
     * 端点租约：持有一次调用对某个端点的占用。
     * <p>
     * 在途计数在 {@link EndpointPool#acquire(Set, String)} 时加一，在 {@link #close()} 时减一，
     * 调用方无法漏掉配对——这是把 in-flight 统计做对的关键。
     */
    public static final class Lease implements Closeable {
        private final EndpointPool pool;
        private final Endpoint endpoint;
        private final boolean probe;
        private final long startNanos = System.nanoTime();
        private final AtomicBoolean settled = new AtomicBoolean();
        private final AtomicBoolean released = new AtomicBoolean();

        private Lease(EndpointPool pool, Endpoint endpoint, boolean probe) {
            this.pool = pool;
            this.endpoint = endpoint;
            this.probe = probe;
        }

        /**
         * @return 本次占用的端点
         */
        public Endpoint endpoint() {
            return endpoint;
        }

        /**
         * @return 本次是否是熔断后的半开探测请求
         */
        public boolean isProbe() {
            return probe;
        }

        /**
         * 记录调用成功（会解除熔断、更新延迟）。
         */
        public void success() {
            if (settled.compareAndSet(false, true)) {
                pool.onLeaseSuccess(endpoint, probe, elapsedMicros());
            }
        }

        /**
         * 记录一次"与端点健康无关"的结果，例如业务 4xx。
         * 只更新延迟与窗口总数，不计失败——否则一批 404 会把健康节点熔断掉。
         */
        public void neutral() {
            if (settled.compareAndSet(false, true)) {
                pool.onLeaseNeutral(endpoint, elapsedMicros());
            }
        }

        /**
         * 记录端点故障（可能触发熔断）。
         *
         * @param cause 失败原因描述，用于日志与事件
         */
        public void failure(String cause) {
            if (settled.compareAndSet(false, true)) {
                pool.onLeaseFailure(endpoint, elapsedMicros(), cause);
            }
        }

        /**
         * 释放在途占用。未显式记录结果时按"中性"处理，避免探测令牌泄漏。
         */
        @Override
        public void close() {
            if (settled.compareAndSet(false, true)) {
                pool.onLeaseNeutral(endpoint, elapsedMicros());
            }
            if (released.compareAndSet(false, true)) {
                endpoint.release();
            }
        }

        private long elapsedMicros() {
            return (System.nanoTime() - startNanos) / 1000L;
        }
    }

    /**
     * 端点池构造器。
     */
    public static final class Builder {
        private final List<Endpoint> endpoints = new ArrayList<Endpoint>();
        private final Set<EndpointPoolListener> listeners = new LinkedHashSet<EndpointPoolListener>();
        private String serviceName;
        private LoadBalanceStrategy strategy;
        private BreakerOptions breaker;
        private ServiceDiscovery discovery;
        private long refreshIntervalMs = 10_000L;
        private boolean preserveHostHeader = true;

        /**
         * 逻辑服务名。设置后只有 {@code http://<serviceName>/...} 这类"不写具体主机"的
         * 请求会被负载均衡改写；写了真实主机或端口的请求原样直连。
         *
         * @param serviceName 服务名，{@code null} 表示对所有请求生效
         * @return this
         */
        public Builder serviceName(String serviceName) {
            this.serviceName = serviceName == null || serviceName.trim().isEmpty()
                    ? null : serviceName.trim().toLowerCase(java.util.Locale.ROOT);
            return this;
        }

        /**
         * 添加端点。
         *
         * @param baseUrl 端点基址
         * @param weight 权重
         * @return this
         */
        public Builder add(String baseUrl, int weight) {
            endpoints.add(new Endpoint(baseUrl, weight));
            return this;
        }

        /**
         * 添加权重 1 的端点。
         *
         * @param baseUrl 端点基址
         * @return this
         */
        public Builder add(String baseUrl) {
            return add(baseUrl, 1);
        }

        /**
         * 添加端点对象。
         *
         * @param endpoint 端点
         * @return this
         */
        public Builder add(Endpoint endpoint) {
            if (endpoint != null) {
                endpoints.add(endpoint);
            }
            return this;
        }

        /**
         * 批量添加端点。
         *
         * @param values 端点集合
         * @return this
         */
        public Builder addAll(List<Endpoint> values) {
            if (values != null) {
                for (Endpoint endpoint : values) {
                    add(endpoint);
                }
            }
            return this;
        }

        /**
         * @param strategy 调度策略，默认 {@link LoadBalanceStrategies#p2cLeastLoaded()}
         * @return this
         */
        public Builder strategy(LoadBalanceStrategy strategy) {
            this.strategy = strategy;
            return this;
        }

        /**
         * @param breaker 熔断参数，默认 {@link BreakerOptions#defaults()}
         * @return this
         */
        public Builder breaker(BreakerOptions breaker) {
            this.breaker = breaker;
            return this;
        }

        /**
         * 设置服务发现。构造时会立即拉取一次，随后按 {@link #refreshIntervalMs(long)} 周期刷新。
         *
         * @param discovery 服务发现
         * @return this
         */
        public Builder discovery(ServiceDiscovery discovery) {
            this.discovery = discovery;
            return this;
        }

        /**
         * @param refreshIntervalMs 发现刷新周期，{@code <=0} 表示只在构造时拉取一次
         * @return this
         */
        public Builder refreshIntervalMs(long refreshIntervalMs) {
            this.refreshIntervalMs = refreshIntervalMs;
            return this;
        }

        /**
         * @param preserveHostHeader 改写 origin 后是否保留原始 Host 头，默认 {@code true}
         * @return this
         */
        public Builder preserveHostHeader(boolean preserveHostHeader) {
            this.preserveHostHeader = preserveHostHeader;
            return this;
        }

        /**
         * @param listener 事件监听
         * @return this
         */
        public Builder listener(EndpointPoolListener listener) {
            if (listener != null) {
                listeners.add(listener);
            }
            return this;
        }

        /**
         * @return 端点池
         */
        public EndpointPool build() {
            return new EndpointPool(this);
        }
    }
}
