package com.alianga.jkit.storage.engine;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 测试用假多吉云管理 API：仿真 {@code POST /auth/tmp_token.json} —— 校验 TOKEN 签名
 * （HMAC-SHA1(secretKey, apiPath + "\n" + body)），通过则回 STS 临时凭据 + 桶清单。
 *
 * <p>同时记录最近一次请求的 Authorization 与 body，供请求形态断言使用。</p>
 *
 * @author 郑明亮
 */
final class FakeDogeApiServer {
    /** 应答的临时凭据（可被用例覆盖）。 */
    volatile Map<String, Object> credentials;
    /** 应答的桶清单（可被用例覆盖）。 */
    volatile java.util.List<Map<String, Object>> buckets;
    /** 颁发凭据的有效期（epoch 毫秒，可被用例覆盖模拟过期）。 */
    volatile long expiredAt = System.currentTimeMillis() + 2L * 3600L * 1000L;
    /** 签名校验用的密钥对。 */
    volatile String accessKey = "fake-ak";
    volatile String secretKey = "fake-sk";
    /** 强制返回 HTTP 403（鉴权失败场景）。 */
    volatile boolean deny;
    /** 最近一次请求的 Authorization 头。 */
    volatile String lastAuthorization;
    /** 最近一次请求的原始 body。 */
    volatile byte[] lastBody;
    /** 请求计数。 */
    final java.util.concurrent.atomic.AtomicInteger requests = new java.util.concurrent.atomic.AtomicInteger();

    private HttpServer server;

    /**
     * 启动假管理 API。
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

    private void handle(HttpExchange exchange) throws IOException {
        try {
            requests.incrementAndGet();
            lastAuthorization = exchange.getRequestHeaders().getFirst("Authorization");
            lastBody = readAll(exchange.getRequestBody());
            if (deny) {
                respond(exchange, 403, "{\"code\":403,\"msg\":\"ERROR_AUTHORIZATION_FAILED\"}");
                return;
            }
            if (!exchange.getRequestURI().getPath().equals("/auth/tmp_token.json")) {
                respond(exchange, 404, "{\"code\":404,\"msg\":\"not found\"}");
                return;
            }
            String expected = expectedAuth(exchange.getRequestURI().getPath());
            if (!expected.equals(lastAuthorization)) {
                respond(exchange, 403, "{\"code\":403,\"msg\":\"signature mismatch\"}");
                return;
            }
            String body = "{\"code\":200,\"msg\":\"OK\",\"data\":{"
                    + "\"Credentials\":" + toJson(credentials) + ","
                    + "\"ExpiredAt\":" + (expiredAt / 1000L) + ","
                    + "\"Buckets\":" + listToJson(buckets)
                    + "}}";
            respond(exchange, 200, body);
        } finally {
            exchange.close();
        }
    }

    private String expectedAuth(String apiPath) {
        return com.alianga.jkit.storage.internal.DogeSigners.tokenAuth(accessKey, secretKey,
                apiPath, lastBody);
    }

    private static String toJson(Map<String, Object> map) {
        if (map == null) {
            return "{}";
        }
        StringBuilder out = new StringBuilder("{");
        boolean first = true;
        for (Map.Entry<String, Object> entry : map.entrySet()) {
            if (!first) {
                out.append(',');
            }
            first = false;
            out.append('"').append(entry.getKey()).append("\":\"").append(entry.getValue()).append('"');
        }
        return out.append('}').toString();
    }

    private static String listToJson(java.util.List<Map<String, Object>> list) {
        if (list == null || list.isEmpty()) {
            return "[]";
        }
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
