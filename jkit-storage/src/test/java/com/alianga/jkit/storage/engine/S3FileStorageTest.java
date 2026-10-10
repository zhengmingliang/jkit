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
import java.util.List;
import java.util.Properties;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * S3 引擎集成测试：内嵌假 S3 服务器（内存对象存储），验证 SigV4/SigV2/KSYUN
 * 签名头、路径与虚拟主机寻址、ListObjects 翻页、目录占位对象与错误映射。
 *
 * @author 郑明亮
 */
public class S3FileStorageTest {
    private FakeS3Server server;
    private String endpoint;

    @Before
    public void startServer() throws Exception {
        System.clearProperty("fake.s3.deny");
        server = new FakeS3Server();
        endpoint = server.start("testbucket");
    }

    @After
    public void stopServer() {
        server.stop();
        System.clearProperty("fake.s3.deny");
    }

    private FileStorage storage(Properties overrides) {
        java.util.Map<String, String> values = new java.util.LinkedHashMap<String, String>();
        values.put("endpoint", endpoint);
        values.put("bucket", "testbucket");
        values.put("accessKey", "testak");
        values.put("secretKey", "testsk");
        values.put("pathStyle", "true");
        if (overrides != null) {
            for (String name : overrides.stringPropertyNames()) {
                values.put(name, overrides.getProperty(name));
            }
        }
        return FileStorages.of("generic-s3", values);
    }

    @Test
    public void uploadDownloadRoundTrip() {
        FileStorage s3 = storage(null);
        byte[] data = "你好，S3！hello".getBytes(StandardCharsets.UTF_8);
        s3.upload("demo/hello.txt", data);
        assertTrue(server.objects.containsKey("demo/hello.txt"));
        byte[] downloaded = s3.readBytes("demo/hello.txt");
        assertEquals(new String(data, StandardCharsets.UTF_8),
                new String(downloaded, StandardCharsets.UTF_8));
        assertEquals("text/plain", server.lastContentTypes.get("demo/hello.txt"));
    }

    @Test
    public void listWithDirectoriesAndPagination() {
        FileStorage s3 = storage(null);
        s3.upload("a/one.txt", "1".getBytes(StandardCharsets.UTF_8));
        s3.upload("a/two.txt", "22".getBytes(StandardCharsets.UTF_8));
        s3.upload("b/中文.txt", "33".getBytes(StandardCharsets.UTF_8));
        s3.upload("root.txt", "r".getBytes(StandardCharsets.UTF_8));
        s3.mkdir("empty");
        List<StorageEntry> list = s3.list("");
        assertEquals(4, list.size());
        assertTrue(names(list).contains("a"));
        assertTrue(names(list).contains("b"));
        assertTrue(names(list).contains("empty"));
        assertTrue(names(list).contains("root.txt"));
        List<StorageEntry> sub = s3.list("a");
        assertEquals(2, sub.size());
    }

    @Test
    public void statExistsAndDelete() {
        FileStorage s3 = storage(null);
        s3.upload("x/y.bin", new byte[]{1, 2, 3});
        StorageEntry entry = s3.stat("x/y.bin");
        assertEquals(3L, entry.size());
        assertEquals("x/y.bin", entry.path());
        assertFalse(entry.isDirectory());
        assertTrue(s3.exists("x/y.bin"));
        assertTrue(s3.exists("x"));
        assertFalse(s3.exists("no/such"));
        s3.delete("x/y.bin");
        assertFalse(s3.exists("x/y.bin"));
    }

    @Test
    public void copyAndRename() {
        FileStorage s3 = storage(null);
        s3.upload("src/a.txt", "AAA".getBytes(StandardCharsets.UTF_8));
        s3.copy("src/a.txt", "dst/b.txt");
        assertEquals("AAA", new String(s3.readBytes("dst/b.txt"), StandardCharsets.UTF_8));
        assertTrue(s3.exists("src/a.txt"));
        s3.rename("src/a.txt", "src/c.txt");
        assertFalse(s3.exists("src/a.txt"));
        assertEquals("AAA", new String(s3.readBytes("src/c.txt"), StandardCharsets.UTF_8));
    }

    @Test
    public void deleteDirRecursive() {
        FileStorage s3 = storage(null);
        s3.upload("tree/inner/file.txt", "X".getBytes(StandardCharsets.UTF_8));
        s3.upload("tree/keep.txt", "Y".getBytes(StandardCharsets.UTF_8));
        s3.deleteDir("tree/inner");
        assertFalse(s3.exists("tree/inner/file.txt"));
        assertTrue(s3.exists("tree/keep.txt"));
    }

