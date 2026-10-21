package com.alianga.jkit.storage.real;

import com.alianga.jkit.storage.engine.DogeFileStorage;
import com.alianga.jkit.storage.FileStorage;
import com.alianga.jkit.storage.FileStorages;
import com.alianga.jkit.storage.StorageConfig;
import com.alianga.jkit.storage.StorageEntry;
import com.alianga.jkit.storage.StorageErrorCode;
import com.alianga.jkit.storage.StorageException;

import org.junit.AfterClass;
import org.junit.Assume;
import org.junit.BeforeClass;
import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * 多吉云真实桶集成测试：付费桶（标准型）与基础桶（基础型）双通道。
 *
 * <p><b>付费桶</b>（fee-standard 等，官方 S3 兼容 API）：验证 TOKEN 签名、临时凭据、
 * SigV4 + 会话令牌、列表语义、目录占位与错误码。</p>
 *
 * <p><b>基础桶</b>（meitu / free / zml-documents，控制台会话适配）：验证控制台登录、
 * CSRF 会话、file/list / file/info / upload/put / file/delete / file/sign、
 * 直链与 URL 鉴权检测、临时链接可下载。官方无公开 API，本套用例是适配正确性的
 * 关键回归保障。</p>
 *
 * <p>凭据来源（系统属性优先，其次环境变量），不满足时整套用例自动跳过（Assume）：</p>
 * <pre>
 * 付费桶：jkit.test.doge.accessKey / secretKey / bucket（或 DOGE_ACCESS_KEY / DOGE_SECRET_KEY / DOGE_BUCKET）
 * 基础桶：jkit.test.doge.user / password / basicBucket（或 DOGE_USER / DOGE_PASSWORD / DOGE_BASIC_BUCKET）
 * </pre>
 *
 * @author 郑明亮
 */
public class DogeRealStorageTest {
    private static final String ROOT = "jkit-real-test";

    private static String accessKey;
    private static String secretKey;
    private static String bucket;
    private static String user;
    private static String password;
    private static String basicBucket;
    private static FileStorage paidStorage;
    private static DogeFileStorage basicStorage;

    @BeforeClass
    public static void resolveCredentials() {
        accessKey = prop("jkit.test.doge.accessKey", "DOGE_ACCESS_KEY");
        secretKey = prop("jkit.test.doge.secretKey", "DOGE_SECRET_KEY");
        bucket = prop("jkit.test.doge.bucket", "DOGE_BUCKET");
        user = prop("jkit.test.doge.user", "DOGE_USER");
        password = prop("jkit.test.doge.password", "DOGE_PASSWORD");
        basicBucket = prop("jkit.test.doge.basicBucket", "DOGE_BASIC_BUCKET");
        if (!accessKey.isEmpty() && !secretKey.isEmpty() && !bucket.isEmpty()) {
            paidStorage = FileStorages.of("doge", StorageConfig.empty()
                    .set("bucketType", "paid")
                    .set("accessKey", accessKey)
                    .set("secretKey", secretKey)
                    .set("bucket", bucket));
        }
        if (!user.isEmpty() && !password.isEmpty() && !basicBucket.isEmpty()) {
            basicStorage = (DogeFileStorage) FileStorages.of("doge", StorageConfig.empty()
                    .set("bucketType", "free")
                    .set("bucket", basicBucket)
                    .set("user", user)
                    .set("password", password));
        }
        Assume.assumeTrue("未提供多吉云凭据（付费桶或基础桶），跳过真实桶测试",
                paidStorage != null || basicStorage != null);
    }

    private static String prop(String key, String env) {
        String value = System.getProperty(key, System.getenv().getOrDefault(env, ""));
        return value == null ? "" : value.trim();
    }

    @AfterClass
    public static void cleanup() {
        cleanupPaid();
        cleanupBasic();
    }

    private static void cleanupPaid() {
        if (paidStorage == null) {
            return;
        }
        try {
            for (StorageEntry entry : paidStorage.list(ROOT)) {
                if (entry.isDirectory()) {
                    paidStorage.deleteDir(entry.path());
                } else {
                    paidStorage.delete(entry.path());
                }
            }
            paidStorage.delete(ROOT);
        } catch (RuntimeException ignore) {
            // 清理失败只留垃圾不影响重跑
        }
    }

