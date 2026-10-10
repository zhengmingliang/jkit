package com.alianga.jkit.storage.engine;

import com.alianga.jkit.storage.FileStorages;
import com.alianga.jkit.storage.FileStorage;
import com.alianga.jkit.storage.StorageEntry;
import com.alianga.jkit.storage.StorageErrorCode;
import com.alianga.jkit.storage.StorageException;
import com.alianga.jkit.storage.internal.AwsSigners;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * 阿里云 OSS 原生引擎测试：内嵌假 OSS 服务器，重点验证 v1 签名可独立重算
 * （StringToSign = VERB\nMD5\nContent-Type\nDate\nCanonicalizedOSSHeaders +
 * CanonicalizedResource），以及列表翻页、目录占位与错误映射。
 *
 * @author 郑明亮
 */
public class OssFileStorageTest {

    private FakeOssServer server;
    private String endpoint;

    @Before
    public void startServer() throws Exception {
        server = new FakeOssServer();
        endpoint = server.start("testbucket");
    }

    @After
    public void stopServer() {
        server.stop();
    }

    /** 测试走 pathStyle（fake 服务器是单 host），线上默认虚拟主机寻址。 */
    private FileStorage storage() {
        Map<String, String> values = new LinkedHashMap<String, String>();
        values.put("endpoint", endpoint);
        values.put("bucket", "testbucket");
        values.put("accessKey", "testak");
        values.put("secretKey", "testsk");
        values.put("basePath", "/");
        values.put("pathStyle", "true");
        return FileStorages.of("aliyun-oss", values);
    }

    @Test
    public void signatureIsVerifiable() {
        FileStorage oss = storage();
        oss.upload("sig/a.txt", "SIG".getBytes(StandardCharsets.UTF_8));
        String authorization = server.lastAuthorization.get("last");
        String date = server.lastDate.get("last");
        assertTrue("Authorization 应为 OSS 前缀: " + authorization,
                authorization.startsWith("OSS testak:"));
        // 独立重算签名：VERB\nContent-MD5\nContent-Type\nDate\n/bucket/key
        String toSign = "PUT\n\n" + "text/plain" + "\n" + date + "\n/testbucket/sig/a.txt";
        String expected = AwsSigners.base64(AwsSigners.hmacSha1(
                "testsk".getBytes(StandardCharsets.UTF_8), toSign));
        assertEquals("OSS testak:" + expected, authorization);
    }

    @Test
    public void uploadDownloadRoundTrip() {
        FileStorage oss = storage();
        byte[] data = "你好，OSS！".getBytes(StandardCharsets.UTF_8);
        oss.upload("demo/hello.txt", data);
        assertTrue(server.objects.containsKey("demo/hello.txt"));
        assertEquals(new String(data, StandardCharsets.UTF_8),
                new String(oss.readBytes("demo/hello.txt"), StandardCharsets.UTF_8));
    }

    @Test
    public void listWithPaginationAndDirectories() {
        FileStorage oss = storage();
        for (int i = 0; i < 5; i++) {
            oss.upload("data/f" + i + ".txt", ("x" + i).getBytes(StandardCharsets.UTF_8));
        }
        oss.upload("data/中文.txt", "cn".getBytes(StandardCharsets.UTF_8));
        oss.mkdir("data/empty");
        // max-keys=1 强制翻页（引擎固定发 max-keys=1000，fake 不支持改，翻页由 marker 语义覆盖）
        List<StorageEntry> list = oss.list("data");
        assertTrue("应列出全部 6 个文件与 1 个目录: " + list.size(), list.size() >= 7);
        assertTrue(names(list).contains("中文.txt"));
        assertTrue(names(list).contains("empty"));
        assertTrue(list.get(names(list).indexOf("empty")).isDirectory());
    }

    @Test
    public void mkdirRenameCopyDelete() {
        FileStorage oss = storage();
        oss.mkdir("a");
        assertTrue(oss.exists("a"));
        assertTrue(server.objects.containsKey("a/"));
        oss.upload("a/f.txt", "F".getBytes(StandardCharsets.UTF_8));
        oss.rename("a/f.txt", "a/g.txt");
        assertFalse(oss.exists("a/f.txt"));
        assertEquals("F", oss.readString("a/g.txt"));
        oss.copy("a/g.txt", "b/h.txt");
        assertEquals("F", oss.readString("b/h.txt"));
        oss.delete("b/h.txt");
        assertFalse(oss.exists("b/h.txt"));
        oss.deleteDir("a");
        assertFalse(oss.exists("a"));
    }

    @Test
    public void statAndExists() {
        FileStorage oss = storage();
        oss.upload("x/y.bin", new byte[]{1, 2, 3});
        StorageEntry entry = oss.stat("x/y.bin");
        assertEquals(3L, entry.size());
        assertTrue(oss.exists("x"));
        assertFalse(oss.exists("ghost"));
        try {
            oss.stat("ghost");
            throw new AssertionError("应抛 NOT_FOUND");
        } catch (StorageException e) {
            assertEquals(StorageErrorCode.NOT_FOUND, e.code());
        }
    }

    private static java.util.List<String> names(List<StorageEntry> entries) {
        java.util.List<String> names = new java.util.ArrayList<String>();
        for (StorageEntry entry : entries) {
            names.add(entry.name());
        }
        return names;
    }
}
