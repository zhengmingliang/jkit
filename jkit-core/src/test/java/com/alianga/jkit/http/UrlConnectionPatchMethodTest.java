package com.alianga.jkit.http;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * {@link UrlConnectionHttpEngine} 对 PATCH 这类非白名单 HTTP 方法的回归测试。
 *
 * <p>{@link java.net.HttpURLConnection#setRequestMethod(String)} 只接受白名单内的标准方法，PATCH 之类扩展方法会被
 * 拒绝并抛出 {@link java.net.ProtocolException}。历史实现随后用 {@code field.setAccessible(true)} 改写 method 字段，
 * 而 JDK 9 起模块封装生效、JDK 17 起该反射直接抛 {@link java.lang.reflect.InaccessibleObjectException}，
 * PATCH 在回退引擎上必然失败。本测试直接驱动引擎（不经过上层的引擎选择），确保各 JDK 下 PATCH 都能真正发出。
 */
public class UrlConnectionPatchMethodTest {
    private static HttpServer server;
    private static String baseUrl;

    @BeforeClass
    public static void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/echo-method", UrlConnectionPatchMethodTest::echoMethod);
        server.start();
        baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @AfterClass
    public static void stopServer() {
        server.stop(0);
    }

    @Test
    public void urlConnectionEngineSupportsPatch() throws Exception {
        HttpRequest request = HttpRequest.patch(baseUrl + "/echo-method").body("p");
        HttpResponse response = HttpEngines.urlConnection().execute(request);
        try {
            String body = response.body().string();
            assertTrue("PATCH 请求应被服务端识别，实际响应：" + body, body.startsWith("PATCH"));
        } finally {
            response.close();
        }
    }

    @Test
    public void urlConnectionEngineSupportsDeleteWithBody() throws Exception {
        HttpRequest request = HttpRequest.delete(baseUrl + "/echo-method").body("{\"id\":1}");
        HttpResponse response = HttpEngines.urlConnection().execute(request);
        try {
            String body = response.body().string();
            assertTrue("DELETE 请求应被服务端识别，实际响应：" + body, body.startsWith("DELETE"));
        } finally {
            response.close();
        }
    }

    @Test
    public void urlConnectionEngineKeepsStandardMethods() throws Exception {
        HttpRequest request = HttpRequest.post(baseUrl + "/echo-method").body("x");
        HttpResponse response = HttpEngines.urlConnection().execute(request);
        try {
            assertEquals("POST", response.body().string());
        } finally {
            response.close();
        }
    }

    private static void echoMethod(HttpExchange exchange) throws IOException {
        InputStream in = exchange.getRequestBody();
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[256];
        int len;
        while ((len = in.read(buf)) > 0) {
            bos.write(buf, 0, len);
        }
        byte[] resp = exchange.getRequestMethod().getBytes("UTF-8");
        exchange.sendResponseHeaders(200, resp.length);
        exchange.getResponseBody().write(resp);
        exchange.close();
    }
}
