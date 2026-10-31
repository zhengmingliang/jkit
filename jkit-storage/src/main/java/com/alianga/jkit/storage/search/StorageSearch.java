package com.alianga.jkit.storage.search;

import com.alianga.jkit.storage.FileStorage;
import com.alianga.jkit.storage.StorageEntry;
import com.alianga.jkit.storage.StorageException;
import com.alianga.jkit.storage.StoragePaths;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.IntConsumer;
import java.util.function.Predicate;

/**
 * 流式递归搜索：逐层惰性展开（区别于 {@code walk()} 的全量物化），命中即批量回调、
 * 可随时取消、达上限立即停——百万对象的大目录里取消与限量能真正省掉剩余遍历。
 *
 * <p>「一句话」用法：</p>
 * <pre>{@code
 * List<StorageEntry> hits = StorageSearch.find(fs, "docs",
 *         e -> e.name().endsWith(".log"));
 * }</pre>
 *
 * @author 郑明亮
 */
public final class StorageSearch {
    /** 简易入口的默认命中上限。 */
    public static final int DEFAULT_LIMIT = 10_000;
    /** 流式回调的批大小。 */
    private static final int BATCH_SIZE = 20;
    /** 进度回调间隔（扫描计数）。 */
    private static final int PROGRESS_STEP = 200;

    private StorageSearch() {
    }

    /** 一次搜索的统计。 */
    public static final class Stats {
        private final int scanned;
        private final int matched;
        private final boolean limited;

        private Stats(int scanned, int matched, boolean limited) {
            this.scanned = scanned;
            this.matched = matched;
            this.limited = limited;
        }

        /** @return 已扫描条目数 */
        public int scanned() {
            return scanned;
        }

        /** @return 命中条目数 */
        public int matched() {
            return matched;
        }

        /** @return true 表示因达到上限而提前停止 */
        public boolean limited() {
            return limited;
        }
    }

    /**
     * 一句话搜索：递归匹配名称/属性的条目，最多 {@link #DEFAULT_LIMIT} 条。
     *
     * @param storage 存储实例
     * @param dir     起始目录（根传空串）
     * @param matcher 匹配谓词（true 收入结果）
     * @return 命中条目
     */
    public static List<StorageEntry> find(FileStorage storage, String dir,
                                          Predicate<StorageEntry> matcher) {
        return find(storage, dir, matcher, DEFAULT_LIMIT);
    }

    /**
     * 一句话搜索（带上限）。
     *
     * @param storage 存储实例
     * @param dir     起始目录
     * @param matcher 匹配谓词
     * @param limit   命中上限
     * @return 命中条目
     */
    public static List<StorageEntry> find(FileStorage storage, String dir,
                                          Predicate<StorageEntry> matcher, int limit) {
        List<StorageEntry> hits = new ArrayList<StorageEntry>();
        find(storage, dir, matcher, limit, null, new Consumer<List<StorageEntry>>() {
            @Override
            public void accept(List<StorageEntry> batch) {
                hits.addAll(batch);
            }
        }, null);
        return hits;
    }

    /**
     * 流式搜索：命中满一批回调一次（最后一批不足也会回调），每扫 {@value #PROGRESS_STEP}
     * 项回调一次进度，取消判断每条目都问。目录不存在抛 NOT_FOUND（防拼写错误静默为空）。
     *
     * @param storage   存储实例
     * @param dir       起始目录（根传空串）
     * @param matcher   匹配谓词
     * @param limit     命中上限
     * @param cancelled 取消判断，null 表示不取消
     * @param onBatch   命中批次回调
     * @param onProgress 进度回调（已扫描数），可为 null
     * @return 统计
     */
    public static Stats find(FileStorage storage, String dir, Predicate<StorageEntry> matcher,
                             int limit, java.util.function.BooleanSupplier cancelled,
                             Consumer<List<StorageEntry>> onBatch, IntConsumer onProgress) {
        String normalized = StoragePaths.normalize(dir);
        if (!normalized.isEmpty() && !storage.exists(normalized)) {
            throw StorageException.notFound(normalized);
        }
        int[] scanned = {0};
        int[] matched = {0};
        boolean[] limited = {false};
        List<StorageEntry> batch = new ArrayList<StorageEntry>(BATCH_SIZE);
        collect(storage, normalized, matcher, limit, cancelled, onBatch, onProgress,
                scanned, matched, limited, batch);
        if (!batch.isEmpty()) {
            onBatch.accept(batch);
        }
        return new Stats(scanned[0], matched[0], limited[0]);
    }

    private static void collect(FileStorage storage, String dir, Predicate<StorageEntry> matcher,
                                int limit, java.util.function.BooleanSupplier cancelled,
                                Consumer<List<StorageEntry>> onBatch, IntConsumer onProgress,
                                int[] scanned, int[] matched, boolean[] limited,
                                List<StorageEntry> batch) {
        for (StorageEntry entry : storage.list(dir)) {
            if (cancelled != null && cancelled.getAsBoolean()) {
                return;
            }
            scanned[0]++;
            if (matcher.test(entry)) {
                if (matched[0] >= limit) {
                    limited[0] = true;
                    return;
                }
                matched[0]++;
                batch.add(entry);
                if (batch.size() >= BATCH_SIZE) {
                    onBatch.accept(new ArrayList<StorageEntry>(batch));
                    batch.clear();
                }
            }
            if (onProgress != null && scanned[0] % PROGRESS_STEP == 0) {
                onProgress.accept(scanned[0]);
            }
            if (entry.isDirectory()) {
                collect(storage, entry.path(), matcher, limit, cancelled, onBatch, onProgress,
                        scanned, matched, limited, batch);
                if (limited[0] || (cancelled != null && cancelled.getAsBoolean())) {
                    return;
                }
            }
        }
    }
}
