package com.alianga.jkit.storage.engine;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 测试用内存版 WebDAV 服务器：覆盖 PROPFIND / PUT / GET / DELETE / MKCOL /
 * MOVE / COPY / HEAD 子集，支持 Basic 认证校验。仅覆盖 jkit-storage 用到的行为。
 *
 * @author 郑明亮
 */
final class FakeDavServer {

    /** 内存条目：目录或文件。 */
    static final class Entity {
        final boolean directory;
        final byte[] data;

        Entity(boolean directory, byte[] data) {
            this.directory = directory;
            this.data = data;
        }
    }

    final Map<String, Entity> tree = new ConcurrentHashMap<>();
    volatile boolean deny = false;
    private HttpServer server;
    private String user;
    private String password;

    /**
     * 启动假服务器。
     *
     * @param basePath 挂载的 basePath（如 "dav"）
     * @param user     Basic 用户名
     * @param password Basic 密码
     * @return endpoint（http://127.0.0.1:端口/basePath）
     * @throws IOException 启动失败
     */
    String start(String basePath, String user, String password) throws IOException {
        this.user = user;
        this.password = password;
        this.prefix = "/" + basePath;
        tree.put(prefix, new Entity(true, new byte[0]));
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", this::handle);
        server.start();
        return "http://127.0.0.1:" + server.getAddress().getPort() + "/" + basePath;
    }

    private String prefix;

    /**
     * 停止服务器。
     */
    void stop() {
        if (server != null) {
            server.stop(0);
        }
    }

    private void handle(HttpExchange exchange) throws IOException {
        try {
            if (!checkAuth(exchange)) {
                exchange.getResponseHeaders().set("WWW-Authenticate", "Basic realm=\"fake\"");
                respond(exchange, 401, "");
                return;
            }
            String method = exchange.getRequestMethod();
            String path = decode(exchange.getRequestURI().getRawPath());
            if (deny) {
                respond(exchange, 403, "");
                return;
            }
            switch (method) {
                case "PROPFIND":
                    handlePropfind(exchange, path);
                    break;
                case "PUT":
                    handlePut(exchange, path);
                    break;
                case "GET":
                    handleGet(exchange, path);
                    break;
                case "HEAD":
                    handleHead(exchange, path);
                    break;
                case "MKCOL":
                    handleMkcol(exchange, path);
                    break;
                case "DELETE":
                    handleDelete(exchange, path);
                    break;
                case "MOVE":
                case "COPY":
                    handleMoveCopy(exchange, method, path);
                    break;
                default:
                    respond(exchange, 405, "");
            }
        } finally {
            exchange.close();
        }
    }

    private boolean checkAuth(HttpExchange exchange) {
        String authorization = exchange.getRequestHeaders().getFirst("Authorization");
        String expected = "Basic " + Base64.getEncoder()
                .encodeToString((user + ":" + password).getBytes(StandardCharsets.UTF_8));
        return expected.equals(authorization);
    }

    private void handlePropfind(HttpExchange exchange, String path) throws IOException {
        String depth = exchange.getRequestHeaders().getFirst("Depth");
        Entity entity = tree.get(path);
        if (entity == null || !entity.directory) {
            if (entity == null) {
                respond(exchange, 404, "");
                return;
            }
        }
        StringBuilder xml = new StringBuilder();
        xml.append("<?xml version=\"1.0\" encoding=\"utf-8\"?>")
                .append("<D:multistatus xmlns:D=\"DAV:\">");
        int maxDepth = "0".equals(depth) ? 0 : 1;
        appendResponse(xml, path, tree.get(path));
        if (maxDepth >= 1 && tree.get(path) != null && tree.get(path).directory) {
            for (Map.Entry<String, Entity> entry : tree.entrySet()) {
                String child = entry.getKey();
                if (child.startsWith(path + "/") && child.indexOf('/', path.length() + 1) < 0) {
                    appendResponse(xml, child, entry.getValue());
                }
            }
        }
        xml.append("</D:multistatus>");
        respond(exchange, 207, xml.toString());
    }

