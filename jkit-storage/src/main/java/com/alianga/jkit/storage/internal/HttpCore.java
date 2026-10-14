package com.alianga.jkit.storage.internal;

import com.alianga.jkit.storage.StorageErrorCode;
import com.alianga.jkit.storage.StorageException;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Field;
import java.net.HttpURLConnection;
import java.net.Proxy;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.nio.file.Files;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 存储模块内部 HTTP 层：直接基于 JDK {@link HttpURLConnection}，不依赖任何第三方库。
 * 相比通用 HTTP 封装，这里需要几个存储协议特有的能力：
 * 任意方法（PROPFIND / MKCOL / MOVE）、定长流式请求体、关闭重定向、错误响应体可读。
 *
 * <p>请求体未知长度时先缓冲到临时文件，再按定长发送——对象存储的 PUT / PUT Blob
 * 都要求 Content-Length。已知长度的流直接透传，不落盘。</p>
 *
 * @author 郑明亮
 */
public final class HttpCore {
    private static final int CONNECT_TIMEOUT_DEFAULT_MS = 15_000;
    private static final int READ_TIMEOUT_DEFAULT_MS = 60_000;

    private HttpCore() {
    }

    /** 请求描述。 */
    public static final class Request {
        private final String method;
        private final String url;
        private final Map<String, String> headers = new LinkedHashMap<String, String>();
        private byte[] bodyBytes;
        private InputStream bodyStream;
        private long bodyLength = -1L;
        private Proxy proxy;
        private int connectTimeoutMs = CONNECT_TIMEOUT_DEFAULT_MS;
        private int readTimeoutMs = READ_TIMEOUT_DEFAULT_MS;

        /**
         * 创建请求。
         *
         * @param method HTTP 方法
         * @param url    完整 URL
         */
        public Request(String method, String url) {
            this.method = method;
            this.url = url;
        }

        /**
         * 设置请求头。
         *
         * @param name  头名
         * @param value 头值，null 忽略
         * @return 自身
         */
        public Request header(String name, String value) {
            if (value != null) {
                headers.put(name, value);
            }
            return this;
        }

        /**
         * 设置内存请求体（Content-Length 自动取其长度）。
         *
         * @param body 请求体，null 视为空体
         * @return 自身
         */
        public Request body(byte[] body) {
            this.bodyBytes = body == null ? new byte[0] : body;
            return this;
        }

        /**
         * 设置流式请求体。
         *
         * @param body   数据源（发送过程中由本类读取并关闭）
         * @param length 已知长度；未知传 -1（缓冲到临时文件后定长发送）
         * @return 自身
         */
        public Request body(InputStream body, long length) {
            this.bodyStream = body;
            this.bodyLength = length;
            return this;
        }

        /**
         * 设置代理与超时。
         *
         * @param proxy            代理，null 直连
         * @param connectTimeoutMs 连接超时毫秒
         * @param readTimeoutMs    读超时毫秒
         * @return 自身
         */
        public Request timeouts(Proxy proxy, int connectTimeoutMs, int readTimeoutMs) {
            this.proxy = proxy;
            if (connectTimeoutMs > 0) {
                this.connectTimeoutMs = connectTimeoutMs;
            }
            if (readTimeoutMs > 0) {
                this.readTimeoutMs = readTimeoutMs;
            }
            return this;
        }
    }

    /** 响应描述。 */
    public static final class Response implements java.io.Closeable {
        private final int status;
        private final String message;
        private final Map<String, List<String>> headers;
        private final InputStream body;
        private final HttpURLConnection connection;

        private Response(int status, String message, Map<String, List<String>> headers,
                         InputStream body, HttpURLConnection connection) {
            this.status = status;
            this.message = message;
            this.headers = headers;
            this.body = body == null ? new ByteArrayInputStream(new byte[0])
                    : new CompleteTrackingInputStream(body);
            this.connection = connection;
        }

        public int status() {
            return status;
        }

