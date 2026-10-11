package com.alianga.jkit.storage.sync;

import com.alianga.jkit.storage.FileStorage;
import com.alianga.jkit.storage.FileStorages;
import com.alianga.jkit.storage.StorageConfig;
import com.alianga.jkit.storage.StorageEntry;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * 同步引擎测试：对比清单、增量同步、全量迁移、镜像删除、过滤、试运行与并发执行。
 * 两侧都用本地引擎（跨平台行为由各引擎自身测试保证）。
 *
 * @author 郑明亮
 */
public class SyncEngineTest {
    private Path sourceRoot;
    private Path targetRoot;
    private FileStorage source;
    private FileStorage target;

    @Before
    public void setUp() throws Exception {
        sourceRoot = Files.createTempDirectory("jkit-sync-src");
        targetRoot = Files.createTempDirectory("jkit-sync-dst");
        source = FileStorages.of("local", StorageConfig.empty().set("basePath", sourceRoot.toString()));
        target = FileStorages.of("local", StorageConfig.empty().set("basePath", targetRoot.toString()));
    }

    @After
    public void tearDown() throws Exception {
        deleteRecursively(sourceRoot);
        deleteRecursively(targetRoot);
    }

    @Test
    public void incrementalSyncCopiesNewAndChanged() {
        source.upload("data/a.txt", "A".getBytes(StandardCharsets.UTF_8));
        source.upload("data/sub/b.txt", "BB".getBytes(StandardCharsets.UTF_8));
        SyncResult first = SyncEngine.sync(source, "data", target, "backup", SyncOptions.incremental(), null);
        assertEquals(2L, first.copied());
        assertEquals("A", target.readString("backup/a.txt"));
        assertEquals("BB", target.readString("backup/sub/b.txt"));

        source.upload("data/a.txt", "A2".getBytes(StandardCharsets.UTF_8));
        source.upload("data/c.txt", "C".getBytes(StandardCharsets.UTF_8));
        SyncResult second = SyncEngine.sync(source, "data", target, "backup", SyncOptions.incremental(), null);
        assertEquals(2L, second.copied());
        assertEquals("A2", target.readString("backup/a.txt"));
        assertEquals(0L, second.failed());
    }

    @Test
    public void diffReportsStatuses() {
        source.upload("same.txt", "S".getBytes(StandardCharsets.UTF_8));
        target.upload("same.txt", "S".getBytes(StandardCharsets.UTF_8));
        source.upload("changed.txt", "old".getBytes(StandardCharsets.UTF_8));
        target.upload("changed.txt", "new-content".getBytes(StandardCharsets.UTF_8));
        target.upload("only-right.txt", "R".getBytes(StandardCharsets.UTF_8));
        List<StorageDiff> diffs = SyncEngine.diff(source, "", target, "", CompareMode.SIZE);
        assertEquals(3, diffs.size());
        for (StorageDiff diff : diffs) {
            switch (diff.relativePath()) {
                case "same.txt":
                    assertEquals(StorageDiff.Status.SAME, diff.status());
                    break;
                case "changed.txt":
                    assertEquals(StorageDiff.Status.DIFFERENT, diff.status());
                    break;
                case "only-right.txt":
                    assertEquals(StorageDiff.Status.RIGHT_ONLY, diff.status());
                    break;
                default:
                    throw new AssertionError("未知差异路径: " + diff.relativePath());
            }
        }
    }

    @Test
    public void fullMigrateCopiesEverything() {
        source.upload("a.txt", "A".getBytes(StandardCharsets.UTF_8));
        SyncEngine.sync(source, "", target, "", SyncOptions.incremental(), null);
        source.upload("a.txt", "A".getBytes(StandardCharsets.UTF_8));
        SyncResult result = SyncEngine.sync(source, "", target, "", SyncOptions.fullMigrate(), null);
        assertEquals(1L, result.copied());
        assertEquals(0L, result.skipped());
    }

    @Test
    public void mirrorDeletesExtras() {
        source.upload("keep.txt", "K".getBytes(StandardCharsets.UTF_8));
        target.upload("stale.txt", "S".getBytes(StandardCharsets.UTF_8));
        SyncResult result = SyncEngine.sync(source, "", target, "", SyncOptions.mirror(), null);
        assertEquals(1L, result.copied());
        assertEquals(1L, result.deleted());
        assertFalse(target.exists("stale.txt"));
        assertTrue(target.exists("keep.txt"));
    }

