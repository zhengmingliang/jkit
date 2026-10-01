package com.alianga.jkit.mock.server;

import com.alianga.jkit.mock.MockDataFormatter;
import com.alianga.jkit.mock.MockSchema;
import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/**
 * 零依赖的本地 Mock HTTP 服务：纯 JDK {@link HttpServer} 实现，不依赖 JavaFX 与任何第三方库，
 * 供桌面工具、单测与脚本复用。
 *
 * <p><b>能力</b>：</p>
 * <ul>
 *   <li>匹配：HTTP 方法（{@code *} 任意）+ 路径模板（{@code {name}} 单段参数），路径先做
 *       percent-decode 再匹配（含中文 / 空格的请求路径可命中）</li>
 *   <li>响应：状态码、固定延迟（毫秒）、任意 Content-Type、固定响应体、
 *       {@link MockRoute#echo} 请求体回显（沿用请求 Content-Type）</li>
 *   <li>动态数据：{@link MockRoute#dynamicSchema} 开启后按 JSON Schema 每次请求生成响应体
 *       （{@link MockSchema}），生成失败回落固定 body 或错误 JSON</li>
 *   <li>CORS：所有响应带 {@code Access-Control-Allow-*}；未命中 OPTIONS 路由的预检自动 204</li>
 *   <li>热更新：运行中 {@link #setRoutes(List)} 立即生效，无需重启</li>
 *   <li>请求日志：环形保留最近 200 条，query / 请求体 / 响应体按 {@link #LOG_TEXT_MAX}
 *       字符截断；客户端提前断开、服务停止打断延迟同样留痕</li>
 * </ul>
 *
 * <p><b>健壮性</b>：路由表为空即全 404（不回落演示路由）；状态码超出 100–599 的路由按
 * 500 兜底返回错误 JSON，不让底层连接裸断；handler 顶层兜底异常并保证每条请求都有日志。</p>
 *
 * <pre>{@code
 * MockHttpServer server = new MockHttpServer();
 * server.start(18081, MockRoute.demoRoutes());
 * server.setRoutes(Arrays.asList(
 *         new MockRoute("GET", "/users/{id}", 200, 120, "application/json; charset=utf-8", "{\"id\":1}")));
 * server.stop();
 * }</pre>
 *
 * @author 郑明亮
 * @since 2.0.4
 */
public final class MockHttpServer {
    private static final int LOG_CAP = 200;
    /** 日志中 query / 请求体 / 响应体各自最多保留的字符数（截断后附长度提示）。 */
    public static final int LOG_TEXT_MAX = 2048;
    private static final String CORS_ORIGIN = "*";
    private static final String JSON_CT = "application/json; charset=utf-8";
    private static final byte[] EMPTY_BODY = new byte[0];
    private static final byte[] NOT_FOUND_BODY =
            "{\"error\":\"no matching route\"}".getBytes(StandardCharsets.UTF_8);
    private static final byte[] INVALID_STATUS_BODY =
            "{\"error\":\"invalid statusCode configured\"}".getBytes(StandardCharsets.UTF_8);

    private final Object lock = new Object();
    private final List<MockRequestLog> logs = new ArrayList<>();
    private final List<Consumer<MockRequestLog>> listeners = new CopyOnWriteArrayList<>();

    private volatile List<MockRoute> routes = Collections.emptyList();
    private volatile HttpServer server;
    private volatile ExecutorService executor;
    private volatile int port;
    private final AtomicBoolean stopping = new AtomicBoolean(false);
    /** 本次启动时刻；未运行时为 null。 */
    private volatile Instant startedAt;

    /**
     * 启动监听。若已在运行则先停止再启。
     *
     * @param port   端口
     * @param routes 路由表；为空则所有请求 404（演示路由请显式传 {@link MockRoute#demoRoutes()}）
     * @throws IOException              端口占用或绑定失败
     * @throws IllegalArgumentException 端口超出 1–65535
     */
    public void start(int port, List<MockRoute> routes) throws IOException {
        if (port < 1 || port > 65535) {
            throw new IllegalArgumentException("端口无效: " + port);
        }
        stop();
        List<MockRoute> effective = routes == null
                ? Collections.<MockRoute>emptyList()
                : Collections.unmodifiableList(new ArrayList<>(routes));
        this.routes = effective;
        this.port = port;
        this.stopping.set(false);

        HttpServer http = HttpServer.create(new InetSocketAddress("127.0.0.1", port), 0);
        http.createContext("/", this::handle);
        ExecutorService pool = Executors.newCachedThreadPool(new DaemonFactory("jkit-mock"));
        http.setExecutor(pool);
        http.start();

        synchronized (lock) {
            this.server = http;
            this.executor = pool;
            this.startedAt = Instant.now();
        }
    }

