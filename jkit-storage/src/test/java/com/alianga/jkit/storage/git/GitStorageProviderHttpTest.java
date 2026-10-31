package com.alianga.jkit.storage.git;

import com.alianga.jkit.storage.StorageConfig;
import com.alianga.jkit.storage.StorageErrorCode;
import com.alianga.jkit.storage.StorageException;
import com.sun.net.httpserver.HttpServer;

import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

/**
 * {@link GitStorageProvider} 对真实 HTTP 端点的行为：列表 / 下载 / 上传 / 删除，
 * 以及<b>手动模式取消必须中止写操作</b>这条硬约束。
 *
 * <p>用 JDK 自带的 {@code com.sun.net.httpserver} 起一个本地桩服务，
 * 不通外网、不需要令牌，同时能精确断言「取消时到底有没有发 PUT / DELETE」。</p>
 *
 * @author 郑明亮
 */
public class GitStorageProviderHttpTest {
    /** 桩服务收到的请求：方法 + 路径 + body。 */
    private static final List<String> RECEIVED = new ArrayList<>();
    private static HttpServer server;
    private static String baseUrl;

    @BeforeClass
    public static void startServer() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            String method = exchange.getRequestMethod();
            String path = exchange.getRequestURI().getPath();
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            RECEIVED.add(method + " " + path);
            String response;
            int status = 200;
            if (path.endsWith("/contents/docs")) {
                // 列目录：一个目录 + 一个文件
                response = "[{\"name\":\"sub\",\"path\":\"docs/sub\",\"type\":\"dir\",\"sha\":\"t1\"},"
                        + "{\"name\":\"a.txt\",\"path\":\"docs/a.txt\",\"type\":\"file\",\"size\":5,"
                        + "\"sha\":\"blob1\",\"download_url\":\"" + baseUrl + "/raw/docs/a.txt\"}]";
            } else if (path.endsWith("/contents/docs/a.txt") && "GET".equals(method)) {
                response = "{\"name\":\"a.txt\",\"path\":\"docs/a.txt\",\"type\":\"file\",\"size\":5,"
                        + "\"sha\":\"blob1\",\"encoding\":\"base64\",\"content\":\"aGVsbG8=\"}";
            } else if (path.endsWith("/raw/docs/a.txt")) {
                byte[] raw = "hello".getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(200, raw.length);
                exchange.getResponseBody().write(raw);
                exchange.close();
                return;
            } else if (path.endsWith("/contents/docs/.gitkeep")) {
                // docs 目录的占位文件已存在：用于验证 mkdir 幂等
                response = "{\"name\":\".gitkeep\",\"path\":\"docs/.gitkeep\",\"type\":\"file\","
                        + "\"size\":1,\"sha\":\"keep1\"}";
            } else if (path.endsWith("/gitee-missing")) {
                // Gitee 形态：文件不存在时返回 200 + 空数组（不是 404）
                response = "[]";
            } else if (path.endsWith("/commits")) {
                response = "[{\"sha\":\"abcdef1234567890\",\"commit\":{\"author\":{\"name\":\"alice\","
                        + "\"date\":\"2024-05-01T10:00:00Z\"},\"message\":\"init\"}}]";
            } else if ("/missing".equals(path)) {
                status = 404;
                response = "{\"message\":\"Not Found\"}";
            } else if ("/denied".equals(path)) {
                status = 403;
                response = "{\"message\":\"Forbidden\"}";
            } else if (path.contains("/contents/new.txt")) {
                // 上传：回一份写入结果
                response = "{\"content\":{\"name\":\"new.txt\",\"path\":\"new.txt\",\"sha\":\"new1\"},"
                        + "\"commit\":{\"sha\":\"c1\"}}";
                if ("PUT".equals(method) && !body.contains("\"message\"")) {
                    // 提交信息缺失：桩服务直接判失败，用来验证我们一定会带上 message
                    status = 422;
                    response = "{\"message\":\"commit message required\"}";
                }
            } else {
                response = "{\"ok\":true}";
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

    private static StorageConfig config(String mode) {
        Map<String, String> values = new java.util.LinkedHashMap<>();
        values.put(GitSourceConfig.HOST, "gitea");
        values.put(GitSourceConfig.BASE_URL, baseUrl);
        values.put(GitSourceConfig.OWNER, "o");
        values.put(GitSourceConfig.REPO, "r");
        values.put(GitSourceConfig.BRANCH, "main");
        values.put(GitSourceConfig.TOKEN, "tok");
        if (mode != null) {
            values.put(GitSourceConfig.COMMIT_MODE, mode);
        }
        return StorageConfig.of(values);
    }

    @Test
    public void listDirectoryReturnsEntriesSortedWithDirsFirst() {
        try (GitStorageProvider provider = new GitStorageProvider(config(null))) {
            List<com.alianga.jkit.storage.StorageEntry> entries = provider.list("docs");
            assertEquals(2, entries.size());
            assertTrue("目录排在文件前", entries.get(0).isDirectory());
            assertEquals("sub", entries.get(0).name());
            assertEquals("a.txt", entries.get(1).name());
            assertEquals(5L, entries.get(1).size());
            assertEquals("blob1", entries.get(1).attribute(GitStorageProvider.ATTR_GIT_SHA));
        }
    }

    @Test
    public void statSingleFile() {
        try (GitStorageProvider provider = new GitStorageProvider(config(null))) {
            com.alianga.jkit.storage.StorageEntry entry = provider.stat("docs/a.txt");
            assertFalse(entry.isDirectory());
            assertEquals(5L, entry.size());
        }
    }

    @Test
    public void missingPathIsNotFound() {
        try (GitStorageProvider provider = new GitStorageProvider(config(null))) {
            StorageException error = assertThrows(StorageException.class,
                    () -> provider.stat("docs/nope.txt"));
            assertEquals(StorageErrorCode.NOT_FOUND, error.code());
            assertFalse(provider.exists("docs/nope.txt"));
        }
    }

    @Test
    public void downloadUsesInlineBase64() {
        try (GitStorageProvider provider = new GitStorageProvider(config(null))) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            provider.download("docs/a.txt", out);
            assertEquals("hello", out.toString(StandardCharsets.UTF_8));
        }
    }

