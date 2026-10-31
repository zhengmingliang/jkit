package com.alianga.jkit.storage.git;

import com.alianga.jkit.storage.StorageConfig;
import com.alianga.jkit.storage.StorageErrorCode;
import com.alianga.jkit.storage.StorageException;

import com.sun.net.httpserver.HttpServer;

import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

/**
 * rename / copy 的两个健壮性约束。
 *
 * <p>G3：单文件 REST 提交有体积上限，rename / copy 此前无界读源，大文件会把整个
 * 内容缓冲进堆（桌面低堆环境 OOM）。G4：rename 写目标时若目标已存在，PUT 不带
 * 目标 blob sha 会被平台以 422/400 拒绝，报错文案对用户是天书——必须先探测目标
 * sha 并带上（覆盖语义）。</p>
 *
 * @author 郑明亮
 */
public class GitRenameCopyGuardTest {
    /** 捕获的写请求 body（PUT / POST contents）。 */
    private static final List<String> WRITE_BODIES = new ArrayList<>();
    private static HttpServer server;
    private static String baseUrl;

    @BeforeClass
    public static void startServer() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            String method = exchange.getRequestMethod();
            String path = exchange.getRequestURI().getPath();
            String body = readAll(exchange);
            String response;
            int status = 200;
            if ("PUT".equals(method) || "POST".equals(method)) {
                WRITE_BODIES.add(body);
                response = "{\"content\":{\"sha\":\"newsha\"},\"commit\":{\"sha\":\"c1\"}}";
            } else if (path.endsWith("/contents/target.txt")) {
                // 目标已存在：给出它的 blob sha（rename 覆盖时必须回传）
                response = "{\"name\":\"target.txt\",\"path\":\"target.txt\",\"type\":\"file\","
                        + "\"size\":5,\"sha\":\"TARGETSHA\"}";
            } else if (path.endsWith("/contents/source.txt")) {
                // 源文件：内联 base64（"hello"），可被有界读取
                response = "{\"name\":\"source.txt\",\"path\":\"source.txt\",\"type\":\"file\","
                        + "\"size\":5,\"sha\":\"SRC\",\"encoding\":\"base64\",\"content\":\"aGVsbG8=\"}";
            } else {
                status = 404;
                response = "{\"message\":\"Not Found\"}";
            }
            byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status, bytes.length);
            exchange.getResponseBody().write(bytes);
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

    /** Java 8 替代 InputStream.readAllBytes（Java 9+）。 */
    private static String readAll(com.sun.net.httpserver.HttpExchange exchange)
            throws java.io.IOException {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        byte[] chunk = new byte[4096];
        int read;
        while ((read = exchange.getRequestBody().read(chunk)) >= 0) {
            out.write(chunk, 0, read);
        }
        return new String(out.toByteArray(), StandardCharsets.UTF_8);
    }

    private static StorageConfig config() {
        Map<String, String> values = new java.util.LinkedHashMap<>();
        values.put(GitSourceConfig.HOST, "gitea");
        values.put(GitSourceConfig.BASE_URL, baseUrl);
        values.put(GitSourceConfig.OWNER, "o");
        values.put(GitSourceConfig.REPO, "r");
        values.put(GitSourceConfig.BRANCH, "main");
        values.put(GitSourceConfig.TOKEN, "tok");
        values.put(GitSourceConfig.COMMIT_MODE, "auto");
        return StorageConfig.of(values);
    }

    // ------------------------------------------------------------ G3 体积防线

    @Test
    public void inlineBoundRejectsOversize() {
        StorageException e = assertThrows(StorageException.class,
                () -> GitStorageProvider.checkInlineBound(21L * 1024 * 1024, "big.bin"));
        assertEquals(StorageErrorCode.CONFLICT, e.code());
        assertTrue(e.getMessage().contains("MB"));
    }

    @Test
    public void inlineBoundAllowsUnderLimit() {
        GitStorageProvider.checkInlineBound(1024L, "small.txt");
        GitStorageProvider.checkInlineBound(20L * 1024 * 1024, "edge.bin");
    }

    // ------------------------------------------------------- G4 rename 目标 sha

    @Test
    public void renameToExistingTargetSendsTargetSha() {
        WRITE_BODIES.clear();
        try (GitStorageProvider provider = new GitStorageProvider(config())) {
            provider.rename("source.txt", "target.txt");
        }
        assertFalse("rename 应发起写请求", WRITE_BODIES.isEmpty());
        // 覆盖已存在目标：写请求必须带上目标的 blob sha，否则平台 422/400
        assertTrue("写目标必须携带已存在目标的 sha（覆盖语义），实际 body=" + WRITE_BODIES.get(0),
                WRITE_BODIES.get(0).contains("TARGETSHA"));
    }

    @Test
    public void copyToExistingTargetSendsTargetSha() {
        WRITE_BODIES.clear();
        try (GitStorageProvider provider = new GitStorageProvider(config())) {
            provider.copy("source.txt", "target.txt");
        }
        assertFalse(WRITE_BODIES.isEmpty());
        assertTrue("copy 覆盖已存在目标同样要带 sha，实际 body=" + WRITE_BODIES.get(0),
                WRITE_BODIES.get(0).contains("TARGETSHA"));
    }
}
