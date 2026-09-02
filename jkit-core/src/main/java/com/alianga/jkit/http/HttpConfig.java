package com.alianga.jkit.http;

import com.alianga.jkit.http.lb.EndpointPool;

import javax.net.ssl.HostnameVerifier;
import javax.net.ssl.SSLContext;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;

/**
 * HTTP 全局配置：超时、HTTP/2、缓冲上限、下载块大小、代理认证、重试、
 * 拦截器、总时长上限、重定向上限、负载均衡端点池。
 * <p>
 * 通过 {@link com.alianga.jkit.HttpUtils#config()} 访问。修改 SSL / HTTP/2 / 代理后
 * 会在下次请求时作用于引擎（必要时重建 JDK {@code HttpClient}）。
 *
 * @author 郑明亮
 */
public final class HttpConfig {
    private static final HttpConfig SHARED = new HttpConfig();

    private volatile int connectTimeoutMs = 60_000;
    private volatile int readTimeoutMs = 60_000;
    private volatile boolean http2 = true;
    private volatile long maxBufferBytes = 64L * 1024 * 1024;
    private volatile int downloadBufferSize = 64 * 1024;
    private volatile String proxyUsername;
    private volatile String proxyPassword;
    private volatile SSLContext sslContext;
    private volatile HostnameVerifier hostnameVerifier;
    private volatile RetryPolicy retryPolicy = RetryPolicy.none();
    private volatile long totalTimeoutMs;
    private volatile int maxRedirects = 5;
    private volatile boolean throwOnHttpError;
    private volatile ExecutorService executor;
    private volatile EndpointPool endpointPool;
    private volatile boolean countReadTimeoutAsEndpointFailure = true;
    private final List<HttpInterceptor> interceptors = new CopyOnWriteArrayList<HttpInterceptor>();