    @Test
    public void mirrorDeletesExtraDirs() {
        source.upload("keep/a.txt", "A".getBytes(StandardCharsets.UTF_8));
        target.upload("keep/stale.txt", "S".getBytes(StandardCharsets.UTF_8));
        target.mkdir("keep/empty-dir");
        target.mkdir("keep/gone/deep");
        SyncResult result = SyncEngine.sync(source, "", target, "", SyncOptions.mirror(), null);
        assertEquals(1L, result.copied());
        assertEquals("多余文件 + 空目录 + 深层目录", 4L, result.deleted());
        assertFalse(target.exists("keep/stale.txt"));
        assertFalse(target.exists("keep/empty-dir"));
        assertFalse(target.exists("keep/gone"));
        assertTrue(target.exists("keep/a.txt"));
        assertEquals(0L, result.failed());
    }

    @Test
    public void dryRunDoesNotTouchTarget() {
        source.upload("a.txt", "A".getBytes(StandardCharsets.UTF_8));
        target.upload("b.txt", "B".getBytes(StandardCharsets.UTF_8));
        SyncResult result = SyncEngine.sync(source, "", target, "",
                SyncOptions.incremental().dryRun(true), null);
        assertEquals(1L, result.copied());
        assertTrue(target.exists("b.txt"));
        assertFalse(target.exists("a.txt"));
    }

    @Test
    public void filterAndConcurrencyAndProgress() {
        source.upload("logs/1.log", "1".getBytes(StandardCharsets.UTF_8));
        source.upload("keep.txt", "K".getBytes(StandardCharsets.UTF_8));
        final AtomicInteger started = new AtomicInteger();
        final AtomicInteger done = new AtomicInteger();
        SyncProgress progress = new SyncProgress() {
            @Override
            public void onStart(long totalFiles, long totalBytes) {
                started.incrementAndGet();
            }

            @Override
            public void onFileDone(String relativePath, long bytes, String action) {
                done.incrementAndGet();
            }

            @Override
            public void onFileError(String relativePath, Exception error) {
            }
        };
        SyncResult result = SyncEngine.sync(source, "", target, "",
                SyncOptions.incremental().concurrency(3)
                        .filter(path -> !path.startsWith("logs/")), progress);
        assertEquals(1L, result.copied());
        assertTrue(target.exists("keep.txt"));
        assertFalse(target.exists("logs/1.log"));
        assertEquals(1, started.get());
        assertEquals(1, done.get());
    }

    @Test
    public void cancelStopsRemainingWork() {
        source.upload("a.txt", "A".getBytes(StandardCharsets.UTF_8));
        source.upload("b.txt", "B".getBytes(StandardCharsets.UTF_8));
        source.upload("c.txt", "C".getBytes(StandardCharsets.UTF_8));
        final SyncCancel cancel = new SyncCancel();
        SyncProgress cancelAfterFirst = new SyncProgress() {
            @Override
            public void onStart(long totalFiles, long totalBytes) {
            }

            @Override
            public void onFileDone(String relativePath, long bytes, String action) {
                cancel.cancel();
            }

            @Override
            public void onFileError(String relativePath, Exception error) {
            }
        };
        SyncResult result = SyncEngine.sync(source, "", target, "",
                SyncOptions.incremental().concurrency(1).cancelToken(cancel), cancelAfterFirst);
        assertEquals("copied", 1L, result.copied());
        assertTrue("aborted=" + result, result.aborted());
        // 目录枚举顺序不定，只能断言未把三个文件全部复制完
        assertFalse(target.exists("a.txt") && target.exists("b.txt") && target.exists("c.txt"));
    }

    @Test
    public void preserveMtimeOnCopy() throws Exception {
        java.nio.file.attribute.FileTime mtime =
                java.nio.file.attribute.FileTime.fromMillis(1_000_000_000_000L);
        source.upload("old.txt", "OLD".getBytes(StandardCharsets.UTF_8));
        Files.setLastModifiedTime(sourceRoot.resolve("old.txt"), mtime);
        SyncEngine.sync(source, "", target, "", SyncOptions.incremental(), null);
        assertEquals(mtime.toMillis(), Files.getLastModifiedTime(targetRoot.resolve("old.txt")).toMillis());
        // 关闭保留则用当前时间
        source.upload("now.txt", "NOW".getBytes(StandardCharsets.UTF_8));
        SyncEngine.sync(source, "", target, "",
                SyncOptions.incremental().preserveMtime(false), null);
        assertTrue(Files.getLastModifiedTime(targetRoot.resolve("now.txt")).toMillis()
                > System.currentTimeMillis() - 60_000L);
    }

