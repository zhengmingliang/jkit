package com.alianga.jkit.storage.real;

import com.alianga.jkit.storage.FileStorages;
import com.alianga.jkit.storage.FileStorage;
import com.alianga.jkit.storage.StorageEntry;
import com.alianga.jkit.storage.StorageErrorCode;
import com.alianga.jkit.storage.StorageException;
import com.alianga.jkit.storage.StoragePaths;

import org.junit.AfterClass;
import org.junit.Assume;
import org.junit.BeforeClass;
import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * 多吉云真实桶集成测试：在真正的多吉云（管理 API + 腾讯 COS 兼容 S3）上验证
 * {@code DogeFileStorage} 的 TOKEN 签名、临时凭据换取、SigV4 + 会话令牌访问、
 * 列表语义、目录占位与错误码——Fake 服务器按文档模拟，抓不出真实服务端的
 * 严格校验差异（如 accept-encoding 必须参与签名、canonical query 排序编码）。
 *
 * <p>凭据来源（系统属性优先，其次环境变量），都不满足时整套用例自动跳过（Assume），
 * CI 不受影响：</p>
 * <pre>
 * -Djkit.test.doge.accessKey=xxx  （或 DOGE_ACCESS_KEY）
 * -Djkit.test.doge.secretKey=xxx  （或 DOGE_SECRET_KEY）
 * -Djkit.test.doge.bucket=xxx     （或 DOGE_BUCKET，业务桶名或 s3Bucket 全名）
 * </pre>
 *
 * <p>测试目录 {@code jkit-real-test/} 下操作，结束清空；桶不删除（免费/付费桶由
 * 用户自备）。</p>
 *
 * @author 郑明亮
 */
public class DogeRealStorageTest {
    private static final String ROOT = "jkit-real-test";
    private static final long KB = 1024L;

    private static String accessKey;
    private static String secretKey;
    private static String bucket;
    private static FileStorage storage;

    @BeforeClass
    public static void resolveCredentials() {
        accessKey = System.getProperty("jkit.test.doge.accessKey",
                System.getenv().getOrDefault("DOGE_ACCESS_KEY", ""));
        secretKey = System.getProperty("jkit.test.doge.secretKey",
                System.getenv().getOrDefault("DOGE_SECRET_KEY", ""));
        bucket = System.getProperty("jkit.test.doge.bucket",
                System.getenv().getOrDefault("DOGE_BUCKET", ""));
        Assume.assumeTrue("未提供多吉云凭据（jkit.test.doge.* / DOGE_*），跳过真实桶测试",
                !accessKey.isEmpty() && !secretKey.isEmpty() && !bucket.isEmpty());
        storage = FileStorages.of("doge", com.alianga.jkit.storage.StorageConfig.empty()
                .set("accessKey", accessKey)
                .set("secretKey", secretKey)
                .set("bucket", bucket));
    }

    @AfterClass
    public static void cleanup() {
        if (storage == null) {
            return;
        }
        try {
            for (StorageEntry entry : storage.list(ROOT)) {
                if (entry.isDirectory()) {
                    storage.deleteDir(entry.path());
                } else {
                    storage.delete(entry.path());
                }
            }
            storage.delete(ROOT);
        } catch (RuntimeException ignore) {
            // 清理失败只留垃圾不影响重跑
        }
    }

    private static String unique(String name) {
        return ROOT + "/" + name + "-" + UUID.randomUUID().toString().substring(0, 8);
    }

    @Test
    public void uploadDownloadRoundTrip() throws IOException {
        String path = unique("roundtrip") + ".txt";
        byte[] data = "你好，多吉云真实桶！Dogé ünïcode".getBytes(StandardCharsets.UTF_8);
        storage.upload(path, new ByteArrayInputStream(data), data.length);
        byte[] downloaded = storage.readBytes(path);
        assertArrayEquals(data, downloaded);
        storage.delete(path);
    }

    @Test
    public void statListAndDirectoryPlaceholder() {
        String dir = unique("dir");
        storage.mkdir(dir);
        String file = dir + "/file.txt";
        storage.upload(file, "content".getBytes(StandardCharsets.UTF_8));

        StorageEntry stat = storage.stat(file);
        assertEquals(7L, stat.size());
        assertTrue(stat.lastModified() > 0L);

        List<StorageEntry> list = storage.list(ROOT);
        assertTrue(names(list).stream().anyMatch(name -> name.startsWith("dir-")));

        List<StorageEntry> sub = storage.list(dir);
        assertEquals(1, sub.size());
        assertEquals("file.txt", sub.get(0).name());
    }

