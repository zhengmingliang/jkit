package com.alianga.jkit;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * 覆盖 P0-2（updateToFile 数据丢失）与 P0-3（propertiesMap 线程安全）。
 */
public class PropertiesUtilTest {

    private Path tempDir;
    private final List<String> loadedFiles = new ArrayList<>();

    @Before
    public void setUp() throws IOException {
        tempDir = Files.createTempDirectory("jkit-putil-");
    }

    @After
    public void tearDown() throws IOException {
        for (String file : loadedFiles) {
            PropertiesUtil.removeKeys(file);
        }
        if (tempDir != null) {
            Files.walk(tempDir)
                    .sorted((a, b) -> b.compareTo(a))
                    .forEach(p -> {
                        try {
                            Files.deleteIfExists(p);
                        } catch (IOException ignore) {
                            // ignore
                        }
                    });
        }
    }

    private Path writeProps(String name, String... lines) throws IOException {
        Path p = tempDir.resolve(name);
        Files.write(p, String.join("\n", lines).getBytes(StandardCharsets.UTF_8));
        return p;
    }

    /**
     * P0-2 复现：修复前 updateToFile 会先截断文件再读回，只保留 setProperty 的那一行。
     * 修复后应保留所有原有配置。
     */
    @Test
    public void updateToFileShouldPreserveAllEntries() throws Exception {
        Path file = writeProps("a.properties",
                "# 服务器配置", "host=127.0.0.1", "port=8080", "timeout=30");

        String path = file.toString();
        assertTrue("preload failed", PropertiesUtil.loadFromPath(path, false));
        loadedFiles.add(path);

        // 走 update 入口（会调用私有 updateToFile）
        boolean ok = PropertiesUtil.update("port", "9090", "更新端口", true, path);
        assertTrue("update should report success", ok);

        // 读回：除 port 外 host/timeout 必须还在
        Properties reRead = new Properties();
        try (FileInputStream in = new FileInputStream(file.toFile())) {
            reRead.load(in);
        }
        assertEquals("127.0.0.1", reRead.getProperty("host"));
        assertEquals("9090", reRead.getProperty("port"));
        assertEquals("30", reRead.getProperty("timeout"));
    }

    /**
     * updateToFile 在目标不存在时必须抛 FileNotFoundException（原行为，防止静默失败）。
     */
    @Test
    public void updateToFileThrowsWhenFileMissing() throws Exception {
        Path file = writeProps("b.properties", "a=1");
        String path = file.toString();
        assertTrue(PropertiesUtil.loadFromPath(path, false));
        loadedFiles.add(path);
        // 删除已加载的文件，再触发回写
        Files.delete(file);

        try {
            PropertiesUtil.update("a", "2", "c", true, path);
            fail("expected FileNotFoundException");
        } catch (java.io.FileNotFoundException expected) {
            // OK
        }
    }

    /**
     * updateToFile 当 update=false 时只改内存缓存，不落盘。
     */
    @Test
    public void updateWithoutWritingFile() throws Exception {
        Path file = writeProps("c.properties", "k1=v1", "k2=v2");
        String path = file.toString();
        assertTrue(PropertiesUtil.loadFromPath(path, false));
        loadedFiles.add(path);

        assertTrue(PropertiesUtil.update("k1", "v1-new", "c", false, path));
        assertEquals("v1-new", PropertiesUtil.getString("k1"));

        // 文件内容应未被修改
        Properties reRead = new Properties();
        try (FileInputStream in = new FileInputStream(file.toFile())) {
            reRead.load(in);
        }
        assertEquals("v1", reRead.getProperty("k1"));
    }

    /**
     * P0-3：并发加载 + 并发读取，修复前应触发 ConcurrentModificationException，
     * 修复后不得抛出。
     */
    @Test
    public void concurrentLoadAndIterateShouldBeSafe() throws Exception {
        // 准备 50 个配置文件
        for (int i = 0; i < 50; i++) {
            Path p = writeProps("cc-" + i + ".properties", "idx=" + i, "mod=" + (i % 7));
            String path = p.toString();
            assertTrue(PropertiesUtil.loadFromPath(path, false));
            loadedFiles.add(path);
        }

        int threads = 8;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger errors = new AtomicInteger();

        // 一半线程反复 getAllKeys / getString，另一半反复 reload 不同配置文件
        for (int t = 0; t < threads / 2; t++) {
            pool.submit(() -> {
                try {
                    start.await();
                    for (int i = 0; i < 200; i++) {
                        Set<String> keys = PropertiesUtil.getAllKeys();
                        assertFalse(keys.isEmpty());
                        PropertiesUtil.getString("idx");
                    }
                } catch (Throwable th) {
                    errors.incrementAndGet();
                    th.printStackTrace();
                }
            });
            final int idx = t;
            pool.submit(() -> {
                try {
                    start.await();
                    for (int i = 0; i < 50; i++) {
                        // 强制重载（loadOnce=false）触发 propertiesMap.put
                        String path = loadedFiles.get((idx * 7 + i) % loadedFiles.size());
                        PropertiesUtil.loadFromPath(path, false);
                    }
                } catch (Throwable th) {
                    errors.incrementAndGet();
                    th.printStackTrace();
                }
            });
        }
        start.countDown();
        pool.shutdown();
        assertTrue("workers did not finish", pool.awaitTermination(30, TimeUnit.SECONDS));
        assertEquals("concurrent load/iterate triggered errors", 0, errors.get());
    }

    /**
     * 兼容性：getKeys(fileName) 不应因 thread-safe map 改动而回归。
     */
    @Test
    public void getKeysReturnsLoadedEntries() throws Exception {
        Path file = writeProps("d.properties", "x=1", "y=2");
        String path = file.toString();
        assertTrue(PropertiesUtil.loadFromPath(path, false));
        loadedFiles.add(path);

        Set<String> keys = PropertiesUtil.getKeys(path);
        assertTrue(keys.contains("x"));
        assertTrue(keys.contains("y"));
    }

    /**
     * 兼容性：未加载的 key 返回 null / defaultValue。
     */
    @Test
    public void gettersReturnDefaultsForMissing() throws IOException {
        Path file = writeProps("e.properties", "present=1");
        String path = file.toString();
        assertTrue(PropertiesUtil.loadFromPath(path, false));
        loadedFiles.add(path);

        assertEquals("1", PropertiesUtil.getString("present"));
        assertEquals("fallback", PropertiesUtil.getString("missing", "fallback"));
        assertNotNull(PropertiesUtil.getInteger("present"));
        assertEquals(Integer.valueOf(7), PropertiesUtil.getInteger("missing-int", 7));
        assertEquals(Boolean.TRUE, PropertiesUtil.getBoolean("missing-bool", true));
    }
}
