package com.alianga.jkit.mock.server;

import com.alianga.jkit.http.HttpRequest;
import com.alianga.jkit.http.HttpResponse;
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
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.function.IntSupplier;

/**
 * 零依赖的本地 Mock HTTP 服务：纯 JDK {@link HttpServer} 实现（代理转发复用 jkit 自带
 * HTTP 客户端），不依赖 JavaFX 与任何第三方库，供桌面工具、单测与脚本复用。
 *
 * <p><b>能力</b>：</p>
 * <ul>
 *   <li>匹配：HTTP 方法（{@code *} 任意）+ 路径模板（{@code {name}} 单段参数），路径先做
 *       percent-decode 再匹配；{@code enabled=false} 的路由跳过</li>
 *   <li>响应：状态码、固定延迟、任意 Content-Type、固定响应体、{@link MockRoute#echo}
 *       请求体回显、附加响应头；动态数据三选一——{@code dynamicSchema} 按 JSON Schema 生成
 *       （优先）、{@code dynamicTemplate} 按 Mock.js 模板生成、静态 body</li>
 *   <li>响应方案：{@link MockRoute#responses} 多响应，按激活规则（query / header / body）
 *       带规则的方案先评估，第一个全中的生效；全不中回落无规则的默认方案</li>
 *   <li>占位符：响应体与响应头值支持 {@code {{param.id}}} / {@code {{query.x}}} /
 *       {@code {{header.x}}} / {@code {{body.a.b}}} / {@code {{method}}} / {@code {{path}}}</li>
 *   <li>代理回源：{@link #setProxyTarget(String)} 后未命中路由的请求原样转发到真实后端</li>
 *   <li>CORS：所有响应带 {@code Access-Control-Allow-*}；无代理时未命中 OPTIONS 路由的
 *       预检自动 204</li>
 *   <li>热更新：运行中 {@link #setRoutes(List)} 立即生效，无需重启</li>
 *   <li>请求日志：环形保留最近 200 条，含请求头 / 响应头（每名首值）；客户端提前断开、
 *       服务停止打断延迟同样留痕</li>
 * </ul>
 *
 * <p><b>健壮性</b>：路由表为空且无代理即全 404（不回落演示路由）；状态码超出 100–599 的
 * 响应按 500 兜底返回错误 JSON；handler 顶层兜底异常并保证每条请求都有日志；
 * 请求体超过 {@link #setMaxBodyBytes(long) maxBodyBytes}（默认 64MB）立刻 413 不再读入。</p>
 *
 * <pre>{@code
 * MockHttpServer server = new MockHttpServer();
 * server.start(18081, MockRoute.demoRoutes());
 * server.setProxyTarget("https://real-api.example.com");   // 可选：未命中转发真实后端
 * server.setRoutes(Arrays.asList(MockRoute.builder()
 *         .method("GET").path("/users/{id}")
 *         .dynamicTemplate(true)
 *         .body("{\"id\":\"{{param.id}}\",\"name\":\"@cname\"}")
 *         .header("X-Mock", "1")
 *         .response(MockResponse.builder().statusCode(404)
 *                 .rule(MockResponseRule.queryEquals("debug", "1"))
 *                 .body("{\"error\":\"not found\"}").build())
 *         .build()));
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
    /** 资源存在但指定 id 的记录不存在（REST 资源级 404，与「路由未匹配」区分）。 */
    private static final byte[] RESOURCE_NOT_FOUND_BODY =
            "{\"error\":\"record not found\"}".getBytes(StandardCharsets.UTF_8);
    private static final byte[] INVALID_STATUS_BODY =
            "{\"error\":\"invalid statusCode configured\"}".getBytes(StandardCharsets.UTF_8);
    private static final byte[] BAD_REQUEST_BODY =
            "{\"error\":\"json object body required\"}".getBytes(StandardCharsets.UTF_8);
    private static final byte[] METHOD_NOT_ALLOWED_BODY =
            "{\"error\":\"method not allowed\"}".getBytes(StandardCharsets.UTF_8);
    private static final byte[] UPGRADE_REQUIRED_BODY =
            "{\"error\":\"this route is a WebSocket mock; connect with ws://\"}"
                    .getBytes(StandardCharsets.UTF_8);
    /** 请求体默认大小上限（防止大文件上传白噪音 OOM）：64MB。 */
    public static final long DEFAULT_MAX_BODY_BYTES = 64L * 1024 * 1024;
    private static final String SSE_CONTENT_TYPE = "text/event-stream; charset=utf-8";
    /** 自动 WS 端口时从 HTTP 端口 +1 起最多扫描的端口数。 */
    private static final int WS_PORT_SCAN = 20;
    private static final List<String> HOP_BY_HOP = Collections.unmodifiableList(Arrays.asList(
            "connection", "keep-alive", "proxy-authenticate", "proxy-authorization",
            "te", "trailer", "trailers", "transfer-encoding", "upgrade"));

    private final Object lock = new Object();
    private final List<MockRequestLog> logs = new ArrayList<>();
    private final List<Consumer<MockRequestLog>> listeners = new CopyOnWriteArrayList<>();

    private volatile List<MockRoute> routes = Collections.emptyList();
    /** CRUD 资源存储；缺省纯内存，可换带落盘目录的实现。 */
    private volatile MockCrudStore crudStore = MockCrudStore.inMemory();
    /** 全局变量（{{var.name}} 取值来源）。 */
    private volatile Map<String, String> variables = Collections.emptyMap();
    private volatile HttpServer server;
    private volatile ExecutorService executor;
    /** SSE 帧推送共享调度器（随 HTTP 服务启停）。 */
    private volatile ScheduledExecutorService scheduler;
    /** 伴随 WebSocket 服务；{@code webSocketPort < 0} 时不启动。 */
    private volatile MockWebSocketServer webSocketServer;
    /** WS 监听端口：-1 关闭（缺省，历史行为）；0=HTTP 端口+1；正数为指定端口。 */
    private volatile int webSocketPort = -1;
    private volatile int actualWebSocketPort = -1;
    private volatile int port;
    private volatile String proxyTarget;
    /** 请求体最大读取字节数；超过立刻 413，不在读全量。0 / 负数 = 不限。 */
    private volatile long maxBodyBytes = DEFAULT_MAX_BODY_BYTES;
    private final AtomicBoolean stopping = new AtomicBoolean(false);
    /** 本次启动时刻；未运行时为 null。 */
    private volatile Instant startedAt;
    private final Consumer<MockWebSocketServer.WsEvent> wsLogListener = this::onWsEvent;

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
        // 有界池：延迟压测场景下防止线程无界增长；排队由 HttpServer 自带派发承担。
        // SSE 长连接会长期占用池线程（阻塞在读上检测断开），200 对本地联调足够。
        ExecutorService pool = Executors.newFixedThreadPool(200, new DaemonFactory("jkit-mock"));
        http.setExecutor(pool);
        ScheduledExecutorService sseScheduler =
                MockPushScheduler.newDaemonScheduler("jkit-mock-sse");
        http.start();

        MockWebSocketServer ws = null;
        int wsPort = -1;
        if (webSocketPort >= 0) {
            ws = new MockWebSocketServer();
            ws.setVariables(variables);
            ws.addEventListener(wsLogListener);
            try {
                wsPort = startCompanionWs(ws, effective, port);
            } catch (IOException | RuntimeException ex) {
                http.stop(0);
                pool.shutdownNow();
                sseScheduler.shutdownNow();
                throw ex;
            }
        }

        synchronized (lock) {
            this.server = http;
            this.executor = pool;
            this.scheduler = sseScheduler;
            this.webSocketServer = ws;
            this.actualWebSocketPort = wsPort;
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
        ScheduledExecutorService sseScheduler;
        MockWebSocketServer ws;
        synchronized (lock) {
            http = this.server;
            pool = this.executor;
            sseScheduler = this.scheduler;
            ws = this.webSocketServer;
            this.server = null;
            this.executor = null;
            this.scheduler = null;
            this.webSocketServer = null;
            this.actualWebSocketPort = -1;
            this.startedAt = null;
        }
        if (ws != null) {
            ws.removeEventListener(wsLogListener);
            ws.stop();
        }
        if (http != null) {
            // 立刻停，中断正在 sleep 或阻塞读的 handler 线程
            http.stop(0);
        }
        if (sseScheduler != null) {
            sseScheduler.shutdownNow();
        }
        if (pool != null) {
            pool.shutdownNow();
        }
    }

    public boolean isRunning() {
        return server != null;
    }

    /**
     * 启动伴随 WS 服务：自动模式（{@code webSocketPort==0}）从 HTTP 端口 +1 起顺延，
     * 最多尝试 {@link #WS_PORT_SCAN} 个端口；显式端口则只绑该端口（占用即失败）。
     *
     * @return 实际绑定的 WS 端口
     * @throws IOException 全部候选端口均不可用（自动）或指定端口被占用（显式）
     */
    private int startCompanionWs(MockWebSocketServer ws, List<MockRoute> routes, int httpPort)
            throws IOException {
        if (webSocketPort > 0) {
            ws.start(webSocketPort, routes);
            return webSocketPort;
        }
        IOException last = null;
        for (int candidate = httpPort + 1; candidate <= httpPort + WS_PORT_SCAN; candidate++) {
            try {
                ws.start(candidate, routes);
                return candidate;
            } catch (IOException | RuntimeException ex) {
                last = ex instanceof IOException ? (IOException) ex
                        : new IOException(ex.getMessage(), ex);
            }
        }
        throw new IOException("WebSocket 伴随端口 " + (httpPort + 1) + "-"
                + (httpPort + WS_PORT_SCAN) + " 均被占用", last);
    }

    /** 基址；未运行返回 {@code null}。 */
    public String baseUrl() {
        return isRunning() ? "http://127.0.0.1:" + port : null;
    }

    public int getPort() {
        return port;
    }

    /**
     * 配置伴随 WebSocket 服务端口：{@code -1}（缺省）不启动；{@code 0} 跟随 HTTP
     * 端口 +1；正数为指定端口。仅在下一次 {@link #start} 生效，运行中改动需重启服务。
     *
     * @param wsPort WS 端口
     */
    public void setWebSocketPort(int wsPort) {
        this.webSocketPort = wsPort;
    }

    /** 当前 WS 端口配置（-1 关闭 / 0 跟随）。 */
    public int getWebSocketPort() {
        return webSocketPort;
    }

    /** 运行中伴随 WS 服务的实际端口；未启动返回 {@code -1}。 */
    public int actualWebSocketPort() {
        return actualWebSocketPort;
    }

    /** WS 基址；WS 未启用或未运行返回 {@code null}。 */
    public String webSocketUrl() {
        MockWebSocketServer ws = webSocketServer;
        return ws == null || !ws.isRunning() ? null : "ws://127.0.0.1:" + actualWebSocketPort;
    }

    /** 本次启动时刻；未运行时返回 {@code null}。 */
    public Instant startedAt() {
        return startedAt;
    }

    /**
     * 配置代理回源：未命中路由的请求原样转发到该基址（如 {@code https://real-api.example.com}）。
     * 传 {@code null} / 空串关闭。
     *
     * @param baseUrl 目标基址；末尾 {@code /} 会去掉
     */
    public void setProxyTarget(String baseUrl) {
        String target = baseUrl == null ? "" : baseUrl.trim();
        while (target.endsWith("/")) {
            target = target.substring(0, target.length() - 1);
        }
        this.proxyTarget = target.isEmpty() ? null : target;
    }

    /** 当前代理回源基址；未配置返回 {@code null}。 */
    public String getProxyTarget() {
        return proxyTarget;
    }

    /**
     * 配置请求体最大读取字节数（防 OOM）。缺省 {@link #DEFAULT_MAX_BODY_BYTES}；
     * 传 {@code 0} / 负数表示不限。运行中修改对后续请求立即生效。
     */
    public void setMaxBodyBytes(long maxBodyBytes) {
        this.maxBodyBytes = maxBodyBytes > 0 ? maxBodyBytes : Long.MAX_VALUE;
    }

    /** 当前请求体上限；{@link Long#MAX_VALUE} 表示不限。 */
    public long getMaxBodyBytes() {
        return maxBodyBytes;
    }

    /**
     * 配置 CRUD 资源存储。缺省纯内存；传入带 {@code dataDir} 的实现后，增删改会原子落盘
     * 到 {@code <dataDir>/<resource>.json}，重启自动加载。
     *
     * @param store 资源存储，传 {@code null} 恢复纯内存
     */
    public void setDataStore(MockCrudStore store) {
        this.crudStore = store == null ? MockCrudStore.inMemory() : store;
    }

    /** 当前 CRUD 资源存储。 */
    public MockCrudStore getDataStore() {
        return crudStore;
    }

    /**
     * 配置服务级全局变量：模板占位符 {@code {{var.name}}} 的取值来源。
     * 传 {@code null} / 空表清空。
     */
    public void setVariables(Map<String, String> variables) {
        Map<String, String> snapshot = variables == null
                ? Collections.<String, String>emptyMap()
                : Collections.unmodifiableMap(new LinkedHashMap<>(variables));
        this.variables = snapshot;
        MockWebSocketServer ws = webSocketServer;
        if (ws != null) {
            ws.setVariables(snapshot);
        }
    }

    /** 当前全局变量（不可变）。 */
    public Map<String, String> getVariables() {
        return variables;
    }

    /**
     * 热更新路由表：服务运行中立即生效，无需重启。
     * {@code null} 视为空列表（全部 404）。
     *
     * @param routes 新路由快照
     */
    public void setRoutes(List<MockRoute> routes) {
        List<MockRoute> snapshot = routes == null
                ? Collections.<MockRoute>emptyList()
                : Collections.unmodifiableList(new ArrayList<>(routes));
        this.routes = snapshot;
        MockWebSocketServer ws = webSocketServer;
        if (ws != null) {
            ws.setRoutes(snapshot);
        }
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
        String rawQuery = exchange.getRequestURI().getRawQuery();
        Map<String, String> requestHeaders = headerSnapshot(exchange.getRequestHeaders());

            MockRoute matched = null;
            String protocol = "HTTP";
            int status = 500;
        byte[] responseBytes = NOT_FOUND_BODY;
        String contentType = JSON_CT;
        long latencyMs = 0L;
        byte[] requestBody = EMPTY_BODY;
        boolean proxied = false;
        String faultLabel = "";
        try {
            LimitedBody limited = readAll(exchange.getRequestBody(), maxBodyBytes);
            if (limited.exceeded) {
                // 请求体超过 maxBodyBytes：不等读完就断，直接 413。
                // 不进路由匹配/回显/转发，finally 照常留一条日志（body 为已读前缀）。
                requestBody = limited.bytes;
                status = 413;
                contentType = JSON_CT;
                responseBytes = payloadTooLargeBody(maxBodyBytes);
                writeResponse(exchange, status, contentType, responseBytes);
                return;
            }
            requestBody = limited.bytes;
            matched = findRoute(method, path);
            boolean streamed = false;
            if (matched != null) {
                MockRequestContext ctx = MockRequestContext.of(method, path,
                        matchPathParams(matched.path, path), rawQuery,
                        exchange.getRequestHeaders(), requestBody, variables);
                Resolved resolved;
                if (matched.isSse()) {
                    // SSE：sendResponseHeaders 后长连接推送，本线程阻塞到客户端断开
                    resolved = serveSse(matched, ctx, exchange);
                    streamed = true;
                    protocol = "SSE";
                } else if (matched.isWebSocket()) {
                    // WS 路由被当普通 HTTP 访问：提示需要 Upgrade（浏览器 EventSource/fetch 可见）
                    Map<String, List<String>> upgrade = new LinkedHashMap<>();
                    upgrade.put("Upgrade", Collections.singletonList("websocket"));
                    upgrade.put("Connection", Collections.singletonList("Upgrade"));
                    resolved = new Resolved(426, 0L, JSON_CT, UPGRADE_REQUIRED_BODY, upgrade);
                } else {
                    resolved = matched.crud
                            ? serveCrud(matched, method, path, ctx)
                            : resolveRoute(matched, ctx, requestBody);
                }
                latencyMs = resolved.latencyMs;
                status = resolved.status;
                contentType = resolved.contentType;
                responseBytes = resolved.body;
                if (!streamed && resolved.headers != null && !resolved.headers.isEmpty()) {
                    // SSE 已在分派内部提交响应头，不能再改；普通响应统一在这里挂附加头（同名多枚，审计 P1-2）
                    setResponseHeaders(exchange.getResponseHeaders(), resolved.headers);
                }
            } else if (proxyTarget != null) {
                // 代理回源：未命中路由原样转发真实后端
                Resolved forwarded = forward(exchange, method, rawPath, rawQuery, requestBody);
                status = forwarded.status;
                contentType = forwarded.contentType;
                responseBytes = forwarded.body;
                if (forwarded.headers != null && !forwarded.headers.isEmpty()) {
                    setResponseHeaders(exchange.getResponseHeaders(), forwarded.headers);
                }
                proxied = true;
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
            if (!streamed) {
                if (latencyMs > 0) {
                    long maxMs = matched != null && matched.latencyMaxMs > latencyMs
                            ? matched.latencyMaxMs : latencyMs;
                    if (maxMs > latencyMs) {
                        // 延迟区间随机：[min, max]，更接近真实网络抖动
                        latencyMs += ThreadLocalRandom.current().nextLong(maxMs - latencyMs + 1);
                    }
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
                // 故障注入在「延迟之后、写出之前」生效：延迟与故障可叠加，先慢后断/先慢后坏
                MockFault fault = faultOf(matched);
                if (fault != null && fault.probabilityPercent > 0 && hitFaultProbability(fault)) {
                    faultLabel = fault.toString();
                    switch (fault.mode) {
                        case CONNECTION_CLOSE:
                            // 近似「拔线」：JDK HttpServer 做不到精确 TCP RST，这里不写任何响应
                            // 直接结束交换，sun 实现按响应未提交回收连接；客户端通常读到
                            // EOF / empty reply / connection reset，足以击穿「必然有响应」的容错
                            exchange.close();
                            return;
                        case EMPTY_RESPONSE:
                            // 状态码与响应头正常，体为空
                            responseBytes = EMPTY_BODY;
                            break;
                        case TRUNCATED_BODY:
                            // writeTruncated 自己声明头、写半截、断流收场；exchange 已提交，
                            // 不能再走统一 writeResponse（重复 sendResponseHeaders 会吃掉半截效果）
                            writeTruncated(exchange, status, contentType, responseBytes, fault);
                            return;
                        case SLOW_BODY:
                            // writeSlow 自己声明 chunked 头并分块写完;out 已关闭，主体不再由
                            // 统一出口重写（重写会把 chunked 再提交一次，客户端取到截短体）
                            writeSlow(exchange, status, contentType, responseBytes, fault);
                            return;
                        case INVALID_JSON:
                        default:
                            responseBytes = corruptJson(responseBytes);
                            contentType = JSON_CT;
                            break;
                    }
                }
                writeResponse(exchange, status, contentType, responseBytes);
            }
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
            // 响应头在统一出口拍快照：普通 / 代理 / SSE / 兜底 500 四条路径此刻都已把
            // 实际写出的头放进 getResponseHeaders()（含 CORS 与 Content-Type）
            Map<String, String> responseHeaders = headerSnapshot(exchange.getResponseHeaders());
            pushLog(new MockRequestLog(Instant.now(), method, path,
                    truncateForLog(rawQuery == null ? "" : rawQuery, LOG_TEXT_MAX),
                    matched != null || proxied, status, durationMs,
                    truncateBytesForLog(requestBody, LOG_TEXT_MAX),
                    truncateBytesForLog(responseBytes, LOG_TEXT_MAX), proxied, protocol,
                    requestHeaders, responseHeaders, faultLabel));
            exchange.close();
        }
    }

    /**
     * CRUD 资源模式：路由 path 即资源基址（如 {@code /users}），自动服务
     * {@code GET /users}（列表 + 过滤分页排序）、{@code POST /users}、
     * {@code GET/PUT/PATCH/DELETE /users/{id}}；数据来自 {@link MockCrudStore}，
     * 初始数据可用 body（JSON 数组）播种。
     */
    private Resolved serveCrud(MockRoute route, String method, String path, MockRequestContext ctx) {
        String base = route.path.endsWith("/")
                ? route.path.substring(0, route.path.length() - 1) : route.path;
        String resource = base;
        int slash = base.lastIndexOf('/');
        if (slash >= 0 && slash + 1 < base.length()) {
            resource = base.substring(slash + 1);
        }
        String rest = path.equals(base) ? "" : path.substring(base.length());
        if (rest.startsWith("/")) {
            rest = rest.substring(1);
        }
        // 首次访问播种：body 为 JSON 数组时整体作为初始数据；为单个 JSON 对象时作为唯一一条记录。
        String seedText = route.body == null ? "" : route.body.trim();
        if (seedText.startsWith("[") || seedText.startsWith("{")) {
            try {
                Object parsed = com.alianga.jkit.json.JSON.parse(seedText);
                List<Map<String, Object>> seed = new ArrayList<>();
                if (parsed instanceof List) {
                    for (Object o : (List<?>) parsed) {
                        if (o instanceof Map) {
                            @SuppressWarnings("unchecked")
                            Map<String, Object> row = (Map<String, Object>) o;
                            seed.add(row);
                        }
                    }
                } else if (parsed instanceof Map) {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> row = (Map<String, Object>) parsed;
                    seed.add(row);
                }
                if (!seed.isEmpty()) {
                    crudStore.seed(resource, seed);
                }
            } catch (RuntimeException ignore) {
                // 播种失败按空资源处理
            }
        }
        long latencyMs = route.latencyMs;
        Map<String, List<String>> headers = renderHeadersSafe(route.headers, ctx);
        boolean isGet = "GET".equalsIgnoreCase(method);
        boolean isPost = "POST".equalsIgnoreCase(method);
        boolean isPut = "PUT".equalsIgnoreCase(method);
        boolean isPatch = "PATCH".equalsIgnoreCase(method);
        boolean isDelete = "DELETE".equalsIgnoreCase(method);

        if (rest.isEmpty()) {
            if (isGet) {
                List<Map<String, Object>> rows = crudStore.query(resource, ctx.queryMap());
                return new Resolved(200, latencyMs, JSON_CT,
                        MockDataFormatter.prettyJson(rows).getBytes(StandardCharsets.UTF_8), headers);
            }
            if (isPost) {
                Map<String, Object> body = parseJsonObject(ctx);
                if (body == null) {
                    return new Resolved(400, latencyMs, JSON_CT,
                            BAD_REQUEST_BODY, headers);
                }
                Map<String, Object> created = crudStore.insert(resource, body);
                return new Resolved(201, latencyMs, JSON_CT,
                        MockDataFormatter.prettyJson(created).getBytes(StandardCharsets.UTF_8), headers);
            }
            return new Resolved(405, latencyMs, JSON_CT, METHOD_NOT_ALLOWED_BODY, headers);
        }

        // rest 形如 "107" 或 "107/extra"：资源详情只允许单层，多段路径视为未匹配
        if (rest.contains("/")) {
            return new Resolved(404, latencyMs, JSON_CT, NOT_FOUND_BODY, headers);
        }
        String id = rest;
        if (isGet) {
            Map<String, Object> row = crudStore.find(resource, id);
            return row == null
                    ? new Resolved(404, latencyMs, JSON_CT, RESOURCE_NOT_FOUND_BODY, headers)
                    : new Resolved(200, latencyMs, JSON_CT,
                            MockDataFormatter.prettyJson(row).getBytes(StandardCharsets.UTF_8), headers);
        }
        if (isPut || isPatch) {
            Map<String, Object> body = parseJsonObject(ctx);
            if (body == null) {
                return new Resolved(400, latencyMs, JSON_CT, BAD_REQUEST_BODY, headers);
            }
            Map<String, Object> updated = crudStore.update(resource, id, body, isPatch);
            return updated == null
                    ? new Resolved(404, latencyMs, JSON_CT, RESOURCE_NOT_FOUND_BODY, headers)
                    : new Resolved(200, latencyMs, JSON_CT,
                            MockDataFormatter.prettyJson(updated).getBytes(StandardCharsets.UTF_8), headers);
        }
        if (isDelete) {
            Map<String, Object> removed = crudStore.delete(resource, id);
            return removed == null
                    ? new Resolved(404, latencyMs, JSON_CT, RESOURCE_NOT_FOUND_BODY, headers)
                    : new Resolved(204, latencyMs, "text/plain", EMPTY_BODY, headers);
        }
        return new Resolved(405, latencyMs, JSON_CT, METHOD_NOT_ALLOWED_BODY, headers);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> parseJsonObject(MockRequestContext ctx) {
        try {
            Object parsed = com.alianga.jkit.json.JSON.parse(ctx.bodyText());
            return parsed instanceof Map ? (Map<String, Object>) parsed : null;
        } catch (RuntimeException e) {
            return null;
        }
    }

    /**
     * 组装一条路由的响应：历史整包回显 > 请求参数回显（显式开启）> 响应方案
     * > 动态 Schema > 动态模板 / 静态 body。
     */
    private Resolved resolveRoute(MockRoute route, MockRequestContext ctx, byte[] requestBody) {
        if (route.echo) {
            String reqCt = ctx.headerFirst("Content-Type");
            List<String> errors = route.templated ? new ArrayList<>() : null;
            return new Resolved(route.statusCode, route.latencyMs,
                    reqCt == null || reqCt.trim().isEmpty() ? route.contentType : reqCt,
                    requestBody,
                    withWarning(renderHeadersSafe(route, ctx, errors), errors));
        }
        if (route.echoRequest.enabled) {
            // 「返回请求参数」模式：body 为空直接返回回显 JSON；非空时与模板叠加（{{request}}）
            List<String> errors = route.templated ? new ArrayList<>() : null;
            String body = MockBodyRenderer.routeFrame(route, ctx, errors);
            return new Resolved(route.statusCode, route.latencyMs, route.contentType,
                    body.getBytes(StandardCharsets.UTF_8),
                    withWarning(renderHeadersSafe(route, ctx, errors), errors));
        }
        MockResponse picked = pickResponse(route, ctx);
        if (picked != null) {
            List<String> errors = new ArrayList<>();
            String body = MockBodyRenderer.render(picked.body, picked.dynamicTemplate,
                    route.templated, ctx, null, errors);
            return new Resolved(picked.statusCode, picked.latencyMs, picked.contentType,
                    body.getBytes(StandardCharsets.UTF_8),
                    withWarning(renderHeaders(route, picked.headers, ctx, errors), errors));
        }
        if (route.dynamicSchema && !route.responseSchema.trim().isEmpty()) {
            return new Resolved(route.statusCode, route.latencyMs, route.contentType,
                    renderSchemaBody(route, ctx).getBytes(StandardCharsets.UTF_8),
                    renderHeadersSafe(route, ctx, null));
        }
        List<String> errors = new ArrayList<>();
        String body = MockBodyRenderer.render(route.body, route.dynamicTemplate,
                route.templated, ctx, null, errors);
        return new Resolved(route.statusCode, route.latencyMs, route.contentType,
                body.getBytes(StandardCharsets.UTF_8),
                withWarning(renderHeadersSafe(route, ctx, errors), errors));
    }

    /**
     * 模板渲染失败时把原因通过 {@code X-Mock-Warning} 响应头暴露（HTTP 头只能 ASCII，
     * 原因截断并去非法字符）；无错误返回原表。
     */
    private static Map<String, List<String>> withWarning(Map<String, List<String>> headers,
                                                   List<String> errors) {
        if (errors == null || errors.isEmpty()) {
            return headers;
        }
        String reason = errors.get(0);
        String cleaned = reason.replaceAll("[^\\x20-\\x7E]", "?");
        if (cleaned.length() > 160) {
            cleaned = cleaned.substring(0, 160);
        }
        Map<String, List<String>> out = headers == null
                ? new LinkedHashMap<String, List<String>>() : new LinkedHashMap<>(headers);
        out.put("X-Mock-Warning", Collections.singletonList(cleaned));
        return out;
    }

    /**
     * 概率随机源（可注入，单测固定结果）；缺省 ThreadLocalRandom。约定输出 0–99 的整数，
     * 与 {@link MockFault#probabilityPercent} 直接比较（{@code hit < probability} 即命中）。
     * 不直接写死 {@code Math.random()}，就是为了测试时能 0 恒不中、99 恒中。
     */
    private volatile IntSupplier faultProbabilitySource =
            () -> ThreadLocalRandom.current().nextInt(100);

    /**
     * 注入故障概率随机源（0–99）；传 null 恢复缺省。测试与需要可复现故障的场景用。
     *
     * @param source 随机源；命中判定为 {@code source.getAsInt() < fault.probabilityPercent}
     */
    public void setFaultProbabilitySource(IntSupplier source) {
        this.faultProbabilitySource = source == null
                ? () -> ThreadLocalRandom.current().nextInt(100) : source;
    }

    /** 当前路由的故障配置；NONE（含未配置）返回 null，统一「无故障」分支。 */
    private static MockFault faultOf(MockRoute route) {
        MockFault fault = route == null ? null : route.fault;
        return fault == null || fault.mode == MockFault.Mode.NONE ? null : fault;
    }

    /** 概率命中判定：随机源返回值落在 [0, probabilityPercent) 即命中（percent=100 必中）。 */
    private boolean hitFaultProbability(MockFault fault) {
        int roll = faultProbabilitySource.getAsInt();
        return roll >= 0 && roll < fault.probabilityPercent;
    }

    /**
     * TRUNCATED_BODY：声明完整 Content-Length，只写出一部分后断开。
     * JDK HttpServer 的 sendResponseHeaders 已承诺长度，中途断流客户端侧就是
     * Incomplete/EOF/SocketException——这正是要模拟的现象。返回已写出的前缀供日志留痕。
     */
    private void writeTruncated(HttpExchange exchange, int status, String contentType,
                                byte[] body, MockFault fault) throws IOException {
        Headers headers = exchange.getResponseHeaders();
        headers.set("Access-Control-Allow-Origin", CORS_ORIGIN);
        if (contentType != null && !contentType.trim().isEmpty()) {
            headers.set("Content-Type", contentType);
        }
        // 声明全量长度，但只写一部分：客户端按声明长度读会一直等到断流
        exchange.sendResponseHeaders(status, body.length);
        int written = body.length == 0 ? 0 : fault.truncatedBytesFor(body.length);
        OutputStream out = exchange.getResponseBody();
        if (written > 0) {
            out.write(body, 0, written);
            out.flush();
        }
        // 不 close out，让交换直接结束——半截响应由连接关闭顶替
        exchange.close();
    }

    /**
     * SLOW_BODY：分块写出，块间 sleep。sleep 温和抖动 50ms 步进，响应停服同样可控。
     */
    private void writeSlow(HttpExchange exchange, int status, String contentType,
                           byte[] body, MockFault fault) throws IOException {
        Headers headers = exchange.getResponseHeaders();
        headers.set("Access-Control-Allow-Origin", CORS_ORIGIN);
        if (contentType != null && !contentType.trim().isEmpty()) {
            headers.set("Content-Type", contentType);
        }
        // 分块输出没有总长：0 让 JDK HttpServer 走 chunked
        exchange.sendResponseHeaders(status, 0L);
        OutputStream out = exchange.getResponseBody();
        int chunk = fault.chunkBytes > 0 ? fault.chunkBytes : Math.max(1, body.length / 2);
        long delay = Math.max(0L, fault.chunkDelayMs);
        for (int offset = 0; offset < body.length || offset == 0 && body.length == 0;
                offset += chunk) {
            int to = Math.min(offset + chunk, body.length);
            if (to > offset) {
                out.write(body, offset, to - offset);
                out.flush();
            }
            if (to >= body.length) {
                break;
            }
            if (delay > 0 && !sleepInterruptible(delay)) {
                // 服务停止打断分块间隔：直接断流收尾
                exchange.close();
                return;
            }
        }
        out.close();
    }

    /**
     * INVALID_JSON：把 JSON 体在末尾「截断」或「多出尾部逗号」刻意损坏；
     * Content-Type 仍由路由声明（application/json），客户端解析必失败。
     */
    private static byte[] corruptJson(byte[] body) {
        String text = new String(body, StandardCharsets.UTF_8);
        String trimmed = text.stripTrailing();
        if (trimmed.isEmpty()) {
            return "{\"broken\":,".getBytes(StandardCharsets.UTF_8);
        }
        char last = trimmed.charAt(trimmed.length() - 1);
        if (last == '}' || last == ']') {
            // 尾部逗号：{"a":1,} / [1,] —— 历史上最经典的解析器雷区
            return (trimmed.substring(0, trimmed.length() - 1) + "," + last)
                    .getBytes(StandardCharsets.UTF_8);
        }
        // 非括号结尾（纯文本体）：直接截掉末尾两个字符，制造截断类损坏
        return trimmed.substring(0, Math.max(1, trimmed.length() - 2))
                .getBytes(StandardCharsets.UTF_8);
    }

    /** SSE 心跳注释间隔：同时承担断开探测（写出失败即结束连接），1s 让单测能快速收尾。 */
    private static final long SSE_HEARTBEAT_MS = 1000L;

    /**
     * SSE 长连接：先响应 {@code 200 + text/event-stream}，再按 {@link MockPushPolicy}
     * 定时写帧；EventSource（GET）无请求体可探测断开，因此靠每秒一次的注释心跳写出失败
     * 感知客户端关闭。本方法阻塞到客户端断开、有限次推送完成或服务停止，
     * 返回后由统一出口留日志。帧内容与 HTTP 同源（占位符 / Mock.js / 请求参数回显）。
     */
    private Resolved serveSse(MockRoute route, MockRequestContext ctx, HttpExchange exchange) {
        Map<String, List<String>> headers = renderHeadersSafe(route.headers, ctx);
        int status = route.statusCode <= 0 ? 200 : route.statusCode;
        SsePusher pusher = null;
        try {
            exchange.getResponseHeaders().set("Content-Type", SSE_CONTENT_TYPE);
            exchange.getResponseHeaders().set("Cache-Control", "no-cache");
            exchange.getResponseHeaders().set("Connection", "keep-alive");
            exchange.getResponseHeaders().set("Access-Control-Allow-Origin", CORS_ORIGIN);
            if (headers != null) {
                setResponseHeaders(exchange.getResponseHeaders(), headers);
            }
            exchange.sendResponseHeaders(status, 0);
            pusher = new SsePusher(exchange.getResponseBody(), route, ctx);
            MockPushPolicy policy = route.pushPolicy;
            if (policy == null || policy.mode == MockPushPolicy.Mode.EVENT) {
                // SSE 没有客户端上行通道，EVENT 策略不可用：防御性按「连接后推一次」处理
                policy = MockPushPolicy.once();
            }
            ScheduledFuture<?> pushFuture = MockPushScheduler.schedule(scheduler, policy, pusher);
            ScheduledFuture<?> heartbeat = scheduler.scheduleAtFixedRate(pusher::heartbeat,
                    SSE_HEARTBEAT_MS, SSE_HEARTBEAT_MS, java.util.concurrent.TimeUnit.MILLISECONDS);
            pusher.awaitClose(stopping);
            if (pushFuture != null) {
                pushFuture.cancel(false);
            }
            heartbeat.cancel(true);
            pusher.finish();
            return new Resolved(status, route.latencyMs, SSE_CONTENT_TYPE,
                    pusher.summary().getBytes(StandardCharsets.UTF_8), null);
        } catch (IOException | RuntimeException e) {
            // 客户端断开等 IO 异常：连接已结束，按当时状态留痕
            byte[] summary = pusher == null ? EMPTY_BODY
                    : pusher.summary().getBytes(StandardCharsets.UTF_8);
            return new Resolved(status, route.latencyMs, SSE_CONTENT_TYPE, summary, null);
        }
    }

    /** 单个 SSE 连接的帧写出器（调度线程回调，写操作串行化，断开后放行 handler 线程）。 */
    private final class SsePusher implements Runnable {
        private final OutputStream out;
        private final MockRoute route;
        private final MockRequestContext ctx;
        private final Object writeLock = new Object();
        private final AtomicInteger sequence = new AtomicInteger();
        private final java.util.concurrent.CountDownLatch closed =
                new java.util.concurrent.CountDownLatch(1);
        private final StringBuilder pushed = new StringBuilder();

        SsePusher(OutputStream out, MockRoute route, MockRequestContext ctx) {
            this.out = out;
            this.route = route;
            this.ctx = ctx;
        }

        @Override
        public void run() {
            String frame = MockBodyRenderer.routeFrame(route, ctx);
            String eventName = route.pushPolicy == null ? "" : route.pushPolicy.eventName;
            StringBuilder sb = new StringBuilder(frame.length() + 32);
            if (eventName != null && !eventName.isEmpty()) {
                sb.append("event: ").append(eventName).append('\n');
            }
            sb.append("id: ").append(sequence.incrementAndGet()).append('\n');
            String[] lines = frame.replace("\r\n", "\n").split("\n", -1);
            for (String line : lines) {
                sb.append("data: ").append(line).append('\n');
            }
            sb.append('\n');
            writeRaw(sb.toString().getBytes(StandardCharsets.UTF_8));
            synchronized (pushed) {
                if (pushed.length() < LOG_TEXT_MAX) {
                    pushed.append(frame).append('\n');
                }
            }
            // 有限次推送全部发完即结束连接（与调度器侧的自取消双保险）
            MockPushPolicy policy = route.pushPolicy;
            int maxTimes = policy == null ? 0 : policy.repeat;
            if (maxTimes > 0 && sequence.get() >= maxTimes) {
                closed.countDown();
            }
        }

        /** SSE 注释行心跳：真实链路用于保活，这里同时探测客户端断开。 */
        void heartbeat() {
            writeRaw(": ping\n\n".getBytes(StandardCharsets.UTF_8));
        }

        private void writeRaw(byte[] bytes) {
            try {
                synchronized (writeLock) {
                    out.write(bytes);
                    out.flush();
                }
            } catch (IOException e) {
                closed.countDown();
                throw new RuntimeException(e);
            }
        }

        void awaitClose(AtomicBoolean stopping) {
            while (closed.getCount() > 0 && !stopping.get()) {
                try {
                    closed.await(200L, java.util.concurrent.TimeUnit.MILLISECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }

        void finish() {
            synchronized (writeLock) {
                try {
                    out.flush();
                } catch (IOException ignore) {
                    // 关闭路径无需上报
                }
            }
        }

        String summary() {
            synchronized (pushed) {
                return truncateForLog(pushed.toString(), LOG_TEXT_MAX);
            }
        }
    }

    /** WS 伴随服务的连接 / 消息事件记入请求日志（关闭不记，避免噪声）。 */
    private void onWsEvent(MockWebSocketServer.WsEvent event) {
        if (event.kind == MockWebSocketServer.WsEvent.Kind.CLOSE) {
            return;
        }
        int status = event.kind == MockWebSocketServer.WsEvent.Kind.CONNECT ? 101 : 200;
        // WS 条目记录的是握手请求头；消息帧不是 HTTP 交互，无响应头
        Map<String, String> reqHeaders = event.kind == MockWebSocketServer.WsEvent.Kind.CONNECT
                ? event.headers : Collections.<String, String>emptyMap();
        pushLog(new MockRequestLog(Instant.now(), "WS", event.path, "",
                true, status, 0L, truncateForLog(event.text, LOG_TEXT_MAX), "", false, "WS",
                reqHeaders, Collections.<String, String>emptyMap()));
    }

    /**
     * 响应方案挑选（审计 P0-5）。单方案直接命中；多方案按路由策略：
     * <ul>
     *   <li>FIRST：先按顺序评估带激活规则的方案，第一个规则全中的生效；全不中回落到
     *       无规则的默认方案（没有默认方案则取第一条）。</li>
     *   <li>SEQUENCE：按命中次数轮播（“第 1 次 200、之后 503”），激活规则忽略。
     *       计数器挂在路由对象上，热更新换新路由实例即从 0 计。</li>
     *   <li>RANDOM：按方案权重随机（“30% 失败率”），激活规则忽略。</li>
     * </ul>
     */
    private static MockResponse pickResponse(MockRoute route, MockRequestContext ctx) {
        List<MockResponse> responses = route.responses;
        if (responses == null || responses.isEmpty()) {
            return null;
        }
        if (responses.size() == 1) {
            return responses.get(0);
        }
        MockPlanStrategy strategy = route.responseStrategy;
        if (strategy == MockPlanStrategy.SEQUENCE) {
            int index = (int) (route.planHitCounter.getAndIncrement() % responses.size());
            return responses.get(index);
        }
        if (strategy == MockPlanStrategy.RANDOM) {
            return pickWeighted(responses);
        }
        MockResponse fallback = null;
        for (MockResponse response : responses) {
            if (response.rules.isEmpty()) {
                if (fallback == null) {
                    fallback = response;
                }
                continue;
            }
            if (allRulesMatch(response, ctx)) {
                return response;
            }
        }
        return fallback != null ? fallback : responses.get(0);
    }

    /** 按权重随机挑方案（权重下限 1，见 {@link MockResponse#weight}）。 */
    private static MockResponse pickWeighted(List<MockResponse> responses) {
        long total = 0L;
        for (MockResponse response : responses) {
            total += Math.max(1, response.weight);
        }
        long pick = ThreadLocalRandom.current().nextLong(Math.max(1L, total));
        long acc = 0L;
        for (MockResponse response : responses) {
            acc += Math.max(1, response.weight);
            if (pick < acc) {
                return response;
            }
        }
        return responses.get(responses.size() - 1);
    }

    private static boolean allRulesMatch(MockResponse response, MockRequestContext ctx) {
        for (MockResponseRule rule : response.rules) {
            if (!rule.matches(ctx)) {
                return false;
            }
        }
        return true;
    }

    private String renderSchemaBody(MockRoute route, MockRequestContext ctx) {
        try {
            String schemaText = MockTemplateRenderer.render(route.responseSchema, ctx);
            Object data = new MockSchema().generate(schemaText.trim());
            return MockDataFormatter.prettyJson(data);
        } catch (RuntimeException ex) {
            return route.body == null || route.body.isEmpty()
                    ? "{\"error\":\"schema mock failed\",\"message\":\"" + escapeJsonBrief(ex.getMessage()) + "\"}"
                    : route.body;
        }
    }

    private static Map<String, List<String>> renderHeadersSafe(Map<String, List<String>> headers,
                                                               MockRequestContext ctx) {
        if (headers == null || headers.isEmpty()) {
            return null;
        }
        return MockTemplateRenderer.renderHeaders(headers, ctx);
    }

    /**
     * 路由级附加响应头渲染：templated 路由改用 {@link MockResponseTemplate}（值取不到保留
     * 原文并收集 warning），未开启时行为不变。ERRS 可空。
     */
    private static Map<String, List<String>> renderHeadersSafe(MockRoute route,
                                                               MockRequestContext ctx,
                                                               List<String> errs) {
        return renderHeaders(route, route.headers, ctx, errs);
    }

    private static Map<String, List<String>> renderHeaders(MockRoute route,
                                                           Map<String, List<String>> headers,
                                                           MockRequestContext ctx,
                                                           List<String> errs) {
        if (headers == null || headers.isEmpty()) {
            return null;
        }
        if (route.templated) {
            Map<String, List<String>> rendered =
                    MockResponseTemplate.renderHeaders(headers, ctx, errs == null
                            ? new ArrayList<String>() : errs);
            return rendered.isEmpty() ? null : rendered;
        }
        return MockTemplateRenderer.renderHeaders(headers, ctx);
    }

    /** 代理转发：未命中路由的请求原样打到真实后端，失败按 502 返回。 */
    private Resolved forward(HttpExchange exchange, String method, String rawPath,
                             String rawQuery, byte[] requestBody) {
        String target = proxyTarget + rawPath
                + (rawQuery == null || rawQuery.isEmpty() ? "" : "?" + rawQuery);
        HttpRequest request = HttpRequest.get(target).method(method);
        for (Map.Entry<String, List<String>> e : exchange.getRequestHeaders().entrySet()) {
            String name = e.getKey();
            if (name == null) {
                continue;
            }
            String lower = name.toLowerCase();
            if (HOP_BY_HOP.contains(lower) || "host".equals(lower) || "content-length".equals(lower)) {
                continue;
            }
            for (String value : e.getValue()) {
                request.header(name, value);
            }
        }
        if (requestBody.length > 0) {
            request.body(requestBody);
        }
        try {
            HttpResponse response = request.execute();
            Map<String, List<String>> headers = new LinkedHashMap<>();
            String contentType = null;
            for (Map.Entry<String, List<String>> e : response.headers().entrySet()) {
                String name = e.getKey();
                if (name == null || e.getValue() == null || e.getValue().isEmpty()) {
                    continue;
                }
                String lower = name.toLowerCase();
                if (HOP_BY_HOP.contains(lower) || lower.startsWith("access-control-")
                        || "content-length".equals(lower) || "server".equals(lower) || "date".equals(lower)) {
                    continue;
                }
                headers.put(name, new ArrayList<>(e.getValue()));
                if ("content-type".equals(lower)) {
                    contentType = e.getValue().get(0);
                }
            }
            byte[] body = response.body().bytes();
            return new Resolved(response.code(), 0L,
                    contentType == null ? JSON_CT : contentType, body, headers);
        } catch (IOException ex) {
            byte[] body = ("{\"error\":\"proxy failed\",\"message\":\""
                    + escapeJsonBrief(ex.getMessage()) + "\"}").getBytes(StandardCharsets.UTF_8);
            return new Resolved(502, 0L, JSON_CT, body, null);
        }
    }

    /** 一次响应的解析结果。 */
    private static final class Resolved {
        private final int status;
        private final long latencyMs;
        private final String contentType;
        private final byte[] body;
        private final Map<String, List<String>> headers;

        Resolved(int status, long latencyMs, String contentType, byte[] body,
                 Map<String, List<String>> headers) {
            this.status = status;
            this.latencyMs = latencyMs;
            this.contentType = contentType;
            this.body = body;
            this.headers = headers;
        }
    }

    /**
     * 把响应方案 / 代理回源的多值附加头写入交易所（审计 P1-2）：同名多枚全部落头，
     * 首枚用 set 保持与旧语义一致（覆盖系统默认同名的 CORS 等），其余枚用 add。
     */
    private static void setResponseHeaders(Headers headers, Map<String, List<String>> extra) {
        for (Map.Entry<String, List<String>> e : extra.entrySet()) {
            if (e.getKey() == null || e.getValue() == null || e.getValue().isEmpty()) {
                continue;
            }
            boolean first = true;
            for (String value : e.getValue()) {
                if (first) {
                    headers.set(e.getKey(), value);
                    first = false;
                } else {
                    headers.add(e.getKey(), value);
                }
            }
        }
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
            if (!route.enabled) {
                continue;
            }
            // CRUD 路由的方法过滤交给资源分派；SSE / WS 握手恒为 GET，方法列不参与匹配
            if (!route.crud && !route.isSse() && !route.isWebSocket()
                    && !matchesMethod(route.method, method)) {
                continue;
            }
            if (route.crud ? matchesCrudBase(route.path, path) : matchesPath(route.path, path)) {
                return route;
            }
        }
        return null;
    }

    /** CRUD 路由匹配基址或基址下的任意子路径（id 段）。 */
    static boolean matchesCrudBase(String base, String path) {
        if (base == null || path == null) {
            return false;
        }
        String trimmed = base.endsWith("/") && base.length() > 1
                ? base.substring(0, base.length() - 1) : base;
        return path.equals(trimmed) || path.startsWith(trimmed + "/");
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
        return matchPathParams(pattern, path) != null;
    }

    /**
     * 路径匹配并提取 {@code {name}} 参数；不匹配返回 {@code null}，匹配返回不可变参数表。
     *
     * @param pattern 路由模板
     * @param path    解码后的请求路径
     * @return 参数表（可能为空表）或 {@code null}
     */
    public static Map<String, String> matchPathParams(String pattern, String path) {
        if (Objects.equals(pattern, path)) {
            return Collections.emptyMap();
        }
        if (pattern == null || path == null) {
            return null;
        }
        String[] pSeg = splitPath(pattern);
        String[] rSeg = splitPath(path);
        boolean tailWildcard = pSeg.length > 0
                && pSeg[pSeg.length - 1].length() >= 4
                && pSeg[pSeg.length - 1].charAt(0) == '{'
                && pSeg[pSeg.length - 1].endsWith("*}");
        if (pSeg.length != rSeg.length && !tailWildcard) {
            return null;
        }
        if (tailWildcard && rSeg.length < pSeg.length - 1) {
            return null;
        }
        Map<String, String> params = null;
        for (int i = 0; i < pSeg.length; i++) {
            String p = pSeg[i];
            // 尾段 {name*}：吞掉剩余全部段（可空），如 /static/{file*}
            if (i == pSeg.length - 1 && p.length() >= 4 && p.charAt(0) == '{'
                    && p.endsWith("*}")) {
                if (params == null) {
                    params = new LinkedHashMap<>();
                }
                StringBuilder tail = new StringBuilder();
                for (int j = i; j < rSeg.length; j++) {
                    if (j > i) {
                        tail.append('/');
                    }
                    tail.append(rSeg[j]);
                }
                params.put(p.substring(1, p.length() - 2), tail.toString());
                return Collections.unmodifiableMap(params);
            }
            String r = rSeg[i];
            if (isParam(p)) {
                if (r.isEmpty()) {
                    return null;
                }
                if (params == null) {
                    params = new LinkedHashMap<>();
                }
                params.put(p.substring(1, p.length() - 1), r);
                continue;
            }
            if (!p.equals(r)) {
                return null;
            }
        }
        return params == null
                ? Collections.<String, String>emptyMap()
                : Collections.unmodifiableMap(params);
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

    /**
     * 请求/响应头日志快照：每名取首值，键名不区分大小写排序（同一请求多次记录展示稳定），
     * 返回不可变表。多值头（如重复 Set-Cookie 场景）仅保留第一个值。
     */
    static Map<String, String> headerSnapshot(Map<String, List<String>> headers) {
        Map<String, String> out = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        if (headers != null) {
            for (Map.Entry<String, List<String>> e : headers.entrySet()) {
                if (e.getKey() != null && e.getValue() != null && !e.getValue().isEmpty()) {
                    out.put(e.getKey(), e.getValue().get(0));
                }
            }
        }
        return Collections.unmodifiableMap(out);
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

    /**
     * 读取请求体并限制字节数：先读 {@code limit} 字节，再试图多读 1 字节判断超限。
     * 超限时 {@link LimitedBody#exceeded} 为真，且 {@code bytes} 已含前 {@code limit} 字节
     * （供日志留痕），读入的总内存不超过 limit + 1。日志最终由调用方截断展示。
     */
    private static LimitedBody readAll(InputStream in, long limit) throws IOException {
        if (in == null) {
            return new LimitedBody(EMPTY_BODY, false);
        }
        if (limit <= 0) {
            return new LimitedBody(in.readAllBytes(), false);
        }
        java.io.ByteArrayOutputStream buffer = new java.io.ByteArrayOutputStream();
        byte[] chunk = new byte[8192];
        long total = 0L;
        boolean exceeded = false;
        int n;
        while ((n = in.read(chunk)) > 0) {
            long room = limit - total;
            int take = (int) Math.min(room, (long) n);
            if (take > 0) {
                buffer.write(chunk, 0, take);
                total += take;
            }
            if (total >= limit && n > take) {
                // 已到上限且还有剩余：不再整读，防 OOM
                exceeded = true;
                break;
            }
        }
        if (!exceeded && total >= limit) {
            // 恰好等于 limit：再试一字节判断是否真的超出
            int more = in.read();
            exceeded = more >= 0;
        }
        return new LimitedBody(buffer.toByteArray(), exceeded);
    }

    /** {@link #readAll(InputStream, long)} 的结果体。 */
    private static final class LimitedBody {
        private final byte[] bytes;
        private final boolean exceeded;

        LimitedBody(byte[] bytes, boolean exceeded) {
            this.bytes = bytes;
            this.exceeded = exceeded;
        }
    }

    /** 413 兜底响应体。 */
    private static byte[] payloadTooLargeBody(long limitBytes) {
        return ("{\"error\":\"payload too large\",\"limit\":" + limitBytes + "}")
                .getBytes(StandardCharsets.UTF_8);
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