    @Test
    public void existsAndNotFound() {
        String path = unique("exists") + ".txt";
        assertFalse(storage.exists(path));
        storage.upload(path, "x".getBytes(StandardCharsets.UTF_8));
        assertTrue(storage.exists(path));
        try {
            storage.stat(path + "-missing");
            org.junit.Assert.fail("不存在的对象应抛 NOT_FOUND");
        } catch (StorageException e) {
            assertEquals(StorageErrorCode.NOT_FOUND, e.code());
        }
        storage.delete(path);
    }

    @Test
    public void copyRenameAndDelete() {
        String src = unique("copy-src") + ".txt";
        String dst = unique("copy-dst") + ".txt";
        storage.upload(src, "copy me".getBytes(StandardCharsets.UTF_8));
        storage.copy(src, dst);
        assertEquals("copy me", new String(storage.readBytes(dst), StandardCharsets.UTF_8));
        // 源还在
        assertEquals("copy me", new String(storage.readBytes(src), StandardCharsets.UTF_8));

        String renamed = unique("renamed") + ".txt";
        storage.rename(src, renamed);
        assertEquals("copy me", new String(storage.readBytes(renamed), StandardCharsets.UTF_8));
        try {
            storage.stat(src);
            org.junit.Assert.fail("rename 后源对象应不存在");
        } catch (StorageException e) {
            assertEquals(StorageErrorCode.NOT_FOUND, e.code());
        }
        storage.delete(dst);
        storage.delete(renamed);
    }

    @Test
    public void rangeDownload() throws IOException {
        String path = unique("range") + ".bin";
        byte[] data = new byte[(int) (64 * KB)];
        for (int i = 0; i < data.length; i++) {
            data[i] = (byte) (i % 251);
        }
        storage.upload(path, new ByteArrayInputStream(data), data.length);
        Assume.assumeTrue("该桶不支持 Range 读取", storage.supportsRange());
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        storage.download(path, 1000L, out);
        byte[] expected = new byte[data.length - 1000];
        System.arraycopy(data, 1000, expected, 0, expected.length);
        assertArrayEquals(expected, out.toByteArray());
        storage.delete(path);
    }

    @Test
    public void largeUploadMultiPart() throws IOException {
        // 超过分片阈值（默认 64MB）太慢，用未知长度流走引擎缓冲路径（单片退回 PUT）
        String path = unique("stream") + ".txt";
        byte[] data = new byte[(int) (300 * KB)];
        for (int i = 0; i < data.length; i++) {
            data[i] = (byte) (i % 199);
        }
        // 未知长度（size=-1）：引擎自行缓冲后上传
        storage.upload(path, new ByteArrayInputStream(data), -1L);
        byte[] downloaded = storage.readBytes(path);
        assertArrayEquals(data, downloaded);
        storage.delete(path);
    }

    @Test
    public void deleteDirRecursively() {
        String dir = unique("deldir");
        storage.mkdir(dir + "/sub");
        storage.upload(dir + "/a.txt", "a".getBytes(StandardCharsets.UTF_8));
        storage.upload(dir + "/sub/b.txt", "b".getBytes(StandardCharsets.UTF_8));
        storage.deleteDir(dir);
        assertFalse(storage.exists(dir));
    }

    @Test
    public void presignDownloadable() throws IOException {
        String path = unique("presign") + ".txt";
        storage.upload(path, "presigned".getBytes(StandardCharsets.UTF_8));
        String url = storage.presign(path, java.time.Duration.ofMinutes(10));
        assertTrue("预签名 URL 应为 http 链接: " + url, url.startsWith("http"));
        // 用预签名 URL 直接下载（无凭据）
        java.net.HttpURLConnection connection =
                (java.net.HttpURLConnection) new java.net.URL(url).openConnection();
        connection.setConnectTimeout(15_000);
        connection.setReadTimeout(30_000);
        int status = connection.getResponseCode();
        assertTrue("预签名下载应 200: " + status, status == 200);
        byte[] body = readAll(connection.getInputStream());
        assertEquals("presigned", new String(body, StandardCharsets.UTF_8));
        connection.disconnect();
        storage.delete(path);
    }

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
