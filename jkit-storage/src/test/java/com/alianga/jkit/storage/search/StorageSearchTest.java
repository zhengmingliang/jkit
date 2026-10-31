package com.alianga.jkit.storage.search;

import com.alianga.jkit.storage.FileStorages;
import com.alianga.jkit.storage.StorageConfig;
import com.alianga.jkit.storage.StorageEntry;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * 流式搜索语义：惰性展开（取消/限量真正省掉剩余遍历）、限量即停、批次回调、
 * 目录缺失显性报错、一句话入口。
 *
 * @author 郑明亮
 */
public class StorageSearchTest {

    @Rule
    public final TemporaryFolder tmp = new TemporaryFolder();

    private com.alianga.jkit.storage.FileStorage local() throws Exception {
        return FileStorages.of("local", StorageConfig.empty().set("basePath",
                tmp.getRoot().getAbsolutePath()));
    }

    /** 造一棵树：docs 下 3 个 .log + 2 个 .txt，logs/deep 下 2 个 .log，根 1 个 .log。 */
    private void seed(Path root) throws Exception {
        Files.createDirectories(root.resolve("docs"));
        Files.createDirectories(root.resolve("logs/deep"));
        for (String name : new String[]{"docs/a.log", "docs/b.log", "docs/c.log",
                "docs/note.txt", "logs/x.log", "logs/deep/y.log", "root.log"}) {
            Files.write(root.resolve(name), name.getBytes(StandardCharsets.UTF_8));
        }
    }

    @Test
    public void findMatchesRecursively() throws Exception {
        seed(tmp.getRoot().toPath());
        com.alianga.jkit.storage.FileStorage storage = local();
        try {
            List<StorageEntry> hits = StorageSearch.find(storage, "",
                    entry -> !entry.isDirectory() && entry.name().endsWith(".log"));
            assertEquals(6, hits.size());
            List<String> names = new ArrayList<String>();
            for (StorageEntry hit : hits) {
                names.add(hit.path());
            }
            assertTrue(names.contains("root.log"));
            assertTrue(names.contains("logs/deep/y.log"));
        } finally {
            storage.close();
        }
    }

    @Test
    public void limitStopsEarly() throws Exception {
        seed(tmp.getRoot().toPath());
        com.alianga.jkit.storage.FileStorage storage = local();
        try {
            AtomicInteger progress = new AtomicInteger();
            StorageSearch.Stats stats = StorageSearch.find(storage, "", entry ->
                            !entry.isDirectory() && entry.name().endsWith(".log"),
                    2, null, batch -> { }, progress::set);
            assertEquals(2, stats.matched());
            assertTrue("达到上限应标记", stats.limited());
            assertTrue("限量后不应扫完全树（树共 10 项）", progress.get() < 10);
        } finally {
            storage.close();
        }
    }

    @Test
    public void cancelStopsTraversal() throws Exception {
        seed(tmp.getRoot().toPath());
        com.alianga.jkit.storage.FileStorage storage = local();
        try {
            AtomicBoolean cancel = new AtomicBoolean(false);
            AtomicInteger matchedByTest = new AtomicInteger();
            StorageSearch.Stats stats = StorageSearch.find(storage, "", entry -> {
                // 扫到第 3 项后置取消：下一项起不再进入谓词
                if (matchedByTest.incrementAndGet() >= 3) {
                    cancel.set(true);
                }
                return false;
            }, 100, cancel::get, batch -> { }, null);
            assertEquals(0, stats.matched());
            assertEquals("取消后不应扫完全树（树共 10 项）", 3, matchedByTest.get());
            assertTrue(stats.scanned() <= 4);
        } finally {
            storage.close();
        }
    }

    @Test
    public void batchesOfTwentyWithFinalPartial() throws Exception {
        Path root = tmp.getRoot().toPath();
        Files.createDirectories(root.resolve("many"));
        for (int i = 0; i < 45; i++) {
            Files.write(root.resolve("many/f" + i + ".txt"),
                    ("f" + i).getBytes(StandardCharsets.UTF_8));
        }
        com.alianga.jkit.storage.FileStorage storage = local();
        try {
            List<Integer> batchSizes = new ArrayList<Integer>();
            StorageSearch.Stats stats = StorageSearch.find(storage, "many",
                    entry -> !entry.isDirectory() && entry.name().endsWith(".txt"),
                    100, null,
                    batch -> batchSizes.add(batch.size()), null);
            assertEquals(45, stats.matched());
            // 45 条 = 20 + 20 + 5
            assertEquals(Arrays.asList(20, 20, 5), batchSizes);
        } finally {
            storage.close();
        }
    }

    @Test
    public void missingDirFailsLoudly() throws Exception {
        com.alianga.jkit.storage.FileStorage storage = local();
        try {
            try {
                StorageSearch.find(storage, "no/such/dir", entry -> true);
                throw new AssertionError("目录缺失必须显性报错");
            } catch (com.alianga.jkit.storage.StorageException e) {
                assertTrue(String.valueOf(e.getMessage()).contains("no/such/dir"));
            }
        } finally {
            storage.close();
        }
    }

    @Test
    public void matcherDecidesSemantics() throws Exception {
        seed(tmp.getRoot().toPath());
        com.alianga.jkit.storage.FileStorage storage = local();
        try {
            // 大小写不敏感是调用方谓词的事（与 UI 现行为一致）
            List<StorageEntry> hits = StorageSearch.find(storage, "docs",
                    entry -> !entry.isDirectory()
                            && entry.name().toUpperCase(Locale.ROOT).endsWith(".LOG"));
            assertEquals(3, hits.size());
        } finally {
            storage.close();
        }
    }
}
