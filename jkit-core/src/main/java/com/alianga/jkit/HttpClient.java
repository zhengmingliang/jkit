package com.alianga.jkit;

import com.alianga.jkit.http.HttpConfig;
import com.alianga.jkit.http.HttpCookieJar;
import com.alianga.jkit.http.HttpEngine;
import com.alianga.jkit.http.HttpInterceptor;
import com.alianga.jkit.http.HttpRequest;
import com.alianga.jkit.http.HttpResponse;
import com.alianga.jkit.http.HttpResponseBody;
import com.alianga.jkit.http.HttpStatusException;
import com.alianga.jkit.http.RetryPolicy;
import com.alianga.jkit.http.lb.EndpointPool;
import com.alianga.jkit.json.JSON;

import java.io.IOException;
import java.net.Proxy;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 可实例化的 HTTP 客户端。与 {@link HttpUtils} 的全局静态门面不同，每个 {@code HttpClient}
 * 持有独立的配置，多个实例之间的超时、代理、SSL、CookieJar、拦截器等互不污染，且不同线程
 * 并发执行时各自隔离，不会互相改写全局状态。
 *
 * <p>典型用法：
 * <pre>{@code
 * HttpClient a = HttpClient.builder()
 *         .connectTimeout(2000).readTimeout(5000)
 *         .httpProxy("proxy-a", 8080)
 *         .build();
 * HttpClient b = HttpClient.builder()
 *         .connectTimeout(1000)
 *         .build();
 * // a 与 b 的配置互相独立，并发执行也不串味
 * HttpResponse r = a.get("https://example.com");
 * }</pre>
 *
 * 全局默认仍可通过 {@link #shared()} 取到，它与 {@link HttpUtils} 的静态方法共享同一份
 * 进程级配置，二者行为完全等价。
 *
 * @author 郑明亮
 */
public final class HttpClient {
    private final boolean shared;
    private HttpConfig config;
    private Proxy proxy;
    private HttpCookieJar cookieJar;
    private HttpEngine engine;
    private boolean ignoreSsl;
    private boolean fakeIp;
    private String defaultMediaType;

    private HttpClient(boolean shared) {
        this.shared = shared;
    }

    // ---- 仅包内可见的取值入口：发送链路通过这些方法读取"当前活跃客户端"的配置 ----
    // 共享实例（shared）的取值实时转发到 HttpUtils 的全局静态，保证全局默认永远是最新值。

    HttpConfig getConfig() {
        return shared ? HttpConfig.shared() : config;
    }

    Proxy getProxy() {
        return shared ? HttpUtils.rawProxy() : proxy;
    }

    HttpCookieJar getCookieJar() {
        return shared ? HttpUtils.resolveCookieJar() : cookieJar;
    }

    boolean isIgnoreSsl() {
        return shared ? HttpUtils.rawIgnoreSsl() : ignoreSsl;
    }

    HttpEngine getEngine() {
        return shared ? HttpUtils.rawEngine() : engine;
    }

    boolean isFakeIp() {
        return shared ? HttpUtils.fakeIp : fakeIp;
    }

    String getDefaultMediaType() {
        return shared ? HttpUtils.defaultMediaType : defaultMediaType;
    }

    List<HttpInterceptor> getInterceptors() {
        return getConfig().getInterceptors();
    }

    /**
     * 取全局默认的共享客户端。它与 {@link HttpUtils} 的静态 API 共享同一份进程级配置，
     * 适合无需隔离配置的常规调用。
     *
     * @return 共享客户端单例
     */
    public static HttpClient shared() {
        return SharedHolder.INSTANCE;
    }

    private static final class SharedHolder {
        static final HttpClient INSTANCE = new HttpClient(true);
    }

    /**
     * 创建构建器，初始值取自当前全局配置，便于在全局默认基础上做局部覆盖。
     *
     * @return 构建器
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * 进入本客户端的请求作用域并发送，结束后恢复进入前的旧作用域，确保整条发送链路
     * （超时/代理/SSL/CookieJar/引擎/拦截器）都读到本实例的配置，且不会污染其它实例或全局状态。
     *
     * <p>保存/恢复而不是无条件清空：拦截器（如 token 刷新）里嵌套调用其它客户端实例时，
     * 内层结束后外层实例的作用域必须还在，否则外层剩余链路会静默回退到全局配置。
     *
     * @param request 请求
     * @return 响应
     * @throws IOException 网络错误
     */
    public HttpResponse execute(HttpRequest request) throws IOException {
        if (request == null) {
            throw new IllegalArgumentException("request must not be null");
        }
        HttpClient previous = HttpUtils.active();
        HttpUtils.scope(this);
        try {
            if (request.getHeaders().isEmpty()) {
                request.headers(getDefaultHeaders());
            }
            if (isFakeIp()) {
                HttpUtils.setFakeIpHeader(request.getHeaders());
            }
            HttpUtils.applyDefaults(request);
            return HttpUtils.send(request);
        } finally {
            HttpUtils.scope(previous);
        }
    }

    private Map<String, String> getDefaultHeaders() {
        Map<String, String> headers = new LinkedHashMap<String, String>();
        headers.put("User-Agent", RandomUtils.getRandomUserAgent());
        return headers;
    }

    /**
     * 简单 GET，返回完整响应。
     *
     * @param url 请求地址
     * @return 响应
     * @throws IOException 网络错误
     */
    public HttpResponse get(String url) throws IOException {
        return execute(HttpRequest.get(url));
    }

    /**
     * GET，只传请求头。
     *
     * @param url     请求地址
     * @param headers 请求头；非空时替换默认头
     * @return 响应
     * @throws IOException 网络错误
     */
    public HttpResponse get(String url, Map<String, String> headers) throws IOException {
        HttpRequest request = HttpRequest.get(url);
        if (headers != null && !headers.isEmpty()) {
            request.headers(headers);
        }
        return execute(request);
    }

    /**
     * GET，拼接查询参数并指定请求头。
     *
     * @param url     请求地址
     * @param params  查询参数
     * @param headers 请求头；非空时替换默认头
     * @return 响应
     * @throws IOException 网络错误
     */
    public HttpResponse get(String url, Map<String, Object> params, Map<String, String> headers)
            throws IOException {
        HttpRequest request = HttpRequest.get(HttpUtils.appendQuery(url, params));
        if (headers != null && !headers.isEmpty()) {
            request.headers(headers);
        }
        return execute(request);
    }

    /**
     * POST 字符串 body，使用本客户端默认 Content-Type。
     *
     * @param url  请求地址
     * @param body 请求体
     * @return 响应
     * @throws IOException 网络错误
     */
    public HttpResponse post(String url, String body) throws IOException {
        return post(url, body, getDefaultMediaType());
    }

    /**
     * POST 字符串 body，指定 Content-Type。
     *
     * @param url         请求地址
     * @param body        请求体
     * @param contentType Content-Type，{@code null} 时不设置
     * @return 响应
     * @throws IOException 网络错误
     */
    public HttpResponse post(String url, String body, String contentType) throws IOException {
        HttpRequest request = HttpRequest.post(url);
        if (contentType != null) {
            request.contentType(contentType);
        }
        if (body != null) {
            request.body(body.getBytes(StandardCharsets.UTF_8));
        }
        return execute(request);
    }

    /**
     * POST 对象（序列化为 JSON），Content-Type 固定为 {@code application/json; charset=utf-8}。
     *
     * @param url    请求地址
     * @param object 待序列化对象
     * @return 响应
     * @throws IOException 网络错误
     */
    public HttpResponse postJson(String url, Object object) throws IOException {
        String json = JSON.toJsonString(object);
        return post(url, json, "application/json; charset=utf-8");
    }

    /**
     * PUT 字符串 body，指定 Content-Type。
     *
     * @param url         请求地址
     * @param body        请求体
     * @param contentType Content-Type，{@code null} 时不设置
     * @return 响应
     * @throws IOException 网络错误
     */
    public HttpResponse put(String url, String body, String contentType) throws IOException {
        HttpRequest request = HttpRequest.put(url);
        if (contentType != null) {
            request.contentType(contentType);
        }
        if (body != null) {
            request.body(body.getBytes(StandardCharsets.UTF_8));
        }
        return execute(request);
    }

    /**
     * DELETE 请求。
     *
     * @param url 请求地址
     * @return 响应
     * @throws IOException 网络错误
     */
    public HttpResponse delete(String url) throws IOException {
        return execute(HttpRequest.delete(url));
    }

    /**
     * 取本实例的可变配置（超时、HTTP/2、重试、重定向、编码、缓冲上限等）。
     *
     * @return 本实例配置
     */
    public HttpConfig config() {
        return getConfig();
    }

    private String bodyString(HttpResponse response) throws IOException {
        String text;
        try {
            HttpResponseBody body = response.body();
            text = body == null ? "" : body.string();
        } finally {
            response.close();
        }
        if (getConfig().isThrowOnHttpError() && !response.isSuccessful()) {
            throw new HttpStatusException(response, text);
        }
        return text;
    }

    /**
     * {@link HttpClient} 构建器。初始值取自当前全局配置，调用方只需覆盖关心的项。
     */
    public static final class Builder {
        private final HttpClient client = new HttpClient(false);

        private Builder() {
            client.config = HttpConfig.shared().copy();
            client.proxy = HttpUtils.getProxy();
            client.cookieJar = HttpUtils.getCookieJar();
            client.ignoreSsl = HttpUtils.isIgnoreSsl();
            client.fakeIp = HttpUtils.fakeIp;
            client.defaultMediaType = HttpUtils.defaultMediaType;
        }

        public Builder connectTimeout(int millis) {
            client.config.setConnectTimeoutMs(millis);
            return this;
        }

        public Builder readTimeout(int millis) {
            client.config.setReadTimeoutMs(millis);
            return this;
        }

        public Builder proxy(Proxy proxy) {
            client.proxy = proxy;
            if (proxy == null) {
                client.config.setProxyAuth(null, null);
            }
            return this;
        }

        public Builder httpProxy(String host, int port) {
            return proxy(new Proxy(Proxy.Type.HTTP, new java.net.InetSocketAddress(host, port)));
        }

        public Builder proxyAuth(String username, String password) {
            client.config.setProxyAuth(username, password);
            return this;
        }

        public Builder ignoreSsl(boolean ignore) {
            client.ignoreSsl = ignore;
            return this;
        }

        public Builder sslContext(javax.net.ssl.SSLContext sslContext,
                                  javax.net.ssl.HostnameVerifier hostnameVerifier) {
            client.config.setSslContext(sslContext).setHostnameVerifier(hostnameVerifier);
            return this;
        }

        public Builder cookieJar(HttpCookieJar cookieJar) {
            client.cookieJar = cookieJar;
            return this;
        }

        public Builder engine(HttpEngine engine) {
            client.engine = engine;
            return this;
        }

        public Builder fakeIp(boolean fakeIp) {
            client.fakeIp = fakeIp;
            return this;
        }

        public Builder defaultMediaType(String defaultMediaType) {
            client.defaultMediaType = defaultMediaType;
            return this;
        }

        public Builder retryPolicy(RetryPolicy retryPolicy) {
            client.config.setRetryPolicy(retryPolicy);
            return this;
        }

        public Builder maxRedirects(int maxRedirects) {
            client.config.setMaxRedirects(maxRedirects);
            return this;
        }

        public Builder http2(boolean enabled) {
            client.config.setHttp2(enabled);
            return this;
        }

        public Builder throwOnHttpError(boolean throwOnHttpError) {
            client.config.setThrowOnHttpError(throwOnHttpError);
            return this;
        }

        public Builder endpointPool(EndpointPool endpointPool) {
            client.config.setEndpointPool(endpointPool);
            return this;
        }

        public Builder addInterceptor(HttpInterceptor interceptor) {
            client.config.addInterceptor(interceptor);
            return this;
        }

        public Builder interceptors(List<HttpInterceptor> interceptors) {
            client.config.getInterceptors().clear();
            client.config.getInterceptors().addAll(interceptors);
            return this;
        }

        public HttpClient build() {
            return client;
        }
    }
}
