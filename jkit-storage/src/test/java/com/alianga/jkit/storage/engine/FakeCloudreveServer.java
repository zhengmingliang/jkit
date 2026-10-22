package com.alianga.jkit.storage.engine;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 测试用假 Cloudreve V4 站点：仿真 OAuth2 令牌端点（{@code /session/oauth/token} 授权码换令牌、
 * {@code /session/token/refresh} 刷新）与 {@code /api/v4} 文件 API（list / create / rename /
 * move / delete / upload 会话与分片 / url / info / capacity）。
 *
 * <p>同时记录最近一次请求的 Authorization / method / path / body，供请求形态断言使用。
 * 文件模型为内存 Map：cloudreve://my/&lt;path&gt; → 内容或目录标记，与官方「URI 即路径」一致。</p>
 *
 * @author 郑明亮
 */
final class FakeCloudreveServer {
    /** 应答的令牌（可被用例覆盖）。 */
    volatile String accessToken = "fake-access-token";
    volatile String refreshToken = "fake-refresh-token";
    /** access_token 有效期（秒）。 */
    volatile long expiresIn = 900L;
    /** 授权码换令牌是否应失败（失败场景）。 */
    volatile boolean exchangeShouldFail;
    /** 账号密码登录是否应失败（密码错误场景）。 */
    volatile boolean loginShouldFail;
    /** 刷新是否应失败（过期场景）。 */
    volatile boolean refreshShouldFail;
    /** 文件 API 是否应 401（鉴权失败场景）。 */
    volatile boolean denyFileApi;
    /** 文件 API 应返回的存储容量（字节）。 */
    volatile long totalBytes = 100L * 1024L * 1024L;
    volatile long usedBytes = 32L;

    /** 最近一次请求（多字段原子快照）。 */
    volatile String lastAuthorization;
    volatile String lastMethod;
    volatile String lastPath;
    volatile byte[] lastBody;
    final java.util.concurrent.atomic.AtomicInteger requests = new java.util.concurrent.atomic.AtomicInteger();
    /** 收到的请求行（method + path），供断言请求序列。 */
    final List<String> received = new ArrayList<>();

    /** 内存文件树：cloudreve://my/<path> → 条目（目录 entries 为 null，文件为内容）。 */
    final Map<String, byte[]> files = new ConcurrentHashMap<>();
    final Map<String, Long> directories = new ConcurrentHashMap<>();
    /** 每个文件的上传会话：sessionId → 目标路径。 */
    final Map<String, String> uploadSessions = new ConcurrentHashMap<>();

    private HttpServer server;
    /** 已兑换过的授权码（一次性）。 */
    final List<String> usedCodes = new ArrayList<>();

    /**
     * 启动假站点。
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

    /**
     * 停止服务器。
     */
    void stop() {
        if (server != null) {
            server.stop(0);
        }
    }

    /** 预置一个文件（内容 + 修改时间）。 */
    void seedFile(String path, byte[] content, long updatedAtMillis) {
        files.put("cloudreve://my/" + path, content);
    }

    /** 预置一个目录。 */
    void seedDir(String path) {
        directories.put("cloudreve://my/" + path, 1700000000000L);
    }

    private void handle(HttpExchange exchange) throws IOException {
        try {
            requests.incrementAndGet();
            String method = exchange.getRequestMethod();
            String path = exchange.getRequestURI().getPath();
            byte[] body = readAll(exchange.getRequestBody());
            lastAuthorization = exchange.getRequestHeaders().getFirst("Authorization");
            lastMethod = method;
            lastPath = path;
            lastBody = body;
            received.add(method + " " + path);

            // OAuth2 令牌端点
            if (path.equals("/api/v4/session/oauth/token")) {
                handleExchange(exchange, body);
                return;
            }
            if (path.equals("/api/v4/session/token/refresh")) {
                handleRefresh(exchange, body);
                return;
            }
            if (path.equals("/api/v4/session/token")) {
                handleLogin(exchange, body);
                return;
            }
            if (path.equals("/api/v4/session/oauth/consent")) {
                handleConsent(exchange, body);
                return;
            }
            if (path.equals("/api/v4/user/capacity")) {
                if (denyFileApi) {
                    envelope(exchange, 401, 401, "Login required");
                    return;
                }
                respond(exchange, 200, "{\"code\":0,\"data\":{\"total\":" + totalBytes
                        + ",\"used\":" + usedBytes + "},\"msg\":\"\"}");
                return;
            }

            // 文件 API
            if (!path.startsWith("/api/v4/file")) {
                respond(exchange, 404, "{\"code\":404,\"msg\":\"not found\"}");
                return;
            }
            if (denyFileApi) {
                envelope(exchange, 401, 401, "Login required");
                return;
            }
            // content 端点在真实 Cloudreve 里走 URL 签名（SignRequired），不要求 Bearer 头
            boolean contentPath = path.startsWith("/api/v4/file/content/");
            if (!contentPath && !"Bearer fake-access-token".equals(lastAuthorization)) {
                envelope(exchange, 401, 401, "Login required");
                return;
            }
            handleFileApi(exchange, method, path, exchange.getRequestURI().getRawQuery(), body);
        } finally {
            exchange.close();
        }
    }