    @Test
    public void bidirectionalFillsMissingSides() {
        source.upload("only-left.txt", "L".getBytes(StandardCharsets.UTF_8));
        target.upload("only-right.txt", "R".getBytes(StandardCharsets.UTF_8));
        SyncResult dry = SyncEngine.sync(source, "", target, "",
                SyncOptions.incremental().direction(Direction.BIDIRECTIONAL).dryRun(true), null);
        assertEquals(2L, dry.copied());
        assertEquals(0L, dry.conflicts());
        assertFalse(target.exists("only-left.txt"));
        assertFalse(source.exists("only-right.txt"));

        SyncResult result = SyncEngine.sync(source, "", target, "",
                SyncOptions.incremental().direction(Direction.BIDIRECTIONAL), null);
        assertEquals(2L, result.copied());
        assertEquals(0L, result.conflicts());
        assertEquals("L", target.readString("only-left.txt"));
        assertEquals("R", source.readString("only-right.txt"));
    }

    @Test
    public void bidirectionalNewerWins() throws Exception {
        source.upload("doc.txt", "old".getBytes(StandardCharsets.UTF_8));
        target.upload("doc.txt", "new-content".getBytes(StandardCharsets.UTF_8));
        java.nio.file.attribute.FileTime older =
                java.nio.file.attribute.FileTime.fromMillis(1_000_000_000_000L);
        java.nio.file.attribute.FileTime newer =
                java.nio.file.attribute.FileTime.fromMillis(2_000_000_000_000L);
        Files.setLastModifiedTime(sourceRoot.resolve("doc.txt"), older);
        Files.setLastModifiedTime(targetRoot.resolve("doc.txt"), newer);
        final List<String> actions = new java.util.ArrayList<String>();
        SyncResult result = SyncEngine.sync(source, "", target, "",
                SyncOptions.incremental().direction(Direction.BIDIRECTIONAL)
                        .conflictListener((path, action, detail) -> actions.add(action)), null);
        assertEquals(1L, result.conflicts());
        assertEquals(1, actions.size());
        assertEquals("right-wins", actions.get(0));
        assertEquals("新者覆盖旧者", "new-content", source.readString("doc.txt"));
        assertEquals("new-content", target.readString("doc.txt"));
        assertEquals("胜者 mtime 保留到败方", newer.toMillis(),
                Files.getLastModifiedTime(sourceRoot.resolve("doc.txt")).toMillis());
    }

    @Test
    public void bidirectionalEqualMtimeFallsBackToKeepBoth() throws Exception {
        source.upload("doc.txt", "left-version".getBytes(StandardCharsets.UTF_8));
        target.upload("doc.txt", "right-version-long".getBytes(StandardCharsets.UTF_8));
        java.nio.file.attribute.FileTime same =
                java.nio.file.attribute.FileTime.fromMillis(1_500_000_000_000L);
        Files.setLastModifiedTime(sourceRoot.resolve("doc.txt"), same);
        Files.setLastModifiedTime(targetRoot.resolve("doc.txt"), same);
        SyncResult result = SyncEngine.sync(source, "", target, "",
                SyncOptions.incremental().direction(Direction.BIDIRECTIONAL), null);
        assertEquals(1L, result.conflicts());
        assertEquals("无法判定新旧，各自原文件不动", "left-version", source.readString("doc.txt"));
        assertEquals("right-version-long", target.readString("doc.txt"));
        assertEquals("对端版本以冲突副本名保留", "right-version-long",
                conflictCopy(source, "doc.txt", "doc.conflict-"));
        assertEquals("left-version", conflictCopy(target, "doc.txt", "doc.conflict-"));
    }