    /**
     * @return 进程内共享配置
     */
    public static HttpConfig shared() {
        return SHARED;
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
    public HttpConfig setConnectTimeoutMs(int connectTimeoutMs) {
        this.connectTimeoutMs = Math.max(0, connectTimeoutMs);
        return this;
    }

    /**
     * @return 读取超时（毫秒），{@code 0} 表示不限制（SSE 等长连接）
     */
    public int getReadTimeoutMs() {
        return readTimeoutMs;
    }

    /**
     * @param readTimeoutMs 读取超时（毫秒）
     * @return this
     */
    public HttpConfig setReadTimeoutMs(int readTimeoutMs) {
        this.readTimeoutMs = Math.max(0, readTimeoutMs);
        return this;
    }

    /**
     * JDK 11+ 是否优先协商 HTTP/2（明文 HTTP 仍走 1.1）。
     *
     * @return 是否启用 HTTP/2
     */
    public boolean isHttp2() {
        return http2;
    }

    /**
     * @param http2 是否优先 HTTP/2
     * @return this
     */
    public HttpConfig setHttp2(boolean http2) {
        this.http2 = http2;
        return this;
    }

    /**
     * 非流式响应允许缓冲的最大字节数，超出抛错以避免 OOM。{@code 0} 或负数表示不限制。
     *
     * @return 上限字节数
     */
    public long getMaxBufferBytes() {
        return maxBufferBytes;
    }

    /**
     * @param maxBufferBytes 缓冲上限
     * @return this
     */
    public HttpConfig setMaxBufferBytes(long maxBufferBytes) {
        this.maxBufferBytes = maxBufferBytes;
        return this;
    }

    /**
     * @return 下载/拷贝时的块大小
     */
    public int getDownloadBufferSize() {
        return downloadBufferSize;
    }

    /**
     * @param downloadBufferSize 块大小，至少 1024
     * @return this
     */
    public HttpConfig setDownloadBufferSize(int downloadBufferSize) {
        this.downloadBufferSize = Math.max(1024, downloadBufferSize);
        return this;
    }

    /**
     * @return HTTP 代理用户名
     */
    public String getProxyUsername() {
        return proxyUsername;
    }

    /**
     * @return HTTP 代理密码
     */
    public String getProxyPassword() {
        return proxyPassword;
    }

    /**
     * 设置 HTTP 代理 Basic 认证（写入 {@code Proxy-Authorization}）。
     *
     * @param username 用户名，{@code null} 清除
     * @param password 密码
     * @return this
     */
    public HttpConfig setProxyAuth(String username, String password) {
        this.proxyUsername = username;
        this.proxyPassword = password;
        return this;
    }

    /**
     * @return 自定义 SSL 上下文；未设置时为 {@code null}
     */
    public SSLContext getSslContext() {
        return sslContext;
    }

    /**
     * 设置自定义 SSL 上下文。生产环境建议使用默认校验的上下文。
     *
     * @param sslContext SSL 上下文，{@code null} 恢复默认
     * @return this
     */
    public HttpConfig setSslContext(SSLContext sslContext) {
        this.sslContext = sslContext;
        return this;
    }

    /**
     * @return 自定义主机名校验器；未设置时为 {@code null}
     */
    public HostnameVerifier getHostnameVerifier() {
        return hostnameVerifier;
    }

    /**
     * @param hostnameVerifier 主机名校验器，{@code null} 使用默认校验
     * @return this
     */
    public HttpConfig setHostnameVerifier(HostnameVerifier hostnameVerifier) {
        this.hostnameVerifier = hostnameVerifier;
        return this;
    }

    /**
     * @return 重试策略
     */
    public RetryPolicy getRetryPolicy() {
        return retryPolicy;
    }

    /**
     * @param retryPolicy 重试策略，{@code null} 表示不重试
     * @return this
     */
    public HttpConfig setRetryPolicy(RetryPolicy retryPolicy) {
        this.retryPolicy = retryPolicy == null ? RetryPolicy.none() : retryPolicy;
        return this;
    }

    /**
     * 单次调用的总时长上限（毫秒），覆盖重试、重定向跟随与故障转移的全部耗时。
     * {@code 0} 表示不限制。
     * <p>
     * 只设 connect/read 超时是不够的：一次 3 次重试 + 5 跳重定向的调用，
     * 最坏耗时是单次超时的十几倍。企业场景建议按接口 SLA 设置该值。
     *
     * @return 总时长上限毫秒
     */
    public long getTotalTimeoutMs() {
        return totalTimeoutMs;
    }

    /**
     * @param totalTimeoutMs 总时长上限毫秒，{@code <=0} 表示不限制
     * @return this
     */
    public HttpConfig setTotalTimeoutMs(long totalTimeoutMs) {
        this.totalTimeoutMs = Math.max(0L, totalTimeoutMs);
        return this;
    }

    /**
     * 最多跟随的重定向跳数，{@code 0} 表示不跟随。默认 5。
     *
     * @return 重定向上限
     */
    public int getMaxRedirects() {
        return maxRedirects;
    }

    /**
     * @param maxRedirects 重定向上限
     * @return this
     */
    public HttpConfig setMaxRedirects(int maxRedirects) {
        this.maxRedirects = Math.max(0, maxRedirects);
        return this;
    }

    /**
     * 非 2xx 响应是否直接抛出 {@link HttpStatusException}。默认 {@code false}
     * （保持历史行为：把错误正文当作返回值）。
     * <p>
     * 开启后，返回 {@code String} / {@code byte[]} 的便捷方法遇到 4xx/5xx 会抛异常，
     * 避免上层把错误页当成业务数据解析。
     *
     * @return 是否抛出
     */
    public boolean isThrowOnHttpError() {
        return throwOnHttpError;
    }

    /**
     * @param throwOnHttpError 非 2xx 是否抛出 {@link HttpStatusException}
     * @return this
     */
    public HttpConfig setThrowOnHttpError(boolean throwOnHttpError) {
        this.throwOnHttpError = throwOnHttpError;
        return this;
    }

    /**
     * 异步任务线程池。未设置时使用内置的 20 线程守护池。
     *
     * @return 线程池，未自定义时为 {@code null}
     */
    public ExecutorService getExecutor() {
        return executor;
    }

    /**
     * 设置异步任务（异步下载、SSE 订阅）使用的线程池。
     * <p>
     * 内置池只有 20 个线程且与 SSE 共享，SSE 订阅数一多就会把异步下载饿死；
     * 有大量长连接时请换成自己的池。生命周期由调用方负责。
     *
     * @param executor 线程池，{@code null} 恢复内置池
     * @return this
     */
    public HttpConfig setExecutor(ExecutorService executor) {
        this.executor = executor;
        return this;
    }

    /**
     * 负载均衡端点池。设置后，命中的请求 URL 的 origin 会被替换为池内选出的端点，
     * 并在端点故障时自动转移到其他端点。
     * <p>
     * 池带 {@code serviceName} 时只作用于 {@code http://<serviceName>/...} 这类请求；
     * 不带时作用于所有请求（单上游场景才建议这么用）。
     *
     * @return 端点池，未启用时为 {@code null}
     */
    public EndpointPool getEndpointPool() {
        return endpointPool;
    }

    /**
     * 设置全局负载均衡端点池，{@code null} 关闭负载均衡。
     * 单个请求可用 {@link HttpRequest#endpointPool(EndpointPool)} 覆盖。
     *
     * @param endpointPool 端点池
     * @return this
     */
    public HttpConfig setEndpointPool(EndpointPool endpointPool) {
        this.endpointPool = endpointPool;
        return this;
    }

    /**
     * 读超时是否计入端点故障（影响负载均衡熔断）。默认 {@code true}。
     * <p>
     * 对单向推送、长轮询等"本就不期待及时响应"的接口设为 {@code false}，
     * 否则正常的读超时会把健康节点熔断掉。
     *
     * @return 是否计入
     */
    public boolean isCountReadTimeoutAsEndpointFailure() {
        return countReadTimeoutAsEndpointFailure;
    }

    /**
     * @param count 读超时是否计入端点故障
     * @return this
     */
    public HttpConfig setCountReadTimeoutAsEndpointFailure(boolean count) {
        this.countReadTimeoutAsEndpointFailure = count;
        return this;
    }

    /**
     * 注册拦截器，按注册顺序执行（先注册的在最外层）。
     *
     * @param interceptor 拦截器，{@code null} 忽略
     * @return this
     */
    public HttpConfig addInterceptor(HttpInterceptor interceptor) {
        if (interceptor != null) {
            interceptors.add(interceptor);
        }
        return this;
    }

    /**
     * 移除拦截器。
     *
     * @param interceptor 拦截器
     * @return 是否移除成功
     */
    public boolean removeInterceptor(HttpInterceptor interceptor) {
        return interceptors.remove(interceptor);
    }

    /**
     * 清空全部拦截器。
     *
     * @return this
     */
    public HttpConfig clearInterceptors() {
        interceptors.clear();
        return this;
    }

    /**
     * @return 拦截器列表（可安全遍历的实时视图）
     */
    public List<HttpInterceptor> getInterceptors() {
        return interceptors;
    }
}
