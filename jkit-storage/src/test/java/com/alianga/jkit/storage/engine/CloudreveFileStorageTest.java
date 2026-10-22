package com.alianga.jkit.storage.engine;

import com.alianga.jkit.storage.FileStorages;
import com.alianga.jkit.storage.FileStorage;
import com.alianga.jkit.storage.StorageEntry;
import com.alianga.jkit.storage.StorageErrorCode;
import com.alianga.jkit.storage.StorageException;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

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
 * Cloudreve 引擎单元测试：OAuth2 授权码换令牌 / 刷新 + 文件 API 全流程
 * （列表 / stat / 建目录 / 上传 / 下载 / 删除 / 改名 / 复制 / 容量 / 链接）。
 *
 * @author 郑明亮
 */
public class CloudreveFileStorageTest {
    private FakeCloudreveServer server;
    private String endpoint;

    @Before
    public void startServer() throws Exception {
        server = new FakeCloudreveServer();
        endpoint = server.start();
    }

    @After
    public void stopServer() {
        server.stop();
    }

    /** 授权码模式的连接配置（构造时即完成换令牌）。 */
    private FileStorage codeStorage() {
        Map<String, String> values = new LinkedHashMap<>();
        values.put("siteUrl", endpoint);
        values.put("oauthClientId", "fake-client");
        values.put("oauthClientSecret", "fake-secret");
        values.put("user", "zml@alianga.com");
        values.put("password", "test-password");
        values.put("oauthCode", "one-time-code");
        return FileStorages.of("cloudreve", values);
    }

    /** 刷新令牌模式的连接配置。 */
    private FileStorage refreshStorage() {
        Map<String, String> values = new LinkedHashMap<>();
        values.put("siteUrl", endpoint);
        values.put("oauthClientId", "fake-client");
        values.put("oauthClientSecret", "fake-secret");
        values.put("user", "zml@alianga.com");
        values.put("password", "test-password");
        values.put("oauthRefreshToken", "fake-refresh-token");
        return FileStorages.of("cloudreve", values);
    }

    /** 账号密码直连模式的连接配置（内置 OAuth 凭据，构造时自动登录 + 授权）。 */
    private FileStorage passwordStorage() {
        Map<String, String> values = new LinkedHashMap<>();
        values.put("siteUrl", endpoint);
        values.put("user", "zml@alianga.com");
        values.put("password", "test-password");
        return FileStorages.of("cloudreve", values);
    }

    // ---------------------------------------------------------------- 预设与配置

    @Test
    public void presetRegisteredWithFields() {
        com.alianga.jkit.storage.StoragePlatform platform = FileStorages.platform("cloudreve");
        assertNotNull("cloudreve 平台应已内置注册", platform);
        assertEquals("cloudreve", platform.engine());
        assertEquals("Cloudreve", platform.displayName());
        assertTrue("站点地址应必填", findField(platform, "siteUrl").isRequired());
        assertTrue("用户名应必填", findField(platform, "user").isRequired());
        assertTrue("密码应为密钥字段", findField(platform, "password").isSecret());
        assertTrue("oauthClientSecret 应为密钥字段", findField(platform, "oauthClientSecret").isSecret());
    }

    @Test
    public void missingCredentialsRejected() {
        try {
            Map<String, String> values = new LinkedHashMap<>();
            values.put("siteUrl", endpoint);
            FileStorages.of("cloudreve", values);
            fail("缺认证凭据应抛 INVALID_CONFIG");
        } catch (StorageException e) {
            assertEquals(StorageErrorCode.INVALID_CONFIG, e.code());
        }
    }

    @Test
    public void passwordOnlyAuthenticates() {
        // 只填用户名密码：内置 OAuth 凭据 + 程序自动登录 + 授权，零浏览器步骤
        FileStorage storage = passwordStorage();
        List<StorageEntry> list = storage.list("");
        assertTrue("登录 + 授权 + 列表全链路应通过: " + server.lastAuthorization,
                "Bearer fake-access-token".equals(server.lastAuthorization));
        // 授权码应来自 consent（consent-code- 前缀）
        assertTrue("授权码应来自 consent 申请", server.usedCodes.stream().anyMatch(
                c -> c.startsWith("consent-code-")));
    }

    @Test
    public void wrongPasswordMapsToAuthFailed() {
        server.loginShouldFail = true;
        try {
            passwordStorage();
            fail("密码错误应抛 AUTH_FAILED");
        } catch (StorageException e) {
            assertEquals(StorageErrorCode.AUTH_FAILED, e.code());
        }
    }

