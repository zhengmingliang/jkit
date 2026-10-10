package com.alianga.jkit.storage.sync;

import com.alianga.jkit.storage.FileStorage;
import com.alianga.jkit.storage.StorageEntry;
import com.alianga.jkit.storage.StorageErrorCode;
import com.alianga.jkit.storage.StorageException;
import com.alianga.jkit.storage.StoragePaths;
import com.alianga.jkit.thread.ExecutorServiceUtil;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 同步引擎：跨任意两个存储平台做内容对比、增量同步、镜像同步与全量迁移。
 *
 * <pre>{@code
 * FileStorage source = FileStorages.of("local", ...);
 * FileStorage target = FileStorages.of("oss", ...);
 * SyncResult result = SyncEngine.sync(source, "data", target, "backup",
 *         SyncOptions.incremental(), progressListener);
 * }</pre>
 *
 * <p>语义：以「相对路径」对齐两侧；复制前自动在目标端创建缺失的父目录；
 * 镜像模式只删除目标端多余的<b>文件</b>（不动目录占位）。对比策略见
 * {@link CompareMode}；执行并发可配，回调见 {@link SyncProgress}。</p>
 *
 * @author 郑明亮
 */
public final class SyncEngine {
    private SyncEngine() {
    }

    /**
     * 对比两个存储目录：按相对路径对齐，返回全部文件的差异清单（含 SAME）。
     *
     * @param left     左侧存储
     * @param leftDir  左侧目录，根目录传空串或 null
     * @param right    右侧存储
     * @param rightDir 右侧目录
     * @param mode     对比策略
     * @return 差异清单（文件级，不含目录）
     */
    public static List<StorageDiff> diff(FileStorage left, String leftDir,
                                         FileStorage right, String rightDir, CompareMode mode) {
        CompareMode compareMode = mode == null ? CompareMode.SIZE : mode;
        Map<String, StorageEntry> leftFiles = filesOf(left, leftDir);
        Map<String, StorageEntry> rightFiles = filesOf(right, rightDir);
        List<StorageDiff> diffs = new ArrayList<StorageDiff>();
        for (Map.Entry<String, StorageEntry> entry : leftFiles.entrySet()) {
            StorageEntry rightEntry = rightFiles.get(entry.getKey());
            if (rightEntry == null) {
                diffs.add(new StorageDiff(entry.getKey(), entry.getValue(), null, StorageDiff.Status.LEFT_ONLY));
            } else if (isSame(entry.getValue(), rightEntry, compareMode)) {
                diffs.add(new StorageDiff(entry.getKey(), entry.getValue(), rightEntry, StorageDiff.Status.SAME));
            } else {
                diffs.add(new StorageDiff(entry.getKey(), entry.getValue(), rightEntry, StorageDiff.Status.DIFFERENT));
            }
        }
        for (Map.Entry<String, StorageEntry> entry : rightFiles.entrySet()) {
            if (!leftFiles.containsKey(entry.getKey())) {
                diffs.add(new StorageDiff(entry.getKey(), null, entry.getValue(), StorageDiff.Status.RIGHT_ONLY));
            }
        }
        diffs.sort((a, b) -> a.relativePath().compareTo(b.relativePath()));
        return diffs;
    }

    /**
     * 执行同步：把 source/sourceDir 增量或全量同步到 target/targetDir。
     *
     * @param source    源存储
     * @param sourceDir 源目录
     * @param target    目标存储
     * @param targetDir 目标目录
     * @param options   选项
     * @param progress  进度回调，可为 null
     * @return 执行结果
     */
    public static SyncResult sync(FileStorage source, String sourceDir, FileStorage target,
                                  String targetDir, SyncOptions options, SyncProgress progress) {
        SyncOptions opts = options == null ? SyncOptions.incremental() : options;
        Map<String, StorageEntry> sourceFiles = filesOf(source, sourceDir);
        Map<String, StorageEntry> targetFiles = filesOf(target, targetDir);

        List<String> toCopy = new ArrayList<String>();
        List<String> toDelete = new ArrayList<String>();
        long totalBytes = 0L;
        long skipped = 0L;
        for (Map.Entry<String, StorageEntry> entry : sourceFiles.entrySet()) {
            if (!opts.accepts(entry.getKey())) {
                continue;
            }
            StorageEntry targetEntry = targetFiles.get(entry.getKey());
            if (targetEntry != null && isSame(entry.getValue(), targetEntry, opts.compareMode())) {
                skipped++;
            } else {
                toCopy.add(entry.getKey());
                totalBytes += entry.getValue().size() < 0 ? 0L : entry.getValue().size();
            }
        }
        if (opts.isMirror()) {
            for (String relative : targetFiles.keySet()) {
                if (!sourceFiles.containsKey(relative) && opts.accepts(relative)) {
                    toDelete.add(relative);
                }
            }
        }
        long totalActions = toCopy.size() + toDelete.size();
        if (progress != null) {
            progress.onStart(totalActions, totalBytes);
        }
        if (opts.isDryRun()) {
            return new SyncResult(toCopy.size(), skipped, toDelete.size(), 0L, totalBytes, false);
        }

        prepareDirs(source, sourceDir, target, targetDir, opts, progress);

        AtomicLong copied = new AtomicLong();
        AtomicLong deleted = new AtomicLong();
        AtomicLong failed = new AtomicLong();
        AtomicLong bytes = new AtomicLong();
        AtomicBoolean aborted = new AtomicBoolean();
        List<String> errors = java.util.Collections.synchronizedList(new ArrayList<String>());

        if (opts.concurrency() <= 1) {
            for (String relative : toCopy) {
                if (aborted.get() || cancelled(opts)) {
                    break;
                }
                copyOne(source, sourceDir, target, targetDir, relative, opts, progress,
                        copied, failed, bytes, errors, aborted);
            }
            for (String relative : toDelete) {
                if (aborted.get() || cancelled(opts)) {
                    break;
                }
                deleteOne(target, targetDir, relative, opts, progress, deleted, failed, errors, aborted);
            }
        } else {
            runParallel(opts, source, sourceDir, target, targetDir, toCopy, toDelete, progress,
                    copied, deleted, failed, bytes, errors, aborted);
        }
        boolean abortedFinal = aborted.get() || cancelled(opts);
        return new SyncResult(copied.get(), skipped, deleted.get(), failed.get(), bytes.get(),
                abortedFinal);
    }

