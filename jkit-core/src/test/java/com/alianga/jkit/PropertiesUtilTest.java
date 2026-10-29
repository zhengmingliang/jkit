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

    /**
     * 并发回写同一文件：updateToFile 的「读→改→写」按文件粒度串行后，
     * 最终文件必须包含所有线程的更新（无 lost update），且不留 tmp 残留。
     * 修复前两个线程同时回写会在同一个固定名 tmp 上交错写坏，或后写方
     * 用旧快照覆盖先写方的 key。
     */
    @Test
    public void concurrentUpdateToSameFileKeepsAllWrites() throws Exception {
        String[] lines = new String[9];
        lines[0] = "# concurrent";
        for (int i = 0; i < 8; i++) {
            lines[i + 1] = "key" + i + "=old" + i;
        }
        Path file = writeProps("conc.properties", lines);
        String path = file.toString();
        assertTrue(PropertiesUtil.loadFromPath(path, false));
        loadedFiles.add(path);

        int threads = 8;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger errors = new AtomicInteger();
        for (int i = 0; i < threads; i++) {
            final int idx = i;
            pool.submit(() -> {
                try {
                    start.await();
                    for (int r = 0; r < 20; r++) {
                        PropertiesUtil.update("key" + idx, "new" + idx + "_" + r, "c", true, path);
                    }
                } catch (Throwable th) {
                    errors.incrementAndGet();
                    th.printStackTrace();
                }
            });
        }
        start.countDown();
        pool.shutdown();
        assertTrue("workers did not finish", pool.awaitTermination(60, TimeUnit.SECONDS));
        assertEquals("concurrent update errors", 0, errors.get());

        Properties reRead = new Properties();
        try (FileInputStream in = new FileInputStream(file.toFile())) {
            reRead.load(in);
        }
        for (int i = 0; i < threads; i++) {
            assertEquals("key" + i + " lost update", "new" + i + "_19", reRead.getProperty("key" + i));
        }
        // 不能留下 tmp 残留
        try (java.util.stream.Stream<Path> list = Files.list(tempDir)) {
            long tmpCount = list.filter(p -> p.getFileName().toString().endsWith(".jkit-tmp")).count();
            assertEquals("tmp files left behind", 0, tmpCount);
        }
    }

    /**
     * 对 .yml 配置回写必须拒绝：rewriteFile 走 properties 格式 store，会把 yml
     * 整体重写成 properties 格式、结构损坏。修复前静默写坏，修复后抛
     * IllegalArgumentException 且文件保持原样。
     */
    @Test
    public void updateRejectsYamlWriteBack() throws Exception {
        Path file = writeProps("app.yml", "spring:", "  mode: online");
        String path = file.toString();
        assertTrue(PropertiesUtil.loadFromPath(path, false));
        loadedFiles.add(path);

        try {
            PropertiesUtil.update("spring.mode", "offline", "c", true, path);
            fail("expected IllegalArgumentException for yaml write-back");
        } catch (IllegalArgumentException expected) {
            // OK
        }
        // 文件内容必须原样保留
        String content = new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
        assertTrue("yaml must stay untouched", content.contains("spring:"));
        assertTrue(content.contains("mode: online"));
    }

    /**
     * 写回失败不再静默：目录只读时 update(..., true) 必须抛 FileNotFoundException
     * （本类声明的唯一受检异常），而不是返回 true 让调用方误以为落盘成功。
     */
    @Test
    public void updateThrowsWhenWriteBackFails() throws Exception {
        org.junit.Assume.assumeFalse("root".equals(System.getProperty("user.name")));
        Path file = writeProps("ro.properties", "k=v");
        String path = file.toString();
        assertTrue(PropertiesUtil.loadFromPath(path, false));
        loadedFiles.add(path);

        java.util.Set<java.nio.file.attribute.PosixFilePermission> original =
                Files.getPosixFilePermissions(file.getParent());
        Files.setPosixFilePermissions(file.getParent(),
                java.nio.file.attribute.PosixFilePermissions.fromString("r-xr-xr-x"));
        try {
            try {
                PropertiesUtil.update("k", "v2", "c", true, path);
                fail("expected FileNotFoundException when directory is read-only");
            } catch (java.io.FileNotFoundException expected) {
                // OK
            }
        } finally {
            Files.setPosixFilePermissions(file.getParent(), original);
        }
        // 原文件内容不被破坏
        Properties reRead = new Properties();
        try (FileInputStream in = new FileInputStream(file.toFile())) {
            reRead.load(in);
        }
        assertEquals("v", reRead.getProperty("k"));
    }
}
