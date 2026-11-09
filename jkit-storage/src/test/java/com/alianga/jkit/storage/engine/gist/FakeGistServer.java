package com.alianga.jkit.storage.engine.gist;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 测试用假 GitHub Gist API：仿真 {@code GET /gists}（分页列举）、
 * {@code POST /gists}（新建）、{@code GET/PATCH/DELETE /gists/{id}} 与
 * {@code raw_url} 回源端点。
 *
 * <p>文件模型为内存 {@code Map<gistId, Map<扁平文件名, 内容文本>>}，PATCH 按
 * GitHub 语义处理：值为 null / 不含 content 与 filename 即删除，含 filename 即改名，
 * 含 content 即覆盖内容。同时记录最近一次请求的方法 / 路径 / 头 / body，供形态断言。</p>
 *
 * @author 郑明亮
 */
final class FakeGistServer {
    /** 应答所需的令牌；为空表示不校验（测试匿名读）。 */
    volatile String requiredToken = "fake-token";
    /** 是否在鉴权失败时返回 401。 */
    volatile boolean requireAuth = true;
    /** 下一个请求强制返回的状态码（0 表示不强制）。 */
    volatile int forcedStatus;
    /** 顶层 truncated 标记（模拟 &gt;300 文件）。 */
    volatile boolean topLevelTruncated;
    /** 某个文件是否标记为 truncated（内容只给前若干字节，需走 raw_url）。 */
    volatile String truncatedFile;
    volatile String truncatedPreview = "PREVIEW";
    volatile byte[] truncatedFull = "FULL-CONTENT-FROM-RAW".getBytes(StandardCharsets.UTF_8);
    /** 分页：第一页故意凑满 per_page 触发翻页。 */
    volatile boolean paginate;

    volatile String lastMethod;
    volatile String lastPath;
    volatile String lastAuthorization;
    volatile String lastAccept;
    volatile String lastUserAgent;
    volatile String lastApiVersion;
    volatile String lastContentType;
    volatile byte[] lastBody;
    final List<String> received = new ArrayList<String>();
    final java.util.concurrent.atomic.AtomicInteger requests = new java.util.concurrent.atomic.AtomicInteger();

    /** gistId → (文件名 → 内容文本)。 */
    final Map<String, Map<String, String>> gists = new LinkedHashMap<String, Map<String, String>>();
    /** gistId → 描述。 */
    final Map<String, String> descriptions = new LinkedHashMap<String, String>();
    /** gistId → html_url。 */
    final Map<String, String> htmlUrls = new LinkedHashMap<String, String>();

    private HttpServer server;
    private int nextId = 1000;

    /**
     * 启动假 API。
     *
     * @return 监听地址（http://127.0.0.1:端口）
     * @throws IOException 启动失败
     */
    String start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", this::handle);
        server.start();
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    /** 停止服务器。 */
    void stop() {
        if (server != null) {
            server.stop(0);
        }
    }

    /** 预置一个 Gist（含若干文件）。 */
    void seed(String gistId, String description, String... nameContentPairs) {
        Map<String, String> files = new LinkedHashMap<String, String>();
        for (int i = 0; i < nameContentPairs.length; i += 2) {
            files.put(nameContentPairs[i], nameContentPairs[i + 1]);
        }
        gists.put(gistId, files);
        descriptions.put(gistId, description);
        htmlUrls.put(gistId, "https://gist.github.com/" + gistId);
    }

    /** 读取某个 Gist 的当前文件映射（测试断言用）。 */
    Map<String, String> files(String gistId) {
        return gists.get(gistId);
    }

    /** raw 下载地址（相对路径）。 */
    String rawPath(String gistId, String name) {
        return "/raw/" + gistId + "/" + name;
    }

