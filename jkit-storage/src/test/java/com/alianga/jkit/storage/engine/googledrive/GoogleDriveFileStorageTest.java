package com.alianga.jkit.storage.engine.googledrive;

import com.alianga.jkit.storage.FileStorage;
import com.alianga.jkit.storage.FileStorages;
import com.alianga.jkit.storage.StorageConfig;
import com.alianga.jkit.storage.StorageEntry;
import com.alianga.jkit.storage.StorageErrorCode;
import com.alianga.jkit.storage.StorageException;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * Google Drive 存储适配器单元与集成仿真测试：
 * 覆盖 OAuth 授权与刷新、路径映射、全套增删改查、断点续传、异常捕获与团队共享盘。
 *
 * @author 郑明亮
 */
public class GoogleDriveFileStorageTest {

    private FakeGoogleDriveServer server;

    @Before
    public void setUp() throws Exception {
        server = new FakeGoogleDriveServer();
        server.start();
    }

    @After
    public void tearDown() {
        if (server != null) {
            server.stop();
        }
    }

    private StorageConfig createConfig(String basePath) {
        Map<String, String> map = new LinkedHashMap<String, String>();
        map.put(GoogleDriveConfig.CLIENT_ID, "test-client-id");
        map.put(GoogleDriveConfig.CLIENT_SECRET, "test-client-secret");
        map.put(GoogleDriveConfig.ACCESS_TOKEN, server.accessToken);
        map.put(GoogleDriveConfig.REFRESH_TOKEN, server.refreshToken);
        map.put(GoogleDriveConfig.EXPIRES_AT, String.valueOf(System.currentTimeMillis() + 3600_000L));
        map.put(GoogleDriveConfig.BASE_PATH, basePath != null ? basePath : "/");
        map.put("apiBase", server.apiBase());
        map.put("uploadBase", server.uploadBase());
        map.put("tokenUrl", server.tokenUrl());
        return StorageConfig.of(map);
    }

    @Test
    public void testConfigValidation() {
        // 缺少 accessToken 与 refreshToken 时应抛异常
        try {
            GoogleDriveConfig.validate(StorageConfig.of(new LinkedHashMap<String, String>()));
            fail("应抛出缺少令牌异常");
        } catch (StorageException e) {
            assertEquals(StorageErrorCode.INVALID_CONFIG, e.code());
        }

        // 有 refreshToken 但缺 clientId / clientSecret 时抛异常
        Map<String, String> map = new LinkedHashMap<String, String>();
        map.put(GoogleDriveConfig.REFRESH_TOKEN, "r-token");
        try {
            GoogleDriveConfig.validate(StorageConfig.of(map));
            fail("应抛出缺少凭据异常");
        } catch (StorageException e) {
            assertEquals(StorageErrorCode.INVALID_CONFIG, e.code());
        }

        // 仅有 accessToken 时校验通过
        map.clear();
        map.put(GoogleDriveConfig.ACCESS_TOKEN, "a-token");
        GoogleDriveConfig.validate(StorageConfig.of(map));
    }

    @Test
    public void testOAuthUrlAndTokenExchange() {
        String authUrl = GoogleDriveOAuth.buildAuthorizeUrl("my-client", "http://localhost:8080/cb", null, "s123");
        assertTrue(authUrl.contains("client_id=my-client"));
        assertTrue(authUrl.contains("redirect_uri=http%3A%2F%2Flocalhost%3A8080%2Fcb"));
        assertTrue(authUrl.contains("access_type=offline"));
        assertTrue(authUrl.contains("state=s123"));

        // 换取令牌
        GoogleDriveOAuth.TokenResult token = GoogleDriveOAuth.requestToken(
                server.tokenUrl(), "my-client", "my-secret",
                "grant_type=authorization_code&code=test-code&client_id=my-client&client_secret=my-secret&redirect_uri=http://localhost:8080/cb",
                null, null);
        assertEquals("mock-access-token", token.accessToken());
        assertEquals("mock-refresh-token", token.refreshToken());

        // 刷新令牌
        GoogleDriveOAuth.TokenResult refreshed = GoogleDriveOAuth.requestToken(
                server.tokenUrl(), "my-client", "my-secret",
                "grant_type=refresh_token&client_id=my-client&client_secret=my-secret&refresh_token=mock-refresh-token",
                "mock-refresh-token", null);
        assertEquals("mock-access-token-refreshed", refreshed.accessToken());
    }

