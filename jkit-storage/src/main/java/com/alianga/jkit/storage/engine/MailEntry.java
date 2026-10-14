package com.alianga.jkit.storage.engine;

/**
 * 邮件存储条目元数据：IMAP 侧一条邮件（或其附件）对应的名称、大小、修改时间与 UID。
 * {@code uid} 作为 {@link com.alianga.jkit.storage.StorageEntry#etag()} 使用；
 * {@code name} 不含路径前缀。
 *
 * @author 郑明亮
 */
public final class MailEntry {
    private final String name;
    private final boolean directory;
    private final long size;
    private final long mtimeEpochMs;
    private final String uid;

    /**
     * 构造不可变条目。
     *
     * @param name          名称（无路径前缀）
     * @param directory     是否目录
     * @param size          字节数；未知传 {@code -1}
     * @param mtimeEpochMs  修改时间（epoch 毫秒）；未知传 {@code -1}
     * @param uid           IMAP UID（或其它版本标识），可为 null
     */
    public MailEntry(String name, boolean directory, long size, long mtimeEpochMs, String uid) {
        this.name = name == null ? "" : name;
        this.directory = directory;
        this.size = size;
        this.mtimeEpochMs = mtimeEpochMs;
        this.uid = uid;
    }

    /**
     * 条目名称（无路径前缀）。
     *
     * @return 名称
     */
    public String name() {
        return name;
    }

    /**
     * 是否目录。
     *
     * @return true 表示目录
     */
    public boolean directory() {
        return directory;
    }

    /**
     * 字节数。
     *
     * @return 大小；未知为 {@code -1}
     */
    public long size() {
        return size;
    }

    /**
     * 修改时间（epoch 毫秒）。
     *
     * @return 修改时间；未知为 {@code -1}
     */
    public long mtimeEpochMs() {
        return mtimeEpochMs;
    }

    /**
     * IMAP UID（用作 etag）。
     *
     * @return UID，可为 null
     */
    public String uid() {
        return uid;
    }

    @Override
    public String toString() {
        return (directory ? "[D] " : "[F] ") + name + " uid=" + uid;
    }
}