    /** 授权码换令牌：校验 form 与授权码，回 OAuth 标准响应。 */
    private void handleExchange(HttpExchange exchange, byte[] body) throws IOException {
        Map<String, String> form = parseForm(body);
        if (exchangeShouldFail || !"authorization_code".equals(form.get("grant_type"))
                || form.get("code") == null || form.get("code").isEmpty()) {
            respond(exchange, 400, "{\"error\":\"invalid_grant\",\"error_description\":"
                    + "\"Invalid or expired authorization code\",\"error_codes\":[40020]}");
            return;
        }
        usedCodes.add(form.get("code"));
        respond(exchange, 200, "{\"access_token\":\"" + accessToken + "\",\"token_type\":\"Bearer\","
                + "\"expires_in\":" + expiresIn + ",\"refresh_token\":\"" + refreshToken + "\","
                + "\"scope\":\"openid Files.Read Files.Write offline_access\"}");
    }

    /** 账号密码登录：校验 JSON 的 email / password，回会话令牌（envelope 包裹）。 */
    private void handleLogin(HttpExchange exchange, byte[] body) throws IOException {
        if (loginShouldFail) {
            envelope(exchange, 200, 40080, "Incorrect password or email address");
            return;
        }
        Map<String, Object> json = com.alianga.jkit.storage.internal.SimpleJson.parseObject(
                new String(body, StandardCharsets.UTF_8));
        String email = json == null ? "" : String.valueOf(json.get("email"));
        String pwd = json == null ? "" : String.valueOf(json.get("password"));
        if (!"zml@alianga.com".equals(email) || !"test-password".equals(pwd)) {
            envelope(exchange, 200, 40080, "Incorrect password or email address");
            return;
        }
        String loginAccess = "login-session-token";
        respond(exchange, 200, "{\"code\":0,\"msg\":\"\",\"data\":{\"user\":{\"email\":\""
                + email + "\"},\"token\":{\"access_token\":\"" + loginAccess
                + "\",\"refresh_token\":\"login-refresh\"}}}");
    }

    /** 自动同意授权：校验 Bearer 与 redirect_uri，回一次性授权码。 */
    private void handleConsent(HttpExchange exchange, byte[] body) throws IOException {
        if (!"Bearer login-session-token".equals(lastAuthorization)) {
            envelope(exchange, 200, 401, "Login required");
            return;
        }
        Map<String, Object> json = com.alianga.jkit.storage.internal.SimpleJson.parseObject(
                new String(body, StandardCharsets.UTF_8));
        String redirect = json == null ? "" : String.valueOf(json.get("redirect_uri"));
        String scope = json == null ? "" : String.valueOf(json.get("scope"));
        // 相对回调地址（真实站点校验注册的相对值）
        if (!redirect.endsWith("/callback/desktop")) {
            envelope(exchange, 200, 40001, "Invalid redirect URI");
            return;
        }
        if (!scope.contains("Files.Write") || !scope.contains("openid")) {
            envelope(exchange, 200, 40001, "Invalid scope requested");
            return;
        }
        String code = "consent-code-" + System.nanoTime();
        respond(exchange, 200, "{\"code\":0,\"msg\":\"\",\"data\":{\"code\":\"" + code
                + "\",\"state\":\"jkit-auth\"}}");
    }

