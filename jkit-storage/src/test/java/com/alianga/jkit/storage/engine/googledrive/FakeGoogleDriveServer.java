package com.alianga.jkit.storage.engine.googledrive;

import com.alianga.jkit.storage.internal.SimpleJson;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TimeZone;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 模拟 Google Drive REST API v3 与 OAuth2 端点测试服务。
 * 覆盖：
 * <ul>
 *   <li>OAuth2 令牌端点：授权码换取令牌、刷新令牌、失败与 invalid_grant 模拟；</li>
 *   <li>文件管理 API：按条件检索文件/文件夹、获取元数据、创建文件夹、删除/移入回收站、重命名/移动、复制；</li>
 *   <li>上传 API：multipart/related 上传支持解析 JSON 元数据与二进制文件体；</li>
 *   <li>下载 API：alt=media 流式下载与 Range 标头断点续传；</li>
 *   <li>共享盘 API：drives 列表查询；</li>
 *   <li>异常注入：401 未授权触发自动刷新、403 限流/权限不足、404 未找到。</li>
 * </ul>
 *
 * @author 郑明亮
 */
final class FakeGoogleDriveServer {
    private HttpServer server;
    private int port;

    volatile String accessToken = "mock-access-token";
    volatile String refreshToken = "mock-refresh-token";
    volatile long expiresIn = 3600L;

    volatile boolean failExchange;
    volatile boolean failRefresh;
    volatile boolean trigger401Once;
    volatile boolean trigger403RateLimit;
    volatile boolean trigger403PermissionDenied;

    final AtomicInteger requests = new AtomicInteger();
    final List<String> requestHistory = new ArrayList<String>();

    static final class MockFile {
        final String id;
        String name;
        String mimeType;
        String parentId;
        byte[] content;
        boolean trashed;
        long modifiedTime;

        MockFile(String id, String name, String mimeType, String parentId, byte[] content, boolean trashed) {
            this.id = id;
            this.name = name;
            this.mimeType = mimeType;
            this.parentId = parentId;
            this.content = content == null ? new byte[0] : content;
            this.trashed = trashed;
            this.modifiedTime = System.currentTimeMillis();
        }
    }

    final Map<String, MockFile> filesById = new ConcurrentHashMap<String, MockFile>();

    public String start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        port = server.getAddress().getPort();

        // 默认根目录与测试团队盘
        filesById.put("root", new MockFile("root", "root", GoogleDriveConfig.FOLDER_MIME_TYPE, null, null, false));
        filesById.put("team-drive-1", new MockFile("team-drive-1", "Team Drive 1",
                GoogleDriveConfig.FOLDER_MIME_TYPE, null, null, false));

        server.createContext("/oauth2/token", new TokenHandler());
        server.createContext("/drive/v3", new ApiHandler());
        server.createContext("/upload/drive/v3", new UploadHandler());