    private static void cleanupBasic() {
        if (basicStorage == null) {
            return;
        }
        try {
            for (StorageEntry entry : basicStorage.list(ROOT)) {
                basicStorage.delete(entry.path());
            }
            basicStorage.delete(ROOT);
        } catch (RuntimeException ignore) {
            // ignore
        }
    }

    private static String unique(String name) {
        return ROOT + "/" + name + "-" + UUID.randomUUID().toString().substring(0, 8);
    }

    // ================================================================ 付费桶（官方 API）

    @Test
    public void paidUploadDownloadRoundTrip() {
        Assume.assumeTrue(paidStorage != null);
        String path = unique("roundtrip") + ".txt";
        byte[] data = "你好，多吉云付费桶！Dogé ünïcode".getBytes(StandardCharsets.UTF_8);
        paidStorage.upload(path, new ByteArrayInputStream(data), data.length);
        assertArrayEquals(data, paidStorage.readBytes(path));
        paidStorage.delete(path);
    }

    @Test
    public void paidStatListAndDirectoryPlaceholder() {
        Assume.assumeTrue(paidStorage != null);
        String dir = unique("dir");
        paidStorage.mkdir(dir);
        String file = dir + "/file.txt";
        paidStorage.upload(file, "content".getBytes(StandardCharsets.UTF_8));
        assertEquals(7L, paidStorage.stat(file).size());
        assertTrue(paidStorage.stat(dir).isDirectory());
        assertTrue(names(paidStorage.list(ROOT)).stream().anyMatch(n -> n.startsWith("dir-")));
        assertEquals(1, paidStorage.list(dir).size());
    }

    @Test
    public void paidCopyRenameDeleteDirAndPresign() throws IOException {
        Assume.assumeTrue(paidStorage != null);
        String src = unique("copy-src") + ".txt";
        String dst = unique("copy-dst") + ".txt";
        paidStorage.upload(src, "copy me".getBytes(StandardCharsets.UTF_8));
        paidStorage.copy(src, dst);
        assertEquals("copy me", new String(paidStorage.readBytes(dst), StandardCharsets.UTF_8));
        String renamed = unique("renamed") + ".txt";
        paidStorage.rename(src, renamed);
        try {
            paidStorage.stat(src);
            org.junit.Assert.fail("rename 后源对象应不存在");
        } catch (StorageException e) {
            assertEquals(StorageErrorCode.NOT_FOUND, e.code());
        }
        // 预签名（STS 临时凭据带 X-Amz-Security-Token）
        String url = paidStorage.presign(dst, java.time.Duration.ofMinutes(10));
        java.net.HttpURLConnection connection =
                (java.net.HttpURLConnection) new java.net.URL(url).openConnection();
        connection.setConnectTimeout(15_000);
        connection.setReadTimeout(30_000);
        assertEquals(200, connection.getResponseCode());
        connection.disconnect();
        // 递归删除
        String dir = unique("deldir");
        paidStorage.mkdir(dir + "/sub");
        paidStorage.upload(dir + "/sub/b.txt", "b".getBytes(StandardCharsets.UTF_8));
        paidStorage.deleteDir(dir);
        assertFalse(paidStorage.exists(dir));
        paidStorage.delete(dst);
        paidStorage.delete(renamed);
    }

    // ================================================================ 基础桶（控制台会话适配）

    @Test
    public void basicLoginListStatAndRoundTrip() {
        Assume.assumeTrue(basicStorage != null);
        String path = unique("basic-round") + ".txt";
        byte[] data = "你好，多吉云基础桶！jkit 控制台会话适配验证".getBytes(StandardCharsets.UTF_8);
        basicStorage.upload(path, new ByteArrayInputStream(data), data.length);
        assertArrayEquals(data, basicStorage.readBytes(path));
        assertEquals(data.length, basicStorage.stat(path).size());
        List<StorageEntry> siblings = basicStorage.list(ROOT);
        assertTrue(names(siblings).stream().anyMatch(n -> n.startsWith("basic-round-")));
        basicStorage.delete(path);
        try {
            basicStorage.stat(path);
            org.junit.Assert.fail("删除后应 NOT_FOUND");
        } catch (StorageException e) {
            assertEquals(StorageErrorCode.NOT_FOUND, e.code());
        }
    }