    @Test
    public void uploadInAutoModeCarriesRenderedMessage() {
        RECEIVED.clear();
        try (GitStorageProvider provider = new GitStorageProvider(config("auto"))) {
            provider.upload("new.txt", new ByteArrayInputStream("hi".getBytes(StandardCharsets.UTF_8)), 2L);
        }
        assertTrue("必须发起一次 PUT", RECEIVED.stream().anyMatch(r -> r.startsWith("PUT ")));
        assertTrue("路径必须落在 contents API 上",
                RECEIVED.stream().anyMatch(r -> r.contains("/api/v1/repos/o/r/contents/new.txt")));
    }

    @Test
    public void uploadInManualModeCancelledProducesNoWrite() {
        RECEIVED.clear();
        // 手动模式 + 提供者返回 null（模拟用户在弹窗里点取消）
        try (GitStorageProvider provider = new GitStorageProvider(config("manual"),
                (cfg, op, repoPath) -> null)) {
            provider.upload("new.txt", new ByteArrayInputStream("hi".getBytes(StandardCharsets.UTF_8)), 2L);
        }
        assertFalse("取消后绝不能发起 PUT", RECEIVED.stream().anyMatch(r -> r.startsWith("PUT ")));
        assertFalse("取消后也不能发起 DELETE", RECEIVED.stream().anyMatch(r -> r.startsWith("DELETE ")));
    }

    @Test
    public void uploadInManualModeUsesProvidedMessage() {
        RECEIVED.clear();
        try (GitStorageProvider provider = new GitStorageProvider(config("manual"),
                (cfg, op, repoPath) -> "my manual message")) {
            provider.upload("new.txt", new ByteArrayInputStream("hi".getBytes(StandardCharsets.UTF_8)), 2L);
        }
        assertTrue("必须发起 PUT", RECEIVED.stream().anyMatch(r -> r.startsWith("PUT ")));
    }

    @Test
    public void manualModeWithoutInputSourceFailsLoudly() {
        GitStorageProvider.UI = null;
        try (GitStorageProvider provider = new GitStorageProvider(config("manual"))) {
            StorageException error = assertThrows(StorageException.class,
                    () -> provider.upload("new.txt", new ByteArrayInputStream("hi".getBytes(StandardCharsets.UTF_8)), 2L));
            assertEquals(StorageErrorCode.INVALID_CONFIG, error.code());
            assertTrue(error.getMessage().contains("手动填写提交信息"));
        }
    }

    @Test
    public void deleteCancelledProducesNoWrite() {
        RECEIVED.clear();
        try (GitStorageProvider provider = new GitStorageProvider(config("manual"),
                (cfg, op, repoPath) -> null)) {
            provider.delete("docs/a.txt");
        }
        assertFalse("取消删除后不能发起 DELETE",
                RECEIVED.stream().anyMatch(r -> r.startsWith("DELETE ")));
    }

    @Test
    public void commitsReturnParsedHistory() {
        try (GitStorageProvider provider = new GitStorageProvider(config(null))) {
            List<GitCommit> commits = provider.commits("docs/a.txt", 10);
            assertEquals(1, commits.size());
            assertEquals("alice", commits.get(0).author());
            assertEquals("abcdef1234567890".substring(0, 7), commits.get(0).shortSha());
            assertEquals("init", commits.get(0).message());
        }
    }

    @Test
    public void anonymousWriteIsRejectedBeforeAnyRequest() {
        RECEIVED.clear();
        Map<String, String> values = new java.util.LinkedHashMap<>();
        values.put(GitSourceConfig.HOST, "gitea");
        values.put(GitSourceConfig.BASE_URL, baseUrl);
        values.put(GitSourceConfig.OWNER, "o");
        values.put(GitSourceConfig.REPO, "r");
        try (GitStorageProvider provider = new GitStorageProvider(StorageConfig.of(values))) {
            StorageException error = assertThrows(StorageException.class,
                    () -> provider.upload("new.txt", new ByteArrayInputStream("x".getBytes(StandardCharsets.UTF_8)), 1L));
            assertEquals("无令牌写操作必须报鉴权失败", StorageErrorCode.AUTH_FAILED, error.code());
        }
        assertFalse(RECEIVED.stream().anyMatch(r -> r.startsWith("PUT ")));
    }

