package com.alianga.jkit.storage.sync;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

/**
 * 同步选项：对比策略、镜像删除、并发度、过滤与失败上限。
 *
 * <p>预设：</p>
 * <ul>
 *   <li>{@link #incremental()} 增量同步：只复制缺失或不一致的文件（默认 SIZE 对比）；</li>
 *   <li>{@link #fullMigrate()} 全量迁移：所有文件都复制一遍（ALWAYS 对比）；</li>
 *   <li>{@link #mirror()} 镜像同步：增量 + 删除目标端多余文件（危险，源为准）。</li>
 * </ul>
 *
 * @author 郑明亮
 */
public final class SyncOptions {
    /** 失败到该数量即中止（防雪崩），可设极大值表示尽力而为。 */
    public static final long UNLIMITED_ERRORS = Long.MAX_VALUE;

    private CompareMode compareMode = CompareMode.SIZE;
    private boolean mirror;
    private boolean dryRun;
    private int concurrency = 4;
    private long maxErrors = 100;
    private Predicate<String> filter;

    private SyncOptions() {
    }

    /**
     * 增量同步预设。
     *
     * @return 选项
     */
    public static SyncOptions incremental() {
        return new SyncOptions();
    }

    /**
     * 全量迁移预设。
     *
     * @return 选项
     */
    public static SyncOptions fullMigrate() {
        SyncOptions options = new SyncOptions();
        options.compareMode = CompareMode.ALWAYS;
        return options;
    }

    /**
     * 镜像同步预设（会删除目标端多余文件）。
     *
     * @return 选项
     */
    public static SyncOptions mirror() {
        SyncOptions options = new SyncOptions();
        options.mirror = true;
        return options;
    }

    /**
     * 设置对比策略。
     *
     * @param mode 对比策略
     * @return 自身
     */
    public SyncOptions compareMode(CompareMode mode) {
        this.compareMode = mode == null ? CompareMode.SIZE : mode;
        return this;
    }

    /**
     * 是否镜像删除目标端多余文件（以源为准，谨慎开启）。
     *
     * @param mirror true 开启
     * @return 自身
     */
    public SyncOptions mirror(boolean mirror) {
        this.mirror = mirror;
        return this;
    }

    /**
     * 试运行：只统计将要执行的动作，不真正传输 / 删除。
     *
     * @param dryRun true 试运行
     * @return 自身
     */
    public SyncOptions dryRun(boolean dryRun) {
        this.dryRun = dryRun;
        return this;
    }

    /**
     * 并发线程数（1 = 串行）。
     *
     * @param concurrency 线程数，最小 1
     * @return 自身
     */
    public SyncOptions concurrency(int concurrency) {
        this.concurrency = Math.max(1, concurrency);
        return this;
    }

    /**
     * 失败上限：累计失败达到该值即中止剩余动作。
     *
     * @param maxErrors 上限
     * @return 自身
     */
    public SyncOptions maxErrors(long maxErrors) {
        this.maxErrors = maxErrors;
        return this;
    }

    /**
     * 相对路径过滤器：返回 true 的路径参与同步，null 表示全部。
     *
     * @param filter 过滤器
     * @return 自身
     */
    public SyncOptions filter(Predicate<String> filter) {
        this.filter = filter;
        return this;
    }

    public CompareMode compareMode() {
        return compareMode;
    }

    public boolean isMirror() {
        return mirror;
    }

    public boolean isDryRun() {
        return dryRun;
    }

    public int concurrency() {
        return concurrency;
    }

    public long maxErrors() {
        return maxErrors;
    }

    /**
     * 判断路径是否参与同步。
     *
     * @param relativePath 相对路径
     * @return true 参与
     */
    public boolean accepts(String relativePath) {
        return filter == null || filter.test(relativePath);
    }

    /**
     * 预设组合的只读展示。
     *
     * @return 描述串
     */
    @Override
    public String toString() {
        List<String> parts = new ArrayList<String>();
        parts.add("mode=" + compareMode);
        if (mirror) {
            parts.add("mirror");
        }
        if (dryRun) {
            parts.add("dryRun");
        }
        parts.add("concurrency=" + concurrency);
        return "SyncOptions{" + String.join(", ", parts) + "}";
    }
}