    @Test
    public void testLoopbackReceiver() throws Exception {
        try (GoogleDriveOAuth.LoopbackReceiver receiver = new GoogleDriveOAuth.LoopbackReceiver(0, "/gd/callback")) {
            int port = receiver.port();
            assertTrue(port > 0);

            // 异步触发回调
            new Thread(() -> {
                try {
                    Thread.sleep(100);
                    java.net.URL url = new java.net.URL("http://127.0.0.1:" + port + "/gd/callback?code=mock-code-123");
                    java.net.HttpURLConnection conn = (java.net.HttpURLConnection) url.openConnection();
                    conn.getResponseCode();
                    conn.disconnect();
                } catch (Exception ignore) {
                    // 忽略
                }
            }).start();

            String code = receiver.waitForCode(5);
            assertEquals("mock-code-123", code);
        }
    }

    /** 回调窗口被关闭时等待方应立即返回，而不是空等满超时。 */
    @Test
    public void testLoopbackReceiverCloseSettlesWaiters() throws Exception {
        final GoogleDriveOAuth.LoopbackReceiver receiver =
                new GoogleDriveOAuth.LoopbackReceiver(0, "/gd/callback");
        new Thread(() -> {
            try {
                Thread.sleep(200);
            } catch (InterruptedException ignore) {
                Thread.currentThread().interrupt();
            }
            receiver.close();
        }).start();
        long start = System.currentTimeMillis();
        try {
            receiver.waitForCode(60);
            fail("关闭窗口后 waitForCode 应抛异常");
        } catch (StorageException expected) {
            long elapsed = System.currentTimeMillis() - start;
            assertTrue("应立即返回而不是等满 60s，实际耗时 " + elapsed + "ms", elapsed < 5_000L);
        }
    }

    @Test
    public void testCompleteStorageOperations() {
        StorageConfig config = createConfig("/");
        FileStorage storage = FileStorages.of(GoogleDriveConfig.PLATFORM_ID, config);
        assertEquals(GoogleDriveConfig.PLATFORM_ID, storage.platform());
        assertTrue(storage.supportsRange());

        // 1. mkdir
        storage.mkdir("docs/sub");
        assertTrue(storage.exists("docs"));
        assertTrue(storage.exists("docs/sub"));
        StorageEntry dirEntry = storage.stat("docs/sub");
        assertTrue(dirEntry.isDirectory());
        assertEquals("docs/sub", dirEntry.path());

        // 2. upload (已知大小)
        byte[] content = "Hello Google Drive!".getBytes(StandardCharsets.UTF_8);
        storage.upload("docs/sub/hello.txt", new ByteArrayInputStream(content), content.length);
        assertTrue(storage.exists("docs/sub/hello.txt"));
        StorageEntry stat = storage.stat("docs/sub/hello.txt");
        assertFalse(stat.isDirectory());
        assertEquals(content.length, stat.size());

        // 3. download
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        storage.download("docs/sub/hello.txt", out);
        assertEquals("Hello Google Drive!", new String(out.toByteArray(), StandardCharsets.UTF_8));

        // 4. range download
        ByteArrayOutputStream rangeOut = new ByteArrayOutputStream();
        storage.download("docs/sub/hello.txt", 6L, rangeOut);
        assertEquals("Google Drive!", new String(rangeOut.toByteArray(), StandardCharsets.UTF_8));

        // 5. list
        List<StorageEntry> list = storage.list("docs/sub");
        assertEquals(1, list.size());
        assertEquals("docs/sub/hello.txt", list.get(0).path());

        // 6. copy
        storage.copy("docs/sub/hello.txt", "docs/hello-copy.txt");
        assertTrue(storage.exists("docs/hello-copy.txt"));
        ByteArrayOutputStream copyOut = new ByteArrayOutputStream();
        storage.download("docs/hello-copy.txt", copyOut);
        assertEquals("Hello Google Drive!", new String(copyOut.toByteArray(), StandardCharsets.UTF_8));

        // 7. rename
        storage.rename("docs/hello-copy.txt", "docs/renamed.txt");
        assertFalse(storage.exists("docs/hello-copy.txt"));
        assertTrue(storage.exists("docs/renamed.txt"));

        // 8. delete (移入回收站)
        storage.delete("docs/renamed.txt");
        assertFalse(storage.exists("docs/renamed.txt"));

        // 9. upload with unknown size (-1L)
        byte[] unknownPayload = "Stream payload of unknown size".getBytes(StandardCharsets.UTF_8);
        storage.upload("docs/stream.bin", new ByteArrayInputStream(unknownPayload), -1L);
        assertTrue(storage.exists("docs/stream.bin"));
        ByteArrayOutputStream streamOut = new ByteArrayOutputStream();
        storage.download("docs/stream.bin", streamOut);
        assertEquals("Stream payload of unknown size", new String(streamOut.toByteArray(), StandardCharsets.UTF_8));

        storage.close();
    }