    private void handle(HttpExchange exchange) throws IOException {
        requests.incrementAndGet();
        String method = exchange.getRequestMethod();
        String path = exchange.getRequestURI().getPath();
        lastMethod = method;
        lastPath = exchange.getRequestURI().toString();
        lastAuthorization = exchange.getRequestHeaders().getFirst("Authorization");
        lastAccept = exchange.getRequestHeaders().getFirst("Accept");
        lastUserAgent = exchange.getRequestHeaders().getFirst("User-Agent");
        lastApiVersion = exchange.getRequestHeaders().getFirst("X-GitHub-Api-Version");
        lastContentType = exchange.getRequestHeaders().getFirst("Content-Type");
        lastBody = readBody(exchange.getRequestBody());
        received.add(method + " " + lastPath);

        if (forcedStatus != 0) {
            respond(exchange, forcedStatus, "{\"message\":\"forced\"}");
            return;
        }
        if (requireAuth && !("Bearer " + requiredToken).equals(lastAuthorization)) {
            respond(exchange, 401, "{\"message\":\"Bad credentials\"}");
            return;
        }
        try {
            if (path.startsWith("/raw/")) {
                handleRaw(exchange);
            } else if ("/gists".equals(path) && "GET".equals(method)) {
                handleList(exchange);
            } else if ("/gists".equals(path) && "POST".equals(method)) {
                handleCreate(exchange);
            } else if (path.startsWith("/gists/")) {
                handleItem(exchange, method, path.substring("/gists/".length()));
            } else {
                respond(exchange, 404, "{\"message\":\"Not Found\"}");
            }
        } catch (RuntimeException e) {
            respond(exchange, 500, "{\"message\":\"" + e.getMessage() + "\"}");
        }
    }

