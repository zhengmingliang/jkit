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
import static org.junit.Assert.assertTrue;

/**
 * WebDAV 引擎测试：内嵌内存版 WebDAV 服务器，验证 PROPFIND 列表 / PUT / GET /
 * MKCOL / MOVE / COPY / DELETE 与 Basic 认证、错误映射。
 *
 * @author 郑明亮
 */
public class WebDavFileStorageTest {
    private FakeDavServer server;
    private String endpoint;

    @Before
    public void startServer() throws Exception {
        server = new FakeDavServer();
        endpoint = server.start("dav", "zhangsan", "s3cret");
    }

    @After
    public void stopServer() {
        server.stop();
    }

    private FileStorage storage() {
        Map<String, String> values = new LinkedHashMap<String, String>();
        values.put("endpoint", endpoint);
        values.put("user", "zhangsan");
        values.put("password", "s3cret");
        values.put("basePath", "/");
        return FileStorages.of("webdav", values);
    }

    @Test
    public void uploadDownloadRoundTrip() {
        FileStorage dav = storage();
        byte[] data = "你好 WebDAV".getBytes(StandardCharsets.UTF_8);
        dav.upload("docs/hello.txt", data);
        assertTrue(server.tree.containsKey("/dav/docs/hello.txt"));
        assertEquals(new String(data, StandardCharsets.UTF_8),
                new String(dav.readBytes("docs/hello.txt"), StandardCharsets.UTF_8));
    }

    @Test
    public void listWithDirectoriesAndChineseNames() {
        FileStorage dav = storage();
        dav.upload("docs/笔记.md", "note".getBytes(StandardCharsets.UTF_8));
        dav.upload("docs/中文 目录/inner.txt", "in".getBytes(StandardCharsets.UTF_8));
        dav.mkdir("docs/empty");
        dav.upload("root.txt", "r".getBytes(StandardCharsets.UTF_8));
        List<StorageEntry> list = dav.list("docs");
        assertEquals(3, list.size());
        assertTrue(names(list).contains("笔记.md"));
        assertTrue(names(list).contains("中文 目录"));
        assertTrue(names(list).contains("empty"));
        assertTrue(list.get(names(list).indexOf("empty")).isDirectory());
    }

    @Test
    public void mkdirRenameCopyDelete() {
        FileStorage dav = storage();
        dav.mkdir("a");
        dav.mkdir("a");
        assertTrue(dav.exists("a"));
        dav.upload("a/f.txt", "F".getBytes(StandardCharsets.UTF_8));
        dav.rename("a/f.txt", "a/g.txt");
        assertFalse(dav.exists("a/f.txt"));
        assertTrue(dav.exists("a/g.txt"));
        dav.copy("a", "b");
        assertTrue(dav.exists("b/g.txt"));
        dav.delete("b/g.txt");
        assertFalse(dav.exists("b/g.txt"));
        dav.deleteDir("a");
        assertFalse(dav.exists("a"));
    }

    @Test
    public void statEntryMetadata() {
        FileStorage dav = storage();
        dav.upload("meta.bin", new byte[]{1, 2, 3, 4});
        StorageEntry entry = dav.stat("meta.bin");
        assertEquals(4L, entry.size());
        assertFalse(entry.isDirectory());
        assertTrue(entry.lastModified() > 0L);
        dav.mkdir("folder");
        StorageEntry dir = dav.stat("folder");
        assertTrue(dir.isDirectory());
    }

    @Test
    public void errorMapping() {
        FileStorage dav = storage();
        try {
            dav.stat("missing.txt");
            throw new AssertionError("应抛 NOT_FOUND");
        } catch (StorageException e) {
            assertEquals(StorageErrorCode.NOT_FOUND, e.code());
        }
        server.deny = true;
        try {
            dav.upload("deny.txt", "d".getBytes(StandardCharsets.UTF_8));
            throw new AssertionError("应抛 AUTH_FAILED");
        } catch (StorageException e) {
            assertEquals(StorageErrorCode.AUTH_FAILED, e.code());
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