    @Test
    public void testBasePathIsolation() {
        // 配置了根前缀 /work
        StorageConfig config = createConfig("/work");
        FileStorage storage = FileStorages.of(GoogleDriveConfig.PLATFORM_ID, config);

        storage.upload("notes.txt", "Work notes".getBytes(StandardCharsets.UTF_8));
        assertTrue(storage.exists("notes.txt"));
        StorageEntry entry = storage.stat("notes.txt");
        assertEquals("notes.txt", entry.path());

        List<StorageEntry> entries = storage.list("");
        assertEquals(1, entries.size());
        assertEquals("notes.txt", entries.get(0).path());
    }

    @Test
    public void testTokenRefreshOn401() {
        StorageConfig config = createConfig("/");
        GoogleDriveFileStorage storage = (GoogleDriveFileStorage) FileStorages.of(GoogleDriveConfig.PLATFORM_ID, config);

        storage.mkdir("test401");
        // 模拟触发一次 401
        server.trigger401Once = true;

        // 应该自动通过 refreshToken 刷新令牌并重试成功
        assertTrue(storage.exists("test401"));
        assertEquals("mock-access-token-refreshed", storage.client().accessToken());
    }

    /** 上传请求体是一次性流：401 时刷新令牌但本次操作报错，提示用户重试（不能静默丢数据）。 */
    @Test
    public void testUpload401RefreshesTokenButAsksRetry() {
        StorageConfig config = createConfig("/");
        GoogleDriveFileStorage storage = (GoogleDriveFileStorage) FileStorages.of(GoogleDriveConfig.PLATFORM_ID, config);

        byte[] payload = "payload".getBytes(StandardCharsets.UTF_8);
        // 先成功上传一次：路径缓存就绪，后续 upload 只发一个 multipart 请求
        storage.upload("retry-me.txt", new ByteArrayInputStream(payload), payload.length);
        assertTrue(storage.exists("retry-me.txt"));

        // 令牌换成刷新后的值，再让上传请求收到一次 401
        server.trigger401Once = true;
        try {
            storage.upload("retry-me.txt", new ByteArrayInputStream(payload), payload.length);
            fail("上传遇 401 应抛认证异常（数据流不可重放）");
        } catch (StorageException e) {
            assertEquals(StorageErrorCode.AUTH_FAILED, e.code());
            assertTrue("应提示重试该操作: " + e.getMessage(), e.getMessage().contains("重试"));
        }
        // 令牌已刷新：用户重试即可成功
        assertEquals("mock-access-token-refreshed", storage.client().accessToken());
        storage.upload("retry-me.txt", new ByteArrayInputStream(payload), payload.length);
        assertTrue(storage.exists("retry-me.txt"));
    }

    @Test
    public void testApiErrors() {
        StorageConfig config = createConfig("/");
        FileStorage storage = FileStorages.of(GoogleDriveConfig.PLATFORM_ID, config);

        // 404 测试
        try {
            storage.stat("non-existent-file-xyz.txt");
            fail("应抛出 NOT_FOUND");
        } catch (StorageException e) {
            assertEquals(StorageErrorCode.NOT_FOUND, e.code());
        }

        // 403 限流
        server.trigger403RateLimit = true;
        try {
            storage.stat("any");
            fail("应抛出 RATE_LIMITED");
        } catch (StorageException e) {
            assertEquals(StorageErrorCode.RATE_LIMITED, e.code());
        } finally {
            server.trigger403RateLimit = false;
        }

        // 403 权限不足
        server.trigger403PermissionDenied = true;
        try {
            storage.stat("any");
            fail("应抛出 AUTH_FAILED");
        } catch (StorageException e) {
            assertEquals(StorageErrorCode.AUTH_FAILED, e.code());
        } finally {
            server.trigger403PermissionDenied = false;
        }
    }

    @Test
    public void testSharedDrives() {
        StorageConfig config = createConfig("/");
        GoogleDriveFileStorage storage = (GoogleDriveFileStorage) FileStorages.of(GoogleDriveConfig.PLATFORM_ID, config);

        List<GoogleDriveClient.SharedDrive> drives = storage.client().listSharedDrives();
        assertNotNull(drives);
        assertEquals(1, drives.size());
        assertEquals("team-drive-1", drives.get(0).id());
        assertEquals("Team Shared Drive 1", drives.get(0).name());
    }
}