    private static boolean cancelled(SyncOptions opts) {
        return opts.cancelToken() != null && opts.cancelToken().isCancelled();
    }

    private static void runParallel(SyncOptions opts, FileStorage source, String sourceDir,
                                    FileStorage target, String targetDir, List<String> toCopy,
                                    List<String> toDelete, SyncProgress progress, AtomicLong copied,
                                    AtomicLong deleted, AtomicLong failed, AtomicLong bytes,
                                    List<String> errors, AtomicBoolean aborted) {
        ExecutorService pool = Executors.newFixedThreadPool(opts.concurrency(),
                ExecutorServiceUtil.getDefaultThreadFactory());
        try {
            List<Future<?>> futures = new ArrayList<Future<?>>();
            for (final String relative : toCopy) {
                futures.add(pool.submit(new Runnable() {
                    @Override
                    public void run() {
                        if (aborted.get() || cancelled(opts)) {
                            return;
                        }
                        copyOne(source, sourceDir, target, targetDir, relative, opts, progress,
                                copied, failed, bytes, errors, aborted);
                    }
                }));
            }
            for (final String relative : toDelete) {
                futures.add(pool.submit(new Runnable() {
                    @Override
                    public void run() {
                        if (aborted.get() || cancelled(opts)) {
                            return;
                        }
                        deleteOne(target, targetDir, relative, opts, progress, deleted, failed, errors, aborted);
                    }
                }));
            }
            for (Future<?> future : futures) {
                try {
                    future.get();
                } catch (java.lang.InterruptedException e) {
                    Thread.currentThread().interrupt();
                    aborted.set(true);
                } catch (java.util.concurrent.ExecutionException ignore) {
                    // 任务内部已把异常计入 failed / errors
                }
            }
        } finally {
            pool.shutdownNow();
        }
    }

    /** 内存中转阈值：超过该大小的文件同步走临时文件，避免占满堆。 */
    private static final long MEMORY_LIMIT = 32L * 1024L * 1024L;

    private static void copyOne(FileStorage source, String sourceDir, FileStorage target,
                                String targetDir, String relative, SyncOptions opts,
                                SyncProgress progress, AtomicLong copied, AtomicLong failed,
                                AtomicLong bytes, List<String> errors, AtomicBoolean aborted) {
        java.nio.file.Path temp = null;
        try {
            StorageEntry entry = source.stat(StoragePaths.join(sourceDir, relative));
            long size = entry.size() < 0 ? 0L : entry.size();
            if (size > MEMORY_LIMIT) {
                temp = java.nio.file.Files.createTempFile("jkit-storage-sync", ".tmp");
                try (java.io.OutputStream fileOut = java.nio.file.Files.newOutputStream(temp)) {
                    source.download(StoragePaths.join(sourceDir, relative), fileOut);
                }
                try (java.io.InputStream fileIn = java.nio.file.Files.newInputStream(temp)) {
                    if (opts.isPreserveMtime() && entry.lastModified() != StorageEntry.MTIME_UNKNOWN) {
                        target.upload(StoragePaths.join(targetDir, relative), fileIn,
                                java.nio.file.Files.size(temp),
                                com.alianga.jkit.storage.UploadMetadata.mtime(entry.lastModified()));
                    } else {
                        target.upload(StoragePaths.join(targetDir, relative), fileIn,
                                java.nio.file.Files.size(temp));
                    }
                }
                bytes.addAndGet(java.nio.file.Files.size(temp));
            } else {
                java.io.ByteArrayOutputStream buffer =
                        new java.io.ByteArrayOutputStream((int) Math.max(size, 64L));
                source.download(StoragePaths.join(sourceDir, relative), buffer);
                byte[] data = buffer.toByteArray();
                if (opts.isPreserveMtime() && entry.lastModified() != StorageEntry.MTIME_UNKNOWN) {
                    target.upload(StoragePaths.join(targetDir, relative), data,
                            com.alianga.jkit.storage.UploadMetadata.mtime(entry.lastModified()));
                } else {
                    target.upload(StoragePaths.join(targetDir, relative), data);
                }
                bytes.addAndGet(data.length);
            }
            copied.incrementAndGet();
            if (progress != null) {
                progress.onFileDone(relative, size, "copy");
            }
        } catch (Exception e) {
            failed.incrementAndGet();
            errors.add(relative + ": " + e.getMessage());
            if (progress != null) {
                progress.onFileError(relative, e);
            }
            if (failed.get() >= opts.maxErrors()) {
                aborted.set(true);
            }
        } finally {
            if (temp != null) {
                try {
                    java.nio.file.Files.deleteIfExists(temp);
                } catch (java.io.IOException ignore) {
                    // 临时文件清理失败不影响结果
                }
            }
        }
    }

