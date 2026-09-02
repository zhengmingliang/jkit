package com.alianga.jkit.http.lb;

import java.io.IOException;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 服务发现：把逻辑服务名解析为一组带权重的 {@link Endpoint}。
 * <p>
 * 返回带权端点而不是裸 URL 字符串，是为了让注册中心的权重（Nacos 的 {@code weight}、
 * Consul 的 tag 权重）能真正参与调度——只返回 URL 的话，加权策略就没有输入了。
 * <p>
 * 实现约定：
 * <ul>
 *   <li>解析不到任何可用实例时抛 {@link IOException}，不要返回空列表。
 *       {@link EndpointPool} 收到异常会保留上一次的端点快照（fail-static），
 *       返回空列表则意味着"上游真的全没了"，语义完全不同。</li>
 *   <li>方法可能被后台刷新线程周期调用，实现需线程安全。</li>
 * </ul>
 *
 * @author 郑明亮
 */
public interface ServiceDiscovery {
    /**
     * 解析服务名。
     *
     * @param serviceName 逻辑服务名，可为 {@code null}（实现自带固定目标时）
     * @return 端点列表，非空
     * @throws IOException 发现失败或无可用实例
     */
    List<Endpoint> resolve(String serviceName) throws IOException;

    /**
     * 本实现是否必须依赖服务名才能解析。
     * <p>
     * 注册中心（Nacos/Consul）与 DNS 都需要服务名；{@link Static} 自带固定端点，不需要。
     * {@link EndpointPool} 在构造时据此提前报错，而不是等到第一次刷新才抛出
     * "requires a service name" 这种让人一头雾水的异常。
     *
     * @return 是否必须提供服务名，默认 {@code true}
     */
    default boolean requiresServiceName() {
        return true;
    }

    /**
     * 静态端点列表：不做发现，直接返回给定端点。适合手工配置多机房地址，
     * 也可作为发现失败时的兜底。
     */
    final class Static implements ServiceDiscovery {
        private final List<Endpoint> endpoints;

        /**
         * @param baseUrls 端点基址，权重均为 1
         */
        public Static(String... baseUrls) {
            if (baseUrls == null || baseUrls.length == 0) {
                throw new IllegalArgumentException("at least one endpoint is required");
            }
            List<Endpoint> list = new ArrayList<Endpoint>(baseUrls.length);
            for (String baseUrl : baseUrls) {
                list.add(new Endpoint(baseUrl));
            }
            this.endpoints = Collections.unmodifiableList(list);
        }

        /**
         * @param endpoints 端点列表
         */
        public Static(List<Endpoint> endpoints) {
            if (endpoints == null || endpoints.isEmpty()) {
                throw new IllegalArgumentException("at least one endpoint is required");
            }
            this.endpoints = Collections.unmodifiableList(new ArrayList<Endpoint>(endpoints));
        }

        @Override
        public List<Endpoint> resolve(String serviceName) {
            return endpoints;
        }

        @Override
        public boolean requiresServiceName() {
            return false;
        }

        @Override
        public String toString() {
            return "Static" + endpoints;
        }
    }

    /**
     * DNS 发现：把服务名解析为全部 A/AAAA 记录，按给定 scheme 与端口生成端点。
     * <p>
     * 适合 Kubernetes 的 headless service：每次调用都会重新解析，配合
     * {@link EndpointPool.Builder#refreshIntervalMs(long)} 就能感知扩缩容。
     * <p>
     * 注意 JVM 有 DNS 缓存（{@code networkaddress.cache.ttl}），刷新周期短于该 TTL 时
     * 拿到的还是缓存结果。
     */
    final class Dns implements ServiceDiscovery {
        private final String scheme;
        private final int port;

        /**
         * @param scheme {@code http} 或 {@code https}
         * @param port 端口，{@code <=0} 表示使用 scheme 默认端口
         */
        public Dns(String scheme, int port) {
            String value = scheme == null ? "http" : scheme.trim().toLowerCase(Locale.ROOT);
            if (!"http".equals(value) && !"https".equals(value)) {
                throw new IllegalArgumentException("scheme must be http or https: " + scheme);
            }
            this.scheme = value;
            this.port = port;
        }

        /**
         * @param scheme {@code http} 或 {@code https}，端口用协议默认值
         */
        public Dns(String scheme) {
            this(scheme, -1);
        }

