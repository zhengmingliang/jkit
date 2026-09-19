package com.alianga.jkit.http;

import com.alianga.jkit.HttpUtils;
import com.alianga.jkit.http.encoding.ContentEncodings;
import com.alianga.jkit.jdk.UnsafeUtils;

import javax.net.ssl.HttpsURLConnection;

import java.io.Closeable;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Field;
import java.net.HttpURLConnection;
import java.net.ProtocolException;
import java.net.Proxy;
import java.net.URL;
import java.util.List;
import java.util.Map;

/**
 * 基于 {@link HttpURLConnection} 的传输实现，面向 JDK 8 及作为高版本回退。
 *
 * @author 郑明亮
 */
public final class UrlConnectionHttpEngine implements HttpEngine {
    /**
     * {@link HttpURLConnection} 的 method 字段，延迟查找；volatile 保证并发首次访问的可见性
     */
    private static volatile Field methodField;
    /**
     * method 字段查找失败标记，避免每次请求重复抛异常
     */
    private static volatile boolean methodFieldMissed;

    @Override
    public String name() {
        return HttpEngines.URL_CONNECTION;
    }

    @Override
    public HttpResponse execute(HttpRequest request) throws IOException {
        if (request == null || request.getUrl() == null) {
            throw new IOException("the request URL can not be null");
        }
        HttpURLConnection conn = open(request);
        boolean disconnect = true;
        try {
            configure(conn, request);
            writeBody(conn, request);
            int code = conn.getResponseCode();
            String message = conn.getResponseMessage();
            Map<String, List<String>> headers = conn.getHeaderFields();
            String contentType = conn.getContentType();
            if (contentType == null) {
                contentType = conn.getHeaderField("Content-Type");
            }
            String contentEncoding = conn.getHeaderField("Content-Encoding");
            long contentLength = conn.getContentLengthLong();
            long maxBuffer = request.resolveMaxBufferBytes();
            if (!request.isStreamResponse() && maxBuffer > 0 && contentLength > maxBuffer) {
                throw new IOException("response body " + contentLength + " exceeds maxBufferBytes " + maxBuffer);
            }
            InputStream raw = responseStream(conn, code);
            InputStream decoded = HttpIo.decodeContentEncoding(raw, contentEncoding);
            if (request.isStreamResponse()) {
                disconnect = false;
                Closeable resource = new ConnectionResource(conn, decoded);
                HttpResponseBody body = HttpResponseBody.ofStream(decoded, contentType, contentLength, resource);
                return new HttpResponse(code, message, request.getUrl(), headers, body);
            }
            byte[] bytes;
            try {
                bytes = HttpIo.readAll(decoded, maxBuffer);
            } finally {
                HttpIo.closeQuietly(decoded);
            }
            // 正文已读尽并关闭流，socket 可以回到 JDK 的 keep-alive 缓存复用。
            // 这里绝不能调用 disconnect()：它会把连接从缓存中强行销毁，
            // 使每个缓冲式请求都重新做一次 TCP + TLS 握手。
            // 仅在异常路径（disconnect 仍为 true）才销毁连接。
            disconnect = false;
            return new HttpResponse(code, message, request.getUrl(), headers,
                    HttpResponseBody.ofBytes(bytes, contentType));
        } finally {
            if (disconnect) {
                conn.disconnect();
            }
        }
    }

    private static HttpURLConnection open(HttpRequest request) throws IOException {
        URL url = new URL(request.getUrl());
        Proxy proxy = request.getProxy();
        if (proxy != null) {
            return (HttpURLConnection) url.openConnection(proxy);
        }
        return (HttpURLConnection) url.openConnection();
    }

    private static void configure(HttpURLConnection conn, HttpRequest request) throws IOException {
        setRequestMethod(conn, request.getMethod() == null ? HttpRequest.GET : request.getMethod());
        conn.setConnectTimeout(Math.max(0, request.getConnectTimeoutMs()));
        conn.setReadTimeout(Math.max(0, request.getReadTimeoutMs()));
        conn.setInstanceFollowRedirects(request.isFollowRedirects());
        conn.setUseCaches(false);
        if (conn instanceof HttpsURLConnection) {
            HttpsURLConnection https = (HttpsURLConnection) conn;
            if (request.isIgnoreSsl()) {
                https.setSSLSocketFactory(request.getSslContext() == null
                        ? SSLSocketClient.getSSLSocketFactory() : request.getSslContext().getSocketFactory());
                https.setHostnameVerifier(request.getHostnameVerifier() == null
                        ? SSLSocketClient.getHostnameVerifier() : request.getHostnameVerifier());
            } else {
                if (request.getSslContext() != null) {
                    https.setSSLSocketFactory(request.getSslContext().getSocketFactory());
                }
                if (request.getHostnameVerifier() != null) {
                    https.setHostnameVerifier(request.getHostnameVerifier());
                }
            }
        }
        boolean hasAcceptEncoding = false;
        for (Map.Entry<String, String> entry : request.getHeaders().entrySet()) {
            if (entry.getKey() == null || entry.getValue() == null) {
                continue;
            }
            if ("Accept-Encoding".equalsIgnoreCase(entry.getKey())) {
                hasAcceptEncoding = true;
            }
            conn.setRequestProperty(entry.getKey(), entry.getValue());
        }
        if (!hasAcceptEncoding) {
            conn.setRequestProperty("Accept-Encoding", ContentEncodings.acceptEncodingHeader());
        }
        if (request.getContentType() != null && request.getHeader("Content-Type") == null) {
            conn.setRequestProperty("Content-Type", request.getContentType());
        }
    }