    private void appendResponse(StringBuilder xml, String path, Entity entity) {
        String href = path.endsWith("/") ? path : path + (entity.directory ? "/" : "");
        xml.append("<D:response><D:href>").append(href).append("</D:href>")
                .append("<D:propstat><D:prop>");
        if (entity.directory) {
            xml.append("<D:resourcetype><D:collection/></D:resourcetype>");
        } else {
            xml.append("<D:resourcetype/>")
                    .append("<D:getcontentlength>").append(entity.data.length).append("</D:getcontentlength>")
                    .append("<D:getetag>\"etag-").append(Math.abs(path.hashCode())).append("\"</D:getetag>");
        }
        xml.append("<D:getlastmodified>Wed, 21 Oct 2026 07:28:00 GMT</D:getlastmodified>")
                .append("</D:prop><D:status>HTTP/1.1 200 OK</D:status></D:propstat></D:response>");
    }

    private void handlePut(HttpExchange exchange, String path) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] chunk = new byte[8192];
        try (InputStream in = exchange.getRequestBody()) {
            int read;
            while ((read = in.read(chunk)) >= 0) {
                out.write(chunk, 0, read);
            }
        }
        tree.put(path, new Entity(false, out.toByteArray()));
        respond(exchange, 201, "");
    }

    private void handleGet(HttpExchange exchange, String path) throws IOException {
        Entity entity = tree.get(path);
        if (entity == null || entity.directory) {
            respond(exchange, 404, "");
            return;
        }
        exchange.getResponseHeaders().set("ETag", "\"fake\"");
        exchange.sendResponseHeaders(200, entity.data.length == 0 ? -1 : entity.data.length);
        if (entity.data.length > 0) {
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(entity.data);
            }
        }
    }

    private void handleHead(HttpExchange exchange, String path) throws IOException {
        Entity entity = tree.get(path);
        if (entity == null) {
            exchange.getResponseHeaders().set("Content-Length", "0");
            exchange.sendResponseHeaders(404, -1);
            return;
        }
        exchange.getResponseHeaders().set("Connection", "close");
        exchange.getResponseHeaders().set("Content-Length", "0");
        exchange.sendResponseHeaders(200, -1);
    }

    private void handleMkcol(HttpExchange exchange, String path) throws IOException {
        if (tree.containsKey(path)) {
            respond(exchange, 405, "");
            return;
        }
        if (!tree.containsKey(parentOf(path))) {
            respond(exchange, 409, "");
            return;
        }
        tree.put(path, new Entity(true, new byte[0]));
        respond(exchange, 201, "");
    }

    private void handleDelete(HttpExchange exchange, String path) throws IOException {
        Entity entity = tree.remove(path);
        if (entity == null) {
            respond(exchange, 404, "");
            return;
        }
        if (entity.directory) {
            for (String key : tree.keySet().toArray(new String[0])) {
                if (key.startsWith(path + "/")) {
                    respond(exchange, 409, "");
                    return;
                }
            }
        }
        respond(exchange, 204, "");
    }

    private void handleMoveCopy(HttpExchange exchange, String method, String path) throws IOException {
        String destination = exchange.getRequestHeaders().getFirst("Destination");
        if (destination == null) {
            respond(exchange, 400, "");
            return;
        }
        String target = decode(destination.substring(destination.indexOf('/', destination.indexOf("://") + 3)));
        Entity entity = tree.get(path);
        if (entity == null) {
            respond(exchange, 404, "");
            return;
        }
        if (entity.directory) {
            for (Map.Entry<String, Entity> entry : tree.entrySet()) {
                if (entry.getKey().startsWith(path + "/")) {
                    tree.put(target + entry.getKey().substring(path.length()), entry.getValue());
                    if ("MOVE".equals(method)) {
                        tree.remove(entry.getKey());
                    }
                }
            }
        }
        tree.put(target, entity);
        if ("MOVE".equals(method)) {
            tree.remove(path);
        }
        respond(exchange, 201, "");
    }

    private static String parentOf(String path) {
        int at = path.lastIndexOf('/');
        return at <= 0 ? "/" : path.substring(0, at);
    }

    private static void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(status, bytes.length == 0 ? -1 : bytes.length);
        if (bytes.length > 0) {
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(bytes);
            }
        }
    }

    private static String decode(String rawPath) {
        return URLDecoder.decode(rawPath.replace("+", "%2B"), StandardCharsets.UTF_8);
    }

    /** 仅供测试断言查看内容。 */
    Map<String, Entity> snapshot() {
        return new TreeMap<>(tree);
    }
}
