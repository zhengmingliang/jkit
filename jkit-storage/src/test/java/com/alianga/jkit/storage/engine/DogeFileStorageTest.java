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
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * 多吉云引擎集成测试：内嵌假管理 API（tmp_token 签名校验）+ 假 S3 服务器（虚拟主机寻址），
 * 验证 TOKEN 签名请求形态、凭据编排（桶名 → s3Bucket / s3Endpoint 映射、
 * basePath / listVersion 透传）、临时凭据过期刷新与错误映射。
 *
 * @author 郑明亮
 */
public class DogeFileStorageTest {
    private FakeDogeApiServer api;
    private FakeS3Server s3;
    private String apiEndpoint;
    private String s3Endpoint;

    @Before
    public void startServers() throws Exception {
        System.clearProperty("fake.s3.deny");
        api = new FakeDogeApiServer();
        apiEndpoint = api.start();
        s3 = new FakeS3Server();
        // 假 S3 只按路径匹配桶名：桶名作为 pathStyle 的桶段，走 pathStyle=false 也可以 ——
        // 这里用 pathStyle=true 的假服务器（按 /bucket/key 路由），doge 引擎用虚拟主机寻址，
        // 但测试 JVM 里桶域名无法解析，所以引擎的 endpoint 直接指到假服务器并配 pathStyle 由
        // S3FileStorage 层处理。为让虚拟主机寻址落到同一台假服务器，fake 覆盖桶名为前缀匹配。
        s3Endpoint = s3.start("s-bj-1-testbucket-1258813047");
    }

    @After
    public void stopServers() {
        api.stop();
        s3.stop();
        System.clearProperty("fake.s3.deny");
    }

    private void seedBuckets() {
        api.credentials = new LinkedHashMap<String, Object>();
        api.credentials.put("accessKeyId", "AKIDfake");
        api.credentials.put("secretAccessKey", "fakeSecret=");
        api.credentials.put("sessionToken", "fake-token");
        List<Map<String, Object>> buckets = new ArrayList<Map<String, Object>>();
        Map<String, Object> bucket = new LinkedHashMap<String, Object>();
        bucket.put("name", "fee-standard");
        bucket.put("s3Bucket", "s-bj-1-testbucket-1258813047");
        bucket.put("s3Endpoint", s3Endpoint);
        buckets.add(bucket);
        api.buckets = buckets;
    }

    private FileStorage storage(Map<String, String> overrides) {
        Map<String, String> values = new LinkedHashMap<String, String>();
        values.put("accessKey", "fake-ak");
        values.put("secretKey", "fake-sk");
        values.put("bucket", "fee-standard");
        values.put("apiEndpoint", apiEndpoint);
        // 假 S3 按 /bucket/key 路由（测试 JVM 解析不了桶域名），切路径风格寻址
        values.put("pathStyle", "true");
        if (overrides != null) {
            values.putAll(overrides);
        }
        return FileStorages.of("doge", values);
    }

    @Test
    public void presetRegisteredWithFields() {
        com.alianga.jkit.storage.StoragePlatform platform = FileStorages.platform("doge");
        assertNotNull("doge 平台应已内置注册", platform);
        assertEquals("doge", platform.engine());
        assertEquals("多吉云", platform.displayName());
    }

    @Test
    public void tokenAuthShapeAndUploadRoundTrip() {
        seedBuckets();
        FileStorage doge = storage(null);
        byte[] data = "你好，多吉云！hello".getBytes(StandardCharsets.UTF_8);
        doge.upload("demo/hello.txt", data);
        assertTrue("对象应落到假 S3（含 basePath 前缀）", s3.objects.containsKey("demo/hello.txt"));
        assertEquals(new String(data, StandardCharsets.UTF_8),
                new String(doge.readBytes("demo/hello.txt"), StandardCharsets.UTF_8));
        // 管理 API 请求必须是 TOKEN 前缀
        assertTrue("Authorization 应为 TOKEN 前缀: " + api.lastAuthorization,
                api.lastAuthorization.startsWith("TOKEN fake-ak:"));
        // 请求体应包含 channel=OSS_FULL
        String body = new String(api.lastBody, StandardCharsets.UTF_8);
        assertTrue("请求体应带 channel=OSS_FULL: " + body, body.contains("OSS_FULL"));
        assertTrue("请求体应带 scopes: " + body, body.contains("scopes"));
    }

    @Test
    public void listDirectoryAndEntries() {
        seedBuckets();
        FileStorage doge = storage(null);
        doge.upload("a/one.txt", "1".getBytes(StandardCharsets.UTF_8));
        doge.upload("b/中文.txt", "22".getBytes(StandardCharsets.UTF_8));
        List<StorageEntry> list = doge.list("");
        assertEquals(2, list.size());
        assertTrue(names(list).contains("a"));
        assertTrue(names(list).contains("b"));
        List<StorageEntry> sub = doge.list("a");
        assertEquals(1, sub.size());
        assertEquals("one.txt", sub.get(0).name());
    }

    @Test
    public void unknownBucketGivesInvalidConfig() {
        seedBuckets();
        FileStorage doge = storage(null);
        // 桶名不存在：第一次操作即抛 INVALID_CONFIG
        try {
            api.buckets = new ArrayList<Map<String, Object>>();
            doge.list("");
            fail("未知桶应抛异常");
        } catch (StorageException e) {
            assertEquals(StorageErrorCode.INVALID_CONFIG, e.code());
        }
    }

    @Test
    public void apiDeniedMapsToAuthFailed() {
        seedBuckets();
        api.deny = true;
        FileStorage doge = storage(null);
        try {
            doge.list("");
            fail("管理 API 鉴权失败应抛异常");
        } catch (StorageException e) {
            assertEquals(StorageErrorCode.AUTH_FAILED, e.code());
        }
    }

    @Test
    public void expiredTokenTriggersRefresh() {
        seedBuckets();
        // 颁发一个已过期的凭据：首次操作即刷新（重新请求）
        api.expiredAt = System.currentTimeMillis() - 1000L;
        FileStorage doge = storage(null);
        doge.upload("demo/expired.txt", "x".getBytes(StandardCharsets.UTF_8));
        // 至少请求过一次 tmp_token
        assertTrue("过期凭据应触发刷新", api.requests.get() >= 1);
        assertEquals("x", new String(doge.readBytes("demo/expired.txt"), StandardCharsets.UTF_8));
    }

    @Test
    public void basePathAndListVersionPassThrough() {
        seedBuckets();
        Map<String, String> overrides = new LinkedHashMap<String, String>();
        overrides.put("basePath", "prefix");
        overrides.put("listVersion", "2");
        FileStorage doge = storage(overrides);
        doge.upload("hello.txt", "x".getBytes(StandardCharsets.UTF_8));
        assertEquals("basePath 应拼到对象 key 前", "prefix/hello.txt",
                s3.objects.keySet().iterator().next());
        // V2 列表请求形态（list-type=2）
        doge.list("");
        assertEquals("2", s3.lastListQuery.get().get("list-type"));
    }

    @Test
    public void missingRequiredConfigRejected() {
        try {
            FileStorages.of("doge", java.util.Collections.<String, String>emptyMap());
            fail("缺少必填配置应抛异常");
        } catch (StorageException e) {
            assertEquals(StorageErrorCode.INVALID_CONFIG, e.code());
        }
    }

    private static List<String> names(List<StorageEntry> entries) {
        List<String> out = new ArrayList<String>();
        for (StorageEntry entry : entries) {
            out.add(entry.name());
        }
        return out;
    }
}