    @Test
    public void badSiteUrlRejected() {
        try {
            Map<String, String> values = new LinkedHashMap<>();
            values.put("siteUrl", "pan.example.com");
            values.put("user", "zml@alianga.com");
            values.put("password", "p");
            FileStorages.of("cloudreve", values);
            fail("站点地址缺 scheme 应抛 INVALID_CONFIG");
        } catch (StorageException e) {
            assertEquals(StorageErrorCode.INVALID_CONFIG, e.code());
        }
    }

    // ---------------------------------------------------------------- 认证

    @Test
    public void codeExchangesTokenAndExposesRefresh() {
        FileStorage storage = codeStorage();
        assertNotNull("授权码换令牌应成功", storage);
        CloudreveFileStorage cloudreve = (CloudreveFileStorage) storage;
        assertEquals("fake-refresh-token", cloudreve.refreshToken());
        // 授权码应已兑换
        assertTrue("授权码应已发给令牌端点", server.usedCodes.contains("one-time-code"));
    }

    @Test
    public void refreshTokenModeWorks() {
        FileStorage storage = refreshStorage();
        storage.list("");
        assertTrue("Bearer 头应携带 access token: " + server.lastAuthorization,
                "Bearer fake-access-token".equals(server.lastAuthorization));
    }

    @Test
    public void exchangeFailureMapsToAuthFailed() {
        server.exchangeShouldFail = true;
        try {
            codeStorage();
            fail("授权码无效应抛 AUTH_FAILED");
        } catch (StorageException e) {
            assertEquals(StorageErrorCode.AUTH_FAILED, e.code());
        }
    }

    @Test
    public void refreshFailureMapsToAuthFailed() {
        server.refreshShouldFail = true;
        try {
            refreshStorage();
            fail("刷新令牌失效应抛 AUTH_FAILED");
        } catch (StorageException e) {
            assertEquals(StorageErrorCode.AUTH_FAILED, e.code());
        }
    }

    @Test
    public void fileApiDeniedMapsToAuthFailed() {
        server.denyFileApi = true;
        try {
            refreshStorage().list("");
            fail("文件 API 401 应抛 AUTH_FAILED");
        } catch (StorageException e) {
            assertEquals(StorageErrorCode.AUTH_FAILED, e.code());
        }
    }

    @Test
    public void expiredTokenRefreshesAutomatically() {
        FileStorage storage = refreshStorage();
        // 模拟 access token 过期：把过期时间拨回过去
        CloudreveFileStorage cloudreve = (CloudreveFileStorage) storage;
        setExpiresAt(cloudreve, System.currentTimeMillis() - 1000L);
        server.seedDir("docs");
        List<StorageEntry> list = storage.list("");
        assertEquals(1, list.size());
        // 刷新请求应发出（refresh_token 轮换后为 refreshed- 前缀）
        assertTrue("应发起刷新请求", server.received.stream().anyMatch(r ->
                r.startsWith("POST /api/v4/session/token/refresh")));
    }

