package com.alianga.jkit;

import com.alianga.jkit.convert.ConvertUtils;
import com.alianga.jkit.http.CookieJarImpl;
import com.alianga.jkit.http.CurlParser;
import com.alianga.jkit.http.CurlRequest;
import com.alianga.jkit.http.HttpBodies;
import com.alianga.jkit.http.HttpCall;
import com.alianga.jkit.http.HttpCallBack;
import com.alianga.jkit.http.HttpConfig;
import com.alianga.jkit.http.HttpCookieJar;
import com.alianga.jkit.http.HttpEngine;
import com.alianga.jkit.http.HttpEngines;
import com.alianga.jkit.http.HttpInterceptor;
import com.alianga.jkit.http.HttpIo;
import com.alianga.jkit.http.HttpRequest;
import com.alianga.jkit.http.HttpResponse;
import com.alianga.jkit.http.HttpResponseBody;
import com.alianga.jkit.http.HttpStatusException;
import com.alianga.jkit.http.RetryPolicy;
import com.alianga.jkit.http.SseClient;
import com.alianga.jkit.http.SseEvent;
import com.alianga.jkit.http.SseListener;
import com.alianga.jkit.http.SseMergeFormat;
import com.alianga.jkit.http.SseMergeListener;
import com.alianga.jkit.http.SseMergeResult;
import com.alianga.jkit.http.SseMerger;
import com.alianga.jkit.http.SseReconnectOptions;
import com.alianga.jkit.http.UploadInfo;
import com.alianga.jkit.http.WebSocketListener;
import com.alianga.jkit.http.WebSocketSession;
import com.alianga.jkit.http.lb.Endpoint;
import com.alianga.jkit.http.lb.EndpointPool;
import com.alianga.jkit.json.JSON;
import com.alianga.jkit.log.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UnsupportedEncodingException;
import java.net.HttpCookie;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.SocketAddress;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.StringJoiner;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 纯 JDK HTTP 客户端：GET/POST/PUT/DELETE、JSON、上传下载（含断点续传）、
 * Cookie、代理认证、SSE、WebSocket（JDK 11+）。
 * <p>
 * JDK 8 使用 {@link java.net.HttpURLConnection}；JDK 11+ 优先使用
 * {@code java.net.http.HttpClient}（HTTP/2、连接复用）。全局配置见 {@link #config()}。
 *
 * @author 郑明亮
 * @version 1.0
 */
public class HttpUtils {
    private static final Log log = Log.get(HttpUtils.class);
    /**
     * 下载失败时的最大重试次数
     */
    /**
     * 下载遇到网络抖动时的最大重试次数。
     */
    protected static final int DOWNLOAD_MAX_RETRIES = 3;

    /**
     * @deprecated 命名不准确（实为下载重试次数），请用 {@link #DOWNLOAD_MAX_RETRIES}
     */
    @Deprecated
    protected static final int MAX_SERVER_LOAD_TIMES = DOWNLOAD_MAX_RETRIES;
    private static final String DEFAULT_UA =
            "Mozilla/5.0 (Windows NT 10.0; WOW64) AppleWebKit/537.36 (KHTML, like Gecko) "
                    + "Chrome/65.0.3325.181 Safari/537.36";
    private static final String FORM_CONTENT_TYPE = "application/x-www-form-urlencoded";
    /** 负载均衡故障转移的尝试上限，避免端点很多时把一次调用拖得过长。 */
    private static final int MAX_FAILOVER_ATTEMPTS = 5;
    private static final AtomicInteger ASYNC_THREAD_SEQ = new AtomicInteger(1);
    private static final ExecutorService ASYNC_EXECUTOR = Executors.newFixedThreadPool(20, r -> {
        Thread thread = new Thread(r, "jkit-http-" + ASYNC_THREAD_SEQ.getAndIncrement());
        thread.setDaemon(true);
        return thread;
    });

    /**
     * 异步任务使用的线程池：优先用 {@link HttpConfig#getExecutor()}，否则用内置守护池。
     * <p>
     * 内置池只有 20 个线程且与 SSE 订阅共享，超过 20 条长连接会把异步下载饿死；
     * 有大量 SSE / 异步下载时请通过 {@code HttpUtils.config().setExecutor(...)} 换成自己的池。
     *
     * @return 线程池
     */
    public static ExecutorService asyncExecutor() {
        ExecutorService custom = config().getExecutor();
        return custom == null ? ASYNC_EXECUTOR : custom;
    }

    /**
     * 异步发送请求。
     *
     * @param request 请求
     * @return 响应的 Future，响应由调用方负责关闭
     */
    public static Future<HttpResponse> executeAsync(HttpRequest request) {
        return asyncExecutor().submit(() -> execute(request));
    }

    /**
     * 是否伪造 IP 头（X-REAL-IP / X-FORWARDED-FOR）
     */
    public static volatile boolean fakeIp;
    /**
     * 发送 body 时的默认 Content-Type，可通过含 contentType 的重载覆盖单次请求
     */
    public static volatile String defaultMediaType = "application/json; charset=utf-8";

    private static volatile boolean ignoreSSL = true;
    private static volatile Proxy proxy;
    private static volatile HttpCookieJar cookieJar;
    private static volatile HttpEngine engine;

    private HttpUtils() {
    }

    /**
     * @return 全局 HTTP 配置（超时、HTTP/2、缓冲上限、代理认证）
     */
    public static HttpConfig config() {
        return HttpConfig.shared();
    }

    /**
     * 使后续 HTTPS 请求忽略证书与主机名校验（默认已开启，兼容原 OkHttp 行为）。
     */
    public static void supportHttps() {
        setIgnoreSsl(true);
    }

    /**
     * 推荐的安全默认入口：启用系统证书链和主机名校验。
     * <p>兼容旧版本的 {@link #supportHttps()} 仍然保留，但不建议用于生产环境。</p>
     */
    public static void useSecureSsl() {
        setIgnoreSsl(false);
    }

    /**
     * 开启 JDK 默认证书与主机名校验（生产环境建议调用）。
     */
    public static void verifySsl() {
        setIgnoreSsl(false);
    }

    /**
     * @param ignore 是否忽略 HTTPS 证书
     */
    public static void setIgnoreSsl(boolean ignore) {
        ignoreSSL = ignore;
        engine = null;
    }

    /**
     * 为全局客户端设置自定义 SSL 上下文和主机名校验器。
     *
     * @param sslContext SSL 上下文，null 表示使用默认上下文
     * @param hostnameVerifier 主机名校验器，null 表示使用默认校验
     */
    public static void setSsl(javax.net.ssl.SSLContext sslContext,
                              javax.net.ssl.HostnameVerifier hostnameVerifier) {
        config().setSslContext(sslContext).setHostnameVerifier(hostnameVerifier);
        engine = null;
    }

    /**
     * @return 当前是否忽略 HTTPS 证书
     */
    public static boolean isIgnoreSsl() {
        return ignoreSSL;
    }

    /**
     * 忽略 SNI。
     */
    public static void ignoreSNI() {
        System.setProperty("jsse.enableSNIExtension", "false");
    }

    /**
     * @param millis 全局连接超时
     */
    public static void setConnectTimeout(int millis) {
        config().setConnectTimeoutMs(millis);
        engine = null;
    }

    /**
     * @param millis 全局读取超时，{@code 0} 表示不限制
     */
    public static void setReadTimeout(int millis) {
        config().setReadTimeoutMs(millis);
    }

    /**
     * JDK 11+ 是否优先协商 HTTP/2。
     *
     * @param enabled 是否启用
     */
    public static void setHttp2(boolean enabled) {
        config().setHttp2(enabled);
        engine = null;
    }

    /**
     * 非流式响应的内存上限，超出抛错。
     *
     * @param maxBytes 字节数，{@code <=0} 不限制
     */
    public static void setMaxBufferBytes(long maxBytes) {
        config().setMaxBufferBytes(maxBytes);
    }

    /**
     * Basic 认证头值。
     *
     * @param user 用户名
     * @param password 密码
     * @return {@code Basic xxxxx}
     */
    public static String basicAuth(String user, String password) {
        String raw = (user == null ? "" : user) + ":" + (password == null ? "" : password);
        return "Basic " + Base64.getEncoder().encodeToString(raw.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Bearer 认证头值。
     *
     * @param token 令牌
     * @return {@code Bearer ...}
     */
    public static String bearer(String token) {
        return "Bearer " + (token == null ? "" : token);
    }

    /**
     * 简单的 HTTP GET，返回完整响应。
     *
     * @param url 请求地址
     * @return 响应
     * @throws IOException 网络错误
     */
    public static HttpResponse getResponse(String url) throws IOException {
        HttpRequest request = HttpRequest.get(url).header("User-Agent", DEFAULT_UA);
        applyDefaults(request);
        if (fakeIp) {
            setFakeIpHeader(request.getHeaders());
        }
        return send(request);
    }

    /**
     * 简单的 HTTP GET，返回响应字符串。
     *
     * @param url 请求地址
     * @return 响应正文
     * @throws IOException 网络错误
     */
    public static String get(String url) throws IOException {
        return bodyString(getResponse(url));
    }

    /**
     * GET，只传请求头（无 query、无 body）。
     * <p>
     * 因 Java 类型擦除，不能与 {@link #get(String, Map)} 并存两个 {@code Map} 重载，
     * 查询参数请继续用 {@code Map<String, Object>}；仅请求头请用本方法的
     * {@link #getResponse(String, Map)} 或 {@code get(url, null, headers)}。
     *
     * @param url 请求地址
     * @param headers 请求头
     * @return 响应
     * @throws IOException 网络错误
     */
    public static HttpResponse getResponse(String url, Map<String, String> headers) throws IOException {
        return getResponse(url, null, headers);
    }

    /**
     * GET，拼接查询参数。
     *
     * @param url 请求地址
     * @param params 查询参数
     * @return 响应正文
     * @throws IOException 网络错误
     */
    public static String get(String url, Map<String, Object> params) throws IOException {
        return bodyString(getResponse(url, params, null));
    }

    /**
     * GET，拼接查询参数并指定请求头。
     *
     * @param url 请求地址
     * @param params 查询参数
     * @param headers 请求头；非空时会替换默认头
     * @return 响应正文
     * @throws IOException 网络错误
     */
    public static String get(String url, Map<String, Object> params, Map<String, String> headers)
            throws IOException {
        return bodyString(getResponse(url, params, headers));
    }

    /**
     * GET，返回完整响应。
     *
     * @param url 请求地址
     * @param params 查询参数
     * @param headers 请求头；非空时会替换默认头
     * @return 响应
     * @throws IOException 网络错误
     */
    public static HttpResponse getResponse(String url, Map<String, Object> params, Map<String, String> headers)
            throws IOException {
        if (url == null) {
            throw new RuntimeException("the request URL can not be null");
        }
        url = appendQuery(url, params);
        log.debug(url);
        HttpRequest request = HttpRequest.get(url);
        if (headers != null && !headers.isEmpty()) {
            request.headers(headers);
        } else {
            request.headers(getDefaultHeaders());
            if (fakeIp) {
                setFakeIpHeader(request.getHeaders());
            }
        }
        applyDefaults(request);
        return send(request);
    }

    /**
     * URL 编码。
     *
     * @param value 原始值
     * @return 编码后的字符串
     */
    public static String encodeValue(Object value) {
        if (value == null) {
            return "";
        }
        try {
            return URLEncoder.encode(value.toString(), StandardCharsets.UTF_8.name());
        } catch (UnsupportedEncodingException e) {
            throw new RuntimeException("Encoding failed", e);
        }
    }

    /**
     * 将参数拼成查询串，跳过 {@code null} 值；数组与 List 展开为多组同名参数。
     *
     * @param params 查询参数
     * @return 如 {@code name=zhangsan&amp;age=11}
     */
    public static String getRequestParamString(Map<String, Object> params) {
        if (params == null || params.isEmpty()) {
            return "";
        }
        StringJoiner urlParamJoiner = new StringJoiner("&");
        for (Map.Entry<String, Object> entry : params.entrySet()) {
            if (entry.getValue() == null) {
                continue;
            }
            String encodedKey = encodeValue(entry.getKey());
            if (entry.getValue().getClass().isArray()) {
                Object[] array = (Object[]) entry.getValue();
                for (Object element : array) {
                    if (element != null) {
                        urlParamJoiner.add(encodedKey + "=" + encodeValue(element));
                    }
                }
            } else if (entry.getValue() instanceof List) {
                List<?> list = (List<?>) entry.getValue();
                for (Object element : list) {
                    if (element != null) {
                        urlParamJoiner.add(encodedKey + "=" + encodeValue(element));
                    }
                }
            } else {
                urlParamJoiner.add(encodedKey + "=" + encodeValue(entry.getValue()));
            }
        }
        return urlParamJoiner.toString();
    }

    /**
     * @param cookieJar Cookie 仓库
     */
    public static void setCookieJar(HttpCookieJar cookieJar) {
        HttpUtils.cookieJar = cookieJar;
    }

    /**
     * @return Cookie 仓库，尚未设置时创建内存实现
     */
    public static HttpCookieJar getCookieJar() {
        if (HttpUtils.cookieJar == null) {
            HttpUtils.cookieJar = new CookieJarImpl();
        }
        return HttpUtils.cookieJar;
    }

    /**
     * @return 当前传输引擎（懒创建）
     */
    public static HttpEngine getHttpEngine() {
        HttpEngine current = engine;
        if (current == null) {
            synchronized (HttpUtils.class) {
                current = engine;
                if (current == null) {
                    current = HttpEngines.create();
                    engine = current;
                }
            }
        }
        return current;
    }

    /**
     * 注入传输引擎，主要用于测试。
     *
     * @param httpEngine 引擎，{@code null} 表示下次调用时重新选择
     */
    public static void setEngine(HttpEngine httpEngine) {
        engine = httpEngine;
    }

    /**
     * @return 当前引擎标识
     */
    public static String getEngineName() {
        return getHttpEngine().name();
    }

    /**
     * @param proxy 代理，{@code null} 表示使用系统默认
     */
    public static void setProxy(Proxy proxy) {
        HttpUtils.proxy = proxy;
        if (proxy == null) {
            config().setProxyAuth(null, null);
        }
        engine = null;
    }

    /**
     * 设置 HTTP 代理（无认证）。
     *
     * @param host 主机名或 IP
     * @param port 端口
     */
    public static void setHttpProxy(String host, int port) {
        setHttpProxy(host, port, null, null);
    }

    /**
     * 设置带 Basic 认证的 HTTP 代理。
     *
     * @param host 主机名或 IP
     * @param port 端口
     * @param username 用户名，为 {@code null} 时不带认证
     * @param password 密码
     */
    public static void setHttpProxy(String host, int port, String username, String password) {
        SocketAddress address = new InetSocketAddress(host, port);
        HttpUtils.proxy = new Proxy(Proxy.Type.HTTP, address);
        config().setProxyAuth(username, password);
        engine = null;
    }

    /**
     * 设置 SOCKS 代理（V4/V5，无认证）。
     *
     * @param host 主机名或 IP
     * @param port 端口
     */
    public static void setSocksProxy(String host, int port) {
        SocketAddress address = new InetSocketAddress(host, port);
        HttpUtils.proxy = new Proxy(Proxy.Type.SOCKS, address);
    }

    /**
     * @return 是否已设置自定义代理
     */
    public static boolean isUseProxy() {
        return proxy != null;
    }

    /**
     * POST，无表单无 body，只发 URL。
     *
     * @param url 访问地址
     * @return 响应体
     * @throws IOException 网络错误
     */
    public static HttpResponseBody post(String url) throws IOException {
        return getResponseFromPost(url, null, null).body();
    }

    /**
     * POST，只传请求头（空 body）。
     *
     * @param url 访问地址
     * @param headers 请求头
     * @return 响应
     * @throws IOException 网络错误
     */
    public static HttpResponse getResponseFromPost(String url, Map<String, String> headers) throws IOException {
        return getResponseFromPost(url, null, headers);
    }

    /**
     * POST 表单，返回响应体。
     *
     * @param url 访问地址
     * @param params 表单参数
     * @param headers 请求头，为 {@code null} 时使用默认头
     * @return 响应体
     * @throws IOException 网络错误
     */
    public static HttpResponseBody post(String url, Map<String, Object> params, Map<String, String> headers)
            throws IOException {
        return getResponseFromPost(url, params, headers).body();
    }

    /**
     * POST 表单，返回完整响应。
     *
     * @param url 访问地址
     * @param params 表单参数
     * @param headers 请求头，为 {@code null} 时使用默认头
     * @return 响应
     * @throws IOException 网络错误
     */
    public static HttpResponse getResponseFromPost(String url, Map<String, Object> params,
                                                   Map<String, String> headers) throws IOException {
        Map<String, String> hdrs = headers == null ? getDefaultHeaders() : headers;
        HttpRequest request = HttpRequest.post(url)
                .headers(hdrs)
                .contentType(FORM_CONTENT_TYPE)
                .body(HttpBodies.formUrlEncoded(params));
        applyDefaults(request);
        if (fakeIp) {
            setFakeIpHeader(request.getHeaders());
        }
        log.debug("POST form url:{}, fields:{}", url, HttpIo.maskedKeys(params));
        return send(request);
    }

    /**
     * POST 表单，返回字符串。
     *
     * @param url 访问地址
     * @param params 表单参数
     * @param headers 请求头
     * @return 响应正文
     * @throws IOException 网络错误
     */
    public static String getStringFromPost(String url, Map<String, Object> params, Map<String, String> headers)
            throws IOException {
        return post(url, params, headers).string();
    }

    /**
     * POST 表单，返回字节。
     *
     * @param url 访问地址
     * @param params 表单参数
     * @param headers 请求头
     * @return 响应字节
     * @throws IOException 网络错误
     */
    public static byte[] getBytesFromPost(String url, Map<String, Object> params, Map<String, String> headers)
            throws IOException {
        return post(url, params, headers).bytes();
    }

    /**
     * POST 表单，返回流。
     *
     * @param url 访问地址
     * @param params 表单参数
     * @param headers 请求头
     * @return 响应流
     * @throws IOException 网络错误
     */
    public static InputStream getStreamFromPost(String url, Map<String, Object> params, Map<String, String> headers)
            throws IOException {
        return post(url, params, headers).byteStream();
    }

    /**
     * POST 表单。
     *
     * @param url 访问地址
     * @param param 表单参数
     * @return 响应体
     * @throws IOException 网络错误
     */
    public static HttpResponseBody post(String url, Map<String, Object> param) throws IOException {
        return getResponseFromPost(url, param, null).body();
    }

    /**
     * POST 表单，返回字符串。
     *
     * @param url 访问地址
     * @param param 表单参数
     * @return 响应正文
     * @throws IOException 网络错误
     */
    public static String getStringFromPost(String url, Map<String, Object> param) throws IOException {
        return post(url, param).string();
    }

    /**
     * POST 表单，返回字节。
     *
     * @param url 访问地址
     * @param param 表单参数
     * @return 响应字节
     * @throws IOException 网络错误
     */
    public static byte[] getBytesFromPost(String url, Map<String, Object> param) throws IOException {
        return post(url, param).bytes();
    }

    /**
     * POST 表单，返回流。
     *
     * @param url 访问地址
     * @param param 表单参数
     * @return 响应流
     * @throws IOException 网络错误
     */
    public static InputStream getInputStreamFromPost(String url, Map<String, Object> param) throws IOException {
        return post(url, param).byteStream();
    }

    /**
     * 同步下载到当前目录（文件名从响应头或 URL 解析）。
     *
     * @param url 文件地址
     * @return 本地绝对路径
     * @throws IOException 网络或 IO 错误
     */
    public static String download(String url) throws IOException {
        return download(url, null, "", false, null);
    }

    /**
     * 同步下载。
     *
     * @param url 文件地址
     * @param fileName 文件名或路径，为空则自动解析
     * @return 本地绝对路径
     * @throws IOException 网络或 IO 错误
     */
    public static String download(String url, String fileName) throws IOException {
        return download(url, fileName, "", false, null);
    }

    /**
     * 同步下载到指定目录。
     *
     * @param url 文件地址
     * @param fileName 文件名，为空则自动解析
     * @param dir 保存目录
     * @return 本地绝对路径
     * @throws IOException 网络或 IO 错误
     */
    public static String download(String url, String fileName, String dir) throws IOException {
        return download(url, fileName, dir, false, null);
    }

    /**
     * 同步下载到目标文件。
     *
     * @param url 文件地址
     * @param dest 目标文件
     * @return 本地绝对路径
     * @throws IOException 网络或 IO 错误
     */
    public static String download(String url, File dest) throws IOException {
        return download(url, dest, false, null);
    }

    /**
     * 同步下载到目标文件，带请求头。
     *
     * @param url 文件地址
     * @param dest 目标文件
     * @param headers 请求头
     * @return 本地绝对路径
     * @throws IOException 网络或 IO 错误
     */
    public static String download(String url, File dest, Map<String, String> headers) throws IOException {
        String name = dest == null ? null : dest.getName();
        String dir = dest == null || dest.getParent() == null ? "" : dest.getParent();
        return downloadTo(url, name, dir, dest, false, headers, null);
    }

    /**
     * 同步下载到目录，带请求头。
     *
     * @param url 文件地址
     * @param fileName 文件名
     * @param dir 目录
     * @param headers 请求头
     * @return 本地绝对路径
     * @throws IOException 网络或 IO 错误
     */
    public static String download(String url, String fileName, String dir, Map<String, String> headers)
            throws IOException {
        return downloadTo(url, fileName, dir, null, false, headers, null);
    }

    /**
     * 同步下载，支持断点续传。
     *
     * @param url 文件地址
     * @param dest 目标文件
     * @param resume {@code true} 时若本地已有内容则发送 {@code Range}
     * @return 本地绝对路径
     * @throws IOException 网络或 IO 错误
     */
    public static String download(String url, File dest, boolean resume) throws IOException {
        return download(url, dest, resume, null);
    }

    /**
     * 同步下载，支持断点续传与进度。
     *
     * @param url 文件地址
     * @param dest 目标文件
     * @param resume 是否续传
     * @param progress 进度回调，{@link HttpCallBack#onProcess(long, long)} 为已写入字节与总大小
     * @return 本地绝对路径
     * @throws IOException 网络或 IO 错误
     */
    public static String download(String url, File dest, boolean resume, HttpCallBack<String> progress)
            throws IOException {
        String name = dest == null ? null : dest.getName();
        String dir = dest == null || dest.getParent() == null ? "" : dest.getParent();
        return downloadTo(url, name, dir, dest, resume, null, progress);
    }

    /**
     * 同步下载到目录，可续传、带进度。
     *
     * @param url 文件地址
     * @param fileName 文件名
     * @param dir 目录
     * @param resume 是否续传
     * @param progress 进度回调
     * @return 本地绝对路径
     * @throws IOException 网络或 IO 错误
     */
    public static String download(String url, String fileName, String dir, boolean resume,
                                  HttpCallBack<String> progress) throws IOException {
        return downloadTo(url, fileName, dir, null, resume, null, progress);
    }

    /**
     * 异步下载，返回 Future（不再使用 1 秒读超时）。
     *
     * @param url 文件地址
     * @param dest 目标文件
     * @return 完成后的路径
     */
    public static Future<String> downloadAsync(String url, File dest) {
        return downloadAsync(url, dest, false, null);
    }

    /**
     * 异步下载。
     *
     * @param url 文件地址
     * @param fileName 文件名
     * @param dir 目录
     * @param callback 进度与完成回调
     * @return Future
     */
    public static Future<String> downloadAsync(final String url, final String fileName, final String dir,
                                               final HttpCallBack<String> callback) {
        final HttpCall call = new HttpCall(HttpRequest.get(url));
        return asyncExecutor().submit(() -> {
            try {
                String path = download(url, fileName, dir, false, callback);
                if (callback != null) {
                    callback.onResponse(call, null, path);
                }
                return path;
            } catch (IOException e) {
                if (callback != null) {
                    callback.onFailure(call, e);
                }
                throw e;
            }
        });
    }

    /**
     * 异步下载到文件，可续传。
     *
     * @param url 文件地址
     * @param dest 目标文件
     * @param resume 是否续传
     * @param callback 回调
     * @return Future
     */
    public static Future<String> downloadAsync(final String url, final File dest, final boolean resume,
                                               final HttpCallBack<String> callback) {
        final HttpCall call = new HttpCall(HttpRequest.get(url));
        return asyncExecutor().submit(() -> {
            try {
                String path = download(url, dest, resume, callback);
                if (callback != null) {
                    callback.onResponse(call, null, path);
                }
                return path;
            } catch (IOException e) {
                if (callback != null) {
                    callback.onFailure(call, e);
                }
                throw e;
            }
        });
    }

    /**
     * 下载文件到当前目录，文件名从响应头或 URL 解析。
     *
     * @param url           文件地址
     * @param isSynchronous 是否同步下载，为 {@code false} 时提交异步任务后立即返回
     * @return 同步下载时返回本地绝对路径；异步下载时固定返回 {@code null}（任务尚未完成，
     *         路径无从得知；旧实现返回的是进程内共享的"上一次下载路径"，并发下会串号）
     * @throws IOException 网络或 IO 错误
     * @deprecated 请使用 {@link #download(String)} 或 {@link #downloadAsync(String, File)}
     */
    @Deprecated
    public static String getFileFromHttpData(String url, boolean isSynchronous) throws IOException {
        if (isSynchronous) {
            return download(url);
        }
        downloadAsync(url, (String) null, "", null);
        return null;
    }

    /**
     * 同步下载文件。
     *
     * @param url      文件地址
     * @param fileName 文件名或路径，为空则自动解析
     * @return 本地绝对路径
     * @throws IOException 网络或 IO 错误
     * @deprecated 请使用 {@link #download(String, String)}
     */
    @Deprecated
    public static String getFileFromHttpDataBySyn(final String url, final String fileName) throws IOException {
        return download(url, fileName);
    }

    /**
     * 同步下载文件到指定目录。
     *
     * @param url      文件地址
     * @param fileName 文件名，为空则自动解析
     * @param dir      保存目录
     * @return 本地绝对路径
     * @throws IOException 网络或 IO 错误
     * @deprecated 请使用 {@link #download(String, String, String)}
     */
    @Deprecated
    public static String getFileFromHttpDataBySyn(final String url, final String fileName, String dir)
            throws IOException {
        return download(url, fileName, dir);
    }

    /**
     * 同步下载文件到指定目录，并可附加请求头。
     *
     * @param url      文件地址
     * @param fileName 文件名，为空则自动解析
     * @param dir      保存目录
     * @param headers  请求头，仅第一个元素生效，可不传
     * @return 本地绝对路径
     * @throws IOException 网络或 IO 错误
     * @deprecated 请使用 {@link #download(String, String, String)} 并自行设置请求头后走 {@link #execute}
     */
    @Deprecated
    @SafeVarargs
    public static String getFileFromHttpDataBySyn(final String url, final String fileName, String dir,
                                                  Map<String, String>... headers) throws IOException {
        Map<String, String> header = (headers != null && headers.length > 0) ? headers[0] : null;
        return downloadTo(url, fileName, dir, null, false, header, null);
    }

    /**
     * 异步下载文件，提交任务后立即返回。
     *
     * @param url       文件地址
     * @param fileName_ 文件名，为空则自动解析
     * @return 固定返回 {@code null}：任务刚提交，路径尚未就绪
     * @deprecated 请使用 {@link #downloadAsync(String, File)}，本方法立即返回且拿不到路径
     */
    @Deprecated
    public static String getFileFromHttpDataByAsyn(final String url, final String fileName_) {
        downloadAsync(url, fileName_, null, null);
        return null;
    }

    /**
     * 异步下载文件到指定目录，下载结果通过回调通知。
     *
     * @param url          文件地址
     * @param fileName_    文件名，为空则自动解析
     * @param dir          保存目录
     * @param httpCallBack 下载完成或失败时的回调，可为 {@code null}
     * @deprecated 请使用 {@link #downloadAsync(String, String, String, HttpCallBack)}
     */
    @Deprecated
    public static void getFileFromHttpDataByAsyn(final String url, final String fileName_,
                                                 final String dir, final HttpCallBack<String> httpCallBack) {
        downloadAsync(url, fileName_, dir, httpCallBack);
    }

    /**
     * 通过 HEAD 请求解析远程文件名。
     *
     * @param url 地址
     * @return 文件名，失败时为空串
     */
    public static String getFileName(String url) {
        HttpRequest request = HttpRequest.head(url).headers(getDefaultHeaders());
        applyDefaults(request);
        try {
            HttpResponse response = send(request);
            try {
                return getFileName(response);
            } finally {
                response.close();
            }
        } catch (IOException e) {
            return "";
        }
    }

    /**
     * 根据响应头或 URL 获取文件名。
     *
     * @param response 响应
     * @return 文件名
     */
    public static String getFileName(HttpResponse response) {
        String name = "";
        URI uri = HttpIo.toUri(response.requestUrl());
        if (uri != null) {
            String uriPath = uri.getRawPath();
            if (uriPath != null && uriPath.contains("/")) {
                name = uriPath.substring(uriPath.lastIndexOf('/') + 1);
            } else if (uriPath != null) {
                name = uriPath;
            }
        }
        return parseFileName(response.header("Content-Disposition"), name);
    }

    /**
     * 从 {@code Content-Disposition} 头解析文件名，是全库该头解析的唯一实现。
     *
     * <p>同时支持 {@code filename=name} 和 RFC 5987 的 {@code filename*=charset''name} 两种形式，
     * 并会去掉包裹的引号、按声明的字符集做 URL 解码。
     *
     * @param contentDisposition {@code Content-Disposition} 头值，可为 {@code null}
     * @param defaultName 头中不含文件名时的回退值，可为 {@code null}
     * @return 解析出的文件名，解析不到时返回 {@code defaultName}（{@code null} 归一为空串）
     */
    public static String parseFileName(String contentDisposition, String defaultName) {
        String charset = "UTF-8";
        String name = defaultName == null ? "" : defaultName;
        if (contentDisposition != null) {
            int p1 = contentDisposition.indexOf("filename");
            if (p1 >= 0) {
                int p2 = contentDisposition.indexOf("*=", p1);
                if (p2 >= 0) {
                    int p3 = contentDisposition.indexOf("''", p2);
                    if (p3 >= 0) {
                        charset = contentDisposition.substring(p2 + 2, p3);
                    } else {
                        p3 = p2;
                    }
                    name = contentDisposition.substring(p3 + 2);
                } else {
                    p2 = contentDisposition.indexOf('=', p1);
                    if (p2 >= 0) {
                        name = contentDisposition.substring(p2 + 1);
                    }
                }
            }
        }
        name = stripQuotes(name.trim());
        try {
            name = URLDecoder.decode(name, charset);
        } catch (Exception e) {
            // 保留未解码名称
        }
        return name;
    }

    /**
     * POST JSON（或对象序列化后的 JSON）。
     *
     * @param url 服务器地址
     * @param object 要发送的对象
     * @return 响应正文
     * @throws IOException 网络错误
     */
    public static String sendRequestBody(String url, Object object) throws IOException {
        return bodyString(getResponseFromRequestBody(url, object, null, null));
    }

    /**
     * POST 请求体，指定 Content-Type。
     *
     * @param url 服务器地址
     * @param object 要发送的对象
     * @param contentType Content-Type
     * @return 响应正文
     * @throws IOException 网络错误
     */
    public static String sendRequestBody(String url, Object object, String contentType) throws IOException {
        return bodyString(getResponseFromRequestBody(url, object, null, contentType));
    }

    /**
     * POST 字符串请求体（默认 JSON）。
     *
     * @param url 服务器地址
     * @param json 正文
     * @return 响应正文
     * @throws IOException 网络错误
     */
    public static String sendRequestBody(String url, String json) throws IOException {
        return bodyString(getResponseFromRequestBody(url, json, null, null));
    }

    /**
     * POST 字符串请求体。
     *
     * @param url 服务器地址
     * @param json 正文
     * @param contentType Content-Type
     * @return 响应正文
     * @throws IOException 网络错误
     */
    public static String sendRequestBody(String url, String json, String contentType) throws IOException {
        return bodyString(getResponseFromRequestBody(url, json, null, contentType));
    }

    /**
     * POST 字符串请求体，带请求头。
     *
     * @param url 服务器地址
     * @param json 正文
     * @param header 请求头
     * @return 响应正文
     * @throws IOException 网络错误
     */
    public static String sendRequestBody(String url, String json, Map<String, String> header) throws IOException {
        return bodyString(getResponseFromRequestBody(url, json, header, null));
    }

    /**
     * POST 字符串请求体。
     *
     * @param url 服务器地址
     * @param json 正文
     * @param header 请求头
     * @param contentType Content-Type
     * @return 响应正文
     * @throws IOException 网络错误
     */
    public static String sendRequestBody(String url, String json, Map<String, String> header, String contentType)
            throws IOException {
        return bodyString(getResponseFromRequestBody(url, json, header, contentType));
    }

    /**
     * POST 对象请求体，带请求头。
     *
     * @param url 服务器地址
     * @param object 对象（序列化为 JSON）
     * @param header 请求头
     * @return 响应正文
     * @throws IOException 网络错误
     */
    public static String sendRequestBody(String url, Object object, Map<String, String> header) throws IOException {
        return bodyString(getResponseFromRequestBody(url, object, header, null));
    }

    /**
     * POST 对象请求体。
     *
     * @param url 服务器地址
     * @param object 对象（序列化为 JSON）
     * @param header 请求头
     * @param contentType Content-Type
     * @return 响应正文
     * @throws IOException 网络错误
     */
    public static String sendRequestBody(String url, Object object, Map<String, String> header, String contentType)
            throws IOException {
        return bodyString(getResponseFromRequestBody(url, object, header, contentType));
    }

    /**
     * 将对象序列化为 JSON 后发送。
     *
     * @param url 服务器地址
     * @param object 对象
     * @param header 请求头
     * @param contentType Content-Type
     * @return 响应
     * @throws IOException 网络错误
     */
    public static HttpResponse getResponseFromRequestBody(String url, Object object, Map<String, String> header,
                                                          String contentType) throws IOException {
        return getResponseFromRequestBody(url, JSON.toJsonString(object), header, contentType);
    }

    /**
     * 发送原始字符串请求体。
     *
     * @param url 服务器地址
     * @param content 正文
     * @param header 请求头
     * @param contentType Content-Type
     * @return 响应
     * @throws IOException 网络错误
     */
    public static HttpResponse getResponseFromRequestBody(String url, String content, Map<String, String> header,
                                                          String contentType) throws IOException {
        String mediaType = contentType == null ? defaultMediaType : contentType;
        Map<String, String> hdrs = header == null ? getDefaultHeaders() : header;
        byte[] body = content == null ? new byte[0] : content.getBytes(StandardCharsets.UTF_8);
        HttpRequest request = HttpRequest.post(url)
                .headers(hdrs)
                .contentType(mediaType)
                .body(body);
        applyDefaults(request);
        if (fakeIp) {
            setFakeIpHeader(request.getHeaders());
        }
        return send(request);
    }

    /**
     * 写入伪装 IP 头。
     *
     * @param headers 请求头
     */
    public static void setFakeIpHeader(Map<String, String> headers) {
        if (headers == null) {
            return;
        }
        String randomIp = RandomUtils.getRandomIp();
        headers.put("X-REAL-IP", randomIp);
        headers.put("X-FORWARDED-FOR", randomIp);
    }

    /**
     * @return 默认请求头（随机 User-Agent）
     */
    public static Map<String, String> getDefaultHeaders() {
        Map<String, String> headers = new HashMap<String, String>();
        headers.put("User-Agent", RandomUtils.getRandomUserAgent());
        return headers;
    }

    /**
     * 从响应中拼接 Cookie 的 name=value。
     *
     * @param response 响应
     * @return Cookie 串，没有时为空串
     */
    public static String getCookieValue(HttpResponse response) {
        if (response == null) {
            return "";
        }
        List<HttpCookie> cookies = HttpIo.parseCookies(response.headers("Set-Cookie"));
        if (cookies.isEmpty()) {
            return "";
        }
        StringBuilder builder = new StringBuilder();
        for (HttpCookie cookie : cookies) {
            builder.append(cookie.getName()).append('=').append(cookie.getValue()).append(';');
        }
        return builder.toString();
    }

    /**
     * 上传文件。
     *
     * @param url 上传地址
     * @param uploadInfo 文件信息
     * @return 响应
     * @throws IOException 网络或 IO 错误
     */
    public static HttpResponse upload(String url, UploadInfo uploadInfo) throws IOException {
        return upload(url, uploadInfo, null, null);
    }

    /**
     * 上传文件。
     *
     * @param url 上传地址
     * @param uploadInfo 文件信息
     * @param params 额外表单字段
     * @return 响应
     * @throws IOException 网络或 IO 错误
     */
    public static HttpResponse upload(String url, UploadInfo uploadInfo, Map<String, String> params)
            throws IOException {
        return upload(url, uploadInfo, params, null);
    }

    /**
     * 上传文件。
     *
     * @param url 上传地址
     * @param uploadInfo 文件信息
     * @param params 额外表单字段
     * @param headers 请求头
     * @return 响应
     * @throws IOException 网络或 IO 错误
     */
    public static HttpResponse upload(String url, UploadInfo uploadInfo, Map<String, String> params,
                                      Map<String, String> headers) throws IOException {
        HttpBodies.MultipartPayload payload = HttpBodies.multipart(uploadInfo, params);
        try {
            Map<String, String> merged = new LinkedHashMap<String, String>(getDefaultHeaders());
            if (headers != null) {
                merged.putAll(headers);
            }
            HttpRequest request = HttpRequest.post(url)
                    .headers(merged)
                    .contentType(payload.contentType);
            if (payload.bodyFile != null) {
                // 大文件：请求体已拼装到临时文件，由引擎流式写出，不占内存
                request.bodyFile(payload.bodyFile);
            } else {
                request.body(payload.body);
            }
            applyDefaults(request);
            return send(request);
        } finally {
            payload.cleanup();
        }
    }

    /**
     * POST JSON，等价于 {@link #sendRequestBody(String, Object)}。
     *
     * @param url 地址
     * @param object 对象
     * @return 响应正文
     * @throws IOException 网络错误
     */
    public static String postJson(String url, Object object) throws IOException {
        return sendRequestBody(url, object);
    }

    /**
     * POST JSON，带请求头。
     *
     * @param url 地址
     * @param object 对象
     * @param headers 请求头
     * @return 响应正文
     * @throws IOException 网络错误
     */
    public static String postJson(String url, Object object, Map<String, String> headers) throws IOException {
        return sendRequestBody(url, object, headers, null);
    }

    /**
     * POST 表单，等价于 {@link #getStringFromPost(String, Map)}。
     *
     * @param url 地址
     * @param form 表单
     * @return 响应正文
     * @throws IOException 网络错误
     */
    public static String postForm(String url, Map<String, Object> form) throws IOException {
        return getStringFromPost(url, form);
    }

    /**
     * POST 表单，带请求头。
     *
     * @param url 地址
     * @param form 表单
     * @param headers 请求头
     * @return 响应正文
     * @throws IOException 网络错误
     */
    public static String postForm(String url, Map<String, Object> form, Map<String, String> headers)
            throws IOException {
        return getStringFromPost(url, form, headers);
    }

    /**
     * PUT JSON。
     *
     * @param url 地址
     * @param object 对象
     * @return 响应正文
     * @throws IOException 网络错误
     */
    public static String putJson(String url, Object object) throws IOException {
        return putJson(url, object, null);
    }

    /**
     * PUT JSON，带请求头。
     *
     * @param url 地址
     * @param object 对象
     * @param headers 请求头
     * @return 响应正文
     * @throws IOException 网络错误
     */
    public static String putJson(String url, Object object, Map<String, String> headers) throws IOException {
        return bodyString(sendBody(HttpRequest.PUT, url, JSON.toJsonString(object), headers, defaultMediaType));
    }

    /**
     * PUT 原始正文。
     *
     * @param url 地址
     * @param body 正文
     * @param contentType Content-Type
     * @return 响应正文
     * @throws IOException 网络错误
     */
    public static String put(String url, String body, String contentType) throws IOException {
        return put(url, body, null, contentType);
    }

    /**
     * PUT 原始正文，带请求头。
     *
     * @param url 地址
     * @param body 正文
     * @param headers 请求头
     * @return 响应正文
     * @throws IOException 网络错误
     */
    public static String put(String url, String body, Map<String, String> headers) throws IOException {
        return put(url, body, headers, defaultMediaType);
    }

    /**
     * PUT 原始正文，带请求头与 Content-Type。
     *
     * @param url 地址
     * @param body 正文
     * @param headers 请求头
     * @param contentType Content-Type
     * @return 响应正文
     * @throws IOException 网络错误
     */
    public static String put(String url, String body, Map<String, String> headers, String contentType)
            throws IOException {
        return bodyString(sendBody(HttpRequest.PUT, url, body, headers, contentType));
    }

    /**
     * PUT，只传请求头（空 body）。
     *
     * @param url 地址
     * @param headers 请求头
     * @return 响应正文
     * @throws IOException 网络错误
     */
    public static String put(String url, Map<String, String> headers) throws IOException {
        return bodyString(sendBody(HttpRequest.PUT, url, null, headers, defaultMediaType));
    }

    /**
     * 流式 PUT 本地文件（不把整个文件读入内存）。
     *
     * @param url 地址
     * @param file 文件
     * @param contentType Content-Type
     * @return 响应
     * @throws IOException 网络或 IO 错误
     */
    public static HttpResponse putFile(String url, File file, String contentType) throws IOException {
        return putFile(url, file, contentType, null);
    }

    /**
     * 流式 PUT 本地文件，带请求头。
     *
     * @param url 地址
     * @param file 文件
     * @param contentType Content-Type
     * @param headers 请求头
     * @return 响应
     * @throws IOException 网络或 IO 错误
     */
    public static HttpResponse putFile(String url, File file, String contentType, Map<String, String> headers)
            throws IOException {
        HttpRequest request = HttpRequest.put(url)
                .headers(headers == null ? getDefaultHeaders() : headers)
                .contentType(contentType == null ? "application/octet-stream" : contentType)
                .bodyFile(file)
                .streamResponse(true);
        applyDefaults(request);
        if (fakeIp) {
            setFakeIpHeader(request.getHeaders());
        }
        return send(request);
    }

    /**
     * DELETE。
     *
     * @param url 地址
     * @return 响应正文
     * @throws IOException 网络错误
     */
    public static String delete(String url) throws IOException {
        return bodyString(deleteResponse(url, null, null, null));
    }

    /**
     * DELETE，只传请求头。
     *
     * @param url 地址
     * @param headers 请求头
     * @return 响应正文
     * @throws IOException 网络错误
     */
    public static String delete(String url, Map<String, String> headers) throws IOException {
        return bodyString(deleteResponse(url, null, headers, null));
    }

    /**
     * DELETE，带 JSON/文本 body。
     *
     * @param url 地址
     * @param body 正文
     * @return 响应正文
     * @throws IOException 网络错误
     */
    public static String delete(String url, String body) throws IOException {
        return bodyString(deleteResponse(url, body, null, defaultMediaType));
    }

    /**
     * DELETE，带 body 与 Content-Type。
     *
     * @param url 地址
     * @param body 正文
     * @param contentType Content-Type
     * @return 响应正文
     * @throws IOException 网络错误
     */
    public static String delete(String url, String body, String contentType) throws IOException {
        return bodyString(deleteResponse(url, body, null, contentType));
    }

    /**
     * DELETE，带 body 与请求头。
     *
     * @param url 地址
     * @param body 正文
     * @param headers 请求头
     * @return 响应正文
     * @throws IOException 网络错误
     */
    public static String delete(String url, String body, Map<String, String> headers) throws IOException {
        return bodyString(deleteResponse(url, body, headers, defaultMediaType));
    }

    /**
     * DELETE JSON 对象。
     *
     * @param url 地址
     * @param object 对象
     * @return 响应正文
     * @throws IOException 网络错误
     */
    public static String deleteJson(String url, Object object) throws IOException {
        return deleteJson(url, object, null);
    }

    /**
     * DELETE JSON 对象，带请求头。
     *
     * @param url 地址
     * @param object 对象
     * @param headers 请求头
     * @return 响应正文
     * @throws IOException 网络错误
     */
    public static String deleteJson(String url, Object object, Map<String, String> headers) throws IOException {
        return bodyString(deleteResponse(url, JSON.toJsonString(object), headers, defaultMediaType));
    }

    /**
     * DELETE，返回完整响应。
     *
     * @param url 地址
     * @param headers 请求头
     * @return 响应
     * @throws IOException 网络错误
     */
    public static HttpResponse deleteResponse(String url, Map<String, String> headers) throws IOException {
        return deleteResponse(url, null, headers, null);
    }

    /**
     * DELETE，可带 body、请求头与 Content-Type。
     *
     * @param url 地址
     * @param body 正文，可为 {@code null}
     * @param headers 请求头
     * @param contentType Content-Type
     * @return 响应
     * @throws IOException 网络错误
     */
    public static HttpResponse deleteResponse(String url, String body, Map<String, String> headers,
                                              String contentType) throws IOException {
        return sendBody(HttpRequest.DELETE, url, body, headers, contentType);
    }

    /**
     * PATCH JSON。
     *
     * @param url 地址
     * @param object 对象
     * @return 响应正文
     * @throws IOException 网络错误
     */
    public static String patchJson(String url, Object object) throws IOException {
        return patchJson(url, object, null);
    }

    /**
     * PATCH JSON，带请求头。
     *
     * @param url 地址
     * @param object 对象
     * @param headers 请求头
     * @return 响应正文
     * @throws IOException 网络错误
     */
    public static String patchJson(String url, Object object, Map<String, String> headers) throws IOException {
        return bodyString(sendBody(HttpRequest.PATCH, url, JSON.toJsonString(object), headers, defaultMediaType));
    }

    /**
     * PATCH 原始正文。
     *
     * @param url 地址
     * @param body 正文
     * @param contentType Content-Type
     * @return 响应正文
     * @throws IOException 网络错误
     */
    public static String patch(String url, String body, String contentType) throws IOException {
        return patch(url, body, null, contentType);
    }

    /**
     * PATCH 原始正文，带请求头。
     *
     * @param url 地址
     * @param body 正文
     * @param headers 请求头
     * @param contentType Content-Type
     * @return 响应正文
     * @throws IOException 网络错误
     */
    public static String patch(String url, String body, Map<String, String> headers, String contentType)
            throws IOException {
        return bodyString(sendBody(HttpRequest.PATCH, url, body, headers, contentType));
    }

    /**
     * 流式 GET，适合大响应。调用方必须 {@link HttpResponse#close()}。
     *
     * @param url 地址
     * @return 响应（正文为流）
     * @throws IOException 网络错误
     */
    public static HttpResponse openStream(String url) throws IOException {
        return openStream(url, null, null);
    }

    /**
     * 流式 GET，只传请求头。
     *
     * @param url 地址
     * @param headers 请求头
     * @return 响应
     * @throws IOException 网络错误
     */
    public static HttpResponse openStream(String url, Map<String, String> headers) throws IOException {
        return openStream(url, null, headers);
    }

    /**
     * 流式 GET，可带 query 与请求头。
     *
     * @param url 地址
     * @param params 查询参数
     * @param headers 请求头
     * @return 响应
     * @throws IOException 网络错误
     */
    public static HttpResponse openStream(String url, Map<String, Object> params, Map<String, String> headers)
            throws IOException {
        if (url == null) {
            throw new RuntimeException("the request URL can not be null");
        }
        url = appendQuery(url, params);
        HttpRequest request = HttpRequest.get(url)
                .headers(headers == null ? getDefaultHeaders() : headers)
                .streamResponse(true);
        applyDefaults(request);
        if (fakeIp) {
            setFakeIpHeader(request.getHeaders());
        }
        return send(request);
    }

    /**
     * 执行 curl 请求
     *
     * @param request curl 请求
     * @return {@link HttpResponse }
     * @throws IOException ioexception
     */
    public static HttpResponse execute(CurlRequest request) throws IOException {
        return execute(request.toHttpRequest());
    }
    /**
     * 执行自定义请求（超时/HTTP2 等已在 request 上设置的不再覆盖）。
     *
     * @param request 请求
     * @return 响应
     * @throws IOException 网络错误
     */
    public static HttpResponse execute(HttpRequest request) throws IOException {
        if (request.getHeaders().isEmpty()) {
            request.headers(getDefaultHeaders());
        }
        if (fakeIp) {
            setFakeIpHeader(request.getHeaders());
        }
        applyDefaults(request);
        return send(request);
    }

    /**
     * 订阅 SSE，在后台线程阻塞读取直到结束或 {@link HttpCall#cancel()}。
     *
     * @param url 事件流地址
     * @param listener 回调
     * @return 可用于取消的调用句柄
     */
    public static HttpCall sse(String url, SseListener listener) {
        return sse(url, null, listener);
    }

    /**
     * 订阅 SSE（GET，可带请求头）。
     *
     * @param url 事件流地址
     * @param headers 请求头
     * @param listener 回调
     * @return 调用句柄
     */
    public static HttpCall sse(String url, Map<String, String> headers, SseListener listener) {
        return sse(url, null, headers, null, null, listener);
    }

    /**
     * 订阅 SSE（GET + query）。
     *
     * @param url 地址
     * @param params 查询参数
     * @param headers 请求头
     * @param listener 回调
     * @return 调用句柄
     */
    public static HttpCall sse(String url, Map<String, Object> params, Map<String, String> headers,
                               SseListener listener) {
        return sse(url, params, headers, null, null, listener);
    }

    /**
     * 订阅 SSE，POST 字符串 body（默认 JSON Content-Type）。
     *
     * @param url 地址
     * @param headers 请求头
     * @param body 请求体
     * @param listener 回调
     * @return 调用句柄
     */
    public static HttpCall sse(String url, Map<String, String> headers, String body, SseListener listener) {
        return sse(url, null, headers, body, defaultMediaType, listener);
    }

    /**
     * 订阅 SSE，POST 字符串 body 并指定 Content-Type。
     *
     * @param url 地址
     * @param headers 请求头
     * @param body 请求体
     * @param contentType Content-Type
     * @param listener 回调
     * @return 调用句柄
     */
    public static HttpCall sse(String url, Map<String, String> headers, String body, String contentType,
                               SseListener listener) {
        return sse(url, null, headers, body, contentType, listener);
    }

    /**
     * 订阅 SSE，POST JSON 对象。
     *
     * @param url 地址
     * @param body 对象（序列化为 JSON）
     * @param listener 回调
     * @return 调用句柄
     */
    public static HttpCall sseJson(String url, Object body, SseListener listener) {
        return sseJson(url, body, null, listener);
    }

    /**
     * 订阅 SSE，POST JSON 对象，带请求头。
     *
     * @param url 地址
     * @param body 对象
     * @param headers 请求头
     * @param listener 回调
     * @return 调用句柄
     */
    public static HttpCall sseJson(String url, Object body, Map<String, String> headers, SseListener listener) {
        String json = "{}";
        if (body != null) {
            if (body instanceof CharSequence) {
                String str = body.toString();
                if (JSON.validate(str)) {
                    json = str;
                } else {
                    // 可能是其他格式的字符串，如 text/plain 或者 xml
                    json = str;
                }
            } else {
                json = JSON.toJsonString(body);
            }

        }
        return sse(url, null, headers, json, defaultMediaType, listener);
    }

    /**
     * 订阅 SSE：GET（无 body）或 POST（有 body），可带 query。
     *
     * @param url 地址
     * @param params 查询参数
     * @param headers 请求头
     * @param body 请求体，为 {@code null} 时用 GET
     * @param contentType body 的 Content-Type
     * @param listener 回调
     * @return 调用句柄
     */
    public static HttpCall sse(String url, Map<String, Object> params, Map<String, String> headers,
                               String body, String contentType, final SseListener listener) {
        return sse(buildSseHttpRequest(url, params, headers, body, contentType), listener);
    }

    /**
     * 订阅 SSE：使用已构造好的 {@link HttpRequest}（可自定义方法、头、正文、超时、代理等）。
     * 适合从 {@link #curlToRequest(String)} / {@link CurlRequest#toHttpRequest()} 转换后再发 SSE。
     * 不会改动传入的 request；发送前会补齐 {@code Accept: text/event-stream}，
     * 并强制流式读取、读超时为 0。
     *
     * @param request 请求
     * @param listener 回调
     * @return 调用句柄
     */
    public static HttpCall sse(HttpRequest request, final SseListener listener) {
        if (listener == null) {
            throw new IllegalArgumentException("sse listener is required");
        }
        final HttpRequest sseRequest = prepareSseRequest(request);
        final HttpCall call = new HttpCall(sseRequest);
        asyncExecutor().execute(() -> {
            HttpResponse response = null;
            try {
                response = send(sseRequest);
                listener.onOpen(response);
                InputStream in = response.body() == null ? null : response.body().byteStream();
                SseClient.parse(in, listener, call);
            } catch (IOException e) {
                listener.onError(e);
            } finally {
                if (response != null) {
                    response.close();
                }
                listener.onClosed();
            }
        });
        return call;
    }

    /**
     * 订阅 SSE：直接使用 curl 解析结果。
     *
     * @param curl curl 解析结果
     * @param listener 回调
     * @return 调用句柄
     */
    public static HttpCall sse(CurlRequest curl, SseListener listener) {
        if (curl == null) {
            throw new IllegalArgumentException("sse request is required");
        }
        return sse(curl.toHttpRequest(), listener);
    }

    /**
     * SSE 自动重连：连接中断或服务端关闭流时按指数退避重连，
     * 重连请求自动携带 {@code Last-Event-ID} 头，可续传事件。
     *
     * @param url 地址
     * @param listener 事件回调
     * @return 调用句柄，调用 {@link HttpCall#cancel()} 可停止重连
     */
    public static HttpCall sseReconnect(String url, SseListener listener) {
        return sseReconnect(url, null, null, null, SseReconnectOptions.builder().build(), listener);
    }

    /**
     * SSE 自动重连（自定义重连选项）。
     *
     * @param url 地址
     * @param options 重连选项
     * @param listener 事件回调
     * @return 调用句柄
     */
    public static HttpCall sseReconnect(String url, SseReconnectOptions options, SseListener listener) {
        return sseReconnect(url, null, null, null, options, listener);
    }

    /**
     * SSE 自动重连（完整参数）。
     *
     * @param url 地址
     * @param headers 请求头
     * @param body 请求体，非空时用 POST
     * @param contentType 请求体类型
     * @param options 重连选项，{@code null} 时用默认值
     * @param listener 事件回调
     * @return 调用句柄
     */
    public static HttpCall sseReconnect(String url, Map<String, String> headers, String body,
                                        String contentType, SseReconnectOptions options,
                                        final SseListener listener) {
        return sseReconnect(buildSseHttpRequest(url, null, headers, body, contentType), options, listener);
    }

    /**
     * SSE 自动重连：使用已构造好的 {@link HttpRequest} 作为每次连接的模板。
     *
     * @param request 请求模板，不会被改动
     * @param listener 事件回调
     * @return 调用句柄
     */
    public static HttpCall sseReconnect(HttpRequest request, SseListener listener) {
        return sseReconnect(request, SseReconnectOptions.builder().build(), listener);
    }

    /**
     * SSE 自动重连：使用已构造好的 {@link HttpRequest} 作为每次连接的模板。
     * 重连时在副本上追加 {@code Last-Event-ID}，保留原请求的方法、头、正文、代理等。
     *
     * @param request 请求模板，不会被改动
     * @param options 重连选项，{@code null} 时用默认值
     * @param listener 事件回调
     * @return 调用句柄
     */
    public static HttpCall sseReconnect(HttpRequest request, SseReconnectOptions options,
                                        final SseListener listener) {
        if (listener == null) {
            throw new IllegalArgumentException("sse listener is required");
        }
        if (request == null) {
            throw new IllegalArgumentException("sse request is required");
        }
        if (request.getUrl() == null) {
            throw new RuntimeException("the request URL can not be null");
        }
        final HttpRequest template = request;
        final SseReconnectOptions opts = options == null
                ? SseReconnectOptions.builder().build() : options;
        final HttpCall call = new HttpCall(template);
        asyncExecutor().execute(() -> {
            int attempt = 0;
            long serverRetryMs = -1L;
            Throwable lastCause = null;
            String[] lastEventId = {null};
            try {
                while (!call.isCanceled()) {
                    HttpRequest sseRequest = prepareSseRequest(template);
                    if (opts.isSendLastEventId() && lastEventId[0] != null
                            && !lastEventId[0].isEmpty()) {
                        sseRequest.header("Last-Event-ID", lastEventId[0]);
                    }
                    HttpResponse response = null;
                    boolean streamFailed = false;
                    long[] retryHolder = {-1L};
                    try {
                        response = send(sseRequest);
                        listener.onOpen(response);
                        InputStream in = response.body() == null
                                ? null : response.body().byteStream();
                        SseClient.parse(in,
                                eventTrackingListener(listener, lastEventId, retryHolder), call);
                    } catch (IOException e) {
                        streamFailed = true;
                        lastCause = e;
                        listener.onError(e);
                    } finally {
                        if (response != null) {
                            response.close();
                        }
                    }
                    if (retryHolder[0] > 0) {
                        serverRetryMs = retryHolder[0];
                    }
                    if (call.isCanceled()) {
                        break;
                    }
                    if (!streamFailed && !opts.isReconnectOnStreamEnd()) {
                        break;
                    }
                    attempt++;
                    if (opts.getMaxRetries() >= 0 && attempt > opts.getMaxRetries()) {
                        break;
                    }
                    long delay = backoffDelay(opts, attempt, serverRetryMs);
                    listener.onReconnect(attempt, delay, lastCause);
                    if (!sleepWhileActive(call, delay)) {
                        break;
                    }
                }
            } finally {
                listener.onClosed();
            }
        });
        return call;
    }

    /**
     * SSE 自动重连：直接使用 curl 解析结果作为请求模板。
     *
     * @param curl curl 解析结果
     * @param listener 事件回调
     * @return 调用句柄
     */
    public static HttpCall sseReconnect(CurlRequest curl, SseListener listener) {
        return sseReconnect(curl, null, listener);
    }

    /**
     * SSE 自动重连：直接使用 curl 解析结果作为请求模板。
     *
     * @param curl curl 解析结果
     * @param options 重连选项
     * @param listener 事件回调
     * @return 调用句柄
     */
    public static HttpCall sseReconnect(CurlRequest curl, SseReconnectOptions options,
                                        SseListener listener) {
        if (curl == null) {
            throw new IllegalArgumentException("sse request is required");
        }
        return sseReconnect(curl.toHttpRequest(), options, listener);
    }

    private static HttpRequest buildSseHttpRequest(String url, Map<String, Object> params,
                                                   Map<String, String> headers, String body,
                                                   String contentType) {
        if (url == null) {
            throw new RuntimeException("the request URL can not be null");
        }
        url = appendQuery(url, params);
        Map<String, String> hdrs = headers == null
                ? getDefaultHeaders() : new LinkedHashMap<String, String>(headers);
        if (body != null) {
            return HttpRequest.post(url)
                    .headers(hdrs)
                    .contentType(contentType == null ? defaultMediaType : contentType)
                    .body(body.getBytes(StandardCharsets.UTF_8));
        }
        return HttpRequest.get(url).headers(hdrs);
    }

    private static HttpRequest prepareSseRequest(HttpRequest source) {
        if (source == null) {
            throw new IllegalArgumentException("sse request is required");
        }
        if (source.getUrl() == null) {
            throw new RuntimeException("the request URL can not be null");
        }
        HttpRequest request = source.copy();
        if (request.getHeaders().isEmpty()) {
            request.headers(getDefaultHeaders());
        }
        if (!containsIgnoreCase(request.getHeaders(), "Accept")) {
            request.header("Accept", "text/event-stream");
        }
        request.header("Cache-Control", "no-cache");
        request.streamResponse(true);
        applyDefaults(request);
        request.readTimeoutMs(0);
        if (fakeIp) {
            setFakeIpHeader(request.getHeaders());
        }
        return request;
    }

    private static SseListener mergeSseListener(final SseMergeFormat format,
                                                final SseMergeListener listener) {
        final SseMerger merger = new SseMerger(format);
        return new SseListener() {
            @Override
            public void onOpen(HttpResponse response) {
                listener.onOpen(response);
            }

            @Override
            public void onEvent(SseEvent event) {
                listener.onEvent(event);
                SseMergeResult snapshot = merger.accept(event);
                if (snapshot.isDone() || snapshot.getError() != null
                        || !snapshot.getContentDelta().isEmpty()
                        || !snapshot.getThinkingDelta().isEmpty()
                        || !snapshot.getToolCallDelta().isEmpty()) {
                    listener.onDelta(snapshot);
                }
            }

            @Override
            public void onError(IOException e) {
                listener.onError(e);
            }

            @Override
            public void onClosed() {
                listener.onComplete(merger.snapshot());
            }
        };
    }

    private static SseListener eventTrackingListener(final SseListener delegate,
                                                     final String[] lastEventId,
                                                     final long[] retryHolder) {
        return new SseListener() {
            @Override
            public void onOpen(HttpResponse response) {
                delegate.onOpen(response);
            }

            @Override
            public void onEvent(SseEvent event) {
                if (event.getId() != null && !event.getId().isEmpty()) {
                    lastEventId[0] = event.getId();
                }
                if (event.getRetryMs() > 0) {
                    retryHolder[0] = event.getRetryMs();
                }
                delegate.onEvent(event);
            }

            @Override
            public void onComment(String comment) {
                delegate.onComment(comment);
            }
        };
    }

    private static long backoffDelay(SseReconnectOptions options, int attempt, long serverRetryMs) {
        double backoff = options.getInitialBackoffMs() * Math.pow(options.getMultiplier(), attempt - 1);
        long delay = (long) Math.min(backoff, options.getMaxBackoffMs());
        if (options.isHonorServerRetry() && serverRetryMs > delay) {
            delay = serverRetryMs;
        }
        return Math.max(0, delay);
    }

    private static boolean sleepWhileActive(HttpCall call, long delayMs) {
        long remaining = delayMs;
        while (remaining > 0 && !call.isCanceled()) {
            long step = Math.min(200L, remaining);
            try {
                Thread.sleep(step);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
            remaining -= step;
        }
        return !call.isCanceled();
    }

    /**
     * SSE 自动合并（OpenAI / Gemini / Claude / Ollama / JSONPath / 原文）。
     *
     * @param url 地址
     * @param format 合并格式
     * @param listener 实时回调 {@link SseMergeListener#onDelta(SseMergeResult)}
     * @return 调用句柄
     */
    public static HttpCall sseMerge(String url, SseMergeFormat format, SseMergeListener listener) {
        return sseMerge(url, null, null, null, null, format, listener);
    }

    /**
     * SSE 自动合并，带请求头。
     *
     * @param url 地址
     * @param headers 请求头
     * @param format 格式
     * @param listener 回调
     * @return 调用句柄
     */
    public static HttpCall sseMerge(String url, Map<String, String> headers, SseMergeFormat format,
                                    SseMergeListener listener) {
        return sseMerge(url, null, headers, null, null, format, listener);
    }

    /**
     * SSE 自动合并，POST JSON。
     *
     * @param url 地址
     * @param body JSON 对象
     * @param format 格式
     * @param listener 回调
     * @return 调用句柄
     */
    public static HttpCall sseMergeJson(String url, Object body, SseMergeFormat format,
                                        SseMergeListener listener) {
        return sseMergeJson(url, body, null, format, listener);
    }

    /**
     * SSE 自动合并，POST JSON 带请求头。
     *
     * @param url 地址
     * @param body JSON 对象
     * @param headers 请求头
     * @param format 格式
     * @param listener 回调
     * @return 调用句柄
     */
    public static HttpCall sseMergeJson(String url, Object body, Map<String, String> headers,
                                        SseMergeFormat format, SseMergeListener listener) {
        return sseMerge(url, null, headers, JSON.toJsonString(body), defaultMediaType, format, listener);
    }

    /**
     * SSE 自动合并，可带 query、请求头与 body。
     *
     * @param url 地址
     * @param params 查询参数
     * @param headers 请求头
     * @param body 请求体，为 {@code null} 时 GET
     * @param contentType Content-Type
     * @param format 合并格式
     * @param listener 回调
     * @return 调用句柄
     */
    public static HttpCall sseMerge(String url, Map<String, Object> params, Map<String, String> headers,
                                    String body, String contentType, final SseMergeFormat format,
                                    final SseMergeListener listener) {
        if (format == null) {
            throw new IllegalArgumentException("sse merge format is required");
        }
        if (listener == null) {
            throw new IllegalArgumentException("sse merge listener is required");
        }
        return sse(url, params, headers, body, contentType, mergeSseListener(format, listener));
    }

    /**
     * SSE 自动合并：使用已构造好的 {@link HttpRequest}。
     *
     * @param request 请求，不会被改动
     * @param format 合并格式
     * @param listener 实时回调
     * @return 调用句柄
     */
    public static HttpCall sseMerge(HttpRequest request, SseMergeFormat format,
                                    SseMergeListener listener) {
        if (format == null) {
            throw new IllegalArgumentException("sse merge format is required");
        }
        if (listener == null) {
            throw new IllegalArgumentException("sse merge listener is required");
        }
        return sse(request, mergeSseListener(format, listener));
    }

    /**
     * SSE 自动合并：直接使用 curl 解析结果。
     *
     * @param curl curl 解析结果
     * @param format 合并格式
     * @param listener 实时回调
     * @return 调用句柄
     */
    public static HttpCall sseMerge(CurlRequest curl, SseMergeFormat format,
                                    SseMergeListener listener) {
        if (curl == null) {
            throw new IllegalArgumentException("sse request is required");
        }
        return sseMerge(curl.toHttpRequest(), format, listener);
    }

    /**
     * 将 HttpRequest 转为可复现的 POSIX shell curl 命令。
     *
     * @param request 请求模型
     * @return curl 命令文本
     */
    public static String requestToCurl(HttpRequest request) {
        return CurlRequest.toCurl(request);
    }

    /**
     * 解析 curl 命令。
     *
     * @param curl 命令文本
     * @return 请求模型
     */
    public static CurlRequest parseCurl(String curl) {
        return CurlParser.parse(curl);
    }

    /**
     * 将 curl 转为 {@link HttpRequest}。
     *
     * @param curl 命令文本
     * @return HTTP 请求
     */
    public static HttpRequest curlToRequest(String curl) {
        return CurlParser.parse(curl).toHttpRequest();
    }

    /**
     * 解析 curl 并立即执行。
     *
     * @param curl 命令文本
     * @return 响应
     * @throws IOException 网络错误
     */
    public static HttpResponse curl(String curl) throws IOException {
        return CurlParser.parse(curl).execute();
    }

    /**
     * 解析 curl 并执行，返回正文。
     *
     * @param curl 命令文本
     * @return 响应正文
     * @throws IOException 网络错误
     */
    public static String curlString(String curl) throws IOException {
        return CurlParser.parse(curl).executeString();
    }

    /**
     * 建立 WebSocket（需要 JDK 11+ {@code java.net.http.WebSocket}）。
     *
     * @param url {@code ws://} 或 {@code wss://}
     * @param listener 回调
     * @return 会话
     * @throws IOException 握手失败或当前 JDK 不支持
     */
    public static WebSocketSession webSocket(String url, WebSocketListener listener) throws IOException {
        return webSocket(url, null, listener);
    }

    /**
     * 建立 WebSocket。
     *
     * @param url 地址
     * @param headers 握手头
     * @param listener 回调
     * @return 会话
     * @throws IOException 握手失败或当前 JDK 不支持
     */
    public static WebSocketSession webSocket(String url, Map<String, String> headers, WebSocketListener listener)
            throws IOException {
        try {
            Class<?> clazz = Class.forName("com.alianga.jkit.http.JdkWebSocket");
            java.lang.reflect.Method method = clazz.getMethod("connect", String.class, Map.class,
                    boolean.class, Proxy.class, int.class, WebSocketListener.class);
            Map<String, String> hdrs = headers == null ? getDefaultHeaders() : headers;
            return (WebSocketSession) method.invoke(null, url, hdrs, ignoreSSL, proxy,
                    config().getConnectTimeoutMs(), listener);
        } catch (ClassNotFoundException e) {
            throw new IOException("WebSocket requires JDK 11+ java.net.http.HttpClient", e);
        } catch (ReflectiveOperationException e) {
            Throwable cause = e.getCause() == null ? e : e.getCause();
            if (cause instanceof IOException) {
                throw (IOException) cause;
            }
            throw new IOException("websocket connect failed: " + cause.getMessage(), cause);
        }
    }

    private static HttpResponse sendBody(String method, String url, String body, Map<String, String> headers,
                                         String contentType) throws IOException {
        Map<String, String> hdrs = headers == null ? getDefaultHeaders() : headers;
        HttpRequest request = new HttpRequest(method, url).headers(hdrs);
        if (contentType != null) {
            request.contentType(contentType);
        }
        if (body != null) {
            request.body(body.getBytes(StandardCharsets.UTF_8));
        }
        applyDefaults(request);
        if (fakeIp) {
            setFakeIpHeader(request.getHeaders());
        }
        return send(request);
    }

    static HttpResponse send(HttpRequest request) throws IOException {
        long startNanos = System.nanoTime();
        List<HttpInterceptor> interceptors = config().getInterceptors();
        HttpResponse response;
        if (interceptors.isEmpty()) {
            response = sendWithRetry(request, deadlineOf(request));
        } else {
            response = new InterceptorChain(interceptors, 0, deadlineOf(request)).proceed(request);
        }
        if (response != null) {
            response.elapsedMs((System.nanoTime() - startNanos) / 1_000_000L);
        }
        return response;
    }

    /**
     * 计算本次调用的截止时间戳（纳秒，{@code System.nanoTime()} 基准）。
     *
     * @return 截止时间；未设置总时长上限时返回 {@code 0}
     */
    private static long deadlineOf(HttpRequest request) {
        long total = request.getTotalTimeoutMs() > 0
                ? request.getTotalTimeoutMs()
                : config().getTotalTimeoutMs();
        return total <= 0L ? 0L : System.nanoTime() + total * 1_000_000L;
    }

    /**
     * 剩余可用毫秒数。{@code deadline == 0} 表示不限制，返回 {@link Long#MAX_VALUE}。
     */
    private static long remainingMs(long deadlineNanos) {
        if (deadlineNanos == 0L) {
            return Long.MAX_VALUE;
        }
        return (deadlineNanos - System.nanoTime()) / 1_000_000L;
    }

    private static void checkDeadline(long deadlineNanos, String url) throws IOException {
        if (deadlineNanos != 0L && remainingMs(deadlineNanos) <= 0L) {
            throw new IOException("total timeout exceeded for " + url);
        }
    }

    /**
     * 把单次尝试的 connect/read 超时收敛到剩余总时长以内，避免单次超时把总预算撑爆。
     */
    private static void clampTimeouts(HttpRequest request, long deadlineNanos) {
        if (deadlineNanos == 0L) {
            return;
        }
        long remaining = remainingMs(deadlineNanos);
        if (remaining <= 0L) {
            return;
        }
        int budget = (int) Math.min(Integer.MAX_VALUE, remaining);
        if (request.getConnectTimeoutMs() <= 0 || request.getConnectTimeoutMs() > budget) {
            request.connectTimeoutMs(budget);
        }
        if (request.getReadTimeoutMs() <= 0 || request.getReadTimeoutMs() > budget) {
            request.readTimeoutMs(budget);
        }
    }

    /**
     * 真正的发送逻辑：负载均衡端点选择 + 故障转移 + 重试 + 重定向跟随 + Cookie 存取。
     * 拦截器链的最内层。
     */
    private static HttpResponse sendWithRetry(HttpRequest request, long deadlineNanos) throws IOException {
        prepareForSend(request);
        EndpointPool pool = resolvePool(request);
        if (pool == null) {
            return sendWithRetryDirect(request, deadlineNanos);
        }
        return sendWithLoadBalance(request, pool, deadlineNanos);
    }

    /**
     * 选出本次请求适用的端点池：请求级优先；全局池要么没有 serviceName（对所有请求生效），
     * 要么 URL 主机名与 serviceName 相同（{@code http://orders/api} 这种写法）。
     * <p>
     * 用"主机名等于服务名"作为开关，好处是接入负载均衡不用改任何调用代码，
     * 而写了真实主机或端口的请求会原样直连——这是明确的逃生门。
     */
    private static EndpointPool resolvePool(HttpRequest request) {
        if (request.isBypassLoadBalance()) {
            return null;
        }
        EndpointPool perRequest = request.getEndpointPool();
        if (perRequest != null) {
            return perRequest;
        }
        EndpointPool global = config().getEndpointPool();
        if (global == null) {
            return null;
        }
        String serviceName = global.getServiceName();
        if (serviceName == null) {
            return global;
        }
        String host = HttpIo.hostOf(request.getUrl());
        return serviceName.equals(host) ? global : null;
    }

    /**
     * 带负载均衡的发送：每次尝试挑一个端点，端点级失败自动换端点重试。
     * <p>
     * 尝试次数取 {@code max(重试策略次数, min(端点数, 上限))}，让"每个端点至少有机会被试一次"，
     * 同时仍受总 deadline 约束，不会因为端点多而无限拖长。
     */
    private static HttpResponse sendWithLoadBalance(HttpRequest request, EndpointPool pool,
                                                    long deadlineNanos) throws IOException {
        RetryPolicy policy = config().getRetryPolicy();
        boolean countReadTimeout = config().isCountReadTimeoutAsEndpointFailure();
        int endpointCount = Math.max(1, pool.endpoints().size());
        int maxAttempts = Math.max(policy.getMaxAttempts(),
                Math.min(endpointCount, MAX_FAILOVER_ATTEMPTS));
        Set<String> tried = new LinkedHashSet<String>();
        String originalUrl = request.getUrl();
        IOException last = null;
        Endpoint previous = null;
        int attempt = 0;
        while (attempt < maxAttempts) {
            attempt++;
            checkDeadline(deadlineNanos, originalUrl);
            EndpointPool.Lease lease = pool.acquire(tried, request.getLoadBalanceKey());
            Endpoint endpoint = lease.endpoint();
            tried.add(endpoint.getBaseUrl());
            if (previous != null) {
                pool.notifyFailover(previous, endpoint, last == null ? "retry" : String.valueOf(last));
            }
            HttpRequest attemptRequest = rewriteForEndpoint(request, originalUrl, endpoint, pool);
            HttpResponse response = null;
            boolean handedOff = false;
            try {
                response = sendFollowingRedirects(attemptRequest, deadlineNanos);
                int code = response.code();
                if (RetryPolicy.isEndpointFailure(code)) {
                    lease.failure("HTTP " + code);
                    boolean canRetry = policy.shouldRetry(request, code);
                    boolean canFailover = tried.size() < endpointCount;
                    if (attempt < maxAttempts && (canRetry || canFailover)) {
                        long delay = policy.retryDelayMs(attempt, response);
                        response.close();
                        previous = endpoint;
                        last = new IOException("endpoint " + endpoint.getBaseUrl()
                                + " returned HTTP " + code);
                        sleepBeforeRetry(delay, deadlineNanos);
                        continue;
                    }
                } else if (response.isSuccessful()) {
                    lease.success();
                } else {
                    // 业务 4xx：请求本身的问题，不是节点故障，绝不能计入熔断
                    lease.neutral();
                    if (attempt < maxAttempts && policy.shouldRetry(request, code)) {
                        long delay = policy.retryDelayMs(attempt, response);
                        response.close();
                        previous = endpoint;
                        sleepBeforeRetry(delay, deadlineNanos);
                        continue;
                    }
                }
                handedOff = true;
                return response.attempts(attempt).endpointBaseUrl(endpoint.getBaseUrl());
            } catch (IOException e) {
                if (response != null) {
                    response.close();
                }
                if (RetryPolicy.isEndpointFailure(e, countReadTimeout)) {
                    lease.failure(String.valueOf(e));
                } else {
                    lease.neutral();
                }
                last = e;
                boolean canRetry = policy.shouldRetry(request, e);
                boolean moreEndpoints = tried.size() < endpointCount;
                if (attempt >= maxAttempts || !(canRetry || moreEndpoints)) {
                    throw e;
                }
                previous = endpoint;
                sleepBeforeRetry(policy.backoffMs(attempt), deadlineNanos);
            } finally {
                // 只释放在途占用；结果早已通过 success/failure/neutral 上报
                lease.close();
                if (!handedOff && response != null && response.body() == null) {
                    response.close();
                }
            }
        }
        throw last == null ? new IOException("HTTP request failed") : last;
    }

    /**
     * 把请求的 origin 换成选中的端点，路径 / 查询 / 片段保持不动。
     * <p>
     * 同时按需保留原始 {@code Host} 头：服务发现给出的是 IP，直接改写 URL 会让上游
     * 收到 {@code Host: 10.0.0.7:8080}，按域名路由的网关会 404 或走错后端。
     */
    private static HttpRequest rewriteForEndpoint(HttpRequest request, String originalUrl,
                                                  Endpoint endpoint, EndpointPool pool) {
        String rewritten = HttpIo.replaceOrigin(originalUrl, endpoint.getBaseUrl());
        if (rewritten == null || rewritten.equals(originalUrl)) {
            return request;
        }
        HttpRequest copy = request.copy().url(rewritten);
        if (shouldPreserveHost(pool, copy, originalUrl, endpoint)) {
            copy.header("Host", HttpIo.authorityOf(originalUrl));
        }
        return copy;
    }

    /**
     * 是否需要把原始 {@code Host} 写回请求头。
     * <p>
     * 只在端点是 <b>IP 字面量</b>时才保留：那种情况下 URL 里的原始主机名（逻辑服务名或真实域名）
     * 才是上游做虚拟主机 / 网关路由的依据。如果端点本身就是域名，正确的 Host 就是该端点自己的域名，
     * 硬写回原始主机名反而会让上游路由到错误的后端。
     * <p>
     * 这样语义不再依赖 {@code jdk.httpclient.allowRestrictedHeaders} 这个启动参数是否打开。
     */
    private static boolean shouldPreserveHost(EndpointPool pool, HttpRequest request,
                                              String originalUrl, Endpoint endpoint) {
        if (!pool.isPreserveHostHeader() || request.getHeader("Host") != null) {
            return false;
        }
        String originalAuthority = HttpIo.authorityOf(originalUrl);
        if (originalAuthority == null) {
            return false;
        }
        String endpointHost = HttpIo.hostOf(endpoint.getBaseUrl());
        if (endpointHost == null || !HttpIo.isIpLiteral(endpointHost)) {
            return false;
        }
        return !originalAuthority.equalsIgnoreCase(HttpIo.authorityOf(request.getUrl()));
    }

    /**
     * 不走负载均衡时的发送：仅重试 + 重定向跟随。
     */
    private static HttpResponse sendWithRetryDirect(HttpRequest request, long deadlineNanos)
            throws IOException {
        RetryPolicy policy = config().getRetryPolicy();
        IOException last = null;
        int attempt = 0;
        while (attempt < policy.getMaxAttempts()) {
            attempt++;
            checkDeadline(deadlineNanos, request.getUrl());
            HttpResponse response = null;
            try {
                response = sendFollowingRedirects(request, deadlineNanos);
                if (attempt < policy.getMaxAttempts()
                        && policy.shouldRetry(request, response.code())) {
                    long delay = policy.retryDelayMs(attempt, response);
                    response.close();
                    sleepBeforeRetry(delay, deadlineNanos);
                    continue;
                }
                return response.attempts(attempt);
            } catch (IOException e) {
                if (response != null) {
                    response.close();
                }
                last = e;
                if (attempt >= policy.getMaxAttempts() || !policy.shouldRetry(request, e)) {
                    throw e;
                }
                sleepBeforeRetry(policy.backoffMs(attempt), deadlineNanos);
            }
        }
        throw last == null ? new IOException("HTTP request failed") : last;
    }

    /**
     * 发送并自行跟随重定向。
     * <p>
     * 之所以不交给引擎跟随：引擎内部跟随时，中间跳的 {@code Set-Cookie} 不会进 CookieJar，
     * "登录后 302"这类流程会丢会话 cookie；而且调用方拿不到最终落地的 URL。
     */
    private static HttpResponse sendFollowingRedirects(HttpRequest request, long deadlineNanos)
            throws IOException {
        int maxRedirects = request.isFollowRedirects() ? config().getMaxRedirects() : 0;
        HttpRequest current = request;
        String currentUrl = request.getUrl();
        for (int hop = 0; ; hop++) {
            checkDeadline(deadlineNanos, currentUrl);
            HttpRequest attemptRequest = current == request ? request : current;
            // 引擎不跟随重定向，由本方法逐跳处理
            boolean restoreFollow = attemptRequest.isFollowRedirects();
            attemptRequest.followRedirects(false);
            clampTimeouts(attemptRequest, deadlineNanos);
            HttpResponse response;
            try {
                response = getHttpEngine().execute(attemptRequest);
            } finally {
                attemptRequest.followRedirects(restoreFollow);
            }
            storeCookies(response);
            String location = redirectLocation(response, maxRedirects, hop);
            if (location == null) {
                return response;
            }
            String nextUrl = HttpIo.resolveRedirect(currentUrl, location);
            if (nextUrl == null) {
                return response;
            }
            int code = response.code();
            response.close();
            current = redirectRequest(current, nextUrl, code);
            currentUrl = nextUrl;
            prepareForSend(current);
        }
    }

    /**
     * 判断是否需要继续跟随重定向，返回 Location 头，无需跟随时返回 {@code null}。
     */
    private static String redirectLocation(HttpResponse response, int maxRedirects, int hop) {
        int code = response.code();
        boolean redirect = code == 301 || code == 302 || code == 303 || code == 307 || code == 308;
        if (!redirect || hop >= maxRedirects) {
            return null;
        }
        String location = response.header("Location");
        return location == null || location.trim().isEmpty() ? null : location.trim();
    }

    /**
     * 构造下一跳请求：301/302/303 按浏览器惯例把非 GET/HEAD 改成 GET 并丢弃正文，
     * 307/308 保持方法与正文。跨 origin 时剥离 Authorization 与 Cookie 头，
     * Cookie 会在 {@link #prepareForSend} 里按新 URL 重新从 CookieJar 取。
     */
    private static HttpRequest redirectRequest(HttpRequest source, String nextUrl, int code) {
        HttpRequest next = source.copy().url(nextUrl);
        next.removeHeader("Cookie");
        if (!HttpIo.sameOrigin(source.getUrl(), nextUrl)) {
            next.removeHeader("Authorization");
        }
        if ((code == 301 || code == 302 || code == 303)
                && !HttpRequest.GET.equalsIgnoreCase(source.getMethod())
                && !HttpRequest.HEAD.equalsIgnoreCase(source.getMethod())) {
            next.method(HttpRequest.GET);
            next.body((byte[]) null);
            next.bodyFile(null);
            next.contentType(null);
            next.removeHeader("Content-Type");
            next.removeHeader("Content-Length");
        }
        return next;
    }

    /**
     * 发送前的统一装配：查询串合并、代理、SSL 开关、代理认证、Cookie、Accept-Encoding。
     */
    private static void prepareForSend(HttpRequest request) {
        if (request.hasQueries()) {
            request.url(request.effectiveUrl());
            request.clearQueries();
        }
        if (request.getProxy() == null && proxy != null) {
            request.proxy(proxy);
        }
        if (!request.isIgnoreSslSet()) {
            request.ignoreSsl(ignoreSSL);
        }
        applyProxyAuth(request);
        applyCookies(request);
        HttpIo.applySupportedAcceptEncoding(request);
    }

    /**
     * 拦截器链实现：按注册顺序逐层向内，最内层是 {@link #sendWithRetry}。
     */
    private static final class InterceptorChain implements HttpInterceptor.Chain {
        private final List<HttpInterceptor> interceptors;
        private final int index;
        private final long deadlineNanos;
        private HttpRequest current;

        private InterceptorChain(List<HttpInterceptor> interceptors, int index, long deadlineNanos) {
            this.interceptors = interceptors;
            this.index = index;
            this.deadlineNanos = deadlineNanos;
        }

        @Override
        public HttpRequest request() {
            return current;
        }

        @Override
        public HttpResponse proceed(HttpRequest request) throws IOException {
            if (request == null) {
                throw new IOException("interceptor returned a null request");
            }
            if (index >= interceptors.size()) {
                return sendWithRetry(request, deadlineNanos);
            }
            InterceptorChain next = new InterceptorChain(interceptors, index + 1, deadlineNanos);
            next.current = request;
            HttpResponse response = interceptors.get(index).intercept(next);
            if (response == null) {
                throw new IOException("interceptor " + interceptors.get(index).getClass().getName()
                        + " returned a null response");
            }
            return response;
        }
    }

    private static void sleepBeforeRetry(long delay, long deadlineNanos) throws IOException {
        if (delay <= 0) {
            return;
        }
        long remaining = remainingMs(deadlineNanos);
        if (remaining <= 0L) {
            throw new IOException("total timeout exceeded before retry");
        }
        long actual = Math.min(delay, remaining);
        try {
            Thread.sleep(actual);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("request retry interrupted", e);
        }
    }

    private static void applyDefaults(HttpRequest request) {
        HttpConfig cfg = config();
        if (!request.isConnectTimeoutSet()) {
            request.connectTimeoutMs(cfg.getConnectTimeoutMs());
        }
        if (!request.isReadTimeoutSet()) {
            request.readTimeoutMs(cfg.getReadTimeoutMs());
        }
        request.preferHttp2(cfg.isHttp2());
        if (request.getSslContext() == null && cfg.getSslContext() != null) {
            request.sslContext(cfg.getSslContext());
        }
        if (request.getHostnameVerifier() == null && cfg.getHostnameVerifier() != null) {
            request.hostnameVerifier(cfg.getHostnameVerifier());
        }
        if (request.getMaxBufferBytes() == 0) {
            request.maxBufferBytes(cfg.getMaxBufferBytes());
        }
    }

    private static void applyProxyAuth(HttpRequest request) {
        if (request.getHeader("Proxy-Authorization") != null) {
            return;
        }
        String user = config().getProxyUsername();
        if (user == null || user.isEmpty()) {
            return;
        }
        request.header("Proxy-Authorization", basicAuth(user, config().getProxyPassword()));
    }

    private static boolean containsIgnoreCase(Map<String, String> headers, String name) {
        for (String key : headers.keySet()) {
            if (name.equalsIgnoreCase(key)) {
                return true;
            }
        }
        return false;
    }

    private static void applyCookies(HttpRequest request) {
        if (request.getHeader("Cookie") != null) {
            return;
        }
        if (request.hasCookies()) {
            StringBuilder cookieHeaderValue = new StringBuilder();
            for (Map.Entry<String, String> entry : request.getCookies().entrySet()) {
                if (cookieHeaderValue.length() > 0) {
                    cookieHeaderValue.append("; ");
                }
                cookieHeaderValue.append(entry.getKey()).append('=').append(entry.getValue());
            }
            request.header("Cookie", cookieHeaderValue.toString());
            return;
        }
        HttpCookieJar jar = getCookieJar();
        URI uri = HttpIo.toUri(request.getUrl());
        if (jar == null || uri == null) {
            return;
        }
        List<HttpCookie> cookies = jar.loadForRequest(uri);
        if (cookies == null || cookies.isEmpty()) {
            return;
        }
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < cookies.size(); i++) {
            HttpCookie cookie = cookies.get(i);
            if (i > 0) {
                builder.append("; ");
            }
            builder.append(cookie.getName()).append('=').append(cookie.getValue());
        }
        request.header("Cookie", builder.toString());
    }

    private static void storeCookies(HttpResponse response) {
        if (response == null) {
            return;
        }
        HttpCookieJar jar = getCookieJar();
        URI uri = HttpIo.toUri(response.requestUrl());
        if (jar == null || uri == null) {
            return;
        }
        List<HttpCookie> cookies = HttpIo.parseCookies(response.headers("Set-Cookie"));
        if (!cookies.isEmpty()) {
            jar.saveFromResponse(uri, cookies);
        }
    }

    private static String appendQuery(String url, Map<String, Object> params) {
        String requestParamString = getRequestParamString(params);
        if (url.indexOf('?') != -1) {
            return url + "&" + requestParamString;
        }
        return url + "?" + requestParamString;
    }

    private static String bodyString(HttpResponse response) throws IOException {
        String text;
        try {
            HttpResponseBody body = response.body();
            text = body == null ? "" : body.string();
        } finally {
            response.close();
        }
        if (config().isThrowOnHttpError() && !response.isSuccessful()) {
            // 正文已读出且响应已关闭，片段直接进异常信息，调用方无需再负责关闭
            throw new HttpStatusException(response, text);
        }
        return text;
    }

    /**
     * 原子下载：先写入临时文件，成功后原子替换目标文件（目标文件始终完整，
     * 失败时自动清理临时文件且不影响旧文件）。
     *
     * @param url 下载地址
     * @param dest 目标文件
     * @return 目标文件绝对路径
     * @throws IOException 下载或替换失败
     */
    public static String downloadAtomic(String url, File dest) throws IOException {
        return downloadAtomic(url, dest, null, null);
    }

    /**
     * 原子下载并校验 SHA-256，校验失败抛错且不会替换目标文件。
     *
     * @param url 下载地址
     * @param dest 目标文件
     * @param expectedSha256 期望的 SHA-256 十六进制值（大小写不敏感），{@code null} 跳过校验
     * @return 目标文件绝对路径
     * @throws IOException 下载、校验或替换失败
     */
    public static String downloadAtomic(String url, File dest, String expectedSha256) throws IOException {
        return downloadAtomic(url, dest, expectedSha256, null);
    }

    /**
     * 原子下载（带进度回调）。
     *
     * @param url 下载地址
     * @param dest 目标文件
     * @param expectedSha256 期望 SHA-256，{@code null} 跳过校验
     * @param progress 进度回调，可为 {@code null}
     * @return 目标文件绝对路径
     * @throws IOException 下载、校验或替换失败
     */
    public static String downloadAtomic(String url, File dest, String expectedSha256,
                                        HttpCallBack<String> progress) throws IOException {
        if (dest == null) {
            throw new IOException("atomic download requires a destination file");
        }
        return downloadOnce(url, null, null, dest, false, null, progress, expectedSha256);
    }

    private static String downloadTo(String url, String fileName, String dir, File dest, boolean resume,
                                     Map<String, String> headers, HttpCallBack<String> progress)
            throws IOException {
        IOException last = null;
        for (int i = 0; i <= DOWNLOAD_MAX_RETRIES; i++) {
            try {
                return downloadOnce(url, fileName, dir, dest, resume, headers, progress, null);
            } catch (SocketTimeoutException e) {
                last = e;
                log.warn("网络连接超时{}次", i + 1);
            }
        }
        throw last;
    }

    private static String downloadOnce(String url, String fileName, String dir, File dest, boolean resume,
                                       Map<String, String> headers, HttpCallBack<String> progress,
                                       String expectedSha256)
            throws IOException {
        HttpRequest request = HttpRequest.get(url).streamResponse(true);
        if (headers != null && !headers.isEmpty()) {
            request.headers(headers);
        } else {
            request.headers(getDefaultHeaders());
        }
        // 下载路径禁用内容协商压缩：Range 偏移与 Content-Length 都以未编码字节计，
        // 若服务端返回 gzip，续传会把解码后的字节追加到编码偏移上，得到损坏文件。
        request.header("Accept-Encoding", "identity");
        applyDefaults(request);
        long existing = 0L;
        File hint = dest;
        if (hint == null && StringUtils.isNotBlank(fileName) && StringUtils.isNotBlank(dir)) {
            hint = new File(dir, fileName);
        } else if (hint == null && StringUtils.isNotBlank(fileName) && new File(fileName).getParent() != null) {
            hint = new File(fileName);
        }
        if (resume && hint != null && hint.isFile() && hint.length() > 0) {
            existing = hint.length();
            request.rangeFrom(existing);
        }
        HttpResponse response = send(request);
        try {
            int code = response.code();
            boolean append = false;
            if (resume && existing > 0) {
                if (code == 206) {
                    append = true;
                } else if (code == 416) {
                    // 服务端认为请求范围已越界，即本地文件已完整
                    return hint.getAbsolutePath();
                } else {
                    existing = 0L;
                }
            }
            requireDownloadable(response, url);
            File target = dest != null ? dest : resolveDownloadFile(url, fileName, dir, response);
            target = target.getAbsoluteFile();
            File parent = target.getParentFile();
            if (parent != null && !parent.exists() && !parent.mkdirs() && !parent.isDirectory()) {
                throw new IOException("cannot create download directory: " + parent);
            }
            // 续传必须直接追加到目标文件（已下载的部分就在那里）；其余情况一律先写
            // 临时文件再原子替换，避免中断留下截断的目标文件。
            boolean useTemp = !append;
            File file = target;
            File temp = null;
            if (useTemp) {
                temp = File.createTempFile(tempPrefix(target.getName()), ".part", parent);
                file = temp;
            }
            try {
                long contentLength = -1L;
                if (response.body() != null) {
                    contentLength = response.body().contentLength();
                }
                if (contentLength < 0) {
                    contentLength = ConvertUtils.toLong(response.header("Content-Length"), -1L);
                }
                long total = append && contentLength >= 0 ? existing + contentLength : contentLength;
                long saved = append ? existing : 0L;
                int bufferSize = config().getDownloadBufferSize();
                try (FileOutputStream fos = new FileOutputStream(file, append)) {
                    InputStream in = response.body() == null ? null : response.body().byteStream();
                    if (in != null) {
                        byte[] buf = new byte[bufferSize];
                        int length;
                        while ((length = in.read(buf)) > 0) {
                            fos.write(buf, 0, length);
                            saved += length;
                            if (progress != null) {
                                progress.onProcess(saved, total);
                            }
                        }
                    }
                    fos.flush();
                }
                if (total >= 0 && saved != total) {
                    throw new IOException("download truncated: expected " + total
                            + " bytes but wrote " + saved + " for " + url);
                }
                if (expectedSha256 != null && !expectedSha256.trim().isEmpty()) {
                    verifySha256(file, expectedSha256.trim());
                }
                if (temp != null) {
                    atomicReplace(temp, target);
                }
                return target.getAbsolutePath();
            } catch (IOException e) {
                if (temp != null && temp.exists() && !temp.delete()) {
                    temp.deleteOnExit();
                }
                throw e;
            }
        } finally {
            response.close();
        }
    }

    /**
     * 临时文件前缀：{@code File.createTempFile} 要求前缀至少 3 个字符。
     */
    private static String tempPrefix(String name) {
        String prefix = name == null ? "" : name;
        while (prefix.length() < 3) {
            prefix = prefix + "-dl";
        }
        return prefix;
    }

    /**
     * 下载前校验响应状态：非 2xx 一律抛错，避免把 404/500 的错误页写成目标文件。
     * 错误正文截取前 512 字节作为诊断信息。
     */
    private static void requireDownloadable(HttpResponse response, String url) throws IOException {
        int code = response.code();
        if (code >= 200 && code < 300) {
            return;
        }
        String snippet = "";
        try {
            HttpResponseBody body = response.body();
            if (body != null) {
                InputStream in = body.byteStream();
                if (in != null) {
                    byte[] buf = new byte[512];
                    int read = in.read(buf);
                    if (read > 0) {
                        snippet = new String(buf, 0, read, StandardCharsets.UTF_8)
                                .replace('\n', ' ').replace('\r', ' ').trim();
                    }
                }
            }
        } catch (IOException ignore) {
            // 诊断信息尽力而为
        }
        throw new IOException("download failed: HTTP " + code + " for " + url
                + (snippet.isEmpty() ? "" : ", body: " + snippet));
    }

    /**
     * 校验文件 SHA-256，不匹配时抛出 {@link IOException}。
     */
    private static void verifySha256(File file, String expectedSha256) throws IOException {
        String actual = sha256Hex(file);
        if (!actual.equalsIgnoreCase(expectedSha256)) {
            throw new IOException("SHA-256 checksum mismatch: expected " + expectedSha256
                    + ", actual " + actual);
        }
    }

    /**
     * 计算文件 SHA-256 十六进制值（小写），见 {@link EncryptUtils#sha256(File)}。
     */
    private static String sha256Hex(File file) throws IOException {
        return EncryptUtils.sha256(file);
    }

    /**
     * 原子替换：优先 {@code ATOMIC_MOVE}，文件系统不支持时退化为普通移动。
     */
    private static void atomicReplace(File source, File target) throws IOException {
        try {
            java.nio.file.Files.move(source.toPath(), target.toPath(),
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                    java.nio.file.StandardCopyOption.ATOMIC_MOVE);
        } catch (java.nio.file.AtomicMoveNotSupportedException e) {
            java.nio.file.Files.move(source.toPath(), target.toPath(),
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static File resolveDownloadFile(String url, String fileName, String dir, HttpResponse response) {
        String basePath = "";
        if (StringUtils.isNotBlank(dir)) {
            File pathFile = new File(dir);
            if (!pathFile.exists()) {
                pathFile.mkdirs();
            }
            basePath = pathFile.getAbsolutePath() + File.separator;
        }
        String resolved = fileName;
        if (StringUtils.isBlank(resolved)) {
            resolved = getFileName(response);
            if (StringUtils.isBlank(resolved)) {
                resolved = FileUtils.getFileNameFromUrl(url);
            }
            if (StringUtils.isBlank(resolved)) {
                resolved = RandomUtils.getUUID();
            }
        } else if (!resolved.contains(".")) {
            String extra = getFileName(response);
            if (StringUtils.isBlank(extra)) {
                extra = FileUtils.getFileNameFromUrl(url);
            }
            resolved = resolved + extra;
        }
        return new File(basePath + resolved);
    }

    private static String stripQuotes(String name) {
        if (name == null) {
            return "";
        }
        String value = name.trim();
        if (value.startsWith("\"") && value.endsWith("\"") && value.length() >= 2) {
            return value.substring(1, value.length() - 1);
        }
        return value;
    }
}
