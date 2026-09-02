package com.alianga.jkit.http;

import java.io.File;
import java.io.UnsupportedEncodingException;
import java.net.Proxy;
import java.net.URLEncoder;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * HTTP 请求模型（方法、URL、头、正文、超时、代理等）。
 *
 * @author 郑明亮
 */
public class HttpRequest {
    /**
     * GET
     */
    public static final String GET = "GET";
    /**
     * POST
     */
    public static final String POST = "POST";
    /**
     * HEAD
     */
    public static final String HEAD = "HEAD";
    /**
     * PUT
     */
    public static final String PUT = "PUT";
    /**
     * DELETE
     */
    public static final String DELETE = "DELETE";
    /**
     * PATCH
     */
    public static final String PATCH = "PATCH";

    private String method = GET;
    private String url;
    private final Map<String, String> headers = new LinkedHashMap<String, String>();
    private byte[] body;
    private File bodyFile;
    private String contentType;
    private int connectTimeoutMs = 60_000;
    private int readTimeoutMs = 60_000;
    private boolean connectTimeoutSet;
    private boolean readTimeoutSet;
    private boolean followRedirects = true;
    private boolean streamResponse;
    private boolean ignoreSsl = true;
    private boolean ignoreSslSet;
    private boolean preferHttp2 = true;
    private long maxBufferBytes;
    private long totalTimeoutMs;
    private com.alianga.jkit.http.lb.EndpointPool endpointPool;
    private String loadBalanceKey;
    private boolean bypassLoadBalance;
    private Proxy proxy;
    private javax.net.ssl.SSLContext sslContext;
    private javax.net.ssl.HostnameVerifier hostnameVerifier;
    private final Map<String, Object> queryParams = new LinkedHashMap<String, Object>();
    private final Map<String, String> cookies = new LinkedHashMap<String, String>();
    private Object tag;

    /**
     * 空请求，需再设置 URL。
     */
    public HttpRequest() {
    }

    /**
     * @param method HTTP 方法
     * @param url 请求地址
     */
    public HttpRequest(String method, String url) {
        this.method = method;
        this.url = url;
    }

    /**
     * @param url 请求地址
     * @return GET 请求
     */
    public static HttpRequest get(String url) {
        return new HttpRequest(GET, url);
    }

    /**
     * @param url 请求地址
     * @return POST 请求
     */
    public static HttpRequest post(String url) {
        return new HttpRequest(POST, url);
    }

    /**
     * @param url 请求地址
     * @return HEAD 请求
     */
    public static HttpRequest head(String url) {
        return new HttpRequest(HEAD, url);
    }

    /**
     * @param url 请求地址
     * @return PUT 请求
     */
    public static HttpRequest put(String url) {
        return new HttpRequest(PUT, url);
    }

    /**
     * @param url 请求地址
     * @return DELETE 请求
     */
    public static HttpRequest delete(String url) {
        return new HttpRequest(DELETE, url);
    }

    /**
     * @param url 请求地址
     * @return PATCH 请求
     */
    public static HttpRequest patch(String url) {
        return new HttpRequest(PATCH, url);
    }

    /**
     * @return HTTP 方法
     */
    public String getMethod() {
        return method;
    }

    /**
     * @param method HTTP 方法
     * @return this
     */
    public HttpRequest method(String method) {
        this.method = method;
        return this;
    }

    /**
     * @return 请求地址
     */
    public String getUrl() {
        return url;
    }

    /**
     * @param url 请求地址
     * @return this
     */
    public HttpRequest url(String url) {
        this.url = url;
        return this;
    }

    /**
     * @return 请求头（可修改的有序 Map）
     */
    public Map<String, String> getHeaders() {
        return headers;
    }

    /**
     * 覆盖全部请求头。
     *
     * @param headers 请求头，为 {@code null} 时清空
     * @return this
     */
    public HttpRequest headers(Map<String, String> headers) {
        this.headers.clear();
        if (headers != null) {
            this.headers.putAll(headers);
        }
        return this;
    }

    /**
     * 添加或覆盖单个请求头。
     *
     * @param name 头名称
     * @param value 头值
     * @return this
     */
    public HttpRequest header(String name, String value) {
        if (name != null && value != null) {
            headers.put(name, value);
        }
        return this;
    }