    /**
     * 停止服务并释放端口，中断正在等待延迟的 handler。可重复调用。
     */
    public void stop() {
        stopping.set(true);
        HttpServer http;
        ExecutorService pool;
        synchronized (lock) {
            http = this.server;
            pool = this.executor;
            this.server = null;
            this.executor = null;
            this.startedAt = null;
        }
        if (http != null) {
            // 立刻停，中断正在 sleep 的 handler 线程
            http.stop(0);
        }
        if (pool != null) {
            pool.shutdownNow();
        }
    }

    public boolean isRunning() {
        return server != null;
    }

    /** 基址；未运行返回 {@code null}。 */
    public String baseUrl() {
        return isRunning() ? "http://127.0.0.1:" + port : null;
    }

    public int getPort() {
        return port;
    }

    /** 本次启动时刻；未运行时返回 {@code null}。 */
    public Instant startedAt() {
        return startedAt;
    }

    /**
     * 热更新路由表：服务运行中立即生效，无需重启。
     * {@code null} 视为空列表（全部 404）。
     *
     * @param routes 新路由快照
     */
    public void setRoutes(List<MockRoute> routes) {
        this.routes = routes == null
                ? Collections.<MockRoute>emptyList()
                : Collections.unmodifiableList(new ArrayList<>(routes));
    }

    /** 当前生效路由快照（不可变）。 */
    public List<MockRoute> snapshotRoutes() {
        return routes;
    }

    /** 订阅请求日志（引擎线程回调，UI 侧自行切线程）。 */
    public void addLogListener(Consumer<MockRequestLog> listener) {
        if (listener != null) {
            listeners.add(listener);
        }
    }

    public void removeLogListener(Consumer<MockRequestLog> listener) {
        listeners.remove(listener);
    }

    /** 日志快照，最新在前。 */
    public List<MockRequestLog> snapshotLogs() {
        synchronized (logs) {
            return Collections.unmodifiableList(new ArrayList<>(logs));
        }
    }

    public void clearLogs() {
        synchronized (logs) {
            logs.clear();
        }
    }

    private void handle(HttpExchange exchange) throws IOException {
        long startNs = System.nanoTime();
        String method = exchange.getRequestMethod() == null ? "GET" : exchange.getRequestMethod();
        String rawPath = exchange.getRequestURI().getRawPath();
        String path = rawPath == null || rawPath.isEmpty() ? "/" : decodePath(rawPath);
        String query = exchange.getRequestURI().getRawQuery();
        if (query == null) {
            query = "";
        }

        MockRoute matched = null;
        int status = 500;
        byte[] responseBytes = NOT_FOUND_BODY;
        String contentType = JSON_CT;
        byte[] requestBody = EMPTY_BODY;
        long latencyMs = 0L;
        try {
            requestBody = readAll(exchange.getRequestBody());
            matched = findRoute(method, path);
            if (matched != null) {
                latencyMs = matched.latencyMs;
                status = matched.statusCode;
                contentType = matched.contentType;
                responseBytes = renderResponse(matched, requestBody);
                if (matched.echo) {
                    String reqCt = exchange.getRequestHeaders().getFirst("Content-Type");
                    if (reqCt != null && !reqCt.trim().isEmpty()) {
                        contentType = reqCt;
                    }
                }
            } else if ("OPTIONS".equalsIgnoreCase(method)) {
                // 无显式 OPTIONS 路由时自动 CORS 预检
                status = 204;
                contentType = "text/plain";
                responseBytes = EMPTY_BODY;
            } else {
                status = 404;
                contentType = JSON_CT;
                responseBytes = NOT_FOUND_BODY;
            }
            if (latencyMs > 0 && !sleepInterruptible(latencyMs)) {
                return; // 服务停止打断延迟等待：finally 里照常留痕并关闭连接
            }
            // 状态码守门：超范围会让 sendResponseHeaders 抛异常裸断连接，这里按 500 兜底
            if (status < 100 || status > 599) {
                status = 500;
                contentType = JSON_CT;
                responseBytes = INVALID_STATUS_BODY;
            }
            writeResponse(exchange, status, contentType, responseBytes);
        } catch (IOException ex) {
            // 客户端提前断开等 IO 异常：响应未完成，交 finally 留痕
        } catch (RuntimeException ex) {
            // 引擎外异常（如动态生成未捕获问题）：尽量补一个 500，日志记实际写出的错误体
            byte[] fallback = respondFallbackError(exchange, ex);
            status = 500;
            contentType = JSON_CT;
            responseBytes = fallback;
        } finally {
            long durationMs = (System.nanoTime() - startNs) / 1_000_000L;
            pushLog(new MockRequestLog(Instant.now(), method, path,
                    truncateForLog(query, LOG_TEXT_MAX), matched != null, status, durationMs,
                    truncateBytesForLog(requestBody, LOG_TEXT_MAX),
                    truncateBytesForLog(responseBytes, LOG_TEXT_MAX)));
            exchange.close();
        }
    }

