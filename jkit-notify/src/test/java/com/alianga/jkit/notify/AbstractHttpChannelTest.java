package com.alianga.jkit.notify;

import com.alianga.jkit.json.JSON;

import org.junit.AfterClass;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * 黑盒测试基类：起一个本地 HttpServer 捕获渠道发出的 POST 请求（URL + 请求体）。
 *
 * @author 郑明亮
 * @since 2.0.1
 */
public abstract class AbstractHttpChannelTest {

    /**
     * 服务器收到的单次请求：路径 + 查询串 + 请求头 + 请求体。
     */
    protected static final class Captured {
        private final String path;
        private final String query;
        private final Map<String, String> headers;
        private final String body;

        private Captured(String path, String query, Map<String, String> headers, String body) {
            this.path = path;
            this.query = query;
            this.headers = headers;
            this.body = body;
        }

        /**
         * @return 请求头（键统一小写）
         */
        public Map<String, String> headers() {
            return headers;
        }

        /**
         * @return URL 路径（不含查询串，如 {@code /bot123/sendMessage}）
         */
        public String path() {
            return path;
        }

        /**
         * @return URL 查询串（不含 ?，无参数时为空串）
         */
        public String query() {
            return query;
        }

        /**
         * @return 请求体
         */
        public String body() {
            return body;
        }
    }

    private static HttpServer server;
    private static ConcurrentLinkedQueue<Captured> captured;
    private static ConcurrentLinkedQueue<int[]> statusQueue;
    private static ConcurrentLinkedQueue<String> bodyQueue;
    private static String responseBody;
    private static int responseStatus;

    /**
     * @return 本地服务地址，如 {@code http://127.0.0.1:12345}
     */
    protected static String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    /**
     * 设置下一次请求的应答。
     *
     * <p>同一用例内多次调用构成队列：第 n 次请求取第 n 个应答；
     * 队列耗尽后沿用最后一个应答。
     *
     * @param status HTTP 状态码
     * @param body 应答正文
     */
    protected static void respond(int status, String body) {
        statusQueue.add(new int[]{status});
        bodyQueue.add(body);
        responseStatus = status;
        responseBody = body;
    }

    private static void pollResponse() {
        int[] status = statusQueue.poll();
        String body = bodyQueue.poll();
        if (status != null) {
            responseStatus = status[0];
        }
        if (body != null) {
            responseBody = body;
        }
    }

    /**
     * 取走一条被捕获的请求。
     *
     * @return 最早的未取请求
     */
    protected static Captured take() {
        Captured request = captured.poll();
        assertTrue("no captured request", request != null);
        return request;
    }

    /**
     * @return 是否还有未取走的请求
     */
    protected static boolean isEmpty() {
        return captured.isEmpty();
    }

    /**
     * 每个用例前清空上一用例残留的请求与应答队列，避免断言错位。
     */
    @Before
    public void clearCaptured() {
        captured.clear();
        statusQueue.clear();
        bodyQueue.clear();
        responseStatus = 200;
        responseBody = "";
    }

    /**
     * 启动捕获服务器（JUnit 类级初始化调用）。
     *
     * @throws Exception 启动失败
     */
    @BeforeClass
    public static void startServer() throws Exception {
        captured = new ConcurrentLinkedQueue<Captured>();
        statusQueue = new ConcurrentLinkedQueue<int[]>();
        bodyQueue = new ConcurrentLinkedQueue<String>();
        responseBody = "";
        responseStatus = 200;
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", new HttpHandler() {
            @Override
            public void handle(HttpExchange exchange) {
                try {
                    pollResponse();
                    String path = exchange.getRequestURI().getRawPath();
                    String query = exchange.getRequestURI().getRawQuery();
                    java.util.Map<String, String> headers = new java.util.LinkedHashMap<String, String>();
                    for (Map.Entry<String, List<String>> entry : exchange.getRequestHeaders().entrySet()) {
                        headers.put(entry.getKey().toLowerCase(Locale.ROOT),
                                String.valueOf(entry.getValue().get(0)));
                    }
                    InputStream in = exchange.getRequestBody();
                    ByteArrayOutputStream buffer = new ByteArrayOutputStream();
                    byte[] chunk = new byte[1024];
                    int read = in.read(chunk);
                    while (read >= 0) {
                        buffer.write(chunk, 0, read);
                        read = in.read(chunk);
                    }
                    captured.add(new Captured(path == null ? "" : path,
                            query == null ? "" : query, headers,
                            new String(buffer.toByteArray(), StandardCharsets.UTF_8)));
                    byte[] out = responseBody.getBytes(StandardCharsets.UTF_8);
                    exchange.sendResponseHeaders(responseStatus, out.length);
                    exchange.getResponseBody().write(out);
                } catch (Exception ignored) {
                    // 测试服务器异常不影响断言
                } finally {
                    exchange.close();
                }
            }
        });
        server.start();
    }

    /**
     * 停止捕获服务器（JUnit 类级清理调用）。
     */
    @AfterClass
    public static void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    /**
     * 逐字段比较 JSON 字符串（忽略空白与键序）。
     *
     * @param expected 期望 JSON
     * @param actual 实际 JSON
     */
    protected static void assertJsonEquals(String expected, String actual) {
        assertEquals(JSON.parseObject(expected), JSON.parseObject(actual));
    }
}