    @Test
    public void bidirectionalKeepBothRenames() {
        source.upload("a.txt", "LEFT".getBytes(StandardCharsets.UTF_8));
        target.upload("a.txt", "RIGHT-VERSION".getBytes(StandardCharsets.UTF_8));
        SyncResult result = SyncEngine.sync(source, "", target, "",
                SyncOptions.incremental().direction(Direction.BIDIRECTIONAL)
                        .conflictPolicy(ConflictPolicy.KEEP_BOTH), null);
        assertEquals(1L, result.conflicts());
        assertEquals("LEFT", source.readString("a.txt"));
        assertEquals("RIGHT-VERSION", target.readString("a.txt"));
        assertEquals("左侧版本以 conflict-left 副本名存到右端", "LEFT",
                conflictCopy(target, "a.txt", "a.conflict-left-"));
        assertEquals("右侧版本以 conflict-right 副本名存到左端", "RIGHT-VERSION",
                conflictCopy(source, "a.txt", "a.conflict-right-"));
    }

    @Test
    public void bidirectionalLeftWinsOverwrites() throws Exception {
        source.upload("b.txt", "FROM-LEFT".getBytes(StandardCharsets.UTF_8));
        target.upload("b.txt", "x-longer-right".getBytes(StandardCharsets.UTF_8));
        java.nio.file.attribute.FileTime mtime =
                java.nio.file.attribute.FileTime.fromMillis(1_000_000_000_000L);
        Files.setLastModifiedTime(sourceRoot.resolve("b.txt"), mtime);
        SyncResult result = SyncEngine.sync(source, "", target, "",
                SyncOptions.incremental().direction(Direction.BIDIRECTIONAL)
                        .conflictPolicy(ConflictPolicy.LEFT_WINS), null);
        assertEquals(1L, result.conflicts());
        assertEquals("FROM-LEFT", source.readString("b.txt"));
        assertEquals("FROM-LEFT", target.readString("b.txt"));
        assertEquals("目标 meta 保留胜者 mtime", mtime.toMillis(),
                Files.getLastModifiedTime(targetRoot.resolve("b.txt")).toMillis());
    }

    /** 在 side 根目录找以 prefix 开头的冲突副本并读出内容（恰好一个）。 */
    private static String conflictCopy(FileStorage side, String original, String prefix) {
        List<StorageEntry> matches = new java.util.ArrayList<StorageEntry>();
        for (StorageEntry entry : side.list("")) {
            String name = entry.name();
            if (name.startsWith(prefix) && name.endsWith(
                    original.substring(original.lastIndexOf('.') + 1))) {
                matches.add(entry);
            }
        }
        assertEquals("应恰好一个冲突副本 " + prefix, 1, matches.size());
        return side.readString(matches.get(0).path());
    }

    @Test
    public void maxErrorsAborts() {
        source.upload("a.txt", "A".getBytes(StandardCharsets.UTF_8));
        source.upload("b.txt", "B".getBytes(StandardCharsets.UTF_8));
        FileStorage brokenTarget = new FileStorage() {
            @Override
            public String platform() {
                return "broken";
            }

            @Override
            public boolean exists(String path) {
                return false;
            }

            @Override
            public com.alianga.jkit.storage.StorageEntry stat(String path) {
                throw com.alianga.jkit.storage.StorageException.notFound(path);
            }

            @Override
            public java.util.List<com.alianga.jkit.storage.StorageEntry> list(String dir) {
                return new java.util.ArrayList<com.alianga.jkit.storage.StorageEntry>();
            }

            @Override
            public void mkdir(String dir) {
            }

            @Override
            public void upload(String path, java.io.InputStream in, long size) {
                throw com.alianga.jkit.storage.StorageException.io("模拟失败", path, null);
            }

            @Override
            public void download(String path, java.io.OutputStream out) {
                throw com.alianga.jkit.storage.StorageException.notFound(path);
            }

            @Override
            public void delete(String path) {
            }

            @Override
            public void copy(String src, String dst) {
            }

            @Override
            public void rename(String src, String dst) {
            }
        };
        SyncResult result = SyncEngine.sync(source, "", brokenTarget, "",
                SyncOptions.incremental().maxErrors(1L), null);
        assertEquals(2L, result.failed());
        assertTrue(result.aborted());
        assertEquals("失败明细应与 failed 计数一致", 2, result.errors().size());
        assertTrue("明细应含相对路径: " + result.errors(), result.errors().get(0).startsWith("a.txt: "));
    }

    private static void deleteRecursively(Path root) throws Exception {
        if (!Files.exists(root)) {
            return;
        }
        try (java.util.stream.Stream<Path> walk = Files.walk(root)) {
            for (Path path : walk.sorted(java.util.Comparator.reverseOrder()).toArray(Path[]::new)) {
                Files.deleteIfExists(path);
            }
        }
    }
}
