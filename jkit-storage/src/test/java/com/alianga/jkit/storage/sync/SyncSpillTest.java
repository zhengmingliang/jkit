package com.alianga.jkit.storage.sync;

import com.alianga.jkit.storage.FileStorage;
import com.alianga.jkit.storage.FileStorages;
import com.alianga.jkit.storage.StorageConfig;
import com.alianga.jkit.storage.StorageEntry;
import com.alianga.jkit.storage.StorageException;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.File;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * 同步中转内存有界：stat 大小未知 / 不可信时超限自动落盘；失败后不残留临时文件；
 * 并发越高单路内存上限越小。
 *
 * @author 郑明亮
 */
public class SyncSpillTest {
    private Path sourceRoot;
    private Path targetRoot;

    @Before
    public void setUp() throws Exception {
        sourceRoot = Files.createTempDirectory("jkit-spill-src");
        targetRoot = Files.createTempDirectory("jkit-spill-dst");
    }

    @After
    public void tearDown() throws Exception {
        for (Path root : new Path[]{sourceRoot, targetRoot}) {
            Files.walk(root).sorted((a, b) -> b.compareTo(a)).map(Path::toFile).forEach(File::delete);
        }
    }

    private static FileStorage local(Path root) {
        return FileStorages.of("local", StorageConfig.empty().set("basePath", root.toString()));
    }

    /** 代理：stat 一律报大小未知，模拟不返回大小的后端。 */
    private static FileStorage unknownSize(FileStorage delegate) {
        return (FileStorage) Proxy.newProxyInstance(FileStorage.class.getClassLoader(),
                new Class<?>[]{FileStorage.class}, (proxy, method, args) -> {
                    try {
                        Object value = method.invoke(delegate, args);
                        if ("stat".equals(method.getName()) && value instanceof StorageEntry
                                && !((StorageEntry) value).isDirectory()) {
                            StorageEntry e = (StorageEntry) value;
                            return StorageEntry.file(e.path(), StorageEntry.SIZE_UNKNOWN, e.lastModified(), null);
                        }
                        return value;
                    } catch (InvocationTargetException e) {
                        throw e.getCause();
                    }
                });
    }

    private static long spillFiles() {
        File[] files = new File(System.getProperty("java.io.tmpdir"))
                .listFiles((d, n) -> n.startsWith("jkit-storage-spill"));
        return files == null ? 0 : files.length;
    }

    @Test
    public void unknownSizeSpillsToDisk() {
        FileStorage source = unknownSize(local(sourceRoot));
        byte[] data = new byte[(int) SyncEngine.memoryLimitPerRoute(1) + 1024 * 1024];
        for (int i = 0; i < data.length; i++) {
            data[i] = (byte) (i * 13);
        }
        local(sourceRoot).upload("d/big.bin", data);
        AtomicBoolean sawSpill = new AtomicBoolean();
        FileStorage target = (FileStorage) Proxy.newProxyInstance(FileStorage.class.getClassLoader(),
                new Class<?>[]{FileStorage.class}, (proxy, method, args) -> {
                    if ("upload".equals(method.getName()) && spillFiles() > 0) {
                        sawSpill.set(true);
                    }
                    try {
                        return method.invoke(local(targetRoot), args);
                    } catch (InvocationTargetException e) {
                        throw e.getCause();
                    }
                });
        long before = spillFiles();
        SyncResult result = SyncEngine.sync(source, "d", target, "d", SyncOptions.incremental(), null);
        assertEquals(result.toString(), 1L, result.copied());
        assertTrue("超出内存上限应转写临时文件", sawSpill.get());
        assertArrayEquals(data, local(targetRoot).readBytes("d/big.bin"));
        assertEquals("临时文件应已删除", before, spillFiles());
    }

    @Test
    public void failedCopyLeavesNoTempFile() {
        byte[] data = new byte[(int) SyncEngine.memoryLimitPerRoute(1) + 4096];
        Arrays.fill(data, (byte) 1);
        local(sourceRoot).upload("d/big.bin", data);
        FileStorage failing = (FileStorage) Proxy.newProxyInstance(FileStorage.class.getClassLoader(),
                new Class<?>[]{FileStorage.class}, (proxy, method, args) -> {
                    if ("upload".equals(method.getName())) {
                        throw StorageException.io("模拟上传失败", "d/big.bin", null);
                    }
                    try {
                        return method.invoke(local(targetRoot), args);
                    } catch (InvocationTargetException e) {
                        throw e.getCause();
                    }
                });
        long before = spillFiles();
        SyncResult result = SyncEngine.sync(local(sourceRoot), "d", failing, "d", SyncOptions.incremental(), null);
        assertEquals(1L, result.failed());
        assertEquals("失败后不应残留中转临时文件", before, spillFiles());
    }

    @Test
    public void perRouteLimitShrinksWithConcurrency() {
        long one = SyncEngine.memoryLimitPerRoute(1);
        long sixteen = SyncEngine.memoryLimitPerRoute(16);
        assertTrue(one <= 8L * 1024L * 1024L);
        assertTrue("并发 16 时总预算不超过单路上限的 1 倍（加下限）", sixteen * 16 <= Math.max(one, 16L * 64L * 1024L));
    }
}