    /** 按路由渲染响应字节：动态 Schema > 固定 body；echo 的请求体由调用方处理。 */
    private byte[] renderResponse(MockRoute route, byte[] requestBody) {
        if (route.dynamicSchema && route.responseSchema != null && !route.responseSchema.trim().isEmpty()) {
            try {
                Object data = new MockSchema().generate(route.responseSchema.trim());
                return MockDataFormatter.prettyJson(data).getBytes(StandardCharsets.UTF_8);
            } catch (RuntimeException ex) {
                String fallback = route.body == null || route.body.isEmpty()
                        ? "{\"error\":\"schema mock failed\",\"message\":\"" + escapeJsonBrief(ex.getMessage()) + "\"}"
                        : route.body;
                return fallback.getBytes(StandardCharsets.UTF_8);
            }
        }
        return route.echo ? requestBody : route.body.getBytes(StandardCharsets.UTF_8);
    }

    private void writeResponse(HttpExchange exchange, int status, String contentType, byte[] body)
            throws IOException {
        Headers headers = exchange.getResponseHeaders();
        headers.set("Access-Control-Allow-Origin", CORS_ORIGIN);
        headers.set("Access-Control-Allow-Methods", "GET, POST, PUT, PATCH, DELETE, OPTIONS");
        headers.set("Access-Control-Allow-Headers", "*");
        if (contentType != null && !contentType.trim().isEmpty() && status != 204) {
            headers.set("Content-Type", contentType);
        }
        if (status == 204) {
            exchange.sendResponseHeaders(status, -1);
            return;
        }
        exchange.sendResponseHeaders(status, body.length == 0 ? -1 : body.length);
        if (body.length > 0) {
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        }
    }

    /** 未知 RuntimeException 的 500 兜底：能写就写并返回写出的字节，写不了返回空数组。 */
    private byte[] respondFallbackError(HttpExchange exchange, RuntimeException ex) {
        try {
            byte[] body = ("{\"error\":\"mock handler failed\",\"message\":\""
                    + escapeJsonBrief(ex.getMessage()) + "\"}").getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", JSON_CT);
            exchange.sendResponseHeaders(500, body.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
            return body;
        } catch (IOException ignore) {
            // 客户端已断开或响应已提交，无法补救
            return EMPTY_BODY;
        }
    }

    private MockRoute findRoute(String method, String path) {
        for (MockRoute route : routes) {
            if (matchesMethod(route.method, method) && matchesPath(route.path, path)) {
                return route;
            }
        }
        return null;
    }

    /**
     * 方法匹配：路由为 {@code *} 或 null 匹配任意方法，否则忽略大小写比较。
     */
    public static boolean matchesMethod(String routeMethod, String requestMethod) {
        if (routeMethod == null || "*".equals(routeMethod)) {
            return true;
        }
        return routeMethod.equalsIgnoreCase(requestMethod);
    }

    /**
     * 路径匹配：精确相等，或逐段比较、{@code {name}} 段匹配任意非空段；首尾多余 {@code /} 不影响。
     */
    public static boolean matchesPath(String pattern, String path) {
        if (Objects.equals(pattern, path)) {
            return true;
        }
        if (pattern == null || path == null) {
            return false;
        }
        String[] pSeg = splitPath(pattern);
        String[] rSeg = splitPath(path);
        if (pSeg.length != rSeg.length) {
            return false;
        }
        for (int i = 0; i < pSeg.length; i++) {
            String p = pSeg[i];
            String r = rSeg[i];
            if (isParam(p)) {
                if (r.isEmpty()) {
                    return false;
                }
                continue;
            }
            if (!p.equals(r)) {
                return false;
            }
        }
        return true;
    }

    private static boolean isParam(String segment) {
        return segment.length() >= 3
                && segment.charAt(0) == '{'
                && segment.charAt(segment.length() - 1) == '}';
    }

    private static String[] splitPath(String path) {
        String trimmed = path;
        if (trimmed.startsWith("/")) {
            trimmed = trimmed.substring(1);
        }
        if (trimmed.endsWith("/") && trimmed.length() > 1) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        if (trimmed.isEmpty()) {
            return new String[0];
        }
        return trimmed.split("/");
    }

    /**
     * 路径 percent-decode：仅解 {@code %XX}（含 UTF-8 多字节聚合），不把 {@code +} 当空格（那是 query 规则）。
     */
    static String decodePath(String raw) {
        if (raw == null || raw.indexOf('%') < 0) {
            return raw;
        }
        ByteArrayOutputStream bytes = new ByteArrayOutputStream(raw.length());
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if (c == '%' && i + 2 < raw.length()) {
                int hi = Character.digit(raw.charAt(i + 1), 16);
                int lo = Character.digit(raw.charAt(i + 2), 16);
                if (hi >= 0 && lo >= 0) {
                    bytes.write((hi << 4) | lo);
                    i += 2;
                    continue;
                }
            }
            bytes.write(c & 0xFF);
        }
        return new String(bytes.toByteArray(), StandardCharsets.UTF_8);
    }