    /**
     * 按名称查找请求头（大小写不敏感）。
     *
     * @param name 头名称
     * @return 头值，不存在时为 {@code null}
     */
    public String getHeader(String name) {
        if (name == null) {
            return null;
        }
        for (Map.Entry<String, String> entry : headers.entrySet()) {
            if (name.equalsIgnoreCase(entry.getKey())) {
                return entry.getValue();
            }
        }
        return null;
    }

    /**
     * 按名称移除请求头（大小写不敏感）。
     *
     * @param name 头名称
     * @return this
     */
    public HttpRequest removeHeader(String name) {
        if (name != null) {
            java.util.Iterator<Map.Entry<String, String>> it = headers.entrySet().iterator();
            while (it.hasNext()) {
                if (name.equalsIgnoreCase(it.next().getKey())) {
                    it.remove();
                }
            }
        }
        return this;
    }

    /**
     * 本次调用的总时长上限（毫秒），覆盖重试、重定向与故障转移。
     * {@code 0} 表示沿用 {@link HttpConfig#getTotalTimeoutMs()}。
     *
     * @return 总时长上限
     */
    public long getTotalTimeoutMs() {
        return totalTimeoutMs;
    }

    /**
     * 设置本次调用的总时长上限（毫秒），优先于全局配置。
     *
     * @param totalTimeoutMs 总时长上限，{@code <=0} 表示沿用全局配置
     * @return this
     */
    public HttpRequest totalTimeoutMs(long totalTimeoutMs) {
        this.totalTimeoutMs = Math.max(0L, totalTimeoutMs);
        return this;
    }

    /**
     * @return 只读请求头视图
     */
    public Map<String, String> headersView() {
        return Collections.unmodifiableMap(headers);
    }

    /**
     * @return 请求体，无正文时为 {@code null}
     */
    public byte[] getBody() {
        return body;
    }

    /**
     * @param body 请求体
     * @return this
     */
    public HttpRequest body(byte[] body) {
        this.body = body;
        return this;
    }

    /**
     * @return 以文件作为请求体（流式写出，不把整个文件读进内存）
     */
    public File getBodyFile() {
        return bodyFile;
    }

    /**
     * 以文件作为请求体，适合大文件 PUT/POST。
     *
     * @param bodyFile 本地文件
     * @return this
     */
    public HttpRequest bodyFile(File bodyFile) {
        this.bodyFile = bodyFile;
        return this;
    }

    /**
     * @return Content-Type
     */
    public String getContentType() {
        return contentType;
    }

    /**
     * @param contentType Content-Type
     * @return this
     */
    public HttpRequest contentType(String contentType) {
        this.contentType = contentType;
        return this;
    }

    /**
     * @return 连接超时（毫秒）
     */
    public int getConnectTimeoutMs() {
        return connectTimeoutMs;
    }

    /**
     * @param connectTimeoutMs 连接超时（毫秒）
     * @return this
     */
    public HttpRequest connectTimeoutMs(int connectTimeoutMs) {
        this.connectTimeoutMs = connectTimeoutMs;
        this.connectTimeoutSet = true;
        return this;
    }

    /**
     * 是否显式设置过连接超时。未显式设置时，{@code HttpUtils} 会套用
     * {@link HttpConfig#getConnectTimeoutMs()}；显式设置的值不会被全局配置覆盖。
     *
     * @return 是否显式设置过
     */
    public boolean isConnectTimeoutSet() {
        return connectTimeoutSet;
    }

    /**
     * @return 读取超时（毫秒）
     */
    public int getReadTimeoutMs() {
        return readTimeoutMs;
    }

    /**
     * @param readTimeoutMs 读取超时（毫秒）
     * @return this
     */
    public HttpRequest readTimeoutMs(int readTimeoutMs) {
        this.readTimeoutMs = readTimeoutMs;
        this.readTimeoutSet = true;
        return this;
    }

    /**
     * 是否显式设置过读取超时。语义同 {@link #isConnectTimeoutSet()}。
     *
     * @return 是否显式设置过
     */
    public boolean isReadTimeoutSet() {
        return readTimeoutSet;
    }

