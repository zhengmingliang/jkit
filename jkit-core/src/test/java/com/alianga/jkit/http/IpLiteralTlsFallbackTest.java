package com.alianga.jkit.http;

import com.alianga.jkit.HttpUtils;
import com.sun.net.httpserver.HttpsConfigurator;
import com.sun.net.httpserver.HttpsServer;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeTrue;

/**
 * 回归测试：ignoreSsl 时目标为 IP 字面量（v4/v6）必须能正常握手。
 * <p>
 * 背景：JDK HttpClient 引擎即使装配 trust-all 上下文并关闭 endpointIdentificationAlgorithm，
 * 仍会强制服务端主机名校验，而站点证书一般不含 IP SAN，导致 mock 代理回源配置
 * {@code https://[IPv6]:443} / {@code https://IPv4:443} 必然失败
 * （报错 {@code No subject alternative names matching IP address ...}）。
 * 修复：{@link JdkHttpClientEngine#execute} 对「ignoreSsl + IP 字面量」降级走
 * UrlConnectionHttpEngine（trust-all + 恒真 HostnameVerifier）。
 * <p>
 * 证书由 {@code keytool} 生成并提交到
 * {@code src/test/resources/ssl/ip-literal.p12}，SAN 只有 {@code wx.mail.qq.com}（无 IP 条目），
 * 正是该失败场景的最小复现材料。
 *
 * @author 郑明亮
 */
public class IpLiteralTlsFallbackTest {

    private static final char[] PASSWORD = "changeit".toCharArray();

    private HttpsServer server;

    @Before
    public void startTlsServer() throws Exception {
        // 本测试的意义只在 JDK HttpClient 引擎上有（IP 字面量降级逻辑就写在这里）；
        // 跑在 URL_CONNECTION 引擎或引擎被别的测试全局改写过时跳过。
        assumeTrue("需要 java.net.http.HttpClient 引擎",
                HttpEngines.JDK_HTTP_CLIENT.equals(HttpUtils.getHttpEngine().name()));
        assumeTrue("java.net.http 自 JDK11 提供",
                major(System.getProperty("java.version")) >= 11);

        KeyStore ks = KeyStore.getInstance("PKCS12");
        try (InputStream in = getClass().getResourceAsStream("/ssl/ip-literal.p12")) {
            ks.load(in, PASSWORD);
        }
        KeyManagerFactory kmf = KeyManagerFactory.getInstance("SunX509");
        kmf.init(ks, PASSWORD);
        SSLContext ctx = SSLContext.getInstance("TLS");
        ctx.init(kmf.getKeyManagers(), null, null);
        // 绑定通配地址：v4 / v6 字面量都能连通（Linux/Mac/Win 默认 dual-stack）
        server = HttpsServer.create(new InetSocketAddress(0), 0);
        server.setHttpsConfigurator(new HttpsConfigurator(ctx));
        server.createContext("/", ex -> {
            byte[] body = "{\"ok\":true}".getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(200, body.length);
            try (OutputStream out = ex.getResponseBody()) {
                out.write(body);
            }
            ex.close();
        });
        server.start();
    }

    @After
    public void stopTlsServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    /** ignoreSsl + IPv4 字面量：修复前必失败，修复后走降级引擎返回 200。 */
    @Test
    public void ipv4LiteralWorksWithIgnoreSsl() throws IOException {
        HttpResponse resp = send("https://127.0.0.1:" + port() + "/x", true);
        assertEquals(200, resp.code());
        assertTrue(resp.body().string().contains("\"ok\""));
    }

    /** ignoreSsl + IPv6 字面量：同上。 */
    @Test
    public void ipv6LiteralWorksWithIgnoreSsl() throws IOException {
        HttpResponse resp = send("https://[::1]:" + port() + "/x", true);
        assertEquals(200, resp.code());
        assertTrue(resp.body().string().contains("\"ok\""));
    }

    /** 对照：显式严格校验（ignoreSsl=false）时证书缺 IP SAN 必须仍然拒绝。 */
    @Test
    public void strictVerificationStillRejectsIpLiteral() {
        try {
            send("https://127.0.0.1:" + port() + "/x", false);
            throw new AssertionError("严格校验下 IP 字面量应当失败");
        } catch (Exception expected) {
            // 只要拒收即可；不同 JDK 提示语略有差异，宽松断言常见共性片段
            String message = describe(expected).toLowerCase(java.util.Locale.ROOT);
            assertTrue("应报告证书校验问题: " + message,
                    message.contains("subject alternative")
                            || message.contains("hostname")
                            || message.contains("certificate")
                            || message.contains("pkix"));
        }
    }

    private static HttpResponse send(String url, boolean ignoreSsl) {
        HttpRequest request = HttpRequest.get(url).method("GET")
                .ignoreSsl(ignoreSsl)
                .connectTimeoutMs(10_000).readTimeoutMs(10_000);
        try {
            return HttpUtils.getHttpEngine().execute(request);
        } catch (IOException e) {
            throw new IllegalStateException("request failed: " + url, e);
        }
    }

    private static String describe(Throwable t) {
        StringBuilder sb = new StringBuilder();
        Throwable cur = t;
        int guard = 0;
        while (cur != null && guard++ < 8) {
            if (cur.getMessage() != null) {
                sb.append(cur.getClass().getSimpleName()).append(": ")
                        .append(cur.getMessage()).append("; ");
            }
            cur = cur.getCause();
        }
        return sb.toString();
    }

    private int port() {
        return server.getAddress().getPort();
    }

    private static int major(String version) {
        String value = version == null ? "" : version;
        if (value.startsWith("1.")) {
            value = value.substring(2);
        }
        int dot = value.indexOf('.');
        if (dot > 0) {
            value = value.substring(0, dot);
        }
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            return 8;
        }
    }
}