    private void handleRaw(HttpExchange exchange) throws IOException {
        String rest = exchange.getRequestURI().getPath().substring("/raw/".length());
        int slash = rest.indexOf('/');
        String gistId = slash < 0 ? rest : rest.substring(0, slash);
        String name = slash < 0 ? "" : rest.substring(slash + 1);
        Map<String, String> files = gists.get(gistId);
        if (files == null || !files.containsKey(name)) {
            respond(exchange, 404, "{\"message\":\"Not Found\"}");
            return;
        }
        byte[] bytes = truncatedFull != null ? truncatedFull
                : files.get(name).getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(200, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }

    private void handleList(HttpExchange exchange) throws IOException {
        String query = exchange.getRequestURI().getQuery();
        int page = 1;
        if (query != null) {
            for (String part : query.split("&")) {
                if (part.startsWith("page=")) {
                    page = Integer.parseInt(part.substring(5));
                }
            }
        }
        StringBuilder sb = new StringBuilder("[");
        if (paginate && page == 1) {
            // 第一页凑满 100 项触发翻页，第二页返回真实数据
            for (int i = 0; i < 100; i++) {
                if (i > 0) {
                    sb.append(',');
                }
                sb.append("{\"id\":\"filler").append(i).append("\",\"files\":{}}");
            }
        } else {
            boolean first = true;
            for (Map.Entry<String, Map<String, String>> entry : gists.entrySet()) {
                if (!first) {
                    sb.append(',');
                }
                first = false;
                sb.append("{\"id\":\"").append(entry.getKey()).append("\",")
                        .append("\"description\":\"").append(escape(descriptions.get(entry.getKey())))
                        .append("\",\"html_url\":\"")
                        .append(escape(htmlUrls.get(entry.getKey()))).append("\",")
                        .append("\"updated_at\":\"2026-11-10T08:00:00Z\",\"files\":{");
                boolean f = true;
                for (String name : entry.getValue().keySet()) {
                    if (!f) {
                        sb.append(',');
                    }
                    f = false;
                    sb.append('"').append(escape(name)).append("\":{\"filename\":\"")
                            .append(escape(name)).append("\"}");
                }
                sb.append("}}");
            }
        }
        sb.append(']');
        respond(exchange, 200, sb.toString());
    }

    private void handleCreate(HttpExchange exchange) throws IOException {
        Map<String, Object> body = parseObject(new String(lastBody, StandardCharsets.UTF_8));
        Map<String, Object> files = (Map<String, Object>) body.get("files");
        String id = "gist" + (nextId++);
        Map<String, String> created = new LinkedHashMap<String, String>();
        for (Map.Entry<String, Object> entry : files.entrySet()) {
            Map<String, Object> node = (Map<String, Object>) entry.getValue();
            created.put(entry.getKey(), String.valueOf(node.get("content")));
        }
        gists.put(id, created);
        descriptions.put(id, String.valueOf(body.get("description")));
        htmlUrls.put(id, "https://gist.github.com/" + id);
        respond(exchange, 201, "{\"id\":\"" + id + "\"}");
    }

    private void handleItem(HttpExchange exchange, String method, String gistId) throws IOException {
        Map<String, String> files = gists.get(gistId);
        if (files == null) {
            respond(exchange, 404, "{\"message\":\"Not Found\"}");
            return;
        }
        if ("GET".equals(method)) {
            respond(exchange, 200, gistJson(gistId, files));
            return;
        }
        if ("DELETE".equals(method)) {
            gists.remove(gistId);
            respond(exchange, 204, "");
            return;
        }
        if ("PATCH".equals(method)) {
            Map<String, Object> body = parseObject(new String(lastBody, StandardCharsets.UTF_8));
            Map<String, Object> changes = (Map<String, Object>) body.get("files");
            // 改名先收集，避免遍历时改 Map 触发并发修改
            List<String[]> renames = new ArrayList<String[]>();
            for (Map.Entry<String, Object> entry : changes.entrySet()) {
                Object value = entry.getValue();
                if (value == null) {
                    files.remove(entry.getKey());
                    continue;
                }
                Map<String, Object> node = (Map<String, Object>) value;
                Object content = node.get("content");
                Object filename = node.get("filename");
                if (content == null && filename == null) {
                    files.remove(entry.getKey());
                    continue;
                }
                if (filename != null) {
                    String old = files.remove(entry.getKey());
                    renames.add(new String[]{String.valueOf(filename),
                            content != null ? String.valueOf(content) : old});
                } else {
                    files.put(entry.getKey(), String.valueOf(content));
                }
            }
            for (String[] rename : renames) {
                files.put(rename[0], rename[1]);
            }
            respond(exchange, 200, gistJson(gistId, files));
            return;
        }
        respond(exchange, 405, "{\"message\":\"Method Not Allowed\"}");
    }

    private String gistJson(String gistId, Map<String, String> files) {
        StringBuilder sb = new StringBuilder("{\"id\":\"").append(gistId).append("\",")
                .append("\"description\":\"").append(escape(descriptions.get(gistId))).append("\",")
                .append("\"html_url\":\"").append(escape(htmlUrls.get(gistId))).append("\",")
                .append("\"updated_at\":\"2026-11-10T08:00:00Z\",")
                .append("\"truncated\":").append(topLevelTruncated).append(",\"files\":{");
        boolean first = true;
        for (Map.Entry<String, String> entry : files.entrySet()) {
            if (!first) {
                sb.append(',');
            }
            first = false;
            String name = entry.getKey();
            boolean truncated = name.equals(truncatedFile);
            sb.append('"').append(escape(name)).append("\":{\"filename\":\"")
                    .append(escape(name)).append("\",\"type\":\"text/plain\",\"size\":")
                    .append(entry.getValue().length()).append(",\"truncated\":")
                    .append(truncated).append(",\"content\":\"")
                    .append(escape(truncated ? truncatedPreview : entry.getValue()))
                    .append("\",\"raw_url\":\"http://127.0.0.1:")
                    .append(server.getAddress().getPort()).append(rawPath(gistId, name)).append("\"}");
        }
        sb.append("}}");
        return sb.toString();
    }

    private static Map<String, Object> parseObject(String text) {
        // 复用引擎里的最小 JSON 解析器，避免测试再引第三方库
        return com.alianga.jkit.storage.internal.SimpleJson.parseObject(text);
    }

    private static String escape(String value) {
        if (value == null) {
            return "";
        }
        // 内容里可能有换行 / 引号 / 反斜杠，按最小 JSON 转义
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"':
                    out.append("\\\"");
                    break;
                case '\\':
                    out.append("\\\\");
                    break;
                case '\n':
                    out.append("\\n");
                    break;
                case '\r':
                    out.append("\\r");
                    break;
                case '\t':
                    out.append("\\t");
                    break;
                default:
                    out.append(c);
                    break;
            }
        }
        return out.toString();
    }

    private static byte[] readBody(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[4096];
        int read;
        while ((read = in.read(buf)) >= 0) {
            out.write(buf, 0, read);
        }
        return out.toByteArray();
    }

    private static void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Connection", "close");
        exchange.sendResponseHeaders(status, status == 204 ? -1 : bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            if (status != 204) {
                out.write(bytes);
            }
        }
    }
}