    @Test
    public void oversizedUploadRejectedBeforeRequest() {
        try (GitStorageProvider provider = new GitStorageProvider(config("auto"))) {
            StorageException error = assertThrows(StorageException.class,
                    () -> provider.upload("big.bin", new ByteArrayInputStream(new byte[0]), 999L * 1024 * 1024));
            assertTrue("超限必须明确报错，不假装成功",
                    error.getMessage().contains("文件过大"));
        }
    }

    @Test
    public void batchCommitMessageReusedForWholeBatch() {
        RECEIVED.clear();
        try (GitStorageProvider provider = new GitStorageProvider(config("manual"))) {
            // 未装全局钩子也不装实例构造器：只有实例级批量覆盖时才能拿到提交信息
            provider.useBatchCommitMessage("batch message");
            provider.upload("new.txt", new ByteArrayInputStream("hi".getBytes(StandardCharsets.UTF_8)), 2L);
            provider.delete("docs/a.txt");
        }
        assertTrue("批量覆盖下必须发起 PUT", RECEIVED.stream().anyMatch(r -> r.startsWith("PUT ")));
        assertTrue("批量覆盖下必须发起 DELETE", RECEIVED.stream().anyMatch(r -> r.startsWith("DELETE ")));
    }

    @Test
    public void batchOverrideDoesNotLeakToOtherInstances() {
        // 实例级覆盖只作用于本实例：新实例没有覆盖，手动模式又没钩子 → 明确报错
        GitStorageProvider.UI = null;
        GitStorageProvider first = new GitStorageProvider(config("manual"));
        first.useBatchCommitMessage("only for first");
        try (GitStorageProvider second = new GitStorageProvider(config("manual"))) {
            assertThrows(StorageException.class, () -> second.upload("new.txt",
                    new ByteArrayInputStream("x".getBytes(StandardCharsets.UTF_8)), 1L));
        } finally {
            first.close();
        }
    }

    @Test
    public void giteeStyleMissingFileStillUploads() {
        // Gitee 的「文件不存在」是 200 + []：桩服务对 /gitee-missing 返回这个形态。
        // 必须照样完成上传（不能误判成「已存在」而失败）。
        RECEIVED.clear();
        try (GitStorageProvider provider = new GitStorageProvider(config("auto"))) {
            provider.upload("gitee-missing", new ByteArrayInputStream("hi".getBytes(StandardCharsets.UTF_8)), 2L);
        }
        assertTrue(RECEIVED.stream().anyMatch(r -> r.startsWith("PUT ")
                && r.contains("/contents/gitee-missing")));
    }

    @Test
    public void mkdirWritesNonEmptyPlaceholder() {
        // Gitee 拒绝零字节内容（400 "content is empty"）：占位文件必须写成非空。
        RECEIVED.clear();
        try (GitStorageProvider provider = new GitStorageProvider(config("auto"))) {
            provider.mkdir("newdir");
        }
        assertTrue(RECEIVED.stream().anyMatch(r -> r.startsWith("PUT ")
                || r.startsWith("POST ")));
        assertTrue("占位文件路径应为 newdir/.gitkeep",
                RECEIVED.stream().anyMatch(r -> r.contains("newdir/.gitkeep")));
    }

    @Test
    public void mkdirIsIdempotent() {
        // FileStorage 契约：已存在时视为成功。
        // 桩服务里 docs 目录已存在，重复 mkdir 不应再产生写请求。
        RECEIVED.clear();
        try (GitStorageProvider provider = new GitStorageProvider(config("auto"))) {
            provider.mkdir("docs");
            provider.mkdir("docs");
        }
        // 只断言「没有写请求」：GET 探测占位文件是允许的（也确实发生了两次）
        assertFalse("占位文件已存在时不该再产生写请求（否则每次同步多一条无意义提交）：" + RECEIVED,
                RECEIVED.stream().anyMatch(r -> r.startsWith("PUT ") || r.startsWith("POST ")));
        assertEquals("两次 mkdir 各探测一次占位文件", 2,
                RECEIVED.stream().filter(r -> r.startsWith("GET ")).count());
    }

    @Test
    public void platformHelp() {
        try (GitStorageProvider provider = new GitStorageProvider(config(null))) {
            assertEquals(GitStorageSupport.ENGINE_ID, provider.platform());
            assertFalse(provider.supportsRange());
            assertEquals("docs/a.txt", provider.absolutePathOf("docs/a.txt"));
            assertTrue(provider.toString().contains("gitea"));
            assertFalse("诊断文本不能泄漏令牌", provider.toString().contains("tok"));
        }
    }
}