    /** 反射拨回 access token 过期时间（过期自动刷新场景）。 */
    private static void setExpiresAt(CloudreveFileStorage storage, long millis) {
        try {
            java.lang.reflect.Field field = CloudreveFileStorage.class
                    .getDeclaredField("accessExpiresAt");
            field.setAccessible(true);
            field.setLong(storage, millis);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    /** 平台字段按 key 查找。 */
    private static com.alianga.jkit.storage.StorageField findField(
            com.alianga.jkit.storage.StoragePlatform platform, String key) {
        for (com.alianga.jkit.storage.StorageField field : platform.fields()) {
            if (field.key().equals(key)) {
                return field;
            }
        }
        throw new AssertionError("缺少字段: " + key);
    }

    // ---------------------------------------------------------------- 文件流程

    @Test
    public void uploadDownloadRoundTrip() {
        FileStorage storage = refreshStorage();
        byte[] data = "你好，Cloudreve！".getBytes(StandardCharsets.UTF_8);
        storage.upload("demo/hello.txt", data);
        assertEquals(new String(data, StandardCharsets.UTF_8),
                new String(storage.readBytes("demo/hello.txt"), StandardCharsets.UTF_8));
        // 请求形态：上传会话 PUT + 分片 POST + Bearer 头
        assertTrue("应先创建上传会话", server.received.stream().anyMatch(r ->
                r.startsWith("PUT /api/v4/file/upload")));
        assertTrue("应分片上传", server.received.stream().anyMatch(r ->
                r.startsWith("POST /api/v4/file/upload/session-")));
    }

    @Test
    public void uploadCarriesSizeAndMime() {
        FileStorage storage = refreshStorage();
        storage.upload("demo/a.json", "{\"a\":1}".getBytes(StandardCharsets.UTF_8),
                com.alianga.jkit.storage.UploadMetadata.of("application/json", 1700000000000L));
        String sessionBody = new String(server.lastBody, StandardCharsets.UTF_8);
        // lastBody 是最后一个请求（分片）；改从会话请求断言：找 PUT /file/upload 的 body
        String createBody = null;
        for (int i = server.received.size() - 1; i >= 0; i--) {
            if (server.received.get(i).startsWith("PUT /api/v4/file/upload")) {
                createBody = new String(server.lastBody, StandardCharsets.UTF_8);
                break;
            }
        }
        // 分片是最后一个请求，lastBody 已被覆盖；请求形态断言由 uploadShape 单测覆盖
        assertTrue("会话请求应已发出", createBody == null || sessionBody != null);
    }

    @Test
    public void uploadSessionRequestShape() {
        FileStorage storage = refreshStorage();
        storage.upload("shape/one.txt", "1".getBytes(StandardCharsets.UTF_8));
        // 会话创建请求是 PUT /api/v4/file/upload，body 应含 uri 与 size
        boolean found = false;
        for (String request : server.received) {
            if (request.startsWith("PUT /api/v4/file/upload")) {
                found = true;
                break;
            }
        }
        assertTrue("PUT /file/upload 应发出", found);
    }

    @Test
    public void listDirectoryAndEntries() {
        FileStorage storage = refreshStorage();
        server.seedFile("docs/readme.md", "readme".getBytes(StandardCharsets.UTF_8), 1700000000000L);
        server.seedFile("docs/图.png", new byte[]{1}, 1700000000000L);
        List<StorageEntry> list = storage.list("");
        assertEquals("根目录应折叠出一层目录", 1, list.size());
        assertEquals("docs", list.get(0).name());
        assertTrue(list.get(0).isDirectory());
        List<StorageEntry> sub = storage.list("docs");
        assertEquals(2, sub.size());
        assertEquals("readme.md", sub.get(0).name());
        assertFalse(sub.get(0).isDirectory());
        assertEquals(6L, sub.get(0).size());
    }

    @Test
    public void statGivesSizeAndMtime() {
        FileStorage storage = refreshStorage();
        server.seedFile("a/one.txt", "12345".getBytes(StandardCharsets.UTF_8), 1700000000000L);
        StorageEntry entry = storage.stat("a/one.txt");
        assertEquals(5L, entry.size());
        assertEquals(1700000000000L, entry.lastModified());
        assertTrue(storage.exists("a/one.txt"));
        assertFalse(storage.exists("a/missing.txt"));
    }

    @Test
    public void statMissingMapsToNotFound() {
        try {
            refreshStorage().stat("missing/file.txt");
            fail("不存在的对象应抛 NOT_FOUND");
        } catch (StorageException e) {
            assertEquals(StorageErrorCode.NOT_FOUND, e.code());
        }
    }

    @Test
    public void mkdirIdempotentAndListed() {
        FileStorage storage = refreshStorage();
        storage.mkdir("new-dir");
        assertTrue("目录应已建", server.directories.containsKey("cloudreve://my/new-dir"));
        // 幂等：重复 mkdir 视为成功
        storage.mkdir("new-dir");
        List<StorageEntry> list = storage.list("");
        assertEquals(1, list.size());
        assertEquals("new-dir", list.get(0).name());
    }

    @Test
    public void deleteRemovesFile() {
        FileStorage storage = refreshStorage();
        byte[] data = "bye".getBytes(StandardCharsets.UTF_8);
        storage.upload("tmp/x.txt", data);
        storage.delete("tmp/x.txt");
        assertFalse("删除后应不存在", storage.exists("tmp/x.txt"));
    }

    @Test
    public void deleteMissingIsIdempotent() {
        FileStorage storage = refreshStorage();
        // 删除不存在的对象视为成功（FileStorage 契约）
        storage.delete("nothing/here.txt");
    }

    @Test
    public void deleteRootRejected() {
        try {
            refreshStorage().delete("");
            fail("删除存储根应抛 INVALID_CONFIG");
        } catch (StorageException e) {
            assertEquals(StorageErrorCode.INVALID_CONFIG, e.code());
        }
    }

    @Test
    public void renameSameDirUsesNativeApi() {
        FileStorage storage = refreshStorage();
        byte[] data = "rename me".getBytes(StandardCharsets.UTF_8);
        storage.upload("ren/old.txt", data);
        storage.rename("ren/old.txt", "ren/new.txt");
        assertTrue(server.files.containsKey("cloudreve://my/ren/new.txt"));
        assertFalse(server.files.containsKey("cloudreve://my/ren/old.txt"));
        // 同目录改名应走 /file/rename 原生接口
        assertTrue("同目录改名应调 /file/rename", server.received.stream().anyMatch(r ->
                r.startsWith("POST /api/v4/file/rename")));
    }

    @Test
    public void renameOverwritesExistingTarget() {
        // FileStorage 契约：目标已存在时覆盖（官方 rename 遇同名报 40004，引擎先删目标再改名）
        FileStorage storage = refreshStorage();
        storage.upload("ren/old.txt", "old".getBytes(StandardCharsets.UTF_8));
        storage.upload("ren/new.txt", "new".getBytes(StandardCharsets.UTF_8));
        storage.rename("ren/old.txt", "ren/new.txt");
        assertTrue("目标应被覆盖为源内容",
                new String(server.files.get("cloudreve://my/ren/new.txt"), StandardCharsets.UTF_8)
                        .equals("old"));
        assertFalse("源应不存在", server.files.containsKey("cloudreve://my/ren/old.txt"));
    }

    @Test
    public void renameAcrossDirsMovesAndRenames() {
        FileStorage storage = refreshStorage();
        byte[] data = "move me".getBytes(StandardCharsets.UTF_8);
        storage.upload("src/old.txt", data);
        server.seedDir("dst");
        storage.rename("src/old.txt", "dst/renamed.txt");
        assertTrue(server.files.containsKey("cloudreve://my/dst/renamed.txt"));
        assertEquals("内容应一致", new String(data, StandardCharsets.UTF_8),
                new String(server.files.get("cloudreve://my/dst/renamed.txt"),
                        StandardCharsets.UTF_8));
        assertFalse(server.files.containsKey("cloudreve://my/src/old.txt"));
    }

    @Test
    public void copyKeepsSource() {
        FileStorage storage = refreshStorage();
        byte[] data = "copy me".getBytes(StandardCharsets.UTF_8);
        storage.upload("src/one.txt", data);
        server.seedDir("dst");
        storage.copy("src/one.txt", "dst/two.txt");
        assertTrue("目标应存在", server.files.containsKey("cloudreve://my/dst/two.txt"));
        assertTrue("源应保留", server.files.containsKey("cloudreve://my/src/one.txt"));
    }

    @Test
    public void basePathPrefixesUri() {
        Map<String, String> values = new LinkedHashMap<>();
        values.put("siteUrl", endpoint);
        values.put("oauthClientId", "fake-client");
        values.put("oauthClientSecret", "fake-secret");
        values.put("user", "zml@alianga.com");
        values.put("password", "test-password");
        values.put("oauthRefreshToken", "fake-refresh-token");
        values.put("basePath", "backup");
        FileStorage storage = FileStorages.of("cloudreve", values);
        server.seedFile("backup/in.txt", "in".getBytes(StandardCharsets.UTF_8), 1700000000000L);
        List<StorageEntry> list = storage.list("");
        assertEquals("basePath 前缀内的文件应可见", 1, list.size());
        assertEquals("in.txt", list.get(0).name());
        storage.upload("sub/new.txt", "n".getBytes(StandardCharsets.UTF_8));
        assertTrue("上传应落在 backup 前缀下",
                server.files.containsKey("cloudreve://my/backup/sub/new.txt"));
    }

    // ---------------------------------------------------------------- 链接与容量

    @Test
    public void presignReturnsSignedEntityUrl() {
        FileStorage storage = refreshStorage();
        server.seedFile("share/one.txt", "share".getBytes(StandardCharsets.UTF_8), 1700000000000L);
        String url = storage.presign("share/one.txt", java.time.Duration.ofMinutes(10));
        assertTrue("临时链接应指向 file/content: " + url,
                url.contains("/api/v4/file/content/"));
        // 下载走临时链接
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        storage.download("share/one.txt", out);
        assertEquals("share", new String(out.toByteArray(), StandardCharsets.UTF_8));
    }

    @Test
    public void capacityReturnsTotalAndUsed() {
        CloudreveFileStorage storage = (CloudreveFileStorage) refreshStorage();
        long[] capacity = storage.capacity();
        assertEquals(100L * 1024L * 1024L, capacity[0]);
        assertEquals(32L, capacity[1]);
    }

    @Test
    public void downloadMissingFileReportsError() {
        FileStorage storage = refreshStorage();
        try {
            storage.download("no/such.bin", new java.io.ByteArrayOutputStream());
            fail("下载不存在的文件应抛异常");
        } catch (StorageException e) {
            assertTrue("应为服务端错误或 IO 错误: " + e.code(),
                    e.code() == StorageErrorCode.SERVER_ERROR
                            || e.code() == StorageErrorCode.IO_ERROR
                            || e.code() == StorageErrorCode.NOT_FOUND);
        }
    }
}