    private static void deleteOne(FileStorage target, String targetDir, String relative,
                                  SyncOptions opts, SyncProgress progress, AtomicLong deleted,
                                  AtomicLong failed, List<String> errors, AtomicBoolean aborted) {
        try {
            target.delete(StoragePaths.join(targetDir, relative));
            deleted.incrementAndGet();
            if (progress != null) {
                progress.onFileDone(relative, 0L, "delete");
            }
        } catch (Exception e) {
            failed.incrementAndGet();
            errors.add(relative + ": " + e.getMessage());
            if (progress != null) {
                progress.onFileError(relative, e);
            }
            if (failed.get() >= opts.maxErrors()) {
                aborted.set(true);
            }
        }
    }

    /**
     * 预建目标端目录：源端目录按层级在目标端补齐（对象存储写目录占位、
     * 本地建真实目录、WebDAV MKCOL），复制阶段无需再关心父目录。
     */
    private static void prepareDirs(FileStorage source, String sourceDir, FileStorage target,
                                    String targetDir, SyncOptions opts, SyncProgress progress) {
        List<String> dirs = new ArrayList<String>();
        for (StorageEntry entry : source.walk(sourceDir)) {
            if (!entry.isDirectory()) {
                continue;
            }
            String relative = StoragePaths.relativize(sourceDir, entry.path());
            if (relative == null || relative.isEmpty() || !opts.accepts(relative)) {
                continue;
            }
            dirs.add(relative);
        }
        dirs.sort((a, b) -> {
            int depthA = a.split("/").length;
            int depthB = b.split("/").length;
            return depthA == depthB ? a.compareTo(b) : depthA - depthB;
        });
        for (String relative : dirs) {
            String targetPath = StoragePaths.join(targetDir, relative);
            try {
                if (!target.exists(targetPath)) {
                    target.mkdir(targetPath);
                }
            } catch (Exception e) {
                throw StorageException.io("创建目标目录失败: " + targetPath
                        + "（" + e.getMessage() + "）", targetPath, e);
            }
        }
    }

    private static Map<String, StorageEntry> filesOf(FileStorage storage, String dir) {
        Map<String, StorageEntry> files = new LinkedHashMap<String, StorageEntry>();
        String normalized = StoragePaths.normalize(dir);
        List<StorageEntry> walked;
        try {
            walked = storage.walk(normalized);
        } catch (StorageException e) {
            if (e.code() == StorageErrorCode.NOT_FOUND) {
                return files;
            }
            throw e;
        }
        for (StorageEntry entry : walked) {
            if (entry.isDirectory()) {
                continue;
            }
            String relative = StoragePaths.relativize(normalized, entry.path());
            if (relative != null && !relative.isEmpty()) {
                files.put(relative, entry);
            }
        }
        return files;
    }

    private static boolean isSame(StorageEntry left, StorageEntry right, CompareMode mode) {
        if (mode == CompareMode.ALWAYS) {
            return false;
        }
        if (left.size() != right.size()) {
            return false;
        }
        if (mode == CompareMode.SIZE_AND_ETAG) {
            if (left.etag() != null && right.etag() != null) {
                return left.etag().equalsIgnoreCase(right.etag());
            }
            return true;
        }
        if (mode == CompareMode.SIZE_AND_MTIME) {
            if (left.lastModified() != StorageEntry.MTIME_UNKNOWN
                    && right.lastModified() != StorageEntry.MTIME_UNKNOWN) {
                return left.lastModified() == right.lastModified();
            }
            return true;
        }
        return true;
    }
}
