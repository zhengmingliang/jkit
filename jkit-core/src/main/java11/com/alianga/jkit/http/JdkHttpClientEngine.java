package com.alianga.jkit.http;

import com.alianga.jkit.http.encoding.ContentEncodings;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.PushbackInputStream;
import java.net.Proxy;
import java.net.ProxySelector;
import java.net.SocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest.BodyPublishers;
import java.net.http.HttpResponse.BodyHandlers;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * 基于 {@code java.net.http.HttpClient} 的传输实现（JDK 11+）。
 * <p>
 * 由 {@link HttpEngines} 在运行期通过 {@code Class.forName} 加载，源码位于
 * {@code src/main/java11}，编译到 {@code META-INF/versions/11}。
 *
 * @author 郑明亮
 */
public final class JdkHttpClientEngine implements HttpEngine {
    private final Object lock = new Object();
    private HttpClient cached;
    private Proxy cachedProxy;
    private boolean cachedIgnoreSsl;
    private int cachedConnectTimeout;
    private boolean cachedFollowRedirects = true;
    private boolean cachedHttp2 = true;
    /** 缓存的 client 是用哪个 SSLContext 建的（按引用比较），null 表示用的是默认/trust-all。 */
    private SSLContext cachedSslContext;
    /** 缓存的 client 是按哪个 HostnameVerifier 配置的（按引用比较）。 */
    private javax.net.ssl.HostnameVerifier cachedHostnameVerifier;

    @Override
    public String name() {
        return HttpEngines.JDK_HTTP_CLIENT;
    }

    @Override
    public HttpResponse execute(HttpRequest request) throws IOException {
        if (request == null || request.getUrl() == null) {
            throw new IOException("the request URL can not be null");
        }
        try {
            if (request.isStreamResponse()) {
                return executeStream(request);
            }
            return executeBuffered(request);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("request interrupted", e);
        }
    }

    private HttpResponse executeBuffered(HttpRequest request) throws IOException, InterruptedException {
        HttpClient client = clientFor(request);
        java.net.http.HttpResponse<InputStream> sent =
                client.send(toJdkRequest(request), BodyHandlers.ofInputStream());
        long maxBuffer = request.resolveMaxBufferBytes();
        String encoding = header(sent, "Content-Encoding");
        byte[] body = readBuffered(sent.body(), encoding, maxBuffer);
        String contentType = header(sent, "Content-Type");
        return new HttpResponse(sent.statusCode(), reason(sent), request.getUrl(),
                sent.headers().map(), HttpResponseBody.ofBytes(body, contentType));
    }

    /**
     * 边读边校验 {@code maxBufferBytes}。不能用 {@code BodyHandlers.ofByteArray()}：
     * 那会先把整个（可能是压缩的）正文读进内存再判断上限，压缩炸弹防不住。
     */
    private static byte[] readBuffered(InputStream raw, String encoding, long maxBuffer) throws IOException {
        if (raw == null) {
            return new byte[0];
        }
        PushbackInputStream pushback = new PushbackInputStream(raw, 1);
        try {
            int first = pushback.read();
            if (first < 0) {
                // 空正文：不要交给 GZIPInputStream，否则构造时就抛 EOFException
                return new byte[0];
            }
            pushback.unread(first);
            InputStream decoded = HttpIo.decodeContentEncoding(pushback, encoding);
            try {
                return HttpIo.readAll(decoded, maxBuffer);
            } finally {
                if (decoded != pushback) {
                    HttpIo.closeQuietly(decoded);
                }
            }
        } finally {
            HttpIo.closeQuietly(pushback);
        }
    }

    private HttpResponse executeStream(HttpRequest request) throws IOException, InterruptedException {
        HttpClient client = clientFor(request);
        java.net.http.HttpResponse<InputStream> sent =
                client.send(toJdkRequest(request), BodyHandlers.ofInputStream());
        String encoding = header(sent, "Content-Encoding");
        String contentType = header(sent, "Content-Type");
        long contentLength = HttpIo.parseContentLength(header(sent, "Content-Length"));
        InputStream decoded = HttpIo.decodeContentEncoding(sent.body(), encoding);
        return new HttpResponse(sent.statusCode(), reason(sent), request.getUrl(),
                sent.headers().map(),
                HttpResponseBody.ofStream(decoded, contentType, contentLength, decoded));
    }

