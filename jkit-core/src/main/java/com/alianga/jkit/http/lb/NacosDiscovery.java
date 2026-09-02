package com.alianga.jkit.http.lb;

import com.alianga.jkit.HttpUtils;
import com.alianga.jkit.StringUtils;
import com.alianga.jkit.convert.ConvertUtils;
import com.alianga.jkit.http.HttpBodies;
import com.alianga.jkit.http.HttpRequest;
import com.alianga.jkit.http.HttpResponse;
import com.alianga.jkit.json.JSON;
import com.alianga.jkit.log.Log;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Nacos 服务发现：通过 Nacos Open API（{@code /nacos/v1/ns/instance/list}）拉取健康实例，
 * 转换为带权端点。
 * <p>
 * HTTP 调用直接走本项目的 {@link HttpUtils}，因此自动获得引擎自适应
 * （JDK 8 走 {@code HttpURLConnection}，JDK 11+ 走 {@code java.net.http.HttpClient}）、
 * 连接复用、内容编码解压与统一超时语义，不再自己写一遍 {@code HttpURLConnection}。
 * 请求上标记了 {@link HttpRequest#bypassLoadBalance(boolean)}，避免拉取注册中心的请求
 * 被全局端点池改写到业务端点上去。
 * <p>
 * 覆盖企业环境的常见要求：
 * <ul>
 *   <li><b>命名空间与分组</b>：{@code namespaceId} / {@code groupName} / {@code clusters}；
 *       Nacos 的服务标识是 {@code group@@service}，不带 group 会查不到非 DEFAULT_GROUP 的服务。</li>
 *   <li><b>鉴权</b>：用户名密码（自动登录换 accessToken 并在过期前刷新）或直接给定 accessToken；
 *       开了鉴权的 Nacos 对匿名请求直接返回 403。</li>
 *   <li><b>多地址</b>：Nacos 集群可配多个 {@code host:port}，逐个尝试，避免注册中心本身成为单点。</li>
 *   <li><b>权重</b>：Nacos 的 {@code weight} 是浮点数，这里按比例放大成整型调度权重。</li>
 *   <li><b>元数据</b>：实例 metadata 与 clusterName 一并带出，供自定义策略做同机房优先等路由。</li>
 * </ul>
 * <p>
 * 用法：
 * <pre>{@code
 * ServiceDiscovery discovery = new NacosDiscovery.Builder("10.0.0.1:8848,10.0.0.2:8848")
 *         .namespaceId("prod")
 *         .groupName("ORDER_GROUP")
 *         .auth("nacos", "nacos")
 *         .build();
 *
 * EndpointPool pool = EndpointPool.builder()
 *         .serviceName("order-service")
 *         .discovery(discovery)
 *         .refreshIntervalMs(10_000)
 *         .build();
 * }</pre>
 *
 * @author 郑明亮
 */
public final class NacosDiscovery implements ServiceDiscovery {
    private static final Log log = Log.get(NacosDiscovery.class);
    /** accessToken 提前刷新的余量（毫秒）。 */
    private static final long TOKEN_REFRESH_MARGIN_MS = 10_000L;
    /** 诊断信息里保留的错误正文长度。 */
    private static final int ERROR_SNIPPET_LIMIT = 512;

    private final List<String> serverAddrs;
    private final String namespaceId;
    private final String groupName;
    private final String clusters;
    private final String endpointScheme;
    private final boolean healthyOnly;
    private final boolean nacosOverHttps;
    private final String username;
    private final String password;
    private final int connectTimeoutMs;
    private final int readTimeoutMs;
    private final AtomicInteger cursor = new AtomicInteger();
    private final AtomicReference<Token> token = new AtomicReference<Token>();
    private final String staticAccessToken;

    private NacosDiscovery(Builder builder) {
        this.serverAddrs = Collections.unmodifiableList(new ArrayList<String>(builder.serverAddrs));
        this.namespaceId = builder.namespaceId;
        this.groupName = builder.groupName;
        this.clusters = builder.clusters;
        this.endpointScheme = builder.endpointScheme;
        this.healthyOnly = builder.healthyOnly;
        this.nacosOverHttps = builder.nacosOverHttps;
        this.username = builder.username;
        this.password = builder.password;
        this.staticAccessToken = builder.accessToken;
        this.connectTimeoutMs = builder.connectTimeoutMs;
        this.readTimeoutMs = builder.readTimeoutMs;
    }

    @Override
    public List<Endpoint> resolve(String serviceName) throws IOException {
        if (StringUtils.isBlank(serviceName)) {
            throw new IOException("nacos discovery requires a service name");
        }
        String json = fetchInstanceList(serviceName.trim());
        List<Endpoint> endpoints = parseInstances(json, endpointScheme, healthyOnly);
        if (endpoints.isEmpty()) {
            throw new IOException("nacos returned no usable instance for service " + serviceName
                    + " (group=" + groupName + ", namespace=" + namespaceId + ")");
        }
        return endpoints;
    }

    /**
     * 逐个尝试 Nacos 地址，全部失败才抛异常。
     */
    private String fetchInstanceList(String serviceName) throws IOException {
        IOException last = null;
        int size = serverAddrs.size();
        // 从上次成功的地址开始，避免每次都先撞在故障节点上
        int start = Math.abs(cursor.get() % size);
        for (int i = 0; i < size; i++) {
            int index = (start + i) % size;
            String addr = serverAddrs.get(index);
            try {
                String result = get(buildInstanceListUrl(addr, serviceName));
                cursor.set(index);
                return result;
            } catch (IOException e) {
                last = e;
                log.debug("nacos server {} unavailable: {}", addr, e.getMessage());
            }
        }
        throw new IOException("all nacos servers failed " + serverAddrs
                + " for service " + serviceName, last);
    }

    private String buildInstanceListUrl(String serverAddr, String serviceName) throws IOException {
        StringBuilder sb = new StringBuilder();
        sb.append(nacosOverHttps ? "https://" : "http://").append(serverAddr);
        sb.append("/nacos/v1/ns/instance/list?serviceName=").append(HttpUtils.encodeValue(serviceName));
        if (StringUtils.isNotBlank(namespaceId)) {
            sb.append("&namespaceId=").append(HttpUtils.encodeValue(namespaceId));
        }
        if (StringUtils.isNotBlank(groupName)) {
            sb.append("&groupName=").append(HttpUtils.encodeValue(groupName));
        }
        if (StringUtils.isNotBlank(clusters)) {
            sb.append("&clusters=").append(HttpUtils.encodeValue(clusters));
        }
        sb.append("&healthyOnly=").append(healthyOnly);
        String accessToken = accessToken(serverAddr);
        if (StringUtils.isNotBlank(accessToken)) {
            sb.append("&accessToken=").append(HttpUtils.encodeValue(accessToken));
        }
        return sb.toString();
    }

    /**
     * 取 accessToken：优先用固定 token；配了用户名密码则登录换取并缓存，快过期时自动重登。
     */
    private String accessToken(String serverAddr) throws IOException {
        if (StringUtils.isNotBlank(staticAccessToken)) {
            return staticAccessToken;
        }
        if (StringUtils.isBlank(username)) {
            return null;
        }
        Token cached = token.get();
        long now = System.currentTimeMillis();
        if (cached != null && cached.expiresAtMs - TOKEN_REFRESH_MARGIN_MS > now) {
            return cached.value;
        }
        Token fresh = login(serverAddr);
        token.set(fresh);
        return fresh.value;
    }

    private Token login(String serverAddr) throws IOException {
        String url = (nacosOverHttps ? "https://" : "http://") + serverAddr + "/nacos/v1/auth/login";
        Map<String, Object> form = new LinkedHashMap<String, Object>();
        form.put("username", username);
        form.put("password", password);
        String json = send(HttpRequest.post(url)
                .contentType("application/x-www-form-urlencoded")
                .body(HttpBodies.formUrlEncoded(form)));
        Map<?, ?> parsed = JSON.parseObject(json);
        Object accessToken = parsed == null ? null : parsed.get("accessToken");
        if (accessToken == null) {
            throw new IOException("nacos login response has no accessToken: " + json);
        }
        long ttlSeconds = ConvertUtils.toLongValue(parsed.get("tokenTtl"), 18_000L);
        return new Token(String.valueOf(accessToken), System.currentTimeMillis() + ttlSeconds * 1000L);
    }

    private String get(String url) throws IOException {
        return send(HttpRequest.get(url).header("Accept", "application/json"));
    }

    /**
     * 统一发送：走本模块的引擎，显式设置超时并绕过负载均衡。
     * <p>
     * 非 2xx 时把状态码与正文片段一起抛出——注册中心的 403 / 404 都在正文里说明原因，
     * 丢掉就没法排障。
     */
    private String send(HttpRequest request) throws IOException {
        HttpResponse response = request
                .connectTimeoutMs(connectTimeoutMs)
                .readTimeoutMs(readTimeoutMs)
                // 单次调用的总时长兜底：注册中心卡住时不能把刷新线程一直挂在那里
                .totalTimeoutMs((long) connectTimeoutMs + readTimeoutMs)
                .bypassLoadBalance(true)
                .execute();
        try {
            String body = response.body() == null ? "" : response.body().string();
            if (!response.isSuccessful()) {
                throw new IOException("nacos API returned HTTP " + response.code()
                        + " for " + maskToken(response.requestUrl()) + ": " + snippet(body));
            }
            return body;
        } finally {
            response.close();
        }
    }

    private static String snippet(String body) {
        if (StringUtils.isBlank(body)) {
            return "<no body>";
        }
        String value = body.trim();
        return value.length() <= ERROR_SNIPPET_LIMIT
                ? value : value.substring(0, ERROR_SNIPPET_LIMIT) + "...";
    }

    /**
     * 日志里不要出现 accessToken。
     *
     * @param url 原始 URL
     * @return 脱敏后的 URL
     */
    static String maskToken(String url) {
        if (url == null) {
            return "<unknown>";
        }
        int idx = url.indexOf("accessToken=");
        if (idx < 0) {
            return url;
        }
        int end = url.indexOf('&', idx);
        return url.substring(0, idx + "accessToken=".length()) + "***"
                + (end < 0 ? "" : url.substring(end));
    }

    /**
     * 解析 Nacos 实例列表响应。
     * <p>
     * 用真正的 JSON 解析而不是"在文本里找第一个 key"：真实实例都带 {@code metadata} 子对象，
     * 里面可能也有 {@code ip} / {@code weight} 之类的键，字符串查找会读到嵌套对象里的值，
     * 得到完全错误的地址。
     *
     * @param json 响应正文
     * @param scheme 生成端点用的协议
     * @param healthyOnly 是否只保留 healthy 且 enabled 的实例
     * @return 端点列表，可能为空
     */
    static List<Endpoint> parseInstances(String json, String scheme, boolean healthyOnly) {
        List<Endpoint> result = new ArrayList<Endpoint>();
        if (StringUtils.isBlank(json)) {
            return result;
        }
        Map<?, ?> root;
        try {
            root = JSON.parseObject(json);
        } catch (RuntimeException e) {
            log.warn("cannot parse nacos response: {}", e.getMessage());
            return result;
        }
        if (root == null || !(root.get("hosts") instanceof List)) {
            return result;
        }
        List<?> hosts = (List<?>) root.get("hosts");
        double scale = weightScale(hosts);
        for (Object item : hosts) {
            if (!(item instanceof Map)) {
                continue;
            }
            Endpoint endpoint = toEndpoint((Map<?, ?>) item, scheme, healthyOnly, scale);
            if (endpoint != null) {
                result.add(endpoint);
            }
        }
        return result;
    }

    /**
     * Nacos 权重是浮点数（可低于 1），按最小正权重放大成整型，保持相对比例。
     */
    private static double weightScale(List<?> hosts) {
        double minPositive = Double.MAX_VALUE;
        for (Object item : hosts) {
            if (!(item instanceof Map)) {
                continue;
            }
            double weight = weightOf((Map<?, ?>) item);
            if (weight > 0.0d && weight < minPositive) {
                minPositive = weight;
            }
        }
        return minPositive == Double.MAX_VALUE || minPositive >= 1.0d ? 1.0d : 1.0d / minPositive;
    }

    private static double weightOf(Map<?, ?> host) {
        Object raw = host.get("weight");
        return raw == null ? 1.0d : ConvertUtils.toDoubleValue(raw);
    }

    private static Endpoint toEndpoint(Map<?, ?> host, String scheme, boolean healthyOnly, double scale) {
        Object rawIp = host.get("ip");
        if (rawIp == null || StringUtils.isBlank(String.valueOf(rawIp).trim())) {
            return null;
        }
        double weight = weightOf(host);
        if (weight <= 0.0d) {
            // 权重 0 在 Nacos 里就是"摘流"
            return null;
        }
        if (healthyOnly && !(flag(host, "healthy") && flag(host, "enabled"))) {
            return null;
        }
        int port = (int) ConvertUtils.toLongValue(host.get("port"), 80L);
        if (port < 1 || port > 65535) {
            return null;
        }
        String hostPart = String.valueOf(rawIp).trim();
        if (hostPart.indexOf(':') >= 0 && !hostPart.startsWith("[")) {
            // IPv6 必须加方括号，否则拼出来的是非法 URL
            hostPart = "[" + hostPart + "]";
        }
        int scaled = (int) Math.min(Endpoint.MAX_WEIGHT, Math.max(1L, Math.round(weight * scale)));
        return new Endpoint(scheme + "://" + hostPart + ":" + port, scaled, metadataOf(host));
    }

    /**
     * 布尔字段缺省为 {@code true}：Nacos 老版本响应里可能不带 enabled。
     */
    private static boolean flag(Map<?, ?> host, String key) {
        Object raw = host.get(key);
        return raw == null || ConvertUtils.toBoolean(raw);
    }

    private static Map<String, String> metadataOf(Map<?, ?> host) {
        Map<String, String> metadata = new LinkedHashMap<String, String>();
        Object cluster = host.get("clusterName");
        if (cluster != null) {
            metadata.put("clusterName", String.valueOf(cluster));
        }
        Object raw = host.get("metadata");
        if (raw instanceof Map) {
            for (Map.Entry<?, ?> entry : ((Map<?, ?>) raw).entrySet()) {
                if (entry.getKey() != null) {
                    metadata.put(String.valueOf(entry.getKey()),
                            entry.getValue() == null ? null : String.valueOf(entry.getValue()));
                }
            }
        }
        return metadata;
    }

    /**
     * @return 配置的 Nacos 地址列表（只读）
     */
    public List<String> getServerAddrs() {
        return serverAddrs;
    }

    /**
     * @return 分组名
     */
    public String getGroupName() {
        return groupName;
    }

    /**
     * @return 命名空间，public 时为 {@code null}
     */
    public String getNamespaceId() {
        return namespaceId;
    }

    /**
     * 供测试使用：暴露 URL 构造结果。
     *
     * @param serviceName 服务名
     * @return 实例列表 API 的完整 URL
     * @throws IOException 编码失败
     */
    String instanceListUrlForTest(String serviceName) throws IOException {
        return buildInstanceListUrl(serverAddrs.get(0), serviceName);
    }

    @Override
    public String toString() {
        return "NacosDiscovery{servers=" + serverAddrs + ", namespace=" + namespaceId
                + ", group=" + groupName + "}";
    }

    private static final class Token {
        private final String value;
        private final long expiresAtMs;

        private Token(String value, long expiresAtMs) {
            this.value = value;
            this.expiresAtMs = expiresAtMs;
        }
    }

    /**
     * NacosDiscovery 构造器。
     */
    public static final class Builder {
        private final List<String> serverAddrs = new ArrayList<String>();
        private String namespaceId;
        private String groupName = "DEFAULT_GROUP";
        private String clusters;
        private String endpointScheme = "http";
        private boolean healthyOnly = true;
        private boolean nacosOverHttps;
        private String username;
        private String password;
        private String accessToken;
        private int connectTimeoutMs = 3000;
        private int readTimeoutMs = 5000;

        /**
         * @param serverAddr Nacos 地址，{@code host:port}；多个用逗号分隔
         */
        public Builder(String serverAddr) {
            if (StringUtils.isBlank(serverAddr)) {
                throw new IllegalArgumentException("nacos serverAddr must not be empty");
            }
            addAddresses(serverAddr.split(","));
        }

        /**
         * @param addresses Nacos 地址列表
         */
        public Builder(List<String> addresses) {
            if (addresses == null || addresses.isEmpty()) {
                throw new IllegalArgumentException("nacos serverAddr must not be empty");
            }
            addAddresses(addresses.toArray(new String[0]));
        }

        private void addAddresses(String[] parts) {
            for (String part : parts) {
                if (part == null) {
                    continue;
                }
                String value = part.trim();
                if (!value.isEmpty()) {
                    serverAddrs.add(stripScheme(value));
                }
            }
            if (serverAddrs.isEmpty()) {
                throw new IllegalArgumentException("nacos serverAddr must not be empty");
            }
        }

        private static String stripScheme(String value) {
            int idx = value.indexOf("://");
            return idx < 0 ? value : value.substring(idx + 3);
        }

        /**
         * @param namespaceId 命名空间 ID，{@code null}、空或 {@code public} 表示默认命名空间
         * @return this
         */
        public Builder namespaceId(String namespaceId) {
            this.namespaceId = StringUtils.isBlank(namespaceId)
                    || "public".equals(namespaceId.trim()) ? null : namespaceId.trim();
            return this;
        }

        /**
         * @param groupName 分组名，默认 {@code DEFAULT_GROUP}
         * @return this
         */
        public Builder groupName(String groupName) {
            this.groupName = StringUtils.isBlank(groupName)
                    ? "DEFAULT_GROUP" : groupName.trim();
            return this;
        }

        /**
         * @param clusters 集群名，多个用逗号分隔；用于只取本机房实例
         * @return this
         */
        public Builder clusters(String clusters) {
            this.clusters = StringUtils.isBlank(clusters)
                    ? null : clusters.trim();
            return this;
        }

        /**
         * @param scheme 生成端点使用的协议，{@code http}（默认）或 {@code https}
         * @return this
         */
        public Builder endpointScheme(String scheme) {
            String value = StringUtils.isBlank(scheme) ? "http" : scheme.trim().toLowerCase(Locale.ROOT);
            if (!"http".equals(value) && !"https".equals(value)) {
                throw new IllegalArgumentException("endpoint scheme must be http or https: " + scheme);
            }
            this.endpointScheme = value;
            return this;
        }

        /**
         * @param healthyOnly 是否只取健康实例，默认 {@code true}
         * @return this
         */
        public Builder healthyOnly(boolean healthyOnly) {
            this.healthyOnly = healthyOnly;
            return this;
        }

        /**
         * @param https 访问 Nacos 自身是否走 https，默认 {@code false}
         * @return this
         */
        public Builder nacosOverHttps(boolean https) {
            this.nacosOverHttps = https;
            return this;
        }

        /**
         * 用户名密码鉴权：自动调用 {@code /nacos/v1/auth/login} 换取 accessToken 并缓存刷新。
         *
         * @param username 用户名
         * @param password 密码
         * @return this
         */
        public Builder auth(String username, String password) {
            this.username = username;
            this.password = password;
            return this;
        }

        /**
         * 直接指定 accessToken（自行管理有效期）。
         *
         * @param accessToken token
         * @return this
         */
        public Builder accessToken(String accessToken) {
            this.accessToken = accessToken;
            return this;
        }

        /**
         * @param connectTimeoutMs 连接超时
         * @param readTimeoutMs 读取超时
         * @return this
         */
        public Builder timeouts(int connectTimeoutMs, int readTimeoutMs) {
            this.connectTimeoutMs = Math.max(1, connectTimeoutMs);
            this.readTimeoutMs = Math.max(1, readTimeoutMs);
            return this;
        }

        /**
         * @return Nacos 服务发现
         */
        public NacosDiscovery build() {
            return new NacosDiscovery(this);
        }
    }
}