    /**
     * @return 是否跟随重定向
     */
    public boolean isFollowRedirects() {
        return followRedirects;
    }

    /**
     * @param followRedirects 是否跟随重定向
     * @return this
     */
    public HttpRequest followRedirects(boolean followRedirects) {
        this.followRedirects = followRedirects;
        return this;
    }

    /**
     * @return 是否以流式方式返回响应体（不预先缓冲）
     */
    public boolean isStreamResponse() {
        return streamResponse;
    }

    /**
     * @param streamResponse 是否流式返回响应体
     * @return this
     */
    public HttpRequest streamResponse(boolean streamResponse) {
        this.streamResponse = streamResponse;
        return this;
    }

    /**
     * @return 是否忽略 HTTPS 证书与主机名校验
     */
    public boolean isIgnoreSsl() {
        return ignoreSsl;
    }

    /**
     * 设置是否忽略 HTTPS 证书与主机名校验。
     * <p>
     * <b>一旦调用本方法，该值就是显式的，不会再被 {@code HttpUtils} 的全局开关覆盖。</b>
     * 生产环境请传 {@code false}。
     *
     * @param ignoreSsl 是否忽略 HTTPS 证书与主机名校验
     * @return this
     */
    public HttpRequest ignoreSsl(boolean ignoreSsl) {
        this.ignoreSsl = ignoreSsl;
        this.ignoreSslSet = true;
        return this;
    }

    /**
     * 是否显式设置过 {@code ignoreSsl}。未显式设置时才会套用全局开关。
     *
     * @return 是否显式设置过
     */
    public boolean isIgnoreSslSet() {
        return ignoreSslSet;
    }

    /**
     * @return 代理，未设置时为 {@code null}
     */
    public Proxy getProxy() {
        return proxy;
    }

    /**
     * @param proxy 代理
     * @return this
     */
    public HttpRequest proxy(Proxy proxy) {
        this.proxy = proxy;
        return this;
    }

    /**
     * @return 单请求 SSL 上下文
     */
    public javax.net.ssl.SSLContext getSslContext() {
        return sslContext;
    }

    /**
     * @param sslContext 单请求 SSL 上下文
     * @return this
     */
    public HttpRequest sslContext(javax.net.ssl.SSLContext sslContext) {
        this.sslContext = sslContext;
        return this;
    }

    /**
     * @return 单请求主机名校验器
     */
    public javax.net.ssl.HostnameVerifier getHostnameVerifier() {
        return hostnameVerifier;
    }

    /**
     * @param hostnameVerifier 单请求主机名校验器
     * @return this
     */
    public HttpRequest hostnameVerifier(javax.net.ssl.HostnameVerifier hostnameVerifier) {
        this.hostnameVerifier = hostnameVerifier;
        return this;
    }

    /**
     * @return 是否优先 HTTP/2
     */
    public boolean isPreferHttp2() {
        return preferHttp2;
    }

    /**
     * @param preferHttp2 是否优先 HTTP/2
     * @return this
     */
    public HttpRequest preferHttp2(boolean preferHttp2) {
        this.preferHttp2 = preferHttp2;
        return this;
    }

    /**
     * @return 缓冲上限，{@code <=0} 表示用全局配置
     */
    public long getMaxBufferBytes() {
        return maxBufferBytes;
    }

    /**
     * @param maxBufferBytes 缓冲上限
     * @return this
     */
    public HttpRequest maxBufferBytes(long maxBufferBytes) {
        this.maxBufferBytes = maxBufferBytes;
        return this;
    }

    /**
     * Range 请求：从 {@code start} 字节到文件末尾。
     *
     * @param start 起始偏移
     * @return this
     */
    public HttpRequest rangeFrom(long start) {
        if (start > 0) {
            header("Range", "bytes=" + start + "-");
        }
        return this;
    }

    /**
     * Range 请求：取 {@code [start, end]} 闭区间字节（含两端）。
     *
     * @param start 起始偏移
     * @param end 结束偏移（含）
     * @return this
     */
    public HttpRequest range(long start, long end) {
        if (start >= 0 && end >= start) {
            header("Range", "bytes=" + start + "-" + end);
        }
        return this;
    }