        public String message() {
            return message;
        }

        /**
         * 取响应头（大小写不敏感）。
         *
         * @param name 头名
         * @return 头值，不存在返回 null
         */
        public String header(String name) {
            for (Map.Entry<String, List<String>> entry : headers.entrySet()) {
                if (entry.getKey() != null && entry.getKey().equalsIgnoreCase(name)
                        && entry.getValue() != null && !entry.getValue().isEmpty()) {
                    return entry.getValue().get(0);
                }
            }
            return null;
        }

        public Map<String, List<String>> headers() {
            return headers;
        }

        /**
         * 响应体流（4xx/5xx 时是错误响应体）。读取后调用 {@link #close()}。
         *
         * @return 响应体流，无响应体返回空流
         */
        public InputStream body() {
            return body;
        }

        /**
         * 读取整个响应体到内存（用于小对象与错误 XML），读取后自动关闭。
         *
         * @return 响应体字节
         */
        public byte[] bodyBytes() {
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream(8192);
            try {
                byte[] chunk = new byte[8192];
                int read;
                long total = 0L;
                while ((read = body.read(chunk)) >= 0) {
                    total += read;
                    if (total > Xmls.MAX_RESPONSE_BYTES) {
                        throw StorageException.io("响应体超过上限 " + Xmls.MAX_RESPONSE_BYTES
                                + " 字节，已中止读取", "-", null);
                    }
                    out.write(chunk, 0, read);
                }
                return out.toByteArray();
            } catch (IOException e) {
                throw StorageException.io("读取响应体失败: " + e.getMessage(), "-", e);
            } finally {
                close();
            }
        }

        @Override
        public void close() {
            boolean complete = false;
            try {
                if (body instanceof CompleteTrackingInputStream) {
                    complete = ((CompleteTrackingInputStream) body).isComplete();
                }
                body.close();
            } catch (IOException ignore) {
                // 关闭失败不影响结果
            }
            if (connection != null && !complete) {
                // 响应体未读尽（提前放弃下载等）：连接不能归还池，直接断开，
                // 否则 JDK keep-alive 池会把这个半截连接发给下一个请求
                connection.disconnect();
            }
        }
    }

    /** 跟踪响应体是否被读尽，用于决定连接是归还 keep-alive 池还是直接断开。 */
    private static final class CompleteTrackingInputStream extends java.io.FilterInputStream {
        private volatile boolean complete;

        private CompleteTrackingInputStream(InputStream in) {
            super(in);
        }

        boolean isComplete() {
            return complete;
        }

        @Override
        public int read() throws IOException {
            int value = super.read();
            if (value < 0) {
                complete = true;
            }
            return value;
        }

        @Override
        public int read(byte[] buffer) throws IOException {
            int read = super.read(buffer);
            if (read < 0) {
                complete = true;
            }
            return read;
        }

        @Override
        public int read(byte[] buffer, int offset, int length) throws IOException {
            int read = super.read(buffer, offset, length);
            if (read < 0) {
                complete = true;
            }
            return read;
        }
    }

    /**
     * 把通用网络配置（proxyHost / proxyPort / connectTimeoutMs / readTimeoutMs）
     * 应用到请求上。所有 HTTP 引擎统一走这里，键不存在时保持默认值。
     *
     * @param request 请求
     * @param config  连接配置，可为 null
     * @return 自身
     */
    public static Request configured(Request request, com.alianga.jkit.storage.StorageConfig config) {
        if (config == null) {
            return request;
        }
        Proxy proxy = null;
        String proxyHost = config.getOr("proxyHost", "");
        int proxyPort = config.intOr("proxyPort", 0);
        if (!proxyHost.isEmpty() && proxyPort > 0) {
            proxy = new Proxy(Proxy.Type.HTTP, new java.net.InetSocketAddress(proxyHost, proxyPort));
        }
        request.timeouts(proxy, config.intOr("connectTimeoutMs", 0), config.intOr("readTimeoutMs", 0));
        return request;
    }

