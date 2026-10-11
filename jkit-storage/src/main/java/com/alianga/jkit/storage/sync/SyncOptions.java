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
    private SyncCancel cancelToken;
    private boolean preserveMtime = true;
    private Direction direction = Direction.ONE_WAY;
    private ConflictPolicy conflictPolicy = ConflictPolicy.NEWER_WINS;
    private SyncConflictListener conflictListener;

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

    /**
     * 取消令牌：引擎在每完成一个文件后检查，取消后停止调度剩余动作。
     *
     * @param cancelToken 取消令牌
     * @return 自身
     */
    public SyncOptions cancelToken(SyncCancel cancelToken) {
        this.cancelToken = cancelToken;
        return this;
    }

    /**
     * 是否在复制时保留源端修改时间（平台支持元数据则存对象 meta，
     * 本地文件系统直接设置 mtime）。默认开启。
     *
     * @param preserveMtime true 保留
     * @return 自身
     */
    public SyncOptions preserveMtime(boolean preserveMtime) {
        this.preserveMtime = preserveMtime;
        return this;
    }

    /**
     * 设置同步方向（默认单向）。
     *
     * @param direction 方向
     * @return 自身
     */
    public SyncOptions direction(Direction direction) {
        this.direction = direction == null ? Direction.ONE_WAY : direction;
        return this;
    }

    /**
     * 设置双向同步的冲突策略（默认新者胜，单向同步不使用）。
     *
     * @param policy 冲突策略
     * @return 自身
     */
    public SyncOptions conflictPolicy(ConflictPolicy policy) {
        this.conflictPolicy = policy == null ? ConflictPolicy.NEWER_WINS : policy;
        return this;
    }

    /**
     * 设置冲突回调（可为 null）：双向同步中每个冲突文件被处理时触发，UI 日志用。
     *
     * @param listener 冲突回调
     * @return 自身
     */
    public SyncOptions conflictListener(SyncConflictListener listener) {
        this.conflictListener = listener;
        return this;
    }

    /**
     * 当前同步方向。
     *
     * @return 方向
     */
    public Direction direction() {
        return direction;
    }

    /**
     * 当前冲突策略。
     *
     * @return 策略
     */
    public ConflictPolicy conflictPolicy() {
        return conflictPolicy;
    }

    /**
     * 当前冲突回调。
     *
     * @return 回调，可能为 null
     */
    public SyncConflictListener conflictListener() {
        return conflictListener;
    }

    /**
     * 当前取消令牌。
     *
     * @return 令牌，可能为 null
     */
    public SyncCancel cancelToken() {
        return cancelToken;
    }

    /**
     * 是否保留修改时间。
     *
     * @return true 保留
     */
    public boolean isPreserveMtime() {
        return preserveMtime;
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
        if (direction == Direction.BIDIRECTIONAL) {
            parts.add("bidirectional");
            parts.add("conflict=" + conflictPolicy);
        }
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
