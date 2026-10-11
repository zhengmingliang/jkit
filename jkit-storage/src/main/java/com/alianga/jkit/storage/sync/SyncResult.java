package com.alianga.jkit.storage.sync;

import java.util.ArrayList;
import java.util.List;

/**
 * 同步执行结果：各动作计数、传输字节与失败明细。
 *
 * @author 郑明亮
 */
public final class SyncResult {
    private final long copied;
    private final long skipped;
    private final long deleted;
    private final long failed;
    private final long bytesCopied;
    private final boolean aborted;
    private final List<String> errors;
    private final long conflicts;

    /**
     * 构造结果。
     *
     * @param copied       复制成功数
     * @param skipped      跳过数（已一致）
     * @param deleted      镜像删除成功数（含目录）
     * @param failed       失败数
     * @param bytesCopied  传输字节总数
     * @param aborted      是否因失败达到上限而中止
     * @param errors       失败明细（相对路径: 原因），可为 null
     */
    public SyncResult(long copied, long skipped, long deleted, long failed,
                      long bytesCopied, boolean aborted, List<String> errors) {
        this(copied, skipped, deleted, failed, bytesCopied, aborted, errors, 0L);
    }

    /**
     * 构造结果（完整参数，双向同步用）。
     *
     * @param copied       复制成功数
     * @param skipped      跳过数（已一致）
     * @param deleted      删除成功数
     * @param failed       失败数
     * @param bytesCopied  传输字节总数
     * @param aborted      是否因失败达到上限而中止
     * @param errors       失败明细（相对路径: 原因），可为 null
     * @param conflicts    冲突处理数（双向同步；单向传 0）
     */
    public SyncResult(long copied, long skipped, long deleted, long failed,
                      long bytesCopied, boolean aborted, List<String> errors, long conflicts) {
        this.copied = copied;
        this.skipped = skipped;
        this.deleted = deleted;
        this.failed = failed;
        this.bytesCopied = bytesCopied;
        this.aborted = aborted;
        this.errors = errors == null ? new ArrayList<String>() : new ArrayList<String>(errors);
        this.conflicts = conflicts;
    }

    public long copied() {
        return copied;
    }

    public long skipped() {
        return skipped;
    }

    public long deleted() {
        return deleted;
    }

    public long failed() {
        return failed;
    }

    public long bytesCopied() {
        return bytesCopied;
    }

    /**
     * 是否因失败达到上限而提前中止。
     *
     * @return true 表示中止
     */
    public boolean aborted() {
        return aborted;
    }

    /**
     * 失败明细：每条形如「相对路径: 失败原因」，与 {@link #failed()} 计数一致。
     *
     * @return 失败明细列表（只读语义，成功时为空列表）
     */
    public List<String> errors() {
        return errors;
    }

    /**
     * 冲突处理数（双向同步：两侧同名文件不一致被策略处理的数量；单向为 0）。
     *
     * @return 冲突数
     */
    public long conflicts() {
        return conflicts;
    }

    @Override
    public String toString() {
        return "SyncResult{copied=" + copied + ", skipped=" + skipped + ", deleted=" + deleted
                + ", failed=" + failed + ", bytes=" + bytesCopied + (aborted ? ", aborted" : "")
                + (conflicts > 0 ? ", conflicts=" + conflicts : "")
                + (errors.isEmpty() ? "" : ", errors=" + errors.size()) + "}";
    }
}