    /**
     * Range 请求：取最后 {@code lastBytes} 字节。
     *
     * @param lastBytes 末尾字节数
     * @return this
     */
    public HttpRequest rangeSuffix(long lastBytes) {
        if (lastBytes > 0) {
            header("Range", "bytes=-" + lastBytes);
        }
        return this;
    }

    /**
     * 添加结构化查询参数，发送时自动 URL 编码并拼到 URL 上（{@code null} 值跳过，
     * 集合/数组值展开为多个同名参数）。
     *
     * @param name 参数名
     * @param value 参数值
     * @return this
     */
    public HttpRequest query(String name, Object value) {
        if (name != null && value != null) {
            queryParams.put(name, value);
        }
        return this;
    }

    /**
     * 批量添加查询参数。
     *
     * @param params 参数表，可为 {@code null}
     * @return this
     */
    public HttpRequest queries(Map<String, Object> params) {
        if (params != null) {
            queryParams.putAll(params);
        }
        return this;
    }

    /**
     * @return 是否设置了查询参数
     */
    public boolean hasQueries() {
        return !queryParams.isEmpty();
    }

    /**
     * @return 只读查询参数视图
     */
    public Map<String, Object> getQueries() {
        return Collections.unmodifiableMap(queryParams);
    }

    /**
     * 清空查询参数（由发送流程在拼接 URL 后调用）。
     */
    public void clearQueries() {
        queryParams.clear();
    }

    /**
     * 返回拼接了查询参数的完整 URL，{@code null} 值参数被跳过。
     *
     * @return 最终请求 URL
     */
    public String effectiveUrl() {
        String base = url == null ? "" : url;
        if (queryParams.isEmpty()) {
            return base;
        }
        StringBuilder effective = new StringBuilder(base.length() + 32);
        effective.append(base);
        boolean baseHasQuery = base.indexOf('?') >= 0;
        boolean hasParam = false;
        for (Map.Entry<String, Object> entry : queryParams.entrySet()) {
            if (entry.getKey() == null || entry.getValue() == null) {
                continue;
            }
            if (entry.getValue() instanceof Collection) {
                for (Object item : (Collection<?>) entry.getValue()) {
                    hasParam |= appendQueryParam(effective, baseHasQuery, hasParam, entry.getKey(), item);
                }
            } else if (entry.getValue() instanceof Object[]) {
                for (Object item : (Object[]) entry.getValue()) {
                    hasParam |= appendQueryParam(effective, baseHasQuery, hasParam, entry.getKey(), item);
                }
            } else {
                hasParam |= appendQueryParam(effective, baseHasQuery, hasParam, entry.getKey(), entry.getValue());
            }
        }
        return hasParam ? effective.toString() : base;
    }

    /**
     * 向 {@code effective} 追加一个 {@code name=value} 参数（自动处理分隔符与 URL 编码）。
     *
     * @return 是否实际追加了参数
     */
    private static boolean appendQueryParam(StringBuilder effective, boolean baseHasQuery,
                                            boolean hasParam, String name, Object value) {
        if (value == null) {
            return false;
        }
        effective.append(hasParam || baseHasQuery ? '&' : '?');
        effective.append(encodeQuery(name)).append('=').append(encodeQuery(String.valueOf(value)));
        return true;
    }

    private static String encodeQuery(String value) {
        try {
            return URLEncoder.encode(value, "UTF-8");
        } catch (UnsupportedEncodingException e) {
            return value;
        }
    }

    /**
     * 设置单请求 Cookie（发送时拼成 {@code Cookie} 头，优先于全局 CookieJar）。
     *
     * @param name Cookie 名
     * @param value Cookie 值
     * @return this
     */
    public HttpRequest cookie(String name, String value) {
        if (name != null && value != null) {
            cookies.put(name, value);
        }
        return this;
    }

    /**
     * 批量设置单请求 Cookie。
     *
     * @param cookies Cookie 表，可为 {@code null}
     * @return this
     */
    public HttpRequest cookies(Map<String, String> cookies) {
        if (cookies != null) {
            this.cookies.putAll(cookies);
        }
        return this;
    }

    /**
     * @return 是否设置了单请求 Cookie
     */
    public boolean hasCookies() {
        return !cookies.isEmpty();
    }

