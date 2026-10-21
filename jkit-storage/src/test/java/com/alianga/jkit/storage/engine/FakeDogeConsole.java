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
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 测试用假多吉云控制台（基础桶会话 API）：仿真
 * {@code /user/login.json}（md5 密码登录）、{@code /v2/console/v1/session.json}（CSRF）、
 * {@code /v2/console/v1/oss/file/list|sign|delete.json}、{@code upload/put.json}、
 * {@code bucket/default-domain.json} 与 {@code /cdn/domain/info.json}（URL 鉴权）。
 *
 * @author 郑明亮
 */
final class FakeDogeConsole {
    /** 基础桶文件表：bucket → (key → size)。 */
    final Map<String, Map<String, Long>> files = new ConcurrentHashMap<>();
    /** 对象内容表：bucket|key → bytes。 */
    final Map<String, byte[]> contents = new ConcurrentHashMap<>();
    /** 文件类型表（folder / file）。 */
    final Map<String, String> types = new ConcurrentHashMap<>();
    /** URL 鉴权开关（0=关 1=开），作用在默认域名上。 */
    volatile int urlAuthSwitch;
    /** 登录密码（校验 md5(pw + "~dogecloud~password")）。 */
    volatile String passwordMd5 = md5("test-password~dogecloud~password");
    /** 置 true 时登录失败（密码错误场景）。 */
    volatile boolean loginShouldFail;
    /** 默认域名。 */
    volatile String domain = "cdn.example.com";
    /** 登录次数。 */
    private final java.util.concurrent.atomic.AtomicInteger logins = new java.util.concurrent.atomic.AtomicInteger();

    private HttpServer server;

