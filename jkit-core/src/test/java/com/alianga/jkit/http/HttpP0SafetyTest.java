package com.alianga.jkit.http;

import com.alianga.jkit.HttpUtils;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.AfterClass;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * P0 安全与正确性回归：显式设置不被全局配置覆盖、下载状态码/长度校验、
 * 临时文件原子替换、下载禁用内容编码。
 *
 * @author 郑明亮
 */
public class HttpP0SafetyTest {
    private static HttpServer server;
    private static String baseUrl;
    private static File workDir;
    private static final AtomicReference<String> LAST_ACCEPT_ENCODING = new AtomicReference<String>();

    @BeforeClass
    public static void startServer() throws IOException {
        workDir = new File("target/http-p0-test");
        workDir.mkdirs();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/ok", exchange -> {
            readAll(exchange.getRequestBody());
            LAST_ACCEPT_ENCODING.set(exchange.getRequestHeaders().getFirst("Accept-Encoding"));
            send(exchange, 200, "text/plain", "hello-download".getBytes(StandardCharsets.UTF_8));
        });
        server.createContext("/not-found", exchange -> {
            readAll(exchange.getRequestBody());
            send(exchange, 404, "text/html", "<html>no such file</html>".getBytes(StandardCharsets.UTF_8));
        });
        server.createContext("/server-error", exchange -> {
            readAll(exchange.getRequestBody());
            send(exchange, 500, "application/json", "{\"error\":\"boom\"}".getBytes(StandardCharsets.UTF_8));
        });
        // 声明 100 字节但只写 10 字节，然后直接关闭：模拟被截断的响应
        server.createContext("/truncated", exchange -> {
            readAll(exchange.getRequestBody());
            exchange.getResponseHeaders().set("Content-Type", "application/octet-stream");
            exchange.sendResponseHeaders(200, 100);
            exchange.getResponseBody().write(new byte[10]);
            exchange.getResponseBody().flush();
            exchange.close();
        });
        server.start();
        baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @AfterClass
    public static void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Before
    public void reset() {
        HttpUtils.fakeIp = false;
        HttpUtils.setProxy(null);
        HttpUtils.setCookieJar(new CookieJarImpl());
        HttpUtils.supportHttps();
        HttpUtils.setConnectTimeout(60_000);
        HttpUtils.setReadTimeout(60_000);
        HttpUtils.config().setRetryPolicy(RetryPolicy.none());
        LAST_ACCEPT_ENCODING.set(null);
    }

    // ---------- 显式设置生效 ----------

    @Test
    public void ignoreSslIsNotExplicitByDefault() {
        HttpRequest request = HttpRequest.get("https://example.com");
        assertFalse("默认构造不应视为显式设置", request.isIgnoreSslSet());
        assertTrue("默认值仍为忽略校验（保持向后兼容）", request.isIgnoreSsl());
    }

    @Test
    public void explicitIgnoreSslFalseSurvivesGlobalSwitch() throws IOException {
        HttpUtils.supportHttps(); // 全局：忽略证书校验
        HttpRequest request = HttpRequest.get(baseUrl + "/ok").ignoreSsl(false);
        assertTrue(request.isIgnoreSslSet());
        HttpResponse response = HttpUtils.execute(request);
        try {
            assertEquals(200, response.code());
        } finally {
            response.close();
        }
        assertFalse("send() 不得把用户显式的 ignoreSsl(false) 改回全局值", request.isIgnoreSsl());
    }

    @Test
    public void explicitIgnoreSslTrueSurvivesSecureGlobalSwitch() throws IOException {
        HttpUtils.verifySsl(); // 全局：开启证书校验
        try {
            HttpRequest request = HttpRequest.get(baseUrl + "/ok").ignoreSsl(true);
            HttpResponse response = HttpUtils.execute(request);
            try {
                assertEquals(200, response.code());
            } finally {
                response.close();
            }
            assertTrue("显式 ignoreSsl(true) 也不应被全局的严格模式改写", request.isIgnoreSsl());
        } finally {
            HttpUtils.supportHttps();
        }
    }

    @Test
    public void explicitTimeoutIsNotOverwrittenByGlobalConfig() throws IOException {
        HttpUtils.setConnectTimeout(1234);
        HttpUtils.setReadTimeout(4321);
        // 显式设置成与旧哨兵值相同的 60000，旧实现会被全局配置悄悄改掉
        HttpRequest request = HttpRequest.get(baseUrl + "/ok")
                .connectTimeoutMs(60_000)
                .readTimeoutMs(60_000);
        assertTrue(request.isConnectTimeoutSet());
        assertTrue(request.isReadTimeoutSet());
        HttpResponse response = HttpUtils.execute(request);
        try {
            assertEquals(200, response.code());
        } finally {
            response.close();
        }
        assertEquals(60_000, request.getConnectTimeoutMs());
        assertEquals(60_000, request.getReadTimeoutMs());
    }

    @Test
    public void unsetTimeoutStillPicksUpGlobalConfig() throws IOException {
        HttpUtils.setConnectTimeout(5_000);
        HttpUtils.setReadTimeout(6_000);
        HttpRequest request = HttpRequest.get(baseUrl + "/ok");
        HttpResponse response = HttpUtils.execute(request);
        try {
            assertEquals(200, response.code());
        } finally {
            response.close();
        }
        assertEquals("未显式设置时应套用全局连接超时", 5_000, request.getConnectTimeoutMs());
        assertEquals("未显式设置时应套用全局读取超时", 6_000, request.getReadTimeoutMs());
    }

    @Test
    public void copyPreservesExplicitFlags() {
        HttpRequest plain = HttpRequest.get("http://x/y");
        assertFalse(plain.copy().isConnectTimeoutSet());
        assertFalse(plain.copy().isReadTimeoutSet());
        assertFalse(plain.copy().isIgnoreSslSet());

        HttpRequest explicit = HttpRequest.get("http://x/y")
                .connectTimeoutMs(111).readTimeoutMs(222).ignoreSsl(false);
        HttpRequest copy = explicit.copy();
        assertTrue(copy.isConnectTimeoutSet());
        assertTrue(copy.isReadTimeoutSet());
        assertTrue(copy.isIgnoreSslSet());
        assertEquals(111, copy.getConnectTimeoutMs());
        assertEquals(222, copy.getReadTimeoutMs());
        assertFalse(copy.isIgnoreSsl());
    }

    // ---------- 下载安全 ----------

    @Test
    public void downloadRejects404AndLeavesNoFile() {
        File dest = new File(workDir, "should-not-exist.bin");
        dest.delete();
        try {
            HttpUtils.download(baseUrl + "/not-found", dest.getName(), workDir.getAbsolutePath());
            fail("404 不应被当成下载成功");
        } catch (IOException e) {
            assertTrue("异常信息应包含状态码，实际: " + e.getMessage(), e.getMessage().contains("404"));
        }
        assertFalse("失败的下载不得留下目标文件", dest.exists());
    }

    @Test
    public void downloadRejects500WithBodySnippet() {
        File dest = new File(workDir, "error-page.bin");
        dest.delete();
        try {
            HttpUtils.download(baseUrl + "/server-error", dest.getName(), workDir.getAbsolutePath());
            fail("500 不应被当成下载成功");
        } catch (IOException e) {
            assertTrue("应带上状态码: " + e.getMessage(), e.getMessage().contains("500"));
            assertTrue("应带上正文片段便于排障: " + e.getMessage(), e.getMessage().contains("boom"));
        }
        assertFalse(dest.exists());
    }

    @Test
    public void downloadDetectsTruncatedResponse() {
        File dest = new File(workDir, "truncated.bin");
        dest.delete();
        try {
            HttpUtils.download(baseUrl + "/truncated", dest.getName(), workDir.getAbsolutePath());
            fail("声明 100 字节却只收到 10 字节，应判定为失败");
        } catch (IOException expected) {
            // 服务端提前关闭时底层流会先抛出 premature EOF；若服务端干净收尾，
            // 则由 downloadOnce 的 saved != total 校验兜底。两条路径都必须失败。
        }
        assertFalse("截断的下载不得留下目标文件", dest.exists());
        File[] leftovers = workDir.listFiles((dir, name) -> name.startsWith("truncated") && name.endsWith(".part"));
        assertEquals("失败后不应残留临时文件", 0, leftovers == null ? 0 : leftovers.length);
    }

    @Test
    public void downloadDisablesContentEncodingNegotiation() throws IOException {
        File dest = new File(workDir, "identity.bin");
        dest.delete();
        String path = HttpUtils.download(baseUrl + "/ok", dest.getName(), workDir.getAbsolutePath());
        assertEquals("hello-download", new String(Files.readAllBytes(new File(path).toPath()),
                StandardCharsets.UTF_8));
        assertEquals("下载必须声明 identity，否则 Range 续传会损坏文件",
                "identity", LAST_ACCEPT_ENCODING.get());
    }

    @Test
    public void downloadSucceedsAndLeavesNoPartFile() throws IOException {
        File dest = new File(workDir, "clean.bin");
        dest.delete();
        String path = HttpUtils.download(baseUrl + "/ok", dest.getName(), workDir.getAbsolutePath());
        assertEquals(dest.getAbsolutePath(), path);
        assertTrue(dest.isFile());
        File[] leftovers = workDir.listFiles((dir, name) -> name.endsWith(".part"));
        assertEquals("成功后不应残留临时文件", 0, leftovers == null ? 0 : leftovers.length);
    }

    @Test
    public void failedDownloadDoesNotClobberExistingFile() throws IOException {
        File dest = new File(workDir, "keep-me.bin");
        Files.write(dest.toPath(), "original-content".getBytes(StandardCharsets.UTF_8));
        try {
            HttpUtils.download(baseUrl + "/not-found", dest.getName(), workDir.getAbsolutePath());
            fail("404 应抛错");
        } catch (IOException expected) {
            // ignore
        }
        assertEquals("下载失败不得破坏已存在的旧文件", "original-content",
                new String(Files.readAllBytes(dest.toPath()), StandardCharsets.UTF_8));
    }

    @Test
    public void downloadAtomicStillVerifiesSha256() throws IOException {
        File dest = new File(workDir, "atomic.bin");
        dest.delete();
        try {
            HttpUtils.downloadAtomic(baseUrl + "/ok", dest, "0000000000000000000000000000000000000000000000000000000000000000", null);
            fail("SHA-256 不匹配应抛错");
        } catch (IOException e) {
            assertTrue(e.getMessage().contains("SHA-256"));
        }
        assertFalse("校验失败不得留下目标文件", dest.exists());
    }

    // ---------- 弃用方法不再返回共享静态路径 ----------

    @Test
    @SuppressWarnings("deprecation")
    public void deprecatedAsyncDownloadReturnsNullInsteadOfSharedPath() throws IOException {
        assertNull("异步下载刚提交，不应返回别人的下载路径",
                HttpUtils.getFileFromHttpData(baseUrl + "/ok", false));
        assertNull(HttpUtils.getFileFromHttpDataByAsyn(baseUrl + "/ok", "async.bin"));
    }

    // ---------- 日志脱敏 ----------

    @Test
    public void maskedKeysKeepsOnlyFieldNames() {
        java.util.Map<String, Object> form = new java.util.LinkedHashMap<String, Object>();
        form.put("username", "tom");
        form.put("password", "s3cret");
        String masked = HttpIo.maskedKeys(form);
        assertTrue(masked.contains("username"));
        assertTrue(masked.contains("password"));
        assertFalse("表单值不得进日志", masked.contains("s3cret"));
        assertEquals("[]", HttpIo.maskedKeys(null));
    }

    private static void send(HttpExchange exchange, int code, String contentType, byte[] body) throws IOException {
        if (contentType != null) {
            exchange.getResponseHeaders().set("Content-Type", contentType);
        }
        exchange.sendResponseHeaders(code, body.length);
        exchange.getResponseBody().write(body);
        exchange.close();
    }

    private static byte[] readAll(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[1024];
        int n;
        while ((n = in.read(buf)) != -1) {
            out.write(buf, 0, n);
        }
        return out.toByteArray();
    }
}