    /** 刷新令牌：校验 body 的 refresh_token，回 envelope 包裹的令牌对。 */
    private void handleRefresh(HttpExchange exchange, byte[] body) throws IOException {
        if (refreshShouldFail) {
            envelope(exchange, 200, 40020, "Failed to issue token pair");
            return;
        }
        Map<String, Object> json = com.alianga.jkit.storage.internal.SimpleJson.parseObject(
                new String(body, StandardCharsets.UTF_8));
        String given = json == null ? "" : String.valueOf(json.get("refresh_token"));
        if (!given.equals(refreshToken)) {
            envelope(exchange, 200, 40020, "Failed to issue token pair");
            return;
        }
        String rotated = "refreshed-" + refreshToken;
        refreshToken = rotated;
        respond(exchange, 200, "{\"code\":0,\"msg\":\"\",\"data\":{\"access_token\":\""
                + accessToken + "\",\"refresh_token\":\"" + rotated + "\",\"expires_in\":"
                + expiresIn + "}}");
    }

    /** 文件 API 分发（list / create / rename / move / delete / upload / url / info）。 */
    private void handleFileApi(HttpExchange exchange, String method, String path, String rawQuery,
                               byte[] body) throws IOException {
        String query = rawQuery == null ? "" : rawQuery;
        Map<String, String> params = parseQuery(query);
        if ("/api/v4/file".equals(path) && "GET".equals(method)) {
            handleList(exchange, params);
            return;
        }
        if ("/api/v4/file/create".equals(path) && "POST".equals(method)) {
            handleCreate(exchange, body);
            return;
        }
        if ("/api/v4/file/rename".equals(path) && "POST".equals(method)) {
            handleRename(exchange, body);
            return;
        }
        if ("/api/v4/file/move".equals(path) && "POST".equals(method)) {
            handleMove(exchange, body);
            return;
        }
        if ("/api/v4/file".equals(path) && "DELETE".equals(method)) {
            handleDelete(exchange, body);
            return;
        }
        if ("/api/v4/file/upload".equals(path) && "PUT".equals(method)) {
            handleCreateUploadSession(exchange, body);
            return;
        }
        if ("/api/v4/file/info".equals(path) && "GET".equals(method)) {
            handleInfo(exchange, params);
            return;
        }
        if ("/api/v4/file/url".equals(path) && "POST".equals(method)) {
            handleEntityUrl(exchange, body);
            return;
        }
        if (path.startsWith("/api/v4/file/upload/") && "POST".equals(method)) {
            handleUploadChunk(exchange, path, body);
            return;
        }
        if (path.startsWith("/api/v4/file/content/") && ("GET".equals(method)
                || "HEAD".equals(method))) {
            handleServeEntity(exchange, path);
            return;
        }
        respond(exchange, 404, "{\"code\":404,\"msg\":\"not found\"}");
    }