    private static void writeBody(HttpURLConnection conn, HttpRequest request) throws IOException {
        File bodyFile = request.getBodyFile();
        byte[] body = request.getBody();
        if (!request.isEntityMethod() && bodyFile == null && body == null) {
            return;
        }
        conn.setDoOutput(true);
        setRequestMethod(conn, request.getMethod() == null ? HttpRequest.GET : request.getMethod());
        // 走 active-aware 的 config()：实例客户端在作用域内设置的超时/缓冲对引擎生效
        int bufferSize = HttpUtils.config().getDownloadBufferSize();
        if (bodyFile != null) {
            conn.setFixedLengthStreamingMode(bodyFile.length());
            OutputStream out = conn.getOutputStream();
            try {
                HttpIo.copyFile(bodyFile, out, bufferSize);
                out.flush();
            } finally {
                HttpIo.closeQuietly(out);
            }
            return;
        }
        if (body == null) {
            body = new byte[0];
        }
        conn.setFixedLengthStreamingMode(body.length);
        OutputStream out = conn.getOutputStream();
        try {
            out.write(body);
            out.flush();
        } finally {
            HttpIo.closeQuietly(out);
        }
    }

    private static void setRequestMethod(HttpURLConnection conn, String method) throws IOException {
        try {
            conn.setRequestMethod(method);
        } catch (ProtocolException e) {
            // HttpURLConnection 只承认白名单内的标准方法，PATCH 之类扩展方法会被拒绝，
            // 此时需要直接改写其 method 字段；JDK 9+ 的模块封装会让 setAccessible 失效，故优先走 Unsafe
            if (!forceRequestMethod(conn, method)) {
                throw e;
            }
        }
    }

    /**
     * 直接改写 {@link HttpURLConnection} 的 method 字段，用于支持 PATCH 等非白名单方法。
     *
     * @param conn   连接
     * @param method 目标方法名
     * @return 写入成功返回 {@code true}
     */
    private static boolean forceRequestMethod(HttpURLConnection conn, String method) {
        Field field = methodField();
        if (field == null) {
            return false;
        }
        if (UnsafeUtils.UNSAFE != null) {
            try {
                long offset = UnsafeUtils.objectFieldOffset(field);
                UnsafeUtils.putObject(conn, offset, method);
                return method.equals(UnsafeUtils.getObject(conn, offset));
            } catch (Throwable ignored) {
                // 继续尝试反射方式
            }
        }
        try {
            field.setAccessible(true);
            field.set(conn, method);
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    /**
     * @return {@link HttpURLConnection} 的 method 字段，不可用时为 {@code null}
     */
    private static Field methodField() {
        if (methodField == null && !methodFieldMissed) {
            try {
                methodField = HttpURLConnection.class.getDeclaredField("method");
            } catch (Throwable ignored) {
                methodFieldMissed = true;
            }
        }
        return methodField;
    }

    private static InputStream responseStream(HttpURLConnection conn, int code) {
        try {
            if (code >= 400) {
                InputStream err = conn.getErrorStream();
                if (err != null) {
                    return err;
                }
                try {
                    return conn.getInputStream();
                } catch (IOException e) {
                    return null;
                }
            }
            try {
                return conn.getInputStream();
            } catch (IOException e) {
                return conn.getErrorStream();
            }
        } catch (Exception e) {
            return null;
        }
    }

    private static final class ConnectionResource implements Closeable {
        private final HttpURLConnection conn;
        private final InputStream stream;

        private ConnectionResource(HttpURLConnection conn, InputStream stream) {
            this.conn = conn;
            this.stream = stream;
        }

        @Override
        public void close() {
            HttpIo.closeQuietly(stream);
            conn.disconnect();
        }
    }
}