    /**
     * @return 只读单请求 Cookie 视图
     */
    public Map<String, String> getCookies() {
        return Collections.unmodifiableMap(cookies);
    }

    /**
     * @param userAgent User-Agent
     * @return this
     */
    public HttpRequest userAgent(String userAgent) {
        return header("User-Agent", userAgent);
    }

    /**
     * @param accept Accept
     * @return this
     */
    public HttpRequest accept(String accept) {
        return header("Accept", accept);
    }

    /**
     * @param language Accept-Language
     * @return this
     */
    public HttpRequest acceptLanguage(String language) {
        return header("Accept-Language", language);
    }

    /**
     * @param referer Referer
     * @return this
     */
    public HttpRequest referer(String referer) {
        return header("Referer", referer);
    }

    /**
     * @param authorization Authorization 头原值（如 {@code Basic xxx}）
     * @return this
     */
    public HttpRequest authorization(String authorization) {
        return header("Authorization", authorization);
    }

    /**
     * Bearer Token 认证快捷方式。
     *
     * @param token token
     * @return this
     */
    public HttpRequest bearerToken(String token) {
        return header("Authorization", "Bearer " + token);
    }

    /**
     * 条件请求：实体未变化时返回 304（配合 ETag 缓存）。
     *
     * @param etag 上次响应的 ETag
     * @return this
     */
    public HttpRequest ifNoneMatch(String etag) {
        return header("If-None-Match", etag);
    }

    /**
     * 条件请求：资源在指定时间后未修改时返回 304。
     *
     * @param lastModified HTTP 日期格式字符串（如 {@code GMT} 时间）
     * @return this
     */
    public HttpRequest ifModifiedSince(String lastModified) {
        return header("If-Modified-Since", lastModified);
    }

    /**
     * 设置请求标签，用于日志与链路追踪（不影响传输）。
     *
     * @param tag 标签
     * @return this
     */
    public HttpRequest tag(Object tag) {
        this.tag = tag;
        return this;
    }

    /**
     * @return 请求标签，未设置时为 {@code null}
     */
    public Object getTag() {
        return tag;
    }

    /**
     * 以 UTF-8 字符串设置请求体。
     *
     * @param content 字符串正文，{@code null} 忽略
     * @return this
     */
    public HttpRequest body(String content) {
        return body(content, StandardCharsets.UTF_8);
    }

    /**
     * 以指定字符集字符串设置请求体。
     *
     * @param content 字符串正文，{@code null} 忽略
     * @param charset 字符集，{@code null} 使用 UTF-8
     * @return this
     */
    public HttpRequest body(String content, Charset charset) {
        if (content != null) {
            this.body = content.getBytes(charset == null ? StandardCharsets.UTF_8 : charset);
        }
        return this;
    }

    /**
     * 启用/禁用 {@code Expect: 100-continue}（JDK 8 引擎在流式写大请求体时
     * 可让服务器先校验请求头，不支持的引擎会忽略该头）。
     *
     * @param expectContinue 是否启用
     * @return this
     */
    public HttpRequest expectContinue(boolean expectContinue) {
        if (expectContinue) {
            return header("Expect", "100-continue");
        }
        for (Map.Entry<String, String> entry : headers.entrySet()) {
            if ("Expect".equalsIgnoreCase(entry.getKey())) {
                headers.remove(entry.getKey());
                break;
            }
        }
        return this;
    }

    /**
     * @return 是否为带正文的方法（POST/PUT/PATCH）
     */
    public boolean isEntityMethod() {
        return POST.equalsIgnoreCase(method)
                || PUT.equalsIgnoreCase(method)
                || PATCH.equalsIgnoreCase(method);
    }

