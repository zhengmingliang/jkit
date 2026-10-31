package com.alianga.jkit.storage.git;

import com.alianga.jkit.storage.StorageErrorCode;
import com.alianga.jkit.storage.StorageException;
import com.sun.net.httpserver.HttpServer;

import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

import java.io.InputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

/**
 * 重定向安全：JDK HttpClient 的 NORMAL 模式在跨主机 302 时会把 Authorization
 * 头原样带到新主机（实测 JDK 17 确认）——自建 Gitea/Gogs 的 baseUrl 经反代或
 * 被劫持 DNS 时，一次重定向就把令牌送到第三方。
 *
 * <p>回归锁：跨主机重定向拒绝跟随；同主机重定向正常跟随但<b>不带</b>
 * Authorization 头；头正常跟随的场景内容完整。</p>
 *
 * @author 郑明亮
 */
public class GitTransportRedirectTest {
    private static final Map<String, String> SEEN_AUTH = new ConcurrentHashMap<>();
    private static HttpServer primary;
    private static HttpServer other;
    private static String primaryUrl;
    private static String otherUrl;

    @BeforeClass
    public static void startServers() throws Exception {
        primary = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        other = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        // 先起服务拿端口，再注册引用彼此 URL 的 context
        primary.start();
        other.start();
        primaryUrl = "http://127.0.0.1:" + primary.getAddress().getPort();
        otherUrl = "http://127.0.0.1:" + other.getAddress().getPort();

        primary.createContext("/cross", exchange -> {
            SEEN_AUTH.put("other", nullToEmpty(exchange.getRequestHeaders().getFirst("Authorization")));
            byte[] body = "leaked".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        primary.createContext("/same", exchange -> {
            SEEN_AUTH.put("same", nullToEmpty(exchange.getRequestHeaders().getFirst("Authorization")));
            byte[] body = "ok".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        primary.createContext("/raw/same.bin", exchange -> {
            exchange.getResponseHeaders().add("Location", primaryUrl + "/same");
            exchange.sendResponseHeaders(302, -1);
            exchange.close();
        });
        primary.createContext("/raw/cross.bin", exchange -> {
            exchange.getResponseHeaders().add("Location", otherUrl + "/cross");
            exchange.sendResponseHeaders(302, -1);
            exchange.close();
        });
        other.createContext("/cross", exchange -> {
            SEEN_AUTH.put("other", nullToEmpty(exchange.getRequestHeaders().getFirst("Authorization")));
            byte[] body = "leaked".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    @AfterClass
    public static void stopServers() {
        if (primary != null) {
            primary.stop(0);
        }
        if (other != null) {
            other.stop(0);
        }
    }

    @Test
    public void crossHostRedirectIsRejectedWithoutFollowing() {
        AtomicBoolean seen = new AtomicBoolean();
        other.createContext("/probe", exchange -> seen.set(true));
        try (GitTransport transport = new GitTransport()) {
            StorageException error = assertThrows(StorageException.class,
                    () -> transport.openStream(primaryUrl + "/raw/cross.bin", TestMaps.of(), "f.bin"));
            assertEquals("跨主机重定向必须拒绝", StorageErrorCode.INVALID_CONFIG, error.code());
            assertTrue("异常消息要说明拒绝原因：" + error.getMessage(),
                    error.getMessage().contains("另一台主机"));
        }
    }

    @Test
    public void sameHostRedirectFollowsWithoutAuthorizationHeader() throws Exception {
        try (GitTransport transport = new GitTransport();
             InputStream in = transport.openStream(primaryUrl + "/raw/same.bin",
                     TestMaps.of("Authorization", "token SECRET-T0KEN"), "f.bin")) {
            assertEquals("ok", new String(in.readAllBytes(), StandardCharsets.UTF_8));
        }
        // 关键断言：跟随后的请求绝不带 Authorization（Location 指向哪里不受我们控制）
        assertEquals("同主机重定向的请求也不应带 Authorization 头",
                "", SEEN_AUTH.get("same"));
    }

    @Test
    public void callFollowsSameHostGetRedirect() {
        try (GitTransport transport = new GitTransport()) {
            GitTransport.Response response = transport.call("GET",
                    primaryUrl + "/raw/same.bin", TestMaps.of(), null, "x");
            assertEquals(200, response.status());
            assertEquals("ok", response.body());
        }
    }
}