    /** 列表：返回 uri 目录的直接子项（内存树折叠，文件与目录两个 map 合并）。 */
    private void handleList(HttpExchange exchange, Map<String, String> params) throws IOException {
        String uri = params.getOrDefault("uri", "cloudreve://my/");
        String prefix = uri.endsWith("/") ? uri : uri + "/";
        List<Map<String, Object>> entries = new ArrayList<>();
        java.util.Set<String> seenDirs = new java.util.TreeSet<>();
        for (String key : new java.util.TreeSet<>(files.keySet())) {
            if (!key.startsWith(prefix) || key.equals(prefix)) {
                continue;
            }
            String rest = key.substring(prefix.length());
            int slash = rest.indexOf('/');
            if (slash >= 0) {
                seenDirs.add(prefix + rest.substring(0, slash));
            } else {
                entries.add(fileJson(key, rest));
            }
        }
        // 目录 map 里的直接子目录（无文件的后代目录也要列出）
        for (String key : new java.util.TreeSet<>(directories.keySet())) {
            if (!key.startsWith(prefix) || key.equals(prefix)) {
                continue;
            }
            String rest = key.substring(prefix.length());
            int slash = rest.indexOf('/');
            if (slash < 0) {
                seenDirs.remove(key);
                Map<String, Object> entry = new LinkedHashMap<>();
                entry.put("type", 1);
                entry.put("id", "dir-" + key.hashCode());
                entry.put("name", rest);
                entry.put("path", key);
                entry.put("size", 0L);
                entry.put("updated_at", isoMillis(directories.get(key)));
                entries.add(entry);
            } else {
                seenDirs.add(prefix + rest.substring(0, slash));
            }
        }
        for (String dir : seenDirs) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("type", 1);
            entry.put("id", "dir-" + dir.hashCode());
            entry.put("name", dir.substring(dir.lastIndexOf('/') + 1));
            entry.put("path", dir);
            entry.put("size", 0L);
            entry.put("updated_at", isoMillis(directories.containsKey(dir)
                    ? directories.get(dir) : 1700000000000L));
            entries.add(entry);
        }
        StringBuilder out = new StringBuilder("{\"code\":0,\"msg\":\"\",\"data\":{\"files\":");
        out.append(toJsonList(entries));
        out.append(",\"parent\":{},\"pagination\":{\"page\":1,\"page_size\":500,\"is_cursor\":false}");
        out.append(",\"context_hint\":null}}");
        respond(exchange, 200, out.toString());
    }

    /** 建目录：err_on_conflict=false 时已存在视为成功。 */
    private void handleCreate(HttpExchange exchange, byte[] body) throws IOException {
        Map<String, Object> json = parseJson(body);
        String uri = json == null ? "" : String.valueOf(json.get("uri"));
        String type = json == null ? "" : String.valueOf(json.get("type"));
        if ("folder".equals(type)) {
            directories.put(uri, System.currentTimeMillis());
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("type", 1);
            entry.put("id", "dir-" + uri.hashCode());
            entry.put("name", uri.substring(uri.lastIndexOf('/') + 1));
            entry.put("path", uri);
            entry.put("size", 0L);
            respond(exchange, 200, "{\"code\":0,\"msg\":\"\",\"data\":"
                    + toJson(entry) + "}");
            return;
        }
        respond(exchange, 200, "{\"code\":0,\"msg\":\"\",\"data\":{}}");
    }

    /** 改名：同目录下 key 替换。 */
    private void handleRename(HttpExchange exchange, byte[] body) throws IOException {
        Map<String, Object> json = parseJson(body);
        String uri = json == null ? "" : String.valueOf(json.get("uri"));
        String newName = json == null ? "" : String.valueOf(json.get("new_name"));
        String parent = uri.substring(0, uri.lastIndexOf('/') + 1);
        String oldName = uri.substring(uri.lastIndexOf('/') + 1);
        if (files.containsKey(uri)) {
            byte[] content = files.remove(uri);
            files.put(parent + newName, content);
        } else if (directories.containsKey(uri)) {
            directories.remove(uri);
            directories.put(parent + newName, System.currentTimeMillis());
        } else {
            envelope(exchange, 200, 404, "Not Found");
            return;
        }
        respond(exchange, 200, "{\"code\":0,\"msg\":\"\",\"data\":{}}");
        lastMethod = lastMethod + ":" + oldName + ">" + newName;
    }

    /** 移动 / 复制：源内容搬到 dst 目录下。 */
    private void handleMove(HttpExchange exchange, byte[] body) throws IOException {
        Map<String, Object> json = parseJson(body);
        boolean copy = json != null && Boolean.parseBoolean(String.valueOf(json.get("copy")));
        String dst = json == null ? "" : String.valueOf(json.get("dst"));
        List<String> uris = new ArrayList<>();
        if (json != null && json.get("uris") instanceof List) {
            for (Object item : (List<?>) json.get("uris")) {
                uris.add(String.valueOf(item));
            }
        }
        for (String uri : uris) {
            String name = uri.substring(uri.lastIndexOf('/') + 1);
            String dstBase = dst.endsWith("/") ? dst : dst + "/";
            if (files.containsKey(uri)) {
                byte[] content = files.get(uri);
                if (copy) {
                    files.put(dstBase + name, content);
                } else {
                    files.remove(uri);
                    files.put(dstBase + name, content);
                }
            } else if (directories.containsKey(uri)) {
                if (!copy) {
                    directories.remove(uri);
                }
                directories.put(dstBase + name, System.currentTimeMillis());
            } else {
                envelope(exchange, 200, 404, "Not Found");
                return;
            }
        }
        respond(exchange, 200, "{\"code\":0,\"msg\":\"\",\"data\":{}}");
    }

    /** 删除：移除内存条目（幂等）。 */
    private void handleDelete(HttpExchange exchange, byte[] body) throws IOException {
        Map<String, Object> json = parseJson(body);
        List<String> uris = new ArrayList<>();
        if (json != null && json.get("uris") instanceof List) {
            for (Object item : (List<?>) json.get("uris")) {
                uris.add(String.valueOf(item));
            }
        }
        for (String uri : uris) {
            files.remove(uri);
            directories.remove(uri);
        }
        respond(exchange, 200, "{\"code\":0,\"msg\":\"\",\"data\":{}}");
    }

    /** 创建上传会话：回 session_id + chunk_size=0（一片传完）。 */
    private void handleCreateUploadSession(HttpExchange exchange, byte[] body) throws IOException {
        Map<String, Object> json = parseJson(body);
        String uri = json == null ? "" : String.valueOf(json.get("uri"));
        String sessionId = "session-" + System.nanoTime();
        uploadSessions.put(sessionId, uri);
        respond(exchange, 200, "{\"code\":0,\"msg\":\"\",\"data\":{\"session_id\":\"" + sessionId
                + "\",\"chunk_size\":0,\"expires\":" + (System.currentTimeMillis() / 1000 + 900)
                + ",\"uri\":\"" + uri + "\"}}");
    }

    /** 分片上传：chunk_size=0 时一片 = 全部内容。 */
    private void handleUploadChunk(HttpExchange exchange, String path, byte[] body) throws IOException {
        String rest = path.substring("/api/v4/file/upload/".length());
        int slash = rest.lastIndexOf('/');
        String sessionId = slash > 0 ? rest.substring(0, slash) : rest;
        String uri = uploadSessions.get(sessionId);
        if (uri == null) {
            envelope(exchange, 200, 40011, "Upload session expired");
            return;
        }
        files.put(uri, body);
        respond(exchange, 200, "{\"code\":0,\"msg\":\"\",\"data\":{}}");
    }

    /** 单文件元数据：{@code GET /file/info?uri}。 */
    private void handleInfo(HttpExchange exchange, Map<String, String> params) throws IOException {
        String uri = params.getOrDefault("uri", "");
        if (files.containsKey(uri)) {
            respond(exchange, 200, "{\"code\":0,\"msg\":\"\",\"data\":"
                    + toJson(fileJson(uri, uri.substring(uri.lastIndexOf('/') + 1))) + "}");
            return;
        }
        if (directories.containsKey(uri)) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("type", 1);
            entry.put("id", "dir-" + uri.hashCode());
            entry.put("name", uri.substring(uri.lastIndexOf('/') + 1));
            entry.put("path", uri);
            entry.put("size", 0L);
            entry.put("updated_at", isoMillis(directories.get(uri)));
            respond(exchange, 200, "{\"code\":0,\"msg\":\"\",\"data\":" + toJson(entry) + "}");
            return;
        }
        envelope(exchange, 200, 404, "Not Found");
    }

    /** 下载链接：回带签名的 content URL（指向本机 /api/v4/file/content/...）。 */
    private void handleEntityUrl(HttpExchange exchange, byte[] body) throws IOException {
        Map<String, Object> json = parseJson(body);
        List<String> uris = new ArrayList<>();
        if (json != null && json.get("uris") instanceof List) {
            for (Object item : (List<?>) json.get("uris")) {
                uris.add(String.valueOf(item));
            }
        }
        StringBuilder urls = new StringBuilder("[");
        boolean first = true;
        for (String uri : uris) {
            if (!first) {
                urls.append(',');
            }
            first = false;
            if (!files.containsKey(uri) && !directories.containsKey(uri)) {
                continue;
            }
            String url = serverBase() + "/api/v4/file/content/" + urlEncode(uri)
                    + "/0/" + uri.substring(uri.lastIndexOf('/') + 1);
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("url", url);
            urls.append(toJson(item));
        }
        urls.append("]");
        respond(exchange, 200, "{\"code\":0,\"msg\":\"\",\"data\":{\"urls\":" + urls + "}}");
    }

    /** 实体内容服务：直接回文件字节（带签名校验此处省略）。 */
    private void handleServeEntity(HttpExchange exchange, String path) throws IOException {
        // HttpServer 的 getPath() 已把 %XX 解码一次（%3A -> ':'），URI 里的 '/' 已还原，
        // 不能按第一个 '/' 切：按官方路径段 /{id}/{speed}/{name} 里的 speed（数字）切
        String rest = path.substring("/api/v4/file/content/".length());
        String uri = rest.split("/\\d+/", 2)[0];
        String decoded = URLDecoder.decode(uri, StandardCharsets.UTF_8);
        byte[] content = files.get(decoded);
        if (content == null) {
            respond(exchange, 404, "{\"code\":404,\"msg\":\"not found\"}");
            return;
        }
        exchange.sendResponseHeaders(200, content.length == 0 ? -1 : content.length);
        if (content.length > 0) {
            exchange.getResponseBody().write(content);
        }
        exchange.close();
    }

    // ================================================================ 工具

    private String serverBase() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    private static String urlEncode(String value) {
        try {
            return java.net.URLEncoder.encode(value, "UTF-8");
        } catch (java.io.UnsupportedEncodingException e) {
            return value;
        }
    }

    /** FileResponse（内存条目形态）。 */
    private Map<String, Object> fileJson(String uri, String name) {
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("type", 0);
        entry.put("id", "file-" + uri.hashCode());
        entry.put("name", name);
        entry.put("path", uri);
        entry.put("size", (long) files.get(uri).length);
        entry.put("updated_at", isoMillis(1700000000000L));
        return entry;
    }

    private static String isoMillis(long millis) {
        return java.time.Instant.ofEpochMilli(millis).toString();
    }

    private static Map<String, String> parseQuery(String query) {
        Map<String, String> params = new LinkedHashMap<>();
        if (query == null || query.isEmpty()) {
            return params;
        }
        for (String pair : query.split("&")) {
            int at = pair.indexOf('=');
            if (at <= 0) {
                continue;
            }
            params.put(URLDecoder.decode(pair.substring(0, at), StandardCharsets.UTF_8),
                    URLDecoder.decode(pair.substring(at + 1), StandardCharsets.UTF_8));
        }
        return params;
    }

    private static Map<String, String> parseForm(byte[] body) {
        return parseQuery(new String(body, StandardCharsets.UTF_8));
    }

    private static Map<String, Object> parseJson(byte[] body) {
        return com.alianga.jkit.storage.internal.SimpleJson.parseObject(
                new String(body, StandardCharsets.UTF_8));
    }

    /** envelope 错误（HTTP 200 + code 非 0，与官方一致）。 */
    private static void envelope(HttpExchange exchange, int httpStatus, int code, String msg)
            throws IOException {
        respond(exchange, httpStatus, "{\"code\":" + code + ",\"msg\":\"" + msg + "\"}");
    }

    private static String toJson(Map<String, Object> map) {
        StringBuilder out = new StringBuilder("{");
        boolean first = true;
        for (Map.Entry<String, Object> entry : map.entrySet()) {
            if (!first) {
                out.append(',');
            }
            first = false;
            out.append('"').append(entry.getKey()).append("\":");
            Object value = entry.getValue();
            if (value instanceof Number || value instanceof Boolean) {
                out.append(value);
            } else {
                out.append('"').append(value).append('"');
            }
        }
        return out.append('}').toString();
    }

    private static String toJsonList(List<Map<String, Object>> list) {
        StringBuilder out = new StringBuilder("[");
        boolean first = true;
        for (Map<String, Object> item : list) {
            if (!first) {
                out.append(',');
            }
            first = false;
            out.append(toJson(item));
        }
        return out.append(']').toString();
    }

    private static void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(status, bytes.length == 0 ? -1 : bytes.length);
        if (bytes.length > 0) {
            exchange.getResponseBody().write(bytes);
        }
        exchange.close();
    }

    private static byte[] readAll(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] chunk = new byte[4096];
        int read;
        while ((read = in.read(chunk)) >= 0) {
            out.write(chunk, 0, read);
        }
        in.close();
        return out.toByteArray();
    }
}
