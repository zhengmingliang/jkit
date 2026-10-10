package com.alianga.jkit.storage;

/**
 * 上传元数据：内容类型与最后修改时间。跨平台同步 / 迁移时用于保留源端
 * 修改时间（平台支持元数据则存为对象 meta，本地文件系统直接设置 mtime）。
 *
 * @author 郑明亮
 */
public final class UploadMetadata {
    private final String contentType;
    private final long lastModified;

    private UploadMetadata(String contentType, long lastModified) {
        this.contentType = contentType;
        this.lastModified = lastModified;
    }

    /**
     * 仅保留修改时间。
     *
     * @param epochMillis 最后修改时间（epoch 毫秒），未知传 {@link StorageEntry#MTIME_UNKNOWN}
     * @return 元数据
     */
    public static UploadMetadata mtime(long epochMillis) {
        return new UploadMetadata(null, epochMillis);
    }

    /**
     * 内容类型 + 修改时间。
     *
     * @param contentType 内容类型，null 表示由引擎按扩展名推断
     * @param epochMillis 最后修改时间
     * @return 元数据
     */
    public static UploadMetadata of(String contentType, long epochMillis) {
        return new UploadMetadata(contentType, epochMillis);
    }

    /**
     * 内容类型（null 表示由引擎按扩展名推断）。
     *
     * @return 内容类型
     */
    public String contentType() {
        return contentType;
    }

    /**
     * 最后修改时间（epoch 毫秒），未知为 {@link StorageEntry#MTIME_UNKNOWN}。
     *
     * @return 时间戳
     */
    public long lastModified() {
        return lastModified;
    }

    /**
     * 是否携带有效修改时间。
     *
     * @return true 表示应保留 mtime
     */
    public boolean hasLastModified() {
        return lastModified != StorageEntry.MTIME_UNKNOWN;
    }
}
