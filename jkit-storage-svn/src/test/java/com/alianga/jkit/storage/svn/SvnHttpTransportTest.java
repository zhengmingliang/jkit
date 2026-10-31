package com.alianga.jkit.storage.svn;

import com.alianga.jkit.storage.FileStorage;
import com.alianga.jkit.storage.FileStorages;
import com.alianga.jkit.storage.StorageErrorCode;
import com.alianga.jkit.storage.StorageException;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import com.sun.net.httpserver.HttpsConfigurator;
import com.sun.net.httpserver.HttpsParameters;
import com.sun.net.httpserver.HttpsServer;

import org.junit.Assume;
import org.junit.Test;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * http / https 协议通道测试。
 *
 * <p>本环境没有 Apache + mod_dav_svn，无法起完整 DAV 版本库；因此用 JDK 内置 HTTP(S)
 * 服务做两类确定性验证：①引擎确实走 HTTP 传输（OPTIONS/PROPFIND 打到桩，URL 路径
 * 拼接正确）；②HTTP 状态码到统一错误码的翻译（401 鉴权失败、404 不存在、
 * 连接被拒、自签名证书信任开关）。完整 DAV 生命周期通过环境变量
 * {@code STORAGE_SVN_HTTP_URL} 门控，在有真实 SVN HTTP 服务端的机器上启用。</p>
 *
 * @author 郑明亮
 */
public class SvnHttpTransportTest {

    private static final List<String> REQUESTS = new ArrayList<>();

    @Test
    public void http401MappedToAuthFailureAndUsesHttpTransport() throws Exception {
        REQUESTS.clear();
        AtomicReference<String> authHeader = new AtomicReference<>();
        try (StubServer server = StubServer.http(exchange -> {
            REQUESTS.add(exchange.getRequestMethod() + " " + exchange.getRequestURI());
            authHeader.set(exchange.getRequestHeaders().getFirst("Authorization"));
            empty(401, exchange, "WWW-Authenticate", "Basic realm=\"svn-test\"");
        })) {
            try (FileStorage storage = open("http", server.port(), "/svn/repo",
                    "testuser", "testpass", true)) {
                expectCode(StorageErrorCode.AUTH_FAILED, () -> storage.list(""));
            }
        }
        // 401 后引擎带凭据重试，Authorization 头确实发出（Basic base64(user:pass)）
        assertTrue("应通过 HTTP OPTIONS 发起 DAV 协商，实际请求：" + REQUESTS,
                REQUESTS.stream().anyMatch(r -> r.startsWith("OPTIONS")));
        assertTrue("请求路径应包含 basePath /svn/repo：" + REQUESTS,
                REQUESTS.stream().anyMatch(r -> r.contains("/svn/repo")));
        assertTrue("401 后应携带 Basic 认证头",
                authHeader.get() != null && authHeader.get().startsWith("Basic "));
    }

    @Test
    public void http404OnRepositoryMappedToNotFound() throws Exception {
        try (StubServer server = StubServer.http(exchange -> empty(404, exchange))) {
            try (FileStorage storage = open("http", server.port(), "/no-repo", null, null, true)) {
                expectCode(StorageErrorCode.NOT_FOUND, () -> storage.list(""));
            }
        }
    }

    @Test
    public void httpConnectionRefusedIsConnectFailure() throws Exception {
        int closed = freePort();
        try (FileStorage storage = open("http", closed, "/repo", null, null, true)) {
            expectCode(StorageErrorCode.CONNECT_FAILED, () -> storage.list(""));
        }
    }

    @Test
    public void httpsSelfSignedCertAcceptedWhenTrustEnabled() throws Exception {
        try (StubServer server = StubServer.https(exchange ->
                empty(401, exchange, "WWW-Authenticate", "Basic realm=\"svn-test\""))) {
            try (FileStorage storage = open("https", server.port(), "/svn/repo",
                    "testuser", "testpass", true)) {
                expectCode(StorageErrorCode.AUTH_FAILED, () -> storage.list(""));
            }
        }
    }

    @Test
    public void httpsSelfSignedCertRejectedWhenTrustDisabled() throws Exception {
        try (StubServer server = StubServer.https(exchange -> empty(200, exchange))) {
            try (FileStorage storage = open("https", server.port(), "/svn/repo",
                    null, null, false)) {
                try {
                    storage.list("");
                    fail("自签名证书未被信任时应连接失败");
                } catch (StorageException e) {
                    assertTrue("证书不被信任应归入连接类错误，实际：" + e.code(),
                            e.code() == StorageErrorCode.CONNECT_FAILED
                                    || e.code() == StorageErrorCode.IO_ERROR);
                }
            }
        }
    }

