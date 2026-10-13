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
 * 同步引擎：跨任意两个存储平台做内容对比、增量同步、镜像同步与全量迁移，
 * 并支持双向同步（两侧互为补充 + 冲突策略；双方都有基线清单后传播删除）。
 *
 * <pre>{@code
 * FileStorage source = FileStorages.of("local", ...);
 * FileStorage target = FileStorages.of("oss", ...);
 * SyncResult result = SyncEngine.sync(source, "data", target, "backup",
 *         SyncOptions.incremental(), progressListener);
 * }</pre>
 *
 * <p>单向语义：以「相对路径」对齐两侧；复制前自动在目标端创建缺失的父目录；
 * 镜像模式删除目标端多余文件与多余目录（目录在全部文件处理完后按深路径
 * 在前删除）。对比策略见 {@link CompareMode}；执行并发可配，回调见
 * {@link SyncProgress}。</p>
 *
 * <p>双向语义见 {@link #syncBidirectional}。</p>
 *
 * @author 郑明亮
 */
public final class SyncEngine {
    private SyncEngine() {
    }

    /**
     * 对比两个存储目录：按相对路径对齐，返回全部文件的差异清单（含 SAME）。
     *
     * <p>任一侧目录不存在时抛 {@link StorageErrorCode#NOT_FOUND}——静默返回
     * 空清单会让路径拼写错误看起来像「没有差异」。</p>
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
        requireDir(left, leftDir, "左侧目录");
        requireDir(right, rightDir, "右侧目录");
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
     * <p>options 指定 {@link Direction#BIDIRECTIONAL} 时走双向同步：两侧互为补充，
     * 缺失侧补齐，同名且不一致的文件按 {@link SyncOptions#conflictPolicy()} 处理。</p>
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
        if (opts.direction() == Direction.BIDIRECTIONAL) {
            return syncBidirectional(source, sourceDir, target, targetDir, opts, progress);
        }
        // 源目录必须存在：镜像模式下源路径写错会把目标端全部当成「多余」删光
        requireDir(source, sourceDir, "源目录");
        Map<String, StorageEntry> sourceFiles = filesOf(source, sourceDir);
        Map<String, StorageEntry> targetFiles = filesOf(target, targetDir);

        List<String> toCopy = new ArrayList<String>();
        List<String> toDelete = new ArrayList<String>();
        List<String> toDeleteDirs = new ArrayList<String>();
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
            // 镜像时目标端多余的目录也删（先文件后目录、深路径在前，
            // 否则对非空目录 delete 会 CONFLICT）
            Map<String, StorageEntry> sourceDirs = dirsOf(source, sourceDir);
            List<String> extraDirs = new ArrayList<String>();
            for (String relative : dirsOf(target, targetDir).keySet()) {
                if (!sourceDirs.containsKey(relative) && opts.accepts(relative)) {
                    extraDirs.add(relative);
                }
            }
            extraDirs.sort((a, b) -> {
                int depthA = a.split("/").length;
                int depthB = b.split("/").length;
                return depthA == depthB ? b.compareTo(a) : depthB - depthA;
            });
            toDeleteDirs.addAll(extraDirs);
        }
        long totalActions = toCopy.size() + toDelete.size() + toDeleteDirs.size();
        if (progress != null) {
            progress.onStart(totalActions, totalBytes);
        }
        if (opts.isDryRun()) {
            return new SyncResult(toCopy.size(), skipped, toDelete.size() + toDeleteDirs.size(),
                    0L, totalBytes, false, new ArrayList<String>());
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
                copyOne(source, sourceDir, target, targetDir, relative, relative, opts, progress,
                        copied, failed, bytes, errors, aborted);
            }
            for (String relative : toDelete) {
                if (aborted.get() || cancelled(opts)) {
                    break;
                }
                deleteOne(target, targetDir, relative, opts, progress, deleted, failed, errors, aborted);
            }
            for (String relative : toDeleteDirs) {
                if (aborted.get() || cancelled(opts)) {
                    break;
                }
                deleteOne(target, targetDir, relative, opts, progress, deleted, failed, errors, aborted);
            }
        } else {
            runParallel(opts, source, sourceDir, target, targetDir, toCopy, toDelete, toDeleteDirs,
                    progress, copied, deleted, failed, bytes, errors, aborted);
        }
        boolean abortedFinal = aborted.get() || cancelled(opts);
        return new SyncResult(copied.get(), skipped, deleted.get(), failed.get(), bytes.get(),
                abortedFinal, errors);
    }

    private static boolean cancelled(SyncOptions opts) {
        return opts.cancelToken() != null && opts.cancelToken().isCancelled();
    }

    /**
     * 双向同步：两侧互为补充。仅一侧存在的文件复制到对侧；两侧都在但判定不同
     * （复用 {@link #isSame}）的文件按冲突策略处理。
     *
     * <p>删除只在两侧都有合法的 {@link SyncManifest} 时发生：路径上次两边都有，
     * 这次只剩一边，且剩下的文件没有比清单更新，就删掉剩下的那一边。
     * 清单缺失、损坏，或剩下的文件更新过，都不删除（更新过的文件复制回去）。
     * {@code mirror(true)} 在双向模式下仍然忽略。</p>
     */
    private static SyncResult syncBidirectional(FileStorage left, String leftDir,
                                                FileStorage right, String rightDir,
                                                SyncOptions opts, SyncProgress progress) {
        Map<String, StorageEntry> leftFiles = filesOf(left, leftDir);
        Map<String, StorageEntry> rightFiles = filesOf(right, rightDir);
        Map<String, Long> leftManifest = SyncManifest.read(left, leftDir);
        Map<String, Long> rightManifest = SyncManifest.read(right, rightDir);
        boolean trackDeletes = leftManifest != null && rightManifest != null;

        List<String> leftToRight = new ArrayList<String>();
        List<String> rightToLeft = new ArrayList<String>();
        List<String> deleteOnLeft = new ArrayList<String>();
        List<String> deleteOnRight = new ArrayList<String>();
        List<String> conflictPaths = new ArrayList<String>();
        long skipped = 0L;
        long totalBytes = 0L;
        for (Map.Entry<String, StorageEntry> entry : leftFiles.entrySet()) {
            if (!opts.accepts(entry.getKey())) {
                continue;
            }
            StorageEntry rightEntry = rightFiles.get(entry.getKey());
            if (rightEntry == null) {
                if (deletionWins(entry.getKey(), entry.getValue(), leftManifest, rightManifest, trackDeletes)) {
                    deleteOnLeft.add(entry.getKey());
                } else {
                    leftToRight.add(entry.getKey());
                    totalBytes += sizeOrZero(entry.getValue());
                }
            } else if (isSame(entry.getValue(), rightEntry, opts.compareMode())) {
                skipped++;
            } else {
                conflictPaths.add(entry.getKey());
            }
        }
        for (Map.Entry<String, StorageEntry> entry : rightFiles.entrySet()) {
            if (!opts.accepts(entry.getKey()) || leftFiles.containsKey(entry.getKey())) {
                continue;
            }
            if (deletionWins(entry.getKey(), entry.getValue(), rightManifest, leftManifest, trackDeletes)) {
                deleteOnRight.add(entry.getKey());
            } else {
                rightToLeft.add(entry.getKey());
                totalBytes += sizeOrZero(entry.getValue());
            }
        }

        long totalActions = leftToRight.size() + rightToLeft.size() + conflictPaths.size()
                + deleteOnLeft.size() + deleteOnRight.size();
        if (progress != null) {
            progress.onStart(totalActions, totalBytes);
        }
        if (opts.isDryRun()) {
            return new SyncResult(leftToRight.size() + rightToLeft.size(), skipped,
                    deleteOnLeft.size() + deleteOnRight.size(), 0L,
                    totalBytes, false, new ArrayList<String>(), conflictPaths.size());
        }

        // 双侧都要能接收对方的文件（含冲突副本名），先把缺失的父目录补齐
        if (!leftToRight.isEmpty() || !conflictPaths.isEmpty()) {
            prepareDirs(left, leftDir, right, rightDir, opts, progress);
        }
        if (!rightToLeft.isEmpty() || !conflictPaths.isEmpty()) {
            prepareDirs(right, rightDir, left, leftDir, opts, progress);
        }

        AtomicLong copied = new AtomicLong();
        AtomicLong deleted = new AtomicLong();
        AtomicLong failed = new AtomicLong();
        AtomicLong bytes = new AtomicLong();
        AtomicLong conflicts = new AtomicLong();
        AtomicBoolean aborted = new AtomicBoolean();
        List<String> errors = java.util.Collections.synchronizedList(new ArrayList<String>());

        for (String relative : deleteOnLeft) {
            if (aborted.get() || cancelled(opts)) {
                break;
            }
            deleteOne(left, leftDir, relative, opts, progress, deleted, failed, errors, aborted);
        }
        for (String relative : deleteOnRight) {
            if (aborted.get() || cancelled(opts)) {
                break;
            }
            deleteOne(right, rightDir, relative, opts, progress, deleted, failed, errors, aborted);
        }

        List<String> noDeletes = new ArrayList<String>();
        if (!leftToRight.isEmpty()) {
            runParallel(opts, left, leftDir, right, rightDir, leftToRight, noDeletes, noDeletes,
                    progress, copied, deleted, failed, bytes, errors, aborted);
        }
        if (!rightToLeft.isEmpty()) {
            runParallel(opts, right, rightDir, left, leftDir, rightToLeft, noDeletes, noDeletes,
                    progress, copied, deleted, failed, bytes, errors, aborted);
        }
        for (String relative : conflictPaths) {
            if (aborted.get() || cancelled(opts)) {
                break;
            }
            resolveConflict(left, leftDir, right, rightDir, relative,
                    leftFiles.get(relative), rightFiles.get(relative), opts, progress,
                    copied, failed, bytes, conflicts, errors, aborted);
        }
        boolean abortedFinal = aborted.get() || cancelled(opts);
        if (!abortedFinal) {
            // 复制完成后再清空目录，避免把即将写入新文件的父目录先删掉
            deleteEmptyParents(left, leftDir, deleteOnLeft);
            deleteEmptyParents(right, rightDir, deleteOnRight);
            try {
                SyncManifest.write(left, leftDir, filesOf(left, leftDir));
                SyncManifest.write(right, rightDir, filesOf(right, rightDir));
            } catch (RuntimeException e) {
                errors.add(SyncManifest.FILE + ": " + e.getMessage());
            }
        }
        return new SyncResult(copied.get(), skipped, deleted.get(), failed.get(), bytes.get(),
                abortedFinal, errors, conflicts.get());
    }

    /**
     * 剩下的这一侧是不是「对端已经删掉的旧文件」。
     * 只有上次两边清单都有这条路径，且现存文件不比清单新，删除才赢。
     */
    private static boolean deletionWins(String relative, StorageEntry surviving,
                                        Map<String, Long> survivingManifest,
                                        Map<String, Long> missingManifest, boolean trackDeletes) {
        if (!trackDeletes || survivingManifest == null || missingManifest == null) {
            return false;
        }
        if (!survivingManifest.containsKey(relative) || !missingManifest.containsKey(relative)) {
            return false;
        }
        Long recorded = survivingManifest.get(relative);
        long seen = surviving.lastModified();
        if (recorded == null || recorded.longValue() == StorageEntry.MTIME_UNKNOWN
                || seen == StorageEntry.MTIME_UNKNOWN) {
            return true;
        }
        return seen <= recorded.longValue();
    }

    /**
     * 文件删掉后清掉已经空了的父目录。先列出子项，非空就不删
     * （部分平台的 delete 对目录是递归的）。
     */
    private static void deleteEmptyParents(FileStorage storage, String root, List<String> files) {
        List<String> parents = new ArrayList<String>();
        for (int i = 0; i < files.size(); i++) {
            String parent = StoragePaths.parent(files.get(i));
            while (parent != null && !parent.isEmpty()) {
                if (!parents.contains(parent) && !SyncManifest.isMeta(parent)) {
                    parents.add(parent);
                }
                parent = StoragePaths.parent(parent);
            }
        }
        parents.sort((a, b) -> {
            int depthA = a.split("/").length;
            int depthB = b.split("/").length;
            return depthA == depthB ? b.compareTo(a) : depthB - depthA;
        });
        for (int i = 0; i < parents.size(); i++) {
            String full = StoragePaths.join(root, parents.get(i));
            try {
                if (!storage.list(full).isEmpty()) {
                    continue;
                }
                storage.delete(full);
            } catch (RuntimeException ignore) {
                // 目录不存在，或平台拒绝删除
            }
        }
    }

    /**
     * 处理单个冲突文件：按策略覆盖或保留双份，冲突计数与回调在成功处理后触发。
     */
    private static void resolveConflict(FileStorage left, String leftDir, FileStorage right,
                                        String rightDir, String relative, StorageEntry leftEntry,
                                        StorageEntry rightEntry, SyncOptions opts,
                                        SyncProgress progress, AtomicLong copied, AtomicLong failed,
                                        AtomicLong bytes, AtomicLong conflicts, List<String> errors,
                                        AtomicBoolean aborted) {
        try {
            ConflictPolicy policy = opts.conflictPolicy();
            String action;
            String detail;
            if (policy == ConflictPolicy.LEFT_WINS) {
                copyOne(left, leftDir, right, rightDir, relative, relative, opts, progress,
                        copied, failed, bytes, errors, aborted);
                action = "left-wins";
                detail = "以左侧为准";
            } else if (policy == ConflictPolicy.RIGHT_WINS) {
                copyOne(right, rightDir, left, leftDir, relative, relative, opts, progress,
                        copied, failed, bytes, errors, aborted);
                action = "right-wins";
                detail = "以右侧为准";
            } else if (policy == ConflictPolicy.KEEP_BOTH) {
                String stamp = conflictTimestamp();
                keepBoth(left, leftDir, right, rightDir, relative,
                        "conflict-left-" + stamp, "conflict-right-" + stamp,
                        opts, progress, copied, failed, bytes, errors, aborted);
                action = "keep-both";
                detail = "双方版本各自保留，对方版本以 conflict 副本名存放";
            } else {
                long leftMtime = leftEntry.lastModified();
                long rightMtime = rightEntry.lastModified();
                if (leftMtime != StorageEntry.MTIME_UNKNOWN
                        && rightMtime != StorageEntry.MTIME_UNKNOWN
                        && leftMtime != rightMtime) {
                    boolean leftNewer = leftMtime > rightMtime;
                    if (leftNewer) {
                        copyOne(left, leftDir, right, rightDir, relative, relative, opts, progress,
                                copied, failed, bytes, errors, aborted);
                    } else {
                        copyOne(right, rightDir, left, leftDir, relative, relative, opts, progress,
                                copied, failed, bytes, errors, aborted);
                    }
                    action = leftNewer ? "left-wins" : "right-wins";
                    detail = "新者胜：左 " + leftMtime + " / 右 " + rightMtime;
                } else {
                    String stamp = conflictTimestamp();
                    keepBoth(left, leftDir, right, rightDir, relative,
                            "conflict-" + stamp, "conflict-" + stamp,
                            opts, progress, copied, failed, bytes, errors, aborted);
                    action = "keep-both";
                    detail = "修改时间未知或相等，无法判定新旧，保留双份";
                }
            }
            conflicts.incrementAndGet();
            SyncConflictListener listener = opts.conflictListener();
            if (listener != null) {
                listener.onConflict(relative, action, detail);
            }
            if (progress != null) {
                progress.onFileDone(relative, 0L, "conflict");
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

    /** 保留双份：对方版本以 conflict 副本名复制过来，本侧原文件原地不动。 */
    private static void keepBoth(FileStorage left, String leftDir, FileStorage right,
                                 String rightDir, String relative, String leftSuffix,
                                 String rightSuffix, SyncOptions opts, SyncProgress progress,
                                 AtomicLong copied, AtomicLong failed, AtomicLong bytes,
                                 List<String> errors, AtomicBoolean aborted) {
        copyOne(left, leftDir, right, rightDir, relative, conflictedName(relative, leftSuffix),
                opts, progress, copied, failed, bytes, errors, aborted);
        copyOne(right, rightDir, left, leftDir, relative, conflictedName(relative, rightSuffix),
                opts, progress, copied, failed, bytes, errors, aborted);
    }

    /** 冲突副本名：xxx.txt → xxx.<suffix>.txt（无扩展名的原样追加）。 */
    private static String conflictedName(String relative, String suffix) {
        int slash = relative.lastIndexOf('/');
        String dir = slash < 0 ? "" : relative.substring(0, slash + 1);
        String name = slash < 0 ? relative : relative.substring(slash + 1);
        int dot = name.lastIndexOf('.');
        String stem = dot > 0 ? name.substring(0, dot) : name;
        String ext = dot > 0 ? name.substring(dot) : "";
        return dir + stem + "." + suffix + ext;
    }

    private static String conflictTimestamp() {
        return new java.text.SimpleDateFormat("yyyyMMdd-HHmmss", java.util.Locale.US)
                .format(new java.util.Date());
    }

    private static long sizeOrZero(StorageEntry entry) {
        return entry.size() < 0 ? 0L : entry.size();
    }

    private static void runParallel(SyncOptions opts, FileStorage source, String sourceDir,
                                    FileStorage target, String targetDir, List<String> toCopy,
                                    List<String> toDelete, List<String> toDeleteDirs,
                                    SyncProgress progress, AtomicLong copied,
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
                        copyOne(source, sourceDir, target, targetDir, relative, relative, opts,
                                progress, copied, failed, bytes, errors, aborted);
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
            awaitAll(futures, opts, aborted);
            // 目录必须按深路径在前串行删。并行时父目录可能先被删掉，子目录随后失败。
            for (String relative : toDeleteDirs) {
                if (aborted.get() || cancelled(opts)) {
                    break;
                }
                deleteOne(target, targetDir, relative, opts, progress, deleted, failed, errors, aborted);
            }
        } finally {
            pool.shutdownNow();
        }
    }

    private static void awaitAll(List<Future<?>> futures, SyncOptions opts, AtomicBoolean aborted) {
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
    }

    /**
     * 同一次同步里最多 concurrency 路并发缓冲；在 -Xmx256m 下用 8MiB 上限，
     * 给堆与其他操作留余量。大小未知（{@code size < 0}）一律走临时文件，
     * 避免「未知大小 → 整文件进 ByteArrayOutputStream → OOM」。
     */
    private static final long MEMORY_LIMIT = 8L * 1024L * 1024L;

    private static void copyOne(FileStorage source, String sourceDir, FileStorage target,
                                String targetDir, String relative, String targetRelative,
                                SyncOptions opts, SyncProgress progress, AtomicLong copied,
                                AtomicLong failed, AtomicLong bytes, List<String> errors,
                                AtomicBoolean aborted) {
        java.nio.file.Path temp = null;
        try {
            StorageEntry entry = source.stat(StoragePaths.join(sourceDir, relative));
            long size = entry.size();
            // 未知大小或超过内存上限：落盘中转，不把整文件进堆
            if (size < 0 || size > MEMORY_LIMIT) {
                temp = java.nio.file.Files.createTempFile("jkit-storage-sync", ".tmp");
                try (java.io.OutputStream fileOut = java.nio.file.Files.newOutputStream(temp)) {
                    source.download(StoragePaths.join(sourceDir, relative), fileOut);
                }
                try (java.io.InputStream fileIn = java.nio.file.Files.newInputStream(temp)) {
                    if (opts.isPreserveMtime() && entry.lastModified() != StorageEntry.MTIME_UNKNOWN) {
                        target.upload(StoragePaths.join(targetDir, targetRelative), fileIn,
                                java.nio.file.Files.size(temp),
                                com.alianga.jkit.storage.UploadMetadata.mtime(entry.lastModified()));
                    } else {
                        target.upload(StoragePaths.join(targetDir, targetRelative), fileIn,
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
                    target.upload(StoragePaths.join(targetDir, targetRelative), data,
                            com.alianga.jkit.storage.UploadMetadata.mtime(entry.lastModified()));
                } else {
                    target.upload(StoragePaths.join(targetDir, targetRelative), data);
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
            if (relative == null || relative.isEmpty() || SyncManifest.isMeta(relative)
                    || !opts.accepts(relative)) {
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

    /**
     * 目录存在性校验：目录缺失在对比里表现为「0 个文件」的假象、在单向同步里
     * 会让镜像模式把目标端全部清掉——都不允许静默通过。根目录（空串）跳过。
     */
    private static void requireDir(FileStorage storage, String dir, String role) {
        String normalized = StoragePaths.normalize(dir);
        if (normalized.isEmpty() || storage.exists(normalized)) {
            return;
        }
        throw new StorageException(StorageErrorCode.NOT_FOUND,
                role + "不存在: " + normalized + "（请检查路径拼写；SFTP/FTP 的路径从服务器根目录 / 起）",
                normalized);
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
            if (relative != null && !relative.isEmpty() && !SyncManifest.isMeta(relative)) {
                files.put(relative, entry);
            }
        }
        return files;
    }

    /** 目录集合（相对路径 → 条目），目录不存在时按空处理（镜像删除目标端多余目录用）。 */
    private static Map<String, StorageEntry> dirsOf(FileStorage storage, String dir) {
        Map<String, StorageEntry> dirs = new LinkedHashMap<String, StorageEntry>();
        String normalized = StoragePaths.normalize(dir);
        List<StorageEntry> walked;
        try {
            walked = storage.walk(normalized);
        } catch (StorageException e) {
            if (e.code() == StorageErrorCode.NOT_FOUND) {
                return dirs;
            }
            throw e;
        }
        for (StorageEntry entry : walked) {
            if (!entry.isDirectory()) {
                continue;
            }
            String relative = StoragePaths.relativize(normalized, entry.path());
            if (relative != null && !relative.isEmpty() && !SyncManifest.isMeta(relative)) {
                dirs.put(relative, entry);
            }
        }
        return dirs;
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