    @Test
    public void basicDirectoryMkdirAndList() {
        Assume.assumeTrue(basicStorage != null);
        String dir = unique("basic-dir");
        basicStorage.mkdir(dir);
        String file = dir + "/inner.txt";
        basicStorage.upload(file, "inner".getBytes(StandardCharsets.UTF_8));
        List<StorageEntry> rootList = basicStorage.list(ROOT);
        assertTrue(names(rootList).stream().anyMatch(n -> n.startsWith("basic-dir")));
        assertEquals(1, basicStorage.list(dir).size());
        basicStorage.deleteDir(dir);
        assertFalse(basicStorage.exists(dir));
    }

    @Test
    public void basicTemporaryLinkDownloadable() throws IOException {
        Assume.assumeTrue(basicStorage != null);
        String path = unique("basic-sign") + ".txt";
        basicStorage.upload(path, "temporary-link-probe".getBytes(StandardCharsets.UTF_8));
        String link = basicStorage.temporaryLink(path);
        assertTrue("临时链接应为 http(s): " + link, link.startsWith("http"));
        java.net.HttpURLConnection connection =
                (java.net.HttpURLConnection) new java.net.URL(link).openConnection();
        connection.setConnectTimeout(15_000);
        connection.setReadTimeout(30_000);
        assertEquals("临时链接应可直接下载", 200, connection.getResponseCode());
        byte[] body = readAll(connection.getInputStream());
        assertEquals("temporary-link-probe", new String(body, StandardCharsets.UTF_8));
        connection.disconnect();
        basicStorage.delete(path);
    }

    @Test
    public void basicDirectLinkFollowsUrlAuthPolicy() throws IOException {
        Assume.assumeTrue(basicStorage != null);
        String path = unique("basic-direct") + ".txt";
        basicStorage.upload(path, "direct-link-probe".getBytes(StandardCharsets.UTF_8));
        try {
            String direct = basicStorage.directLinkOf(path);
            boolean authEnabled = basicStorage.urlAuthEnabled();
            java.net.HttpURLConnection connection =
                    (java.net.HttpURLConnection) new java.net.URL(direct).openConnection();
            connection.setConnectTimeout(15_000);
            connection.setReadTimeout(30_000);
            connection.setInstanceFollowRedirects(false);
            int status = connection.getResponseCode();
            connection.disconnect();
            if (!authEnabled) {
                assertEquals("未开 URL 鉴权时直链应 200", 200, status);
            } else {
                assertTrue("开启 URL 鉴权时直链应被拒（403/302）",
                        status == 403 || status == 302 || status == 401);
            }
        } catch (StorageException e) {
            // 未绑定任何域名：UNSUPPORTED 合法（free 桶当前即如此）
            assertEquals(StorageErrorCode.UNSUPPORTED, e.code());
        } finally {
            basicStorage.delete(path);
        }
    }

    @Test
    public void basicUnsupportedOps() {
        Assume.assumeTrue(basicStorage != null);
        try {
            basicStorage.copy("a.txt", "b.txt");
            org.junit.Assert.fail("基础桶 copy 应 UNSUPPORTED");
        } catch (StorageException e) {
            assertEquals(StorageErrorCode.UNSUPPORTED, e.code());
        }
        try {
            basicStorage.rename("a.txt", "b.txt");
            org.junit.Assert.fail("基础桶 rename 应 UNSUPPORTED");
        } catch (StorageException e) {
            assertEquals(StorageErrorCode.UNSUPPORTED, e.code());
        }
    }

    // ================================================================ 工具

    private static List<String> names(List<StorageEntry> entries) {
        List<String> out = new ArrayList<String>();
        for (StorageEntry entry : entries) {
            out.add(entry.name());
        }
        return out;
    }

    private static byte[] readAll(java.io.InputStream in) throws IOException {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        byte[] chunk = new byte[4096];
        int read;
        while ((read = in.read(chunk)) >= 0) {
            out.write(chunk, 0, read);
        }
        in.close();
        return out.toByteArray();
    }
}