    @Test
    public void fullLifecycleAgainstRealDavServerWhenConfigured() throws Exception {
        String url = System.getenv("STORAGE_SVN_HTTP_URL");
        Assume.assumeNotNull("未配置 STORAGE_SVN_HTTP_URL，跳过真实 DAV 生命周期测试", url);
        SvnStorageSupport.register();
        Map<String, String> config = new LinkedHashMap<>();
        if (System.getenv("STORAGE_SVN_USER") != null) {
            config.put("user", System.getenv("STORAGE_SVN_USER"));
            config.put("password", System.getenv().getOrDefault("STORAGE_SVN_PASS", ""));
        }
        java.net.URI uri = java.net.URI.create(url);
        config.put("protocol", uri.getScheme());
        config.put("host", uri.getHost());
        if (uri.getPort() > 0) {
            config.put("port", String.valueOf(uri.getPort()));
        }
        config.put("basePath", uri.getPath() == null ? "/" : uri.getPath());
        config.put("trustServerCert", "true");
        String suffix = "it-" + System.nanoTime();
        try (FileStorage storage = FileStorages.of("svn", config)) {
            storage.mkdir(suffix + "/d");
            byte[] body = "dav-it".getBytes(StandardCharsets.UTF_8);
            storage.upload(suffix + "/d/f.txt", new java.io.ByteArrayInputStream(body), body.length);
            assertTrue(storage.exists(suffix + "/d/f.txt"));
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            storage.download(suffix + "/d/f.txt", out);
            assertEquals(new String(body, StandardCharsets.UTF_8), out.toString(StandardCharsets.UTF_8));
            storage.rename(suffix + "/d/f.txt", suffix + "/d/g.txt");
            assertTrue(storage.exists(suffix + "/d/g.txt"));
            storage.deleteDir(suffix);
        }
    }

    // ---- helpers ----

    private static FileStorage open(String protocol, int port, String basePath,
                                    String user, String password, boolean trustCert) {
        SvnStorageSupport.register();
        Map<String, String> config = new LinkedHashMap<>();
        config.put("protocol", protocol);
        config.put("host", "127.0.0.1");
        config.put("port", String.valueOf(port));
        config.put("basePath", basePath);
        if (user != null) {
            config.put("user", user);
        }
        if (password != null) {
            config.put("password", password);
        }
        config.put("trustServerCert", String.valueOf(trustCert));
        return FileStorages.of("svn", config);
    }

    private static void expectCode(StorageErrorCode expected, ThrowingRunnable runnable) {
        try {
            runnable.run();
            fail("期望抛出 " + expected);
        } catch (StorageException e) {
            assertEquals(expected, e.code());
        } catch (Exception e) {
            throw new AssertionError("期望 StorageException，实际 " + e, e);
        }
    }

    private interface ThrowingRunnable {
        void run() throws Exception;
    }

    private static int freePort() throws IOException {
        try (ServerSocket socket = new ServerSocket()) {
            socket.bind(new InetSocketAddress("127.0.0.1", 0));
            return socket.getLocalPort();
        }
    }

    private static void empty(int status, HttpExchange exchange, String... responseHeader)
            throws IOException {
        for (int i = 0; i + 1 < responseHeader.length; i += 2) {
            exchange.getResponseHeaders().add(responseHeader[i], responseHeader[i + 1]);
        }
        byte[] body = new byte[0];
        exchange.sendResponseHeaders(status, body.length);
        exchange.getResponseBody().write(body);
        exchange.close();
    }

    /** JDK 内置 HTTP/HTTPS 测试桩。 */
    private static final class StubServer implements AutoCloseable {

        private final HttpServer server;

        private StubServer(HttpServer server) {
            this.server = server;
        }

        static StubServer http(HttpHandler handler) throws IOException {
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            prepare(server, handler);
            server.start();
            return new StubServer(server);
        }

        static StubServer https(HttpHandler handler) throws Exception {
            Path keystore = Files.createTempFile("jkit-svn-https", ".p12");
            Files.deleteIfExists(keystore); // keytool 拒绝写入已存在的空 PKCS12 文件
            String password = "changeit";
            String dname = "CN=localhost, OU=jkit-test, O=alianga, L=SH, ST=SH, C=CN";
            Process keytool = new ProcessBuilder("keytool", "-genkeypair", "-alias", "svn-test",
                    "-keyalg", "RSA", "-keysize", "2048", "-validity", "2",
                    "-dname", dname, "-keystore", keystore.toString(),
                    "-storetype", "PKCS12", "-storepass", password, "-keypass", password)
                    .redirectErrorStream(true).start();
            String output = new String(keytool.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            Assume.assumeTrue("keytool 生成自签名证书失败：" + output, keytool.waitFor() == 0);

            char[] pass = password.toCharArray();
            java.security.KeyStore store = java.security.KeyStore.getInstance("PKCS12");
            try (java.io.InputStream in = Files.newInputStream(keystore)) {
                store.load(in, pass);
            }
            KeyManagerFactory kmf = KeyManagerFactory.getInstance("SunX509");
            kmf.init(store, pass);
            SSLContext context = SSLContext.getInstance("TLS");
            context.init(kmf.getKeyManagers(), null, null);

            HttpsServer server = HttpsServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.setHttpsConfigurator(new HttpsConfigurator(context) {
                @Override
                public void configure(HttpsParameters params) {
                    params.setNeedClientAuth(false);
                }
            });
            prepare(server, handler);
            server.start();
            Files.deleteIfExists(keystore);
            return new StubServer(server);
        }

        private static void prepare(HttpServer server, HttpHandler handler) {
            server.createContext("/", handler);
        }

        int port() {
            return server.getAddress().getPort();
        }

        @Override
        public void close() {
            server.stop(0);
        }
    }
}