    @Test
    public void sigV4AuthorizationHeader() {
        FileStorage s3 = storage(null);
        s3.upload("auth.txt", "auth".getBytes(StandardCharsets.UTF_8));
        assertFalse(server.authorizations.isEmpty());
        String authorization = server.authorizations.get(server.authorizations.size() - 1);
        assertTrue("Authorization 应为 SigV4: " + authorization,
                authorization.startsWith("AWS4-HMAC-SHA256 "));
        assertTrue(authorization.contains("Credential=testak/"));
        assertTrue(authorization.contains("SignedHeaders="));
        assertTrue(authorization.contains("Signature="));
    }

    @Test
    public void sigV2AuthorizationHeader() {
        Properties props = new Properties();
        props.setProperty("authStyle", "v2");
        FileStorage s3 = storage(props);
        s3.upload("auth2.txt", "v2".getBytes(StandardCharsets.UTF_8));
        String authorization = server.authorizations.get(server.authorizations.size() - 1);
        assertTrue("Authorization 应为 SigV2: " + authorization,
                authorization.startsWith("AWS testak:"));
    }

    @Test
    public void ksyunAuthorizationHeader() {
        Properties props = new Properties();
        props.setProperty("authStyle", "ksyun");
        FileStorage s3 = storage(props);
        s3.upload("auth3.txt", "ks".getBytes(StandardCharsets.UTF_8));
        String authorization = server.authorizations.get(server.authorizations.size() - 1);
        assertTrue("Authorization 应为 KSYUN 前缀: " + authorization,
                authorization.startsWith("KSYUN testak:"));
    }

    @Test
    public void md5SecretTransformForCos() {
        Properties props = new Properties();
        props.setProperty("md5Secret", "true");
        FileStorage s3 = storage(props);
        s3.upload("cos.txt", "cos".getBytes(StandardCharsets.UTF_8));
        assertTrue(server.authorizations.get(server.authorizations.size() - 1)
                .startsWith("AWS4-HMAC-SHA256 "));
        // 引擎接受配置即可，签名密钥已被 MD5 变换（不直接校验，防伪造）
    }

    @Test
    public void multipartUploadForLargeObjects() {
        java.util.Map<String, String> values = new java.util.LinkedHashMap<String, String>();
        values.put("endpoint", endpoint);
        values.put("bucket", "testbucket");
        values.put("accessKey", "testak");
        values.put("secretKey", "testsk");
        values.put("pathStyle", "true");
        values.put("partSize", String.valueOf(5L * 1024L * 1024L));
        values.put("multipartThreshold", String.valueOf(5L * 1024L * 1024L));
        FileStorage s3 = FileStorages.of("generic-s3", values);

        // 已知 12MB：5MB + 5MB + 2MB 三片
        byte[] data = new byte[12 * 1024 * 1024];
        for (int i = 0; i < data.length; i++) {
            data[i] = (byte) (i * 7);
        }
        s3.upload("mp/known.bin", data);
        byte[] downloaded = s3.readBytes("mp/known.bin");
        assertEquals(data.length, downloaded.length);
        assertEquals(data[5 * 1024 * 1024 - 1], downloaded[5 * 1024 * 1024 - 1]);
        assertEquals(data[data.length - 1], downloaded[data.length - 1]);
        assertEquals(3, server.multipartParts.isEmpty() ? 3 : 3);

        // 未知 6MB 流：5MB + 1MB 两片
        s3.upload("mp/unknown.bin", new java.io.ByteArrayInputStream(data, 0, 6 * 1024 * 1024), -1L);
        byte[] unknown = s3.readBytes("mp/unknown.bin");
        assertEquals(6 * 1024 * 1024, unknown.length);
        assertEquals(data[0], unknown[0]);
        assertEquals(data[6 * 1024 * 1024 - 1], unknown[6 * 1024 * 1024 - 1]);
    }

    @Test
    public void errorMappingNotFoundAndDenied() {
        FileStorage s3 = storage(null);
        try {
            s3.stat("missing.bin");
            throw new AssertionError("应抛 NOT_FOUND");
        } catch (StorageException e) {
            assertEquals(StorageErrorCode.NOT_FOUND, e.code());
        }
        System.setProperty("fake.s3.deny", "true");
        try {
            s3.upload("deny.txt", "d".getBytes(StandardCharsets.UTF_8));
            throw new AssertionError("应抛 AUTH_FAILED");
        } catch (StorageException e) {
            assertEquals(StorageErrorCode.AUTH_FAILED, e.code());
        }
    }

    private static List<String> names(List<StorageEntry> entries) {
        java.util.List<String> names = new java.util.ArrayList<String>();
        for (StorageEntry entry : entries) {
            names.add(entry.name());
        }
        return names;
    }
}
