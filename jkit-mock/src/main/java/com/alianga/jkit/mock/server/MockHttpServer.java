package com.alianga.jkit.mock.server;

import com.alianga.jkit.http.HttpRequest;
import com.alianga.jkit.http.HttpResponse;
import com.alianga.jkit.mock.MockDataFormatter;
import com.alianga.jkit.mock.MockJs;
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
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

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
 *   <li>请求日志：环形保留最近 200 条；客户端提前断开、服务停止打断延迟同样留痕</li>
 * </ul>
 *
 * <p><b>健壮性</b>：路由表为空且无代理即全 404（不回落演示路由）；状态码超出 100–599 的
 * 响应按 500 兜底返回错误 JSON；handler 顶层兜底异常并保证每条请求都有日志。</p>
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
    private static final byte[] INVALID_STATUS_BODY =
            "{\"error\":\"invalid statusCode configured\"}".getBytes(StandardCharsets.UTF_8);
    private static final byte[] BAD_REQUEST_BODY =
            "{\"error\":\"json object body required\"}".getBytes(StandardCharsets.UTF_8);
    private static final byte[] METHOD_NOT_ALLOWED_BODY =
            "{\"error\":\"method not allowed\"}".getBytes(StandardCharsets.UTF_8);
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
    private volatile int port;
    private volatile String proxyTarget;
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
        // 有界池：延迟压测场景下防止线程无界增长；排队由 HttpServer 自带派发承担
        ExecutorService pool = Executors.newFixedThreadPool(200, new DaemonFactory("jkit-mock"));
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
        this.variables = variables == null
                ? Collections.<String, String>emptyMap()
                : Collections.unmodifiableMap(new LinkedHashMap<>(variables));
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
        String rawQuery = exchange.getRequestURI().getRawQuery();

        MockRoute matched = null;
        int status = 500;
        byte[] responseBytes = NOT_FOUND_BODY;
        String contentType = JSON_CT;
        long latencyMs = 0L;
        byte[] requestBody = EMPTY_BODY;
        boolean proxied = false;
        try {
            requestBody = readAll(exchange.getRequestBody());
            matched = findRoute(method, path);
            if (matched != null) {
                MockRequestContext ctx = MockRequestContext.of(method, path,
                        matchPathParams(matched.path, path), rawQuery,
                        exchange.getRequestHeaders(), requestBody, variables);
                Resolved resolved = matched.crud
                        ? serveCrud(matched, method, path, ctx)
                        : resolveRoute(matched, ctx, requestBody);
                latencyMs = resolved.latencyMs;
                status = resolved.status;
                contentType = resolved.contentType;
                responseBytes = resolved.body;
                if (resolved.headers != null && !resolved.headers.isEmpty()) {
                    for (Map.Entry<String, String> e : resolved.headers.entrySet()) {
                        exchange.getResponseHeaders().set(e.getKey(), e.getValue());
                    }
                }
            } else if (proxyTarget != null) {
                // 代理回源：未命中路由原样转发真实后端
                Resolved forwarded = forward(exchange, method, rawPath, rawQuery, requestBody);
                status = forwarded.status;
                contentType = forwarded.contentType;
                responseBytes = forwarded.body;
                if (forwarded.headers != null && !forwarded.headers.isEmpty()) {
                    for (Map.Entry<String, String> e : forwarded.headers.entrySet()) {
                        exchange.getResponseHeaders().set(e.getKey(), e.getValue());
                    }
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
                    truncateForLog(rawQuery == null ? "" : rawQuery, LOG_TEXT_MAX),
                    matched != null || proxied, status, durationMs,
                    truncateBytesForLog(requestBody, LOG_TEXT_MAX),
                    truncateBytesForLog(responseBytes, LOG_TEXT_MAX), proxied));
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
        // 首次访问播种：body 为 JSON 数组时作为初始数据（盘上已有数据则 no-op）
        if (route.body.trim().startsWith("[")) {
            try {
                Object parsed = com.alianga.jkit.json.JSON.parse(route.body.trim());
                if (parsed instanceof List) {
                    List<Map<String, Object>> seed = new ArrayList<>();
                    for (Object o : (List<?>) parsed) {
                        if (o instanceof Map) {
                            @SuppressWarnings("unchecked")
                            Map<String, Object> row = (Map<String, Object>) o;
                            seed.add(row);
                        }
                    }
                    crudStore.seed(resource, seed);
                }
            } catch (RuntimeException ignore) {
                // 播种失败按空资源处理
            }
        }
        long latencyMs = route.latencyMs;
        Map<String, String> headers = renderHeadersSafe(route.headers, ctx);
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

        String id = rest.contains("/") ? rest.substring(0, rest.indexOf('/')) : rest;
        if (isGet) {
            Map<String, Object> row = crudStore.find(resource, id);
            return row == null
                    ? new Resolved(404, latencyMs, JSON_CT, NOT_FOUND_BODY, headers)
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
                    ? new Resolved(404, latencyMs, JSON_CT, NOT_FOUND_BODY, headers)
                    : new Resolved(200, latencyMs, JSON_CT,
                            MockDataFormatter.prettyJson(updated).getBytes(StandardCharsets.UTF_8), headers);
        }
        if (isDelete) {
            Map<String, Object> removed = crudStore.delete(resource, id);
            return removed == null
                    ? new Resolved(404, latencyMs, JSON_CT, NOT_FOUND_BODY, headers)
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

    /** 组装一条路由的响应：回显 > 响应方案 > 动态 Schema > 动态模板 > 静态 body。 */
    private Resolved resolveRoute(MockRoute route, MockRequestContext ctx, byte[] requestBody) {
        if (route.echo) {
            String reqCt = ctx.headerFirst("Content-Type");
            return new Resolved(route.statusCode, route.latencyMs,
                    reqCt == null || reqCt.trim().isEmpty() ? route.contentType : reqCt,
                    requestBody, renderHeadersSafe(route.headers, ctx));
        }
        MockResponse picked = pickResponse(route.responses, ctx);
        if (picked != null) {
            String body = renderBody(picked.body, picked.dynamicTemplate, ctx);
            return new Resolved(picked.statusCode, picked.latencyMs, picked.contentType,
                    body.getBytes(StandardCharsets.UTF_8), renderHeadersSafe(picked.headers, ctx));
        }
        if (route.dynamicSchema && !route.responseSchema.trim().isEmpty()) {
            return new Resolved(route.statusCode, route.latencyMs, route.contentType,
                    renderSchemaBody(route, ctx).getBytes(StandardCharsets.UTF_8),
                    renderHeadersSafe(route.headers, ctx));
        }
        String body = renderBody(route.body, route.dynamicTemplate, ctx);
        return new Resolved(route.statusCode, route.latencyMs, route.contentType,
                body.getBytes(StandardCharsets.UTF_8), renderHeadersSafe(route.headers, ctx));
    }

    /**
     * 响应方案挑选：先按顺序评估带激活规则的方案，第一个规则全中的生效；
     * 全不中回落到无规则的默认方案（没有默认方案则取第一条）。
     */
    private static MockResponse pickResponse(List<MockResponse> responses, MockRequestContext ctx) {
        if (responses == null || responses.isEmpty()) {
            return null;
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

    private static boolean allRulesMatch(MockResponse response, MockRequestContext ctx) {
        for (MockResponseRule rule : response.rules) {
            if (!rule.matches(ctx)) {
                return false;
            }
        }
        return true;
    }

    private String renderBody(String raw, boolean dynamicTemplate, MockRequestContext ctx) {
        String rendered = MockTemplateRenderer.render(raw, ctx);
        if (!dynamicTemplate) {
            return rendered;
        }
        try {
            return MockJs.mockJson(rendered);
        } catch (RuntimeException ex) {
            // 模板非法回落占位符替换后的原文，避免整个请求 500
            return rendered;
        }
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

    private static Map<String, String> renderHeadersSafe(Map<String, String> headers,
                                                         MockRequestContext ctx) {
        if (headers == null || headers.isEmpty()) {
            return null;
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
            Map<String, String> headers = new LinkedHashMap<>();
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
                headers.put(name, e.getValue().get(0));
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
        private final Map<String, String> headers;

        Resolved(int status, long latencyMs, String contentType, byte[] body,
                 Map<String, String> headers) {
            this.status = status;
            this.latencyMs = latencyMs;
            this.contentType = contentType;
            this.body = body;
            this.headers = headers;
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
            // CRUD 路由独占基址上的 GET/POST/PUT/PATCH/DELETE，方法过滤交给 CRUD 分派
            if (!route.crud && !matchesMethod(route.method, method)) {
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