    /**
     * 执行请求。网络异常统一翻译为 {@link StorageException}：
     * DNS / 连接失败 → {@link StorageErrorCode#CONNECT_FAILED}，
     * 超时 → {@link StorageErrorCode#TIMEOUT}，其余 → {@link StorageErrorCode#IO_ERROR}。
     *
     * @param request 请求
     * @return 响应（调用方负责 close）
     */
    public static Response execute(Request request) {
        try {
            return executeOnce(request);
        } catch (StorageException e) {
            if (isRetryableFailure(e, request)) {
                flushDeadConnection(request.url);
                return executeOnce(request);
            }
            throw e;
        }
    }

    /**
     * 重试前把 JDK keep-alive 池里可能残留的死连接消费掉：发一个一次性探测请求，
     * 读到 EOF 或正常响应都算完成任务。探测失败完全在预期内，忽略。
     */
    private static void flushDeadConnection(String url) {
        try {
            java.net.URL target = new java.net.URL(url);
            java.net.URL probeUrl = new java.net.URL(
                    target.getProtocol() + "://" + target.getAuthority() + "/");
            HttpURLConnection connection = (HttpURLConnection) probeUrl.openConnection();
            connection.setConnectTimeout(3_000);
            connection.setReadTimeout(3_000);
            connection.setInstanceFollowRedirects(false);
            connection.setUseCaches(false);
            int status = connection.getResponseCode();
            InputStream in = status < 400 ? connection.getInputStream() : connection.getErrorStream();
            if (in != null) {
                byte[] chunk = new byte[512];
                while (in.read(chunk) >= 0) {
                    // 读尽以归还或终结该连接
                }
                in.close();
            }
        } catch (IOException ignore) {
            // 探测 EOF 也是目的之一（消费死连接）
        }
    }

    private static Response executeOnce(Request request) {
        HttpURLConnection connection = null;
        try {
            connection = open(request);
            writeBody(connection, request);
            int status = connection.getResponseCode();
            Map<String, List<String>> headers =
                    new LinkedHashMap<String, List<String>>(connection.getHeaderFields());
            InputStream body = status >= 400 ? connection.getErrorStream() : connection.getInputStream();
            return new Response(status, connection.getResponseMessage(), headers, body, connection);
        } catch (SocketTimeoutException e) {
            closeQuietly(connection);
            throw StorageException.timeout("请求超时: " + e.getMessage());
        } catch (UnknownHostException e) {
            closeQuietly(connection);
            throw StorageException.connectFailed("无法解析主机: " + e.getMessage());
        } catch (IOException e) {
            closeQuietly(connection);
            throw mapIoException(e);
        }
    }

    /**
     * 连接级失败且请求可安全重放（幂等方法 + 可重放请求体）时，换新连接重试一次。
     * 覆盖三类真实场景：JDK keep-alive 池给出已关闭的连接、服务端在读满请求体前
     * 提前响应（鉴权失败的大上传）、对端在写体过程中重置连接。
     */
    private static boolean isRetryableFailure(StorageException e, Request request) {
        if (e.code() != StorageErrorCode.CONNECT_FAILED && e.code() != StorageErrorCode.IO_ERROR) {
            return false;
        }
        if (!isIdempotent(request.method) || request.bodyStream != null) {
            return false;
        }
        String message = String.valueOf(e.getMessage());
        return message.contains("Unexpected end of file") || message.contains("Connection reset")
                || message.contains("insufficient data") || message.contains("Broken pipe");
    }

    private static boolean isIdempotent(String method) {
        return "GET".equals(method) || "HEAD".equals(method) || "PUT".equals(method)
                || "DELETE".equals(method) || "PROPFIND".equals(method);
    }

