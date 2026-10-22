package com.alianga.jkit.storage.sync;

import com.alianga.jkit.storage.FileStorage;
import com.alianga.jkit.storage.StorageEntry;
import com.alianga.jkit.storage.StorageException;

import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * 同步引擎对「大小未知」条目的判定：{@code size = -1} 表示平台没给大小，
 * 不等于「大小真的是 -1」。
 *
 * <p>场景来源：Gitee 的 contents API 列表不返回 size。GitHub → Gitee 同步时，
 * 两侧内容完全一致的文件（ETag / blob SHA 相同）被全部判成 DIFFERENT，
 * 91 个文件退化成全量重传。本测试按调用方实际用法（{@link SyncEngine#diff}）
 * 钉住修复后的行为。</p>
 *
 * @author 郑明亮
 */
public class SyncEngineUnknownSizeTest {

    /** 可编程的双侧桩存储：条目由测试直接给出。 */
    private static final class StubStorage implements FileStorage {
        private final Map<String, StorageEntry> files = new LinkedHashMap<>();

        StubStorage add(StorageEntry entry) {
            files.put(entry.path(), entry);
            return this;
        }

        @Override
        public String platform() {
            return "stub";
        }

        @Override
        public boolean exists(String path) {
            return files.containsKey(norm(path));
        }

        @Override
        public StorageEntry stat(String path) {
            StorageEntry entry = files.get(norm(path));
            if (entry == null) {
                throw StorageException.notFound(path);
            }
            return entry;
        }

        @Override
        public List<StorageEntry> list(String dir) {
            String prefix = norm(dir);
            List<StorageEntry> result = new ArrayList<>();
            for (StorageEntry entry : files.values()) {
                String parent = parentOf(entry.path());
                if (parent.equals(prefix)) {
                    result.add(entry);
                }
            }
            return result;
        }

        @Override
        public void mkdir(String dir) {
            // 桩存储不建实体目录（list 按路径推导父目录）
        }

        @Override
        public void upload(String path, InputStream in, long size) {
            files.put(norm(path), StorageEntry.file(norm(path), size, StorageEntry.MTIME_UNKNOWN, null));
        }

        @Override
        public void download(String path, OutputStream out) {
            try {
                out.write(new byte[0]);
            } catch (java.io.IOException e) {
                throw StorageException.io("桩存储下载失败", path, e);
            }
        }

        @Override
        public void delete(String path) {
            files.remove(norm(path));
        }

        @Override
        public void copy(String src, String dst) {
            files.put(norm(dst), stat(src));
        }

        @Override
        public void rename(String src, String dst) {
            StorageEntry entry = files.remove(norm(src));
            if (entry != null) {
                files.put(norm(dst), entry);
            }
        }

        private static String norm(String path) {
            return path == null ? "" : path.replace('\\', '/').replaceAll("^/+", "").replaceAll("/+$", "");
        }

        private static String parentOf(String path) {
            int at = path.lastIndexOf('/');
            return at < 0 ? "" : path.substring(0, at);
        }
    }

    private static StorageEntry file(String path, long size, String etag) {
        return StorageEntry.file(path, size, StorageEntry.MTIME_UNKNOWN, etag);
    }

    private static StorageDiff.Status statusOf(List<StorageDiff> diffs, String path) {
        for (StorageDiff diff : diffs) {
            if (diff.relativePath().equals(path)) {
                return diff.status();
            }
        }
        throw new AssertionError("差异清单里没有: " + path);
    }

    @Test
    public void unknownSizeWithEqualEtagIsSame() {
        // Gitee 场景：一侧有真实大小、另一侧 size=-1，但 ETag（blob SHA）相同
        StubStorage left = new StubStorage().add(file("a.png", 13701, "sha-aaa"));
        StubStorage right = new StubStorage().add(file("a.png", -1L, "sha-aaa"));
        for (CompareMode mode : new CompareMode[]{CompareMode.SIZE, CompareMode.SIZE_AND_ETAG,
                CompareMode.SIZE_AND_MTIME}) {
            List<StorageDiff> diffs = SyncEngine.diff(left, "", right, "", mode);
            assertEquals("模式 " + mode + " 下应判为相同",
                    StorageDiff.Status.SAME, statusOf(diffs, "a.png"));
        }
    }

    @Test
    public void unknownSizeWithDifferentEtagIsDifferent() {
        StubStorage left = new StubStorage().add(file("a.png", 13701, "sha-aaa"));
        StubStorage right = new StubStorage().add(file("a.png", -1L, "sha-bbb"));
        List<StorageDiff> diffs = SyncEngine.diff(left, "", right, "", CompareMode.SIZE);
        assertEquals(StorageDiff.Status.DIFFERENT, statusOf(diffs, "a.png"));
    }

    @Test
    public void unknownSizeWithoutEtagsStaysDifferent() {
        // 两侧都没有 ETag：保守判不同。漏同步会丢数据，重传一遍只是多花时间
        StubStorage left = new StubStorage().add(file("a.png", 13701, null));
        StubStorage right = new StubStorage().add(file("a.png", -1L, null));
        List<StorageDiff> diffs = SyncEngine.diff(left, "", right, "", CompareMode.SIZE);
        assertEquals(StorageDiff.Status.DIFFERENT, statusOf(diffs, "a.png"));
    }

    @Test
    public void bothSizesUnknownWithEqualEtagIsSame() {
        StubStorage left = new StubStorage().add(file("a.png", -1L, "sha-same"));
        StubStorage right = new StubStorage().add(file("a.png", -1L, "sha-same"));
        List<StorageDiff> diffs = SyncEngine.diff(left, "", right, "", CompareMode.SIZE);
        assertEquals(StorageDiff.Status.SAME, statusOf(diffs, "a.png"));
    }

    @Test
    public void knownSizesStillComparedBySizeOnly() {
        // 回归护栏：两侧大小都已知时，行为必须和改动前完全一致。
        // 大小不同即使 ETag 相同也算不同——S3 分片上传的 ETag 不是内容 MD5，不能信。
        StubStorage left = new StubStorage().add(file("a.png", 100L, "etag-same"));
        StubStorage right = new StubStorage().add(file("a.png", 200L, "etag-same"));
        List<StorageDiff> diffs = SyncEngine.diff(left, "", right, "", CompareMode.SIZE);
        assertEquals("大小已知且不等时必须判不同（不因 ETag 相同而判同）",
                StorageDiff.Status.DIFFERENT, statusOf(diffs, "a.png"));

        StubStorage left2 = new StubStorage().add(file("b.png", 100L, "etag-same"));
        StubStorage right2 = new StubStorage().add(file("b.png", 100L, "etag-same"));
        List<StorageDiff> same = SyncEngine.diff(left2, "", right2, "", CompareMode.SIZE);
        assertEquals(StorageDiff.Status.SAME, statusOf(same, "b.png"));
    }

    @Test
    public void knownSizeDifferentButEtagModeStillChecksEtag() {
        // SIZE_AND_ETAG：大小相同但 ETag 不同 → 不同（原有语义保留）
        StubStorage left = new StubStorage().add(file("a.png", 100L, "etag-1"));
        StubStorage right = new StubStorage().add(file("a.png", 100L, "etag-2"));
        List<StorageDiff> diffs = SyncEngine.diff(left, "", right, "", CompareMode.SIZE_AND_ETAG);
        assertEquals(StorageDiff.Status.DIFFERENT, statusOf(diffs, "a.png"));
    }

    @Test
    public void alwaysModeReportsEverythingDifferent() {
        StubStorage left = new StubStorage().add(file("a.png", -1L, "sha-same"));
        StubStorage right = new StubStorage().add(file("a.png", -1L, "sha-same"));
        List<StorageDiff> diffs = SyncEngine.diff(left, "", right, "", CompareMode.ALWAYS);
        assertEquals("ALWAYS 模式无条件视为不同",
                StorageDiff.Status.DIFFERENT, statusOf(diffs, "a.png"));
    }

    @Test
    public void rightOnlyStillDetectedAlongsideUnknownSize() {
        StubStorage left = new StubStorage().add(file("a.png", -1L, "sha-a"));
        StubStorage right = new StubStorage()
                .add(file("a.png", -1L, "sha-a"))
                .add(file("extra.png", -1L, "sha-x"));
        List<StorageDiff> diffs = SyncEngine.diff(left, "", right, "", CompareMode.SIZE);
        assertEquals(StorageDiff.Status.SAME, statusOf(diffs, "a.png"));
        assertEquals(StorageDiff.Status.RIGHT_ONLY, statusOf(diffs, "extra.png"));
        assertTrue(diffs.size() == 2);
    }
}