    /**
     * 复制当前请求（头、正文、查询参数、Cookie、超时与代理等一并拷贝）。
     * 用于在不改动原对象的前提下派生 SSE / 重试请求。
     *
     * @return 独立副本
     */
    public HttpRequest copy() {
        HttpRequest copy = new HttpRequest(method, url);
        copy.headers(headers);
        if (body != null) {
            copy.body(body.clone());
        }
        copy.bodyFile(bodyFile);
        copy.contentType(contentType);
        copy.connectTimeoutMs(connectTimeoutMs);
        copy.readTimeoutMs(readTimeoutMs);
        copy.followRedirects(followRedirects);
        copy.streamResponse(streamResponse);
        copy.ignoreSsl(ignoreSsl);
        copy.preferHttp2(preferHttp2);
        copy.maxBufferBytes(maxBufferBytes);
        copy.totalTimeoutMs(totalTimeoutMs);
        copy.endpointPool(endpointPool);
        copy.loadBalanceKey(loadBalanceKey);
        copy.bypassLoadBalance(bypassLoadBalance);
        copy.proxy(proxy);
        copy.sslContext(sslContext);
        copy.hostnameVerifier(hostnameVerifier);
        copy.queries(queryParams);
        copy.cookies(cookies);
        copy.tag(tag);
        // 复制后重置为源请求的显式标记：setter 会把标记置为 true，这里还原真实状态，
        // 否则派生请求（重试 / SSE 重连）会被误判为"用户已显式设置"而不再套用全局配置。
        copy.connectTimeoutSet = connectTimeoutSet;
        copy.readTimeoutSet = readTimeoutSet;
        copy.ignoreSslSet = ignoreSslSet;
        return copy;
    }

    /**
     * 本请求使用的负载均衡端点池，优先于 {@link HttpConfig#getEndpointPool()}。
     *
     * @return 端点池，未设置为 {@code null}
     */
    public com.alianga.jkit.http.lb.EndpointPool getEndpointPool() {
        return endpointPool;
    }

    /**
     * 为本请求指定负载均衡端点池。设置后本请求一定走负载均衡，
     * 不受池的 {@code serviceName} 匹配限制。
     *
     * @param endpointPool 端点池，{@code null} 表示回退到全局配置
     * @return this
     */
    public HttpRequest endpointPool(com.alianga.jkit.http.lb.EndpointPool endpointPool) {
        this.endpointPool = endpointPool;
        return this;
    }

    /**
     * 本请求是否绕过负载均衡（不做 origin 改写）。
     *
     * @return 是否绕过
     */
    public boolean isBypassLoadBalance() {
        return bypassLoadBalance;
    }

    /**
     * 让本请求绕过负载均衡，直连 URL 中写明的地址。
     * <p>
     * 适用于"基础设施自身"的调用：注册中心拉取、健康探测、管理接口等。
     * 尤其重要的是，未设 {@code serviceName} 的全局端点池会作用于<b>所有</b>请求，
     * 服务发现自己的 HTTP 请求若不绕过，就会被改写到业务端点上。
     *
     * @param bypass 是否绕过
     * @return this
     */
    public HttpRequest bypassLoadBalance(boolean bypass) {
        this.bypassLoadBalance = bypass;
        return this;
    }

    /**
     * 会话亲和键：一致性哈希策略据此把同一用户/会话固定路由到同一端点。
     *
     * @return 亲和键，未设置为 {@code null}
     */
    public String getLoadBalanceKey() {
        return loadBalanceKey;
    }

    /**
     * 设置会话亲和键（一致性哈希用），例如用户 ID、租户 ID。
     *
     * @param loadBalanceKey 亲和键
     * @return this
     */
    public HttpRequest loadBalanceKey(String loadBalanceKey) {
        this.loadBalanceKey = loadBalanceKey;
        return this;
    }

    long resolveMaxBufferBytes() {
        if (maxBufferBytes != 0) {
            return maxBufferBytes;
        }
        return HttpConfig.shared().getMaxBufferBytes();
    }

    /**
     * 直接发送本请求。等价于 {@code HttpUtils.execute(this)}，省去把请求交回静态门面的一步。
     * <p>
     * 会经过完整链路：拦截器、重试、重定向跟随、负载均衡与 Cookie 存取。
     *
     * @return 响应，调用方负责关闭
     * @throws java.io.IOException 网络错误
     */
    public HttpResponse execute() throws java.io.IOException {
        return com.alianga.jkit.HttpUtils.execute(this);
    }

    /**
     * 异步发送本请求，使用 {@link HttpConfig#getExecutor()} 或内置守护线程池。
     *
     * @return 响应的 Future，响应由调用方负责关闭
     */
    public java.util.concurrent.Future<HttpResponse> executeAsync() {
        return com.alianga.jkit.HttpUtils.executeAsync(this);
    }
}
