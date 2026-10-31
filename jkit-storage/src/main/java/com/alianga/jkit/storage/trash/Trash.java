package com.alianga.jkit.storage.trash;

import com.alianga.jkit.storage.FileStorage;
import com.alianga.jkit.storage.StorageEntry;
import com.alianga.jkit.storage.StorageErrorCode;
import com.alianga.jkit.storage.StorageException;
import com.alianga.jkit.storage.StoragePaths;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 回收站：软删除语义收口。删除前先 rename 进存储根下 {@code .jkit-trash/<批次>/}，
 * 平台不支持 rename 时按策略回退硬删；支持列出批次、按批次恢复与按有效期清理。
 *
 * <p>批次目录名固定 {@code yyyyMMdd-HHmmss}（本类生成与解析一致），清理按目录名
 * 的时间戳判龄——对象存储的目录占位对象 mtime 常不可靠。</p>
 *
 * <p>有原生回收站的平台（能力声明 {@code hasNativeRecycleBin()}）不必用本类：
 * 直接 {@code delete()} 即进官方回收站可恢复。</p>
 *
 * @author 郑明亮
 */
public final class Trash {
    /** 回收站根目录（相对存储根）。 */
    public static final String TRASH_DIR = ".jkit-trash";
    /** 批次目录名格式。 */
    private static final DateTimeFormatter BATCH_FORMAT = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

    private Trash() {
    }

    /** 单项去向。 */
    public enum Outcome {
        /** 已移入回收站批次。 */
        TRASHED,
        /** rename 不支持，已按策略硬删（有原生回收站的平台即为进官方回收站）。 */
        DELETED,
        /** 失败（含策略不允许回退时的 rename 不支持）。 */
        FAILED
    }

    /** 单项结果。 */
    public static final class Item {
        private final String path;
        private final Outcome outcome;
        private final String error;

        private Item(String path, Outcome outcome, String error) {
            this.path = path;
            this.outcome = outcome;
            this.error = error;
        }

        /** @return 原路径 */
        public String path() {
            return path;
        }

        /** @return 去向 */
        public Outcome outcome() {
            return outcome;
        }

        /** @return 失败原因；非 FAILED 为 null */
        public String error() {
            return error;
        }
    }

    /** 一个回收站批次（对应 {@code .jkit-trash/<批次名>/} 一层）。 */
    public static final class Batch {
        private final String name;
        private final List<StorageEntry> entries;

        private Batch(String name, List<StorageEntry> entries) {
            this.name = name;
            this.entries = Collections.unmodifiableList(entries);
        }

        /** @return 批次目录名（yyyyMMdd-HHmmss） */
        public String name() {
            return name;
        }

        /** @return 批次内的对象（路径含 .jkit-trash 前缀） */
        public List<StorageEntry> entries() {
            return entries;
        }

        /** @return 批次时间；目录名不合法返回 null */
        public LocalDateTime time() {
            return parseBatch(name);
        }
    }

    /**
     * 生成新批次目录名（当前时间）。
     *
     * @return {@code yyyyMMdd-HHmmss}
     */
    public static String newBatchName() {
        return LocalDateTime.now().format(BATCH_FORMAT);
    }

    /**
     * 软删除：把每个路径 rename 进 {@code .jkit-trash/<batch>/<原名>}。
     *
     * <p>同名冲突不会发生（批次目录按时间唯一、批内原路径名唯一——同一目录列表内名字互异）。
     * rename 报 {@link StorageErrorCode#UNSUPPORTED} 时：{@code fallbackToDelete} 为 true
     * 则对原路径 {@code delete()}（计 DELETED），否则计 FAILED。其余异常计 FAILED 并带原因，
     * 不中断整批。</p>
     *
     * @param storage          存储实例
     * @param paths            待删对象路径（文件或目录，删除目录即整棵移入）
     * @param batch            批次目录名（{@link #newBatchName()}）
     * @param fallbackToDelete rename 不支持时是否回退硬删
     * @return 逐项结果（顺序与入参一致）
     */
    public static List<Item> moveToTrash(FileStorage storage, List<String> paths,
                                         String batch, boolean fallbackToDelete) {
        List<Item> items = new ArrayList<Item>(paths.size());
        String batchDir = TRASH_DIR + "/" + batch;
        for (String path : paths) {
            String normalized = StoragePaths.normalize(path);
            try {
                String target = batchDir + "/" + StoragePaths.name(normalized);
                String parent = StoragePaths.parent(target);
                boolean createdBatchDir = false;
                if (!parent.isEmpty() && !storage.exists(parent)) {
                    storage.mkdir(parent);
                    createdBatchDir = true;
                }
                try {
                    storage.rename(normalized, target);
                    items.add(new Item(normalized, Outcome.TRASHED, null));
                } catch (StorageException renameFailure) {
                    if (renameFailure.code() != StorageErrorCode.UNSUPPORTED || !fallbackToDelete) {
                        throw renameFailure;
                    }
                    // 回退硬删；本批刚建的批次目录若因此空置，顺手移除
                    //（非空说明批内已有其它对象落地，删除会失败，忽略即可）
                    try {
                        // 目录（含非空）走递归删除，文件走单对象删除
                        boolean directory;
                        try {
                            directory = storage.stat(normalized).isDirectory();
                        } catch (RuntimeException statFailed) {
                            directory = false;
                        }
                        if (directory) {
                            storage.deleteDir(normalized);
                        } else {
                            storage.delete(normalized);
                        }
                        items.add(new Item(normalized, Outcome.DELETED, null));
                        if (createdBatchDir) {
                            try {
                                storage.deleteDir(batchDir);
                                storage.deleteDir(TRASH_DIR);
                            } catch (RuntimeException ignored) {
                                // 目录非空（批内已有其它对象落地）或平台不支持：保留即可
                            }
                        }
                    } catch (RuntimeException deleteFailed) {
                        items.add(new Item(normalized, Outcome.FAILED, message(deleteFailed)));
                    }
                }
            } catch (StorageException e) {
                items.add(new Item(normalized, Outcome.FAILED, message(e)));
            } catch (RuntimeException e) {
                items.add(new Item(normalized, Outcome.FAILED, message(e)));
            }
        }
        return items;
    }

