package com.alianga.jkit.storage.engine;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

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
    private final boolean foreign;
    private final Map<String, String> attributes;

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
        this.foreign = false;
        this.attributes = Collections.<String, String>emptyMap();
    }

    /**
     * 构造带来源标记与扩展属性的条目。
     *
     * @param name          名称
     * @param directory     是否目录
     * @param size          字节数；未知传 {@code -1}
     * @param mtimeEpochMs  修改时间；未知传 {@code -1}
     * @param uid           UID 或版本标识
     * @param foreign       true 表示非 jkit 写入邮件的附件（只读）
     * @param attributes    扩展属性（可为 null）
     */
    public MailEntry(String name, boolean directory, long size, long mtimeEpochMs, String uid,
                     boolean foreign, Map<String, String> attributes) {
        this.name = name == null ? "" : name;
        this.directory = directory;
        this.size = size;
        this.mtimeEpochMs = mtimeEpochMs;
        this.uid = uid;
        this.foreign = foreign;
        this.attributes = attributes == null || attributes.isEmpty()
                ? Collections.<String, String>emptyMap()
                : Collections.unmodifiableMap(new LinkedHashMap<String, String>(attributes));
    }

    /**
     * 同元数据、换名称（拼目录前缀用）。
     *
     * @param newName 新名称
     * @return 新条目
     */
    public MailEntry withName(String newName) {
        return new MailEntry(newName, directory, size, mtimeEpochMs, uid, foreign, attributes);
    }

    /**
     * 是否非 jkit 写入邮件的附件。
     *
     * @return true 为外来附件
     */
    public boolean foreign() {
        return foreign;
    }

    /**
     * 扩展属性（见 {@code StorageEntry.ATTR_MAIL_*}）。
     *
     * @return 只读 map，不为 null
     */
    public Map<String, String> attributes() {
        return attributes;
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