    private HttpClient clientFor(HttpRequest request) {
        synchronized (lock) {
            if (cached != null
                    && sameProxy(cachedProxy, request.getProxy())
                    && cachedIgnoreSsl == request.isIgnoreSsl()
                    // 必须把 SSL 相关配置纳入缓存键：只判断"传入请求没有自定义上下文"
                    // 会把一个用自定义/trust-all 上下文建出来的 client 复用给后续请求。
                    && cachedSslContext == request.getSslContext()
                    && cachedHostnameVerifier == request.getHostnameVerifier()
                    && cachedConnectTimeout == request.getConnectTimeoutMs()
                    && cachedFollowRedirects == request.isFollowRedirects()
                    && cachedHttp2 == request.isPreferHttp2()) {
                return cached;
            }
            HttpClient.Builder builder = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofMillis(Math.max(1, request.getConnectTimeoutMs())))
                    .followRedirects(request.isFollowRedirects()
                            ? HttpClient.Redirect.NORMAL
                            : HttpClient.Redirect.NEVER)
                    .version(request.isPreferHttp2()
                            ? HttpClient.Version.HTTP_2
                            : HttpClient.Version.HTTP_1_1);
            Proxy proxy = request.getProxy();
            if (proxy != null && proxy.type() != Proxy.Type.DIRECT) {
                final Proxy selected = proxy;
                builder.proxy(new ProxySelector() {
                    @Override
                    public List<Proxy> select(URI uri) {
                        return Collections.singletonList(selected);
                    }

                    @Override
                    public void connectFailed(URI uri, SocketAddress sa, IOException ioe) {
                        // ignore
                    }
                });
            }
            if (request.getSslContext() != null) {
                builder.sslContext(request.getSslContext());
            } else if (request.isIgnoreSsl()) {
                builder.sslContext(trustAllContext());
            }
            if (request.isIgnoreSsl() || request.getHostnameVerifier() != null) {
                SSLParameters params = new SSLParameters();
                params.setEndpointIdentificationAlgorithm(request.isIgnoreSsl() ? null : "HTTPS");
                builder.sslParameters(params);
            }
            cached = builder.build();
            cachedProxy = proxy;
            cachedIgnoreSsl = request.isIgnoreSsl();
            cachedConnectTimeout = request.getConnectTimeoutMs();
            cachedFollowRedirects = request.isFollowRedirects();
            cachedHttp2 = request.isPreferHttp2();
            cachedSslContext = request.getSslContext();
            cachedHostnameVerifier = request.getHostnameVerifier();
            return cached;
        }
    }

    private static java.net.http.HttpRequest toJdkRequest(HttpRequest request) throws IOException {
        URI uri = HttpIo.toUri(request.getUrl());
        if (uri == null) {
            throw new IOException("invalid url: " + request.getUrl());
        }
        java.net.http.HttpRequest.Builder builder = java.net.http.HttpRequest.newBuilder(uri);
        if (request.getReadTimeoutMs() > 0) {
            builder.timeout(Duration.ofMillis(request.getReadTimeoutMs()));
        }
        boolean hasAcceptEncoding = false;
        for (Map.Entry<String, String> entry : request.getHeaders().entrySet()) {
            if (entry.getKey() == null || entry.getValue() == null) {
                continue;
            }
            if (HttpIo.isRestrictedHttpClientHeader(entry.getKey())) {
                continue;
            }
            if ("Accept-Encoding".equalsIgnoreCase(entry.getKey())) {
                hasAcceptEncoding = true;
            }
            try {
                builder.header(entry.getKey(), entry.getValue());
            } catch (IllegalArgumentException e) {
                // 跳过 HttpClient 不允许的头
            }
        }
        if (!hasAcceptEncoding) {
            try {
                builder.header("Accept-Encoding", ContentEncodings.acceptEncodingHeader());
            } catch (IllegalArgumentException e) {
                // ignore
            }
        }
        if (request.getContentType() != null && request.getHeader("Content-Type") == null) {
            builder.header("Content-Type", request.getContentType());
        }
        String method = request.getMethod() == null ? HttpRequest.GET : request.getMethod();
        File bodyFile = request.getBodyFile();
        byte[] body = request.getBody();
        if (bodyFile != null) {
            builder.method(method, BodyPublishers.ofFile(bodyFile.toPath()));
        } else if (body != null) {
            builder.method(method, BodyPublishers.ofByteArray(body));
        } else {
            builder.method(method, BodyPublishers.noBody());
        }
        return builder.build();
    }

    private static String header(java.net.http.HttpResponse<?> response, String name) {
        return response.headers().firstValue(name).orElse(null);
    }

    private static String reason(java.net.http.HttpResponse<?> response) {
        try {
            return response.headers().firstValue(":status").orElse("");
        } catch (Exception e) {
            return "";
        }
    }

    private static boolean sameProxy(Proxy a, Proxy b) {
        if (a == b) {
            return true;
        }
        if (a == null || b == null) {
            return false;
        }
        return a.equals(b);
    }

    private static SSLContext trustAllContext() {
        try {
            SSLContext ctx = SSLContext.getInstance("TLS");
            ctx.init(null, SSLSocketClient.getTrustManager(), new SecureRandom());
            return ctx;
        } catch (Exception e) {
            throw new IllegalStateException("init SSLContext failed", e);
        }
    }
}