    private boolean sleepInterruptible(long latencyMs) {
        long deadline = System.nanoTime() + latencyMs * 1_000_000L;
        while (!stopping.get()) {
            long remainNs = deadline - System.nanoTime();
            if (remainNs <= 0) {
                return true;
            }
            try {
                Thread.sleep(Math.min(50L, Math.max(1L, remainNs / 1_000_000L)));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return false;
    }

    private void pushLog(MockRequestLog entry) {
        synchronized (logs) {
            logs.add(0, entry);
            while (logs.size() > LOG_CAP) {
                logs.remove(logs.size() - 1);
            }
        }
        for (Consumer<MockRequestLog> listener : listeners) {
            try {
                listener.accept(entry);
            } catch (RuntimeException ignore) {
                // UI 监听失败不影响引擎
            }
        }
    }

    private static byte[] readAll(InputStream in) throws IOException {
        if (in == null) {
            return EMPTY_BODY;
        }
        return in.readAllBytes();
    }

    /**
     * 将文本截到 {@code maxChars}；超长时追加原文长度提示。供日志落库，避免撑爆内存。
     */
    public static String truncateForLog(String value, int maxChars) {
        if (value == null || value.isEmpty()) {
            return "";
        }
        if (maxChars < 1) {
            return "";
        }
        if (value.length() <= maxChars) {
            return value;
        }
        return value.substring(0, maxChars) + "…(" + value.length() + " chars)";
    }

    /**
     * 将字节按 UTF-8 解码后截断。超大 body 先限字节再解码，避免为日志整段进堆。
     */
    public static String truncateBytesForLog(byte[] bytes, int maxChars) {
        if (bytes == null || bytes.length == 0 || maxChars < 1) {
            return "";
        }
        // UTF-8 最坏 4 字节/字符；多读一点以免截在多字节中间过早
        int byteLimit = Math.min(bytes.length, Math.max(maxChars * 4, maxChars));
        String decoded = new String(bytes, 0, byteLimit, StandardCharsets.UTF_8);
        if (bytes.length > byteLimit) {
            // 字节已被砍，按解码长度再截，并标原始字节数
            if (decoded.length() > maxChars) {
                return decoded.substring(0, maxChars) + "…(" + bytes.length + " bytes)";
            }
            return decoded + "…(" + bytes.length + " bytes)";
        }
        return truncateForLog(decoded, maxChars);
    }

    private static final class DaemonFactory implements ThreadFactory {
        private final String prefix;
        private final AtomicInteger seq = new AtomicInteger();

        DaemonFactory(String prefix) {
            this.prefix = prefix;
        }

        @Override
        public Thread newThread(Runnable r) {
            Thread t = new Thread(r, prefix + "-" + seq.incrementAndGet());
            t.setDaemon(true);
            return t;
        }
    }

    private static String escapeJsonBrief(String s) {
        if (s == null) {
            return "";
        }
        String trimmed = s.length() > 120 ? s.substring(0, 120) : s;
        return trimmed.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