    /**
     * 列出全部回收站批次（按批次名倒序，新在前）。无回收站目录返回空清单。
     *
     * @param storage 存储实例
     * @return 批次清单
     */
    public static List<Batch> list(FileStorage storage) {
        List<Batch> batches = new ArrayList<Batch>();
        List<StorageEntry> roots;
        try {
            if (!storage.exists(TRASH_DIR)) {
                return batches;
            }
            roots = storage.list(TRASH_DIR);
        } catch (StorageException e) {
            if (e.code() == StorageErrorCode.NOT_FOUND) {
                return batches;
            }
            throw e;
        }
        for (StorageEntry root : roots) {
            if (!root.isDirectory()) {
                continue;
            }
            List<StorageEntry> entries;
            try {
                entries = storage.list(root.path());
            } catch (StorageException e) {
                if (e.code() == StorageErrorCode.NOT_FOUND) {
                    continue;
                }
                throw e;
            }
            batches.add(new Batch(StoragePaths.name(root.path()), entries));
        }
        Collections.sort(batches, new java.util.Comparator<Batch>() {
            @Override
            public int compare(Batch left, Batch right) {
                return right.name.compareTo(left.name);
            }
        });
        return batches;
    }

    /**
     * 从批次恢复对象到目标目录。
     *
     * <p>目标目录不存在会先创建；目标同名冲突时自动编号 {@code name (2).ext}（与
     * 「保留两者」上传冲突同一套命名）。</p>
     *
     * @param storage    存储实例
     * @param batch      批次目录名
     * @param entryNames 批次内对象名（{@code list} 给出的 entry.name()）
     * @param targetDir  恢复目标目录（存储根传空串）
     * @return 恢复后的目标路径（顺序与入参一致）
     */
    public static List<String> restore(FileStorage storage, String batch, List<String> entryNames,
                                       String targetDir) {
        String dir = StoragePaths.normalize(targetDir);
        if (!dir.isEmpty() && !storage.exists(dir)) {
            storage.mkdir(dir);
        }
        List<String> restored = new ArrayList<String>(entryNames.size());
        for (String name : entryNames) {
            String source = TRASH_DIR + "/" + StoragePaths.normalize(batch) + "/" + name;
            String target = (dir.isEmpty() ? "" : dir + "/") + uniqueName(storage, dir, name);
            storage.rename(source, target);
            restored.add(target);
        }
        return restored;
    }

    /** 目标目录内不重名的名字：同名依次追加 {@code  (2)}、{@code  (3)}…（扩展名保持在后）。 */
    private static String uniqueName(FileStorage storage, String dir, String name) {
        String taken = dir.isEmpty() ? name : dir + "/" + name;
        if (!storage.exists(taken)) {
            return name;
        }
        String base = StoragePaths.name(name);
        String stem = base;
        String extension = "";
        int dot = base.lastIndexOf('.');
        if (dot > 0) {
            stem = base.substring(0, dot);
            extension = base.substring(dot);
        }
        for (int i = 2; ; i++) {
            String candidate = stem + " (" + i + ")" + extension;
            String candidatePath = dir.isEmpty() ? candidate : dir + "/" + candidate;
            if (!storage.exists(candidatePath)) {
                return candidate;
            }
        }
    }

    /**
     * 清理过期批次：批次时间早于 {@code maxAge} 前的整批删除（deleteDir 递归）。
     *
     * <p>批次时间取目录名解析；解析不了（外部创建的目录）按 mtime，仍不可知则跳过保留。</p>
     *
     * @param storage 存储实例
     * @param maxAge  保留时长
     * @return 清理掉的批次数
     */
    public static int cleanup(FileStorage storage, Duration maxAge) {
        LocalDateTime threshold = LocalDateTime.now().minus(maxAge);
        int removed = 0;
        for (Batch batch : list(storage)) {
            LocalDateTime time = batch.time();
            if (time == null) {
                long mtime = batch.entries().isEmpty() ? -1L : batch.entries().get(0).lastModified();
                time = mtime < 0 ? null : toLocalDateTime(mtime);
            }
            if (time == null || time.isAfter(threshold)) {
                continue;
            }
            storage.deleteDir(TRASH_DIR + "/" + batch.name());
            removed++;
        }
        return removed;
    }

    private static LocalDateTime parseBatch(String name) {
        try {
            return LocalDateTime.parse(name, BATCH_FORMAT);
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static LocalDateTime toLocalDateTime(long epochMillis) {
        return LocalDateTime.ofInstant(java.time.Instant.ofEpochMilli(epochMillis),
                java.time.ZoneId.systemDefault());
    }

    private static String message(RuntimeException e) {
        String text = e.getMessage();
        return text == null || text.isEmpty() ? e.getClass().getSimpleName() : text;
    }
}