        server.setExecutor(null);
        server.start();
        return "http://127.0.0.1:" + port;
    }

    public void stop() {
        if (server != null) {
            server.stop(0);
        }
    }

    public String tokenUrl() {
        return "http://127.0.0.1:" + port + "/oauth2/token";
    }

    public String apiBase() {
        return "http://127.0.0.1:" + port + "/drive/v3";
    }

    public String uploadBase() {
        return "http://127.0.0.1:" + port + "/upload/drive/v3";
    }

    private final class TokenHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            requests.incrementAndGet();
            byte[] reqBody = readAll(exchange.getRequestBody());
            String bodyStr = new String(reqBody, StandardCharsets.UTF_8);
            requestHistory.add("POST /oauth2/token " + bodyStr);

            if (bodyStr.contains("grant_type=authorization_code")) {
                if (failExchange) {
                    sendJson(exchange, 400, "{\"error\":\"invalid_grant\",\"error_description\":\"Code invalid\"}");
                    return;
                }
                Map<String, Object> resp = new LinkedHashMap<String, Object>();
                resp.put("access_token", accessToken);
                resp.put("refresh_token", refreshToken);
                resp.put("expires_in", expiresIn);
                resp.put("token_type", "Bearer");
                sendJson(exchange, 200, SimpleJson.toJson(resp));
                return;
            }

            if (bodyStr.contains("grant_type=refresh_token")) {
                if (failRefresh) {
                    sendJson(exchange, 400, "{\"error\":\"invalid_grant\",\"error_description\":\"Token revoked\"}");
                    return;
                }
                Map<String, Object> resp = new LinkedHashMap<String, Object>();
                resp.put("access_token", accessToken + "-refreshed");
                resp.put("expires_in", expiresIn);
                resp.put("token_type", "Bearer");
                sendJson(exchange, 200, SimpleJson.toJson(resp));
                return;
            }

            sendJson(exchange, 400, "{\"error\":\"unsupported_grant_type\"}");
        }
    }

    private final class ApiHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            requests.incrementAndGet();
            String method = exchange.getRequestMethod();
            String path = exchange.getRequestURI().getPath();
            String query = exchange.getRequestURI().getRawQuery();
            requestHistory.add(method + " " + path + (query != null ? "?" + query : ""));

            if (trigger401Once) {
                trigger401Once = false;
                sendJson(exchange, 401, "{\"error\":{\"code\":401,\"message\":\"Invalid Credentials\"}}");
                return;
            }
            if (trigger403RateLimit) {
                sendJson(exchange, 403, "{\"error\":{\"code\":403,\"message\":\"userRateLimitExceeded\"}}");
                return;
            }
            if (trigger403PermissionDenied) {
                sendJson(exchange, 403, "{\"error\":{\"code\":403,\"message\":\"insufficientFilePermissions\"}}");
                return;
            }

            // GET /drive/v3/drives
            if ("/drive/v3/drives".equals(path) && "GET".equals(method)) {
                List<Map<String, Object>> drives = new ArrayList<Map<String, Object>>();
                Map<String, Object> d1 = new LinkedHashMap<String, Object>();
                d1.put("id", "team-drive-1");
                d1.put("name", "Team Shared Drive 1");
                drives.add(d1);
                Map<String, Object> resp = new LinkedHashMap<String, Object>();
                resp.put("drives", drives);
                sendJson(exchange, 200, SimpleJson.toJson(resp));
                return;
            }

            // GET /drive/v3/files?q=...
            if ("/drive/v3/files".equals(path) && "GET".equals(method)) {
                handleFileList(exchange, query);
                return;
            }

            // POST /drive/v3/files (create folder)
            if ("/drive/v3/files".equals(path) && "POST".equals(method)) {
                handleCreateFolder(exchange);
                return;
            }

            // Single file ops: /drive/v3/files/{id}
            if (path.startsWith("/drive/v3/files/")) {
                String sub = path.substring("/drive/v3/files/".length());
                if (sub.endsWith("/copy") && "POST".equals(method)) {
                    String srcId = sub.substring(0, sub.length() - 5);
                    handleCopy(exchange, srcId);
                    return;
                }
                String fileId = sub;
                MockFile file = filesById.get(fileId);
                if (file == null || file.trashed && !"PATCH".equals(method)) {
                    sendJson(exchange, 404, "{\"error\":{\"code\":404,\"message\":\"File not found: " + fileId + "\"}}");
                    return;
                }

                if ("GET".equals(method)) {
                    if (query != null && query.contains("alt=media")) {
                        handleDownloadMedia(exchange, file);
                    } else {
                        handleGetMetadata(exchange, file);
                    }
                    return;
                }

                if ("PATCH".equals(method)) {
                    handlePatchFile(exchange, file, query);
                    return;
                }

                if ("DELETE".equals(method)) {
                    filesById.remove(fileId);
                    exchange.sendResponseHeaders(204, -1);
                    exchange.close();
                    return;
                }
            }

            sendJson(exchange, 404, "{\"error\":{\"code\":404,\"message\":\"Not Found\"}}");
        }
    }

    private final class UploadHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            requests.incrementAndGet();
            String method = exchange.getRequestMethod();
            String path = exchange.getRequestURI().getPath();
            requestHistory.add(method + " " + path);

            if (trigger401Once) {
                trigger401Once = false;
                sendJson(exchange, 401, "{\"error\":{\"code\":401,\"message\":\"Invalid Credentials\"}}");
                return;
            }

            // POST /upload/drive/v3/files or PATCH /upload/drive/v3/files/{id}
            byte[] rawBytes = readAll(exchange.getRequestBody());
            String contentType = exchange.getRequestHeaders().getFirst("Content-Type");
            String boundary = parseBoundary(contentType);

            String existingId = null;
            if (path.startsWith("/upload/drive/v3/files/")) {
                existingId = path.substring("/upload/drive/v3/files/".length());
            }

            ParsedMultipart multipart = parseMultipart(rawBytes, boundary);
            String name = multipart.name != null ? multipart.name : "unnamed";
            String parentId = multipart.parentId != null ? multipart.parentId : "root";
            byte[] fileContent = multipart.content != null ? multipart.content : new byte[0];

            MockFile target;
            if (existingId != null && filesById.containsKey(existingId)) {
                target = filesById.get(existingId);
                target.name = name;
                target.content = fileContent;
                target.modifiedTime = System.currentTimeMillis();
            } else {
                String newId = "file-" + UUID.randomUUID().toString().substring(0, 8);
                target = new MockFile(newId, name, "application/octet-stream", parentId, fileContent, false);
                filesById.put(newId, target);
            }

            Map<String, Object> resp = new LinkedHashMap<String, Object>();
            resp.put("id", target.id);
            resp.put("name", target.name);
            resp.put("size", target.content.length);
            resp.put("mimeType", target.mimeType);
            resp.put("modifiedTime", formatIsoTime(target.modifiedTime));
            sendJson(exchange, 200, SimpleJson.toJson(resp));
        }
    }

    private void handleFileList(HttpExchange exchange, String query) throws IOException {
        String decodedQuery = query != null ? URLDecoder.decode(query, StandardCharsets.UTF_8.name()) : "";
        List<Map<String, Object>> files = new ArrayList<Map<String, Object>>();

        String parentFilter = extractParentFromQuery(decodedQuery);
        String nameFilter = extractNameFromQuery(decodedQuery);

        for (MockFile f : filesById.values()) {
            if ("root".equals(f.id) || "team-drive-1".equals(f.id)) {
                continue;
            }
            if (f.trashed) {
                continue;
            }
            if (parentFilter != null && !parentFilter.equals(f.parentId)) {
                continue;
            }
            if (nameFilter != null && !nameFilter.equals(f.name)) {
                continue;
            }

            Map<String, Object> item = new LinkedHashMap<String, Object>();
            item.put("id", f.id);
            item.put("name", f.name);
            item.put("mimeType", f.mimeType);
            item.put("size", f.content.length);
            item.put("modifiedTime", formatIsoTime(f.modifiedTime));
            files.add(item);
        }

        Map<String, Object> resp = new LinkedHashMap<String, Object>();
        resp.put("files", files);
        sendJson(exchange, 200, SimpleJson.toJson(resp));
    }

    private static String extractParentFromQuery(String q) {
        int idx = q.indexOf("' in parents");
        if (idx > 0) {
            int start = q.lastIndexOf('\'', idx - 1);
            if (start >= 0) {
                return q.substring(start + 1, idx);
            }
        }
        return null;
    }

    private static String extractNameFromQuery(String q) {
        int idx = q.indexOf("name = '");
        if (idx >= 0) {
            int start = idx + "name = '".length();
            int end = q.indexOf('\'', start);
            if (end > start) {
                return q.substring(start, end).replace("\\'", "'");
            }
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private void handleCreateFolder(HttpExchange exchange) throws IOException {
        byte[] bytes = readAll(exchange.getRequestBody());
        Map<String, Object> json = SimpleJson.parseObject(new String(bytes, StandardCharsets.UTF_8));
        String name = json != null && json.get("name") != null ? String.valueOf(json.get("name")) : "New Folder";
        String parentId = "root";
        if (json != null && json.get("parents") instanceof List) {
            List<?> pList = (List<?>) json.get("parents");
            if (!pList.isEmpty()) {
                parentId = String.valueOf(pList.get(0));
            }
        }

        String newId = "folder-" + UUID.randomUUID().toString().substring(0, 8);
        MockFile folder = new MockFile(newId, name, GoogleDriveConfig.FOLDER_MIME_TYPE, parentId, null, false);
        filesById.put(newId, folder);

        Map<String, Object> resp = new LinkedHashMap<String, Object>();
        resp.put("id", newId);
        resp.put("name", name);
        resp.put("mimeType", GoogleDriveConfig.FOLDER_MIME_TYPE);
        resp.put("modifiedTime", formatIsoTime(folder.modifiedTime));
        sendJson(exchange, 200, SimpleJson.toJson(resp));
    }

    private void handleGetMetadata(HttpExchange exchange, MockFile file) throws IOException {
        Map<String, Object> resp = new LinkedHashMap<String, Object>();
        resp.put("id", file.id);
        resp.put("name", file.name);
        resp.put("mimeType", file.mimeType);
        resp.put("size", file.content.length);
        resp.put("modifiedTime", formatIsoTime(file.modifiedTime));
        resp.put("trashed", file.trashed);
        sendJson(exchange, 200, SimpleJson.toJson(resp));
    }

    private void handleDownloadMedia(HttpExchange exchange, MockFile file) throws IOException {
        String rangeHeader = exchange.getRequestHeaders().getFirst("Range");
        byte[] data = file.content;
        int status = 200;
        int offset = 0;

        if (rangeHeader != null && rangeHeader.startsWith("bytes=")) {
            String spec = rangeHeader.substring(6).trim();
            if (spec.endsWith("-")) {
                try {
                    offset = Integer.parseInt(spec.substring(0, spec.length() - 1));
                    status = 206;
                } catch (NumberFormatException ignore) {
                    offset = 0;
                }
            }
        }

        if (offset > 0 && offset < data.length) {
            byte[] sliced = new byte[data.length - offset];
            System.arraycopy(data, offset, sliced, 0, sliced.length);
            data = sliced;
            exchange.getResponseHeaders().set("Content-Range",
                    "bytes " + offset + "-" + (file.content.length - 1) + "/" + file.content.length);
        }

        exchange.getResponseHeaders().set("Content-Type", file.mimeType);
        exchange.sendResponseHeaders(status, data.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(data);
        }
    }

    private void handlePatchFile(HttpExchange exchange, MockFile file, String query) throws IOException {
        byte[] bytes = readAll(exchange.getRequestBody());
        Map<String, Object> json = SimpleJson.parseObject(new String(bytes, StandardCharsets.UTF_8));
        if (json != null) {
            if (json.get("name") != null) {
                file.name = String.valueOf(json.get("name"));
            }
            if (Boolean.TRUE.equals(json.get("trashed"))) {
                file.trashed = true;
            }
        }

        if (query != null) {
            String decoded = URLDecoder.decode(query, StandardCharsets.UTF_8.name());
            String addParents = extractQueryParam(decoded, "addParents");
            if (addParents != null && !addParents.isEmpty()) {
                file.parentId = addParents;
            }
        }

        handleGetMetadata(exchange, file);
    }

    @SuppressWarnings("unchecked")
    private void handleCopy(HttpExchange exchange, String srcId) throws IOException {
        MockFile src = filesById.get(srcId);
        if (src == null) {
            sendJson(exchange, 404, "{\"error\":{\"code\":404,\"message\":\"Src not found\"}}");
            return;
        }

        byte[] bytes = readAll(exchange.getRequestBody());
        Map<String, Object> json = SimpleJson.parseObject(new String(bytes, StandardCharsets.UTF_8));
        String name = json != null && json.get("name") != null ? String.valueOf(json.get("name")) : src.name;
        String parentId = src.parentId;
        if (json != null && json.get("parents") instanceof List) {
            List<?> pList = (List<?>) json.get("parents");
            if (!pList.isEmpty()) {
                parentId = String.valueOf(pList.get(0));
            }
        }

        String newId = "file-copy-" + UUID.randomUUID().toString().substring(0, 8);
        byte[] contentCopy = new byte[src.content.length];
        System.arraycopy(src.content, 0, contentCopy, 0, src.content.length);
        MockFile copy = new MockFile(newId, name, src.mimeType, parentId, contentCopy, false);
        filesById.put(newId, copy);

        handleGetMetadata(exchange, copy);
    }

    private static String extractQueryParam(String query, String name) {
        String target = name + "=";
        int idx = query.indexOf(target);
        if (idx >= 0) {
            int start = idx + target.length();
            int end = query.indexOf('&', start);
            return end > start ? query.substring(start, end) : query.substring(start);
        }
        return null;
    }

    private static String parseBoundary(String contentType) {
        if (contentType == null) {
            return null;
        }
        int idx = contentType.indexOf("boundary=");
        if (idx >= 0) {
            return contentType.substring(idx + 9).trim();
        }
        return null;
    }

    private static final class ParsedMultipart {
        String name;
        String parentId;
        byte[] content;
    }

    @SuppressWarnings("unchecked")
    private static ParsedMultipart parseMultipart(byte[] body, String boundary) {
        ParsedMultipart result = new ParsedMultipart();
        if (boundary == null || body == null) {
            return result;
        }
        String bodyStr = new String(body, StandardCharsets.UTF_8);
        String sep = "--" + boundary;
        String[] parts = bodyStr.split(java.util.regex.Pattern.quote(sep));

        for (String part : parts) {
            if (part.contains("application/json")) {
                int headerEnd = part.indexOf("\r\n\r\n");
                if (headerEnd < 0) {
                    headerEnd = part.indexOf("\n\n");
                }
                if (headerEnd > 0) {
                    String jsonStr = part.substring(headerEnd).trim();
                    Map<String, Object> map = SimpleJson.parseObject(jsonStr);
                    if (map != null) {
                        if (map.get("name") != null) {
                            result.name = String.valueOf(map.get("name"));
                        }
                        if (map.get("parents") instanceof List) {
                            List<?> pList = (List<?>) map.get("parents");
                            if (!pList.isEmpty()) {
                                result.parentId = String.valueOf(pList.get(0));
                            }
                        }
                    }
                }
            } else if (part.contains("Content-Type:") && !part.startsWith("--")) {
                // Second part: content
                int headerEnd = part.indexOf("\r\n\r\n");
                int skip = 4;
                if (headerEnd < 0) {
                    headerEnd = part.indexOf("\n\n");
                    skip = 2;
                }
                if (headerEnd > 0) {
                    String rawPart = part.substring(headerEnd + skip);
                    if (rawPart.endsWith("\r\n")) {
                        rawPart = rawPart.substring(0, rawPart.length() - 2);
                    } else if (rawPart.endsWith("\n")) {
                        rawPart = rawPart.substring(0, rawPart.length() - 1);
                    }
                    result.content = rawPart.getBytes(StandardCharsets.UTF_8);
                }
            }
        }
        return result;
    }

    private static void sendJson(HttpExchange exchange, int status, String json) throws IOException {
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=UTF-8");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }

    private static byte[] readAll(InputStream is) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        byte[] buf = new byte[4096];
        int r;
        while ((r = is.read(buf)) != -1) {
            baos.write(buf, 0, r);
        }
        return baos.toByteArray();
    }

    private static String formatIsoTime(long time) {
        SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.ROOT);
        sdf.setTimeZone(TimeZone.getTimeZone("UTC"));
        return sdf.format(new Date(time));
    }
}