        @Override
        public List<Endpoint> resolve(String serviceName) throws IOException {
            String host = hostOf(serviceName);
            if (host.isEmpty()) {
                throw new IOException("DNS discovery requires a host name");
            }
            try {
                InetAddress[] addresses = InetAddress.getAllByName(host);
                // LinkedHashSet 去重：多条记录指向同一地址时只保留一个端点
                LinkedHashSet<String> origins = new LinkedHashSet<String>();
                for (InetAddress address : addresses) {
                    origins.add(originOf(address));
                }
                if (origins.isEmpty()) {
                    throw new IOException("DNS discovery returned no address for " + host);
                }
                List<Endpoint> result = new ArrayList<Endpoint>(origins.size());
                for (String origin : origins) {
                    result.add(new Endpoint(origin));
                }
                return result;
            } catch (UnknownHostException e) {
                throw new IOException("DNS discovery failed for " + host, e);
            }
        }

        /**
         * IPv6 地址必须加方括号，否则 {@code http://fe80::1:8080} 是个非法 URL。
         */
        private String originOf(InetAddress address) {
            String ip = address.getHostAddress();
            int zone = ip.indexOf('%');
            if (zone > 0) {
                ip = ip.substring(0, zone);
            }
            boolean ipv6 = ip.indexOf(':') >= 0;
            String hostPart = ipv6 ? "[" + ip + "]" : ip;
            if (port > 0) {
                return scheme + "://" + hostPart + ":" + port;
            }
            return scheme + "://" + hostPart;
        }

        /**
         * 从服务名或 URL 中取出主机名，兼容 {@code http://a/b}、{@code a:8080}、{@code a}。
         */
        static String hostOf(String serviceName) {
            if (serviceName == null) {
                return "";
            }
            String value = serviceName.trim();
            int scheme = value.indexOf("://");
            if (scheme >= 0) {
                value = value.substring(scheme + 3);
            }
            int slash = value.indexOf('/');
            if (slash >= 0) {
                value = value.substring(0, slash);
            }
            int at = value.lastIndexOf('@');
            if (at >= 0) {
                value = value.substring(at + 1);
            }
            if (value.startsWith("[")) {
                int close = value.indexOf(']');
                return close > 0 ? value.substring(0, close + 1) : value;
            }
            int colon = value.indexOf(':');
            if (colon >= 0 && colon == value.lastIndexOf(':')) {
                value = value.substring(0, colon);
            }
            return value;
        }

        @Override
        public String toString() {
            return "Dns{scheme=" + scheme + ", port=" + port + "}";
        }
    }

    /**
     * 组合发现：按顺序尝试，第一个成功的生效。用于"注册中心优先、静态地址兜底"。
     */
    final class Fallback implements ServiceDiscovery {
        private final List<ServiceDiscovery> delegates;

        /**
         * @param delegates 按优先级排列的发现实现
         */
        public Fallback(ServiceDiscovery... delegates) {
            if (delegates == null || delegates.length == 0) {
                throw new IllegalArgumentException("at least one delegate is required");
            }
            List<ServiceDiscovery> list = new ArrayList<ServiceDiscovery>(delegates.length);
            for (ServiceDiscovery delegate : delegates) {
                if (delegate != null) {
                    list.add(delegate);
                }
            }
            if (list.isEmpty()) {
                throw new IllegalArgumentException("at least one delegate is required");
            }
            this.delegates = Collections.unmodifiableList(list);
        }

        @Override
        public List<Endpoint> resolve(String serviceName) throws IOException {
            IOException last = null;
            Map<String, String> failures = new LinkedHashMap<String, String>();
            for (ServiceDiscovery delegate : delegates) {
                try {
                    List<Endpoint> result = delegate.resolve(serviceName);
                    if (result != null && !result.isEmpty()) {
                        return result;
                    }
                    failures.put(delegate.toString(), "returned no endpoint");
                } catch (IOException e) {
                    last = e;
                    failures.put(delegate.toString(), String.valueOf(e.getMessage()));
                }
            }
            throw new IOException("all discovery delegates failed for " + serviceName
                    + ": " + failures, last);
        }

        @Override
        public boolean requiresServiceName() {
            for (ServiceDiscovery delegate : delegates) {
                if (!delegate.requiresServiceName()) {
                    return false;
                }
            }
            return true;
        }

        @Override
        public String toString() {
            return "Fallback" + delegates;
        }
    }
}
