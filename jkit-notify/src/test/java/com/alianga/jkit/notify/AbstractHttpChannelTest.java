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
     * 服务器收到的单次请求：查询串 + 请求体。
     */
    protected static final class Captured {
        private final String query;
        private final String body;

        private Captured(String query, String body) {
            this.query = query;
            this.body = body;
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
     * @param status HTTP 状态码
     * @param body 应答正文
     */
    protected static void respond(int status, String body) {
        responseStatus = status;
        responseBody = body;
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
     * 每个用例前清空上一用例残留的请求，避免断言错位。
     */
    @Before
    public void clearCaptured() {
        captured.clear();
        respond(200, "");
    }

    /**
     * 启动捕获服务器（JUnit 类级初始化调用）。
     *
     * @throws Exception 启动失败
     */
    @BeforeClass
    public static void startServer() throws Exception {
        captured = new ConcurrentLinkedQueue<Captured>();
        responseBody = "";
        responseStatus = 200;
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", new HttpHandler() {
            @Override
            public void handle(HttpExchange exchange) {
                try {
                    String query = exchange.getRequestURI().getRawQuery();
                    InputStream in = exchange.getRequestBody();
                    ByteArrayOutputStream buffer = new ByteArrayOutputStream();
                    byte[] chunk = new byte[1024];
                    int read = in.read(chunk);
                    while (read >= 0) {
                        buffer.write(chunk, 0, read);
                        read = in.read(chunk);
                    }
                    captured.add(new Captured(query == null ? "" : query,
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