    /**
     * 启动假控制台。
     *
     * @throws IOException 启动失败
     */
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", this::handle);
        server.start();
    }

    /**
     * 停止。
     */
    void stop() {
        if (server != null) {
            server.stop(0);
        }
    }

    /**
     * @return 监听根地址
     */
    String endpoint() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    int loginRequests() {
        return logins.get();
    }

    void seedFile(String bucket, String key, long size, String type) {
        files.computeIfAbsent(bucket, k -> new java.util.concurrent.ConcurrentHashMap<>())
                .put(key, size);
        types.put(bucket + "|" + key, type);
    }

    private void handle(HttpExchange exchange) throws IOException {
        try {
            String path = exchange.getRequestURI().getPath();
            String method = exchange.getRequestMethod();
            String body = readAll(exchange.getRequestBody());
            Map<String, String> query = parseQuery(exchange.getRequestURI().getRawQuery());
            if (path.equals("/user/login.json") && "POST".equals(method)) {
                respondLogin(exchange, body);
                return;
            }
            if (path.equals("/v2/console/v1/session.json")) {
                respond(exchange, 200, "{\"code\":200,\"data\":{\"csrf\":\"csrf-test-token\"}}");
                return;
            }
            if (path.startsWith("/v2/console/v1/oss/")) {
                handleOss(exchange, method, path, query, body);
                return;
            }
            if (path.startsWith("/signed/") && "GET".equals(method)) {
                String rest = path.substring("/signed/".length());
                int slash = rest.indexOf('/');
                String signedBucket = URLDecoder.decode(rest.substring(0, slash), StandardCharsets.UTF_8);
                String signedKey = URLDecoder.decode(rest.substring(slash + 1), StandardCharsets.UTF_8);
                Map<String, Long> bucketFiles = files.get(signedBucket);
                if (bucketFiles == null || !bucketFiles.containsKey(signedKey)) {
                    respond(exchange, 404, "not found");
                    return;
                }
                byte[] stored = contents.get(signedBucket + "|" + signedKey);
                respondBytes(exchange, 200, stored == null ? new byte[0] : stored);
                return;
            }
            if (path.equals("/cdn/domain/info.json")) {
                Map<String, String> form = parseForm(body);
                String id = form.get("id");
                String json = "{\"code\":200,\"data\":{\"name\":\"" + id + "\",\"status\":\"online\","
                        + "\"config\":{\"authKey\":" + (urlAuthSwitch == 1
                        ? "{\"switch\":1,\"target\":\"all\",\"key\":\"k\",\"deadline\":600}"
                        : "null") + "}}}";
                respond(exchange, 200, json);
                return;
            }
            respond(exchange, 404, "{\"code\":404,\"msg\":\"not found\"}");
        } finally {
            exchange.close();
        }
    }

    private void respondLogin(HttpExchange exchange, String body) throws IOException {
        logins.incrementAndGet();
        if (loginShouldFail) {
            exchange.getResponseHeaders().add("Set-Cookie", "token=bad; Path=/");
            respond(exchange, 200, "{\"code\":400,\"msg\":\"Bad Request\",\"data\":{\"id\":\"password\"}}");
            return;
        }
        Map<String, String> form = parseForm(body);
        if (!passwordMd5.equals(form.get("noguess"))) {
            respond(exchange, 200, "{\"code\":400,\"msg\":\"密码错误\",\"data\":{\"id\":\"password\"}}");
            return;
        }
        exchange.getResponseHeaders().add("Set-Cookie", "token=fake-console-token; Path=/; HttpOnly");
        respond(exchange, 200, "{\"code\":200,\"msg\":\"OK\"}");
    }

    private void handleOss(HttpExchange exchange, String method, String path,
                           Map<String, String> query, String body) throws IOException {
        String action = path.substring("/v2/console/v1/oss/".length());
        String cookie = exchange.getRequestHeaders().getFirst("Cookie");
        if (cookie == null || !cookie.contains("fake-console-token")) {
            respond(exchange, 401, "{\"code\":401,\"msg\":\"unauthorized\"}");
            return;
        }
        String bucket = query.getOrDefault("bucket", "free");
        if ("file/list.json".equals(action) && "GET".equals(method)) {
            respondList(exchange, bucket, query);
            return;
        }
        if ("file/sign.json".equals(action) && "GET".equals(method)) {
            String key = new String(Base64.getUrlDecoder().decode(query.get("key")), StandardCharsets.UTF_8);
            if (!files.containsKey(bucket) || !files.get(bucket).containsKey(key)) {
                respond(exchange, 404, "{\"code\":404,\"error\":\"ERROR_OSS_OBJECT_NOT_FOUND\",\"msg\":\"对象不存在\"}");
                return;
            }
            // 临时链接指回本假服务器的内容端点（下载测试可回环）
            respond(exchange, 200, "{\"code\":200,\"data\":{\"url\":\"" + endpoint()
                    + "/signed/" + urlEscape(bucket) + "/" + urlEscape(key)
                    + "\",\"expiresAt\":" + (System.currentTimeMillis() / 1000 + 600) + "}}");
            return;
        }
        if ("file/info.json".equals(action) && "POST".equals(method)) {
            // body: ["key1",...]；响应 [{key, state: found|missing, fsize, etag}]
            String[] keys = body.replace("[", "").replace("]", "").replace("\"", "").split(",");
            StringBuilder rows = new StringBuilder();
            Map<String, Long> bucketFiles = files.getOrDefault(bucket, new java.util.concurrent.ConcurrentHashMap<>());
            for (String key : keys) {
                if (rows.length() > 0) {
                    rows.append(',');
                }
                if (bucketFiles.containsKey(key)) {
                    rows.append("{\"key\":\"").append(jsonEscape(key)).append("\",\"state\":\"found\",\"fsize\":")
                            .append(bucketFiles.get(key)).append(",\"etag\":\"fake\"}");
                } else {
                    rows.append("{\"key\":\"").append(jsonEscape(key)).append("\",\"state\":\"missing\"}");
                }
            }
            respond(exchange, 200, "{\"code\":200,\"data\":[" + rows + "]}");
            return;
        }
        if ("file/delete.json".equals(action) && "POST".equals(method)) {
            // body: ["key1","key2"]
            String[] keys = body.replace("[", "").replace("]", "").replace("\"", "").split(",");
            for (String key : keys) {
                Map<String, Long> bucketFiles = files.get(bucket);
                if (bucketFiles != null) {
                    bucketFiles.remove(key);
                    contents.remove(bucket + "|" + key);
                }
            }
            respond(exchange, 200, "{\"code\":200,\"data\":{\"state\":\"succeeded\",\"items\":[]}}");
            return;
        }
        if ("upload/put.json".equals(action) && "POST".equals(method)) {
            String key = query.get("key");
            Map<String, Long> bucketFiles = files.computeIfAbsent(bucket, k -> new java.util.concurrent.ConcurrentHashMap<>());
            bucketFiles.put(key, (long) body.getBytes(StandardCharsets.UTF_8).length);
            types.put(bucket + "|" + key, key.endsWith("/") ? "folder" : "file");
            contents.put(bucket + "|" + key, body.getBytes(StandardCharsets.UTF_8));
            respond(exchange, 200, "{\"code\":200,\"data\":{\"state\":\"succeeded\",\"bucket\":\"" + bucket
                    + "\",\"key\":\"" + urlEscape(key) + "\"}}");
            return;
        }
        if ("spaces/list.json".equals(action) && "GET".equals(method)) {
            StringBuilder spaces = new StringBuilder();
            for (String name : new String[]{"free", "meitu", "zml-documents", "fee-standard"}) {
                if (spaces.length() > 0) {
                    spaces.append(',');
                }
                spaces.append("{\"name\":\"").append(name).append("\",\"spaceId\":\"space_")
                        .append(Math.abs(name.hashCode()) % 100000).append("\"}");
            }
            respond(exchange, 200, "{\"code\":200,\"data\":{\"spaces\":[" + spaces + "]}}");
            return;
        }
        if ("bucket/default-domain.json".equals(action) && "GET".equals(method)) {
            String json = "{\"code\":200,\"data\":{\"name\":\"" + bucket + "\",\"configuredDomainAvailable\":"
                    + (urlAuthSwitch >= 0) + ",\"configuredDomainId\":\"d1\",\"defaultDomainId\":\"d1\","
                    + "\"domains\":[{\"id\":\"d1\",\"name\":\"" + domain + "\",\"enabled\":true,\"tlsConfigured\":true}]}}";
            respond(exchange, 200, json);
            return;
        }
        respond(exchange, 404, "{\"code\":404,\"msg\":\"not found: " + action + "\"}");
    }

    private void respondList(HttpExchange exchange, String bucket, Map<String, String> query) throws IOException {
        String prefix = query.getOrDefault("prefix", "");
        Map<String, Long> bucketFiles = files.getOrDefault(bucket, new java.util.concurrent.ConcurrentHashMap<>());
        StringBuilder items = new StringBuilder();
        for (Map.Entry<String, Long> entry : bucketFiles.entrySet()) {
            String key = entry.getKey();
            if (!prefix.isEmpty() && !key.startsWith(prefix)) {
                continue;
            }
            if (items.length() > 0) {
                items.append(',');
            }
            String type = types.getOrDefault(bucket + "|" + key,
                    key.endsWith("/") ? "folder" : "file");
            items.append("{\"key\":\"").append(jsonEscape(key)).append("\",\"fsize\":")
                    .append(entry.getValue()).append(",\"type\":\"").append(type)
                    .append("\",\"time\":null,\"hash\":\"fake\"}");
        }
        respond(exchange, 200, "{\"code\":200,\"data\":{\"bucket\":\"" + bucket
                + "\",\"prefix\":\"" + jsonEscape(prefix) + "\",\"files\":[" + items + "]}}");
    }

    private static void respondBytes(HttpExchange exchange, int status, byte[] bytes) throws IOException {
        exchange.sendResponseHeaders(status, bytes.length == 0 ? -1 : bytes.length);
        if (bytes.length > 0) {
            exchange.getResponseBody().write(bytes);
        }
        exchange.close();
    }

    private static void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(status, bytes.length == 0 ? -1 : bytes.length);
        if (bytes.length > 0) {
            exchange.getResponseBody().write(bytes);
        }
        exchange.close();
    }

    private static String readAll(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] chunk = new byte[4096];
        int read;
        while ((read = in.read(chunk)) >= 0) {
            out.write(chunk, 0, read);
        }
        in.close();
        return out.toString("UTF-8");
    }

    private static Map<String, String> parseQuery(String raw) {
        return parsePairs(raw);
    }

    private static Map<String, String> parseForm(String body) {
        return parsePairs(body);
    }

    private static Map<String, String> parsePairs(String raw) {
        Map<String, String> out = new LinkedHashMap<>();
        if (raw == null || raw.isEmpty()) {
            return out;
        }
        for (String pair : raw.split("&")) {
            int eq = pair.indexOf('=');
            if (eq > 0) {
                out.put(URLDecoder.decode(pair.substring(0, eq), StandardCharsets.UTF_8),
                        URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8));
            }
        }
        return out;
    }

    private static String jsonEscape(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private static String urlEscape(String value) {
        return value.replace("\"", "%22");
    }

    private static String md5(String text) {
        try {
            byte[] digest = java.security.MessageDigest.getInstance("MD5")
                    .digest(text.getBytes(StandardCharsets.UTF_8));
            StringBuilder out = new StringBuilder();
            for (byte b : digest) {
                out.append(String.format("%02x", b));
            }
            return out.toString();
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
