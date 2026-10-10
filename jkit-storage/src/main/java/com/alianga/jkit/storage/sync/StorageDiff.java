package com.alianga.jkit.storage.sync;

import com.alianga.jkit.storage.StorageEntry;

/**
 * 单条对比结果：同一相对路径在两侧的状态。
 *
 * <p>{@code left} / {@code right} 对应缺失一侧时为 null。</p>
 *
 * @author 郑明亮
 */
public final class StorageDiff {
    /** 差异类型。 */
    public enum Status {
        /** 两侧一致（按对比策略）。 */
        SAME,
        /** 仅左侧存在。 */
        LEFT_ONLY,
        /** 仅右侧存在。 */
        RIGHT_ONLY,
        /** 两侧都在但判定不同。 */
        DIFFERENT
    }

    private final String relativePath;
    private final StorageEntry left;
    private final StorageEntry right;
    private final Status status;

    /**
     * 构造一条差异。
     *
     * @param relativePath 相对路径
     * @param left         左侧条目，可 null
     * @param right        右侧条目，可 null
     * @param status       差异类型
     */
    public StorageDiff(String relativePath, StorageEntry left, StorageEntry right, Status status) {
        this.relativePath = relativePath;
        this.left = left;
        this.right = right;
        this.status = status;
    }

    public String relativePath() {
        return relativePath;
    }

    public StorageEntry left() {
        return left;
    }

    public StorageEntry right() {
        return right;
    }

    public Status status() {
        return status;
    }

    @Override
    public String toString() {
        return status + " " + relativePath;
    }
}
