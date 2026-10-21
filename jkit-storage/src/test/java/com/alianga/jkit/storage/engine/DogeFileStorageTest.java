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
 * 多吉云引擎单元测试：付费桶（假管理 API + 假 S3）与基础桶（假控制台会话 API）双通道编排。
 *
 * @author 郑明亮
 */
public class DogeFileStorageTest {
    private FakeDogeApiServer api;
    private FakeS3Server s3;
    private FakeDogeConsole console;
    private String apiEndpoint;
    private String s3Endpoint;

    @Before
    public void startServers() throws Exception {
        System.clearProperty("fake.s3.deny");
        api = new FakeDogeApiServer();
        apiEndpoint = api.start();
        s3 = new FakeS3Server();
        s3Endpoint = s3.start("s-bj-1-testbucket-1258813047");
        console = new FakeDogeConsole();
        console.start();
    }

    @After
    public void stopServers() {
        api.stop();
        s3.stop();
        console.stop();
        System.clearProperty("fake.s3.deny");
    }

    private void seedPaidBucket() {
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

    private FileStorage paidStorage() {
        seedPaidBucket();
        Map<String, String> values = new LinkedHashMap<String, String>();
        values.put("bucketType", "paid");
        values.put("accessKey", "fake-ak");
        values.put("secretKey", "fake-sk");
        values.put("bucket", "fee-standard");
        // 测试 JVM 解析不了桶域名：显式覆盖 apiBase 用 pathStyle 假 S3
        values.put("apiEndpoint", apiEndpoint);
        values.put("pathStyle", "true");
        return FileStorages.of("doge", values);
    }

    private FileStorage basicStorage() {
        Map<String, String> values = new LinkedHashMap<String, String>();
        values.put("bucketType", "free");
        values.put("bucket", "free");
        values.put("user", "18519094522");
        values.put("password", "test-password");
        values.put("domain", "cdn.example.com");
        values.put("apiEndpoint", console.endpoint());
        return FileStorages.of("doge", values);
    }

    // ---------------------------------------------------------------- 付费桶

    @Test
    public void presetRegisteredWithFields() {
        com.alianga.jkit.storage.StoragePlatform platform = FileStorages.platform("doge");
        assertNotNull("doge 平台应已内置注册", platform);
        assertEquals("doge", platform.engine());
        assertEquals("多吉云", platform.displayName());
    }

    @Test
    public void paidTokenAuthShapeAndUploadRoundTrip() {
        FileStorage doge = paidStorage();
        byte[] data = "你好，多吉云付费桶！".getBytes(StandardCharsets.UTF_8);
        doge.upload("demo/hello.txt", data);
        assertTrue("对象应落到假 S3", s3.objects.containsKey("demo/hello.txt"));
        assertEquals(new String(data, StandardCharsets.UTF_8),
                new String(doge.readBytes("demo/hello.txt"), StandardCharsets.UTF_8));
        assertTrue("Authorization 应为 TOKEN 前缀: " + api.lastAuthorization,
                api.lastAuthorization.startsWith("TOKEN fake-ak:"));
        String body = new String(api.lastBody, StandardCharsets.UTF_8);
        assertTrue("请求体应带 channel=OSS_FULL: " + body, body.contains("OSS_FULL"));
    }

    @Test
    public void paidListDirectoryAndEntries() {
        FileStorage doge = paidStorage();
        doge.upload("a/one.txt", "1".getBytes(StandardCharsets.UTF_8));
        doge.upload("b/中文.txt", "22".getBytes(StandardCharsets.UTF_8));
        List<StorageEntry> list = doge.list("");
        assertEquals(2, list.size());
        List<StorageEntry> sub = doge.list("a");
        assertEquals(1, sub.size());
        assertEquals("one.txt", sub.get(0).name());
    }

    @Test
    public void paidUnknownBucketGivesInvalidConfig() {
        try {
            api.credentials = new LinkedHashMap<String, Object>();
            api.credentials.put("accessKeyId", "AKIDfake");
            api.credentials.put("secretAccessKey", "fakeSecret=");
            api.credentials.put("sessionToken", "fake-token");
            api.buckets = new ArrayList<Map<String, Object>>();
            FileStorages.of("doge", paidValues()).list("");
            fail("未知桶应抛异常");
        } catch (StorageException e) {
            assertEquals(StorageErrorCode.INVALID_CONFIG, e.code());
        }
    }

    /** 付费桶配置（不预填桶清单）。 */
    private Map<String, String> paidValues() {
        Map<String, String> values = new LinkedHashMap<String, String>();
        values.put("bucketType", "paid");
        values.put("accessKey", "fake-ak");
        values.put("secretKey", "fake-sk");
        values.put("bucket", "fee-standard");
        values.put("apiEndpoint", apiEndpoint);
        values.put("pathStyle", "true");
        return values;
    }

    @Test
    public void paidApiDeniedMapsToAuthFailed() {
        seedPaidBucket();
        api.deny = true;
        try {
            paidStorage().list("");
            fail("管理 API 鉴权失败应抛异常");
        } catch (StorageException e) {
            assertEquals(StorageErrorCode.AUTH_FAILED, e.code());
        }
    }

    @Test
    public void paidMissingKeyRejected() {
        try {
            Map<String, String> values = new LinkedHashMap<String, String>();
            values.put("bucketType", "paid");
            values.put("bucket", "fee-standard");
            FileStorages.of("doge", values);
            fail("付费桶缺密钥应抛异常");
        } catch (StorageException e) {
            assertEquals(StorageErrorCode.INVALID_CONFIG, e.code());
        }
    }

    // ---------------------------------------------------------------- 基础桶

    @Test
    public void basicLoginAndList() {
        FileStorage doge = basicStorage();
        console.seedFile("free", "img/", 0L, "folder");
        console.seedFile("free", "img/a.png", 1024L, "file");
        List<StorageEntry> list = doge.list("");
        assertEquals("根目录应折叠出一层目录", 1, list.size());
        assertEquals("img", list.get(0).name());
        assertTrue(list.get(0).isDirectory());
        List<StorageEntry> sub = doge.list("img");
        assertEquals(1, sub.size());
        assertEquals("a.png", sub.get(0).name());
        // list 延迟元数据（SIZE_UNKNOWN）；stat 才有精确大小（file/info）
        assertEquals(StorageEntry.SIZE_UNKNOWN, sub.get(0).size());
        assertEquals(1024L, doge.stat("img/a.png").size());
        assertTrue("登录请求应发出", console.loginRequests() >= 1);
    }

    @Test
    public void basicUploadDownloadDelete() {
        FileStorage doge = basicStorage();
        byte[] data = "你好基础桶".getBytes(StandardCharsets.UTF_8);
        doge.upload("jkit/round.txt", data);
        assertEquals(new String(data, StandardCharsets.UTF_8),
                new String(doge.readBytes("jkit/round.txt"), StandardCharsets.UTF_8));
        doge.delete("jkit/round.txt");
        assertEquals("删除后应不存在", 0, doge.list("jkit").size());
    }

    @Test
    public void basicCopyRenameUnsupported() {
        FileStorage doge = basicStorage();
        try {
            doge.copy("a.txt", "b.txt");
            fail("基础桶 copy 应抛 UNSUPPORTED");
        } catch (StorageException e) {
            assertEquals(StorageErrorCode.UNSUPPORTED, e.code());
        }
        try {
            doge.rename("a.txt", "b.txt");
            fail("基础桶 rename 应抛 UNSUPPORTED");
        } catch (StorageException e) {
            assertEquals(StorageErrorCode.UNSUPPORTED, e.code());
        }
    }

    @Test
    public void basicDirectLinkAndUrlAuth() {
        FileStorage doge = basicStorage();
        // Fake 控制台默认域名未开 URL 鉴权：直链可用
        String direct = ((DogeFileStorage) doge).directLinkOf("img/a.png");
        assertEquals("https://cdn.example.com/img/a.png", direct);
        org.junit.Assert.assertFalse("默认未开启 URL 鉴权", ((DogeFileStorage) doge).urlAuthEnabled());
        // 开启 URL 鉴权后检测应返回 true
        console.urlAuthSwitch = 1;
        ((DogeFileStorage) doge).clearUrlAuthCache();
        org.junit.Assert.assertTrue("开启后应检测到 URL 鉴权", ((DogeFileStorage) doge).urlAuthEnabled());
        console.urlAuthSwitch = 0;
    }

    @Test
    public void basicTemporaryLinkFromSign() {
        FileStorage doge = basicStorage();
        console.seedFile("free", "img/a.png", 1024L, "file");
        String link = doge.presign("img/a.png", java.time.Duration.ofMinutes(10));
        assertTrue("临时链接应来自 file/sign: " + link, link.contains("/signed/free/img/a.png"));
    }

    @Test
    public void basicWrongPasswordMapsToAuthFailed() {
        console.loginShouldFail = true;
        try {
            basicStorage().list("");
            fail("密码错误应抛 AUTH_FAILED");
        } catch (StorageException e) {
            assertEquals(StorageErrorCode.AUTH_FAILED, e.code());
        }
    }
}