    private static HttpURLConnection open(Request request) throws IOException {
        java.net.URL url = new java.net.URL(request.url);
        HttpURLConnection connection = (HttpURLConnection) (request.proxy == null
                ? url.openConnection() : url.openConnection(request.proxy));
        connection.setConnectTimeout(request.connectTimeoutMs);
        connection.setReadTimeout(request.readTimeoutMs);
        connection.setInstanceFollowRedirects(false);
        connection.setUseCaches(false);
        setMethod(connection, request.method);
        for (Map.Entry<String, String> header : request.headers.entrySet()) {
            connection.setRequestProperty(header.getKey(), header.getValue());
        }
        return connection;
    }

    private static void setMethod(HttpURLConnection connection, String method) throws IOException {
        try {
            connection.setRequestMethod(method);
        } catch (IOException e) {
            // JDK 只接受标准方法，PROPFIND / MKCOL / MOVE / REPORT 等走反射设置
            forceMethod(connection, method);
        }
    }

    private static void forceMethod(HttpURLConnection connection, String method) {
        try {
            Field field = HttpURLConnection.class.getDeclaredField("method");
            field.setAccessible(true);
            field.set(connection, method);
        } catch (ReflectiveOperationException ignore) {
            // 反射失败会在 getResponseCode 时以 ProtocolException 暴露，这里不吞请求
        }
    }

    private static void writeBody(HttpURLConnection connection, Request request) throws IOException {
        if (request.bodyBytes != null) {
            connection.setFixedLengthStreamingMode(request.bodyBytes.length);
            if (request.bodyBytes.length > 0 || "POST".equals(request.method)
                    || "PUT".equals(request.method) || "PATCH".equals(request.method)) {
                connection.setDoOutput(true);
                OutputStream out = connection.getOutputStream();
                out.write(request.bodyBytes);
                out.flush();
            }
            return;
        }
        if (request.bodyStream == null) {
            return;
        }
        if (request.bodyLength >= 0) {
            connection.setFixedLengthStreamingMode(request.bodyLength);
            connection.setDoOutput(true);
            pumpAndClose(request.bodyStream, connection.getOutputStream());
            return;
        }
        spoolUnknownLength(connection, request);
    }

    /** 未知长度请求体：全部缓冲到临时文件后按实际长度发送。 */
    private static void spoolUnknownLength(HttpURLConnection connection, Request request) throws IOException {
        java.io.File temp = TempFiles.create("jkit-storage-spool", -1L, "-");
        try {
            long total;
            try (OutputStream fileOut = Files.newOutputStream(temp.toPath())) {
                total = pumpCounting(request.bodyStream, fileOut);
            }
            connection.setFixedLengthStreamingMode(total);
            connection.setDoOutput(true);
            InputStream fileIn = Files.newInputStream(temp.toPath());
            try {
                pumpAndClose(fileIn, connection.getOutputStream());
            } finally {
                fileIn.close();
            }
        } finally {
            TempFiles.deleteQuietly(temp);
        }
    }

    private static void pumpAndClose(InputStream in, OutputStream out) throws IOException {
        try {
            byte[] chunk = new byte[8192];
            int read;
            while ((read = in.read(chunk)) >= 0) {
                out.write(chunk, 0, read);
            }
            out.flush();
        } finally {
            try {
                in.close();
            } catch (IOException ignore) {
                // 关闭失败不影响结果
            }
        }
    }

    private static long pumpCounting(InputStream in, OutputStream out) throws IOException {
        long total = 0L;
        byte[] chunk = new byte[8192];
        int read;
        while ((read = in.read(chunk)) >= 0) {
            out.write(chunk, 0, read);
            total += read;
        }
        return total;
    }

    private static StorageException mapIoException(IOException e) {
        String message = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
        if (message != null && message.toLowerCase().contains("timed out")) {
            return StorageException.timeout("请求超时: " + message);
        }
        return StorageException.connectFailed(
                "网络错误: " + message + "（" + e.getClass().getSimpleName() + "）");
    }

    private static void closeQuietly(HttpURLConnection connection) {
        if (connection != null) {
            connection.disconnect();
        }
    }
}
