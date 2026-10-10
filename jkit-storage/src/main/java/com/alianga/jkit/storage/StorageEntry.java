package com.alianga.jkit.storage;

/**
 * 统一的对象元数据：一次 {@link FileStorage#list} 或 {@link FileStorage#stat}
 * 返回的文件 / 目录条目。路径约定见 {@link StoragePaths}。
 *
 * <p>不同平台能提供的元数据不同（如 FTP 可能拿不到精确修改时间），拿不到的字段
 * 一律用「类型默认值」表示：size=-1、lastModified=-1、etag=null。</p>
 *
 * @author 郑明亮
 */
public final class StorageEntry {
    /** 未知大小的占位值。 */
    public static final long SIZE_UNKNOWN = -1L;
    /** 未知修改时间的占位值。 */
    public static final long MTIME_UNKNOWN = -1L;

    /** 对象完整路径（相对 bucket / basePath，规范格式，目录不带尾部斜杠）。 */
    private final String path;
    /** 最后一段名称。 */
    private final String name;
    /** 是否目录。 */
    private final boolean directory;
    /** 字节数；未知为 {@link #SIZE_UNKNOWN}。 */
    private final long size;
    /** 最后修改时间（epoch 毫秒）；未知为 {@link #MTIME_UNKNOWN}。 */
    private final long lastModified;
    /** 平台原生的版本标识（S3 ETag 等）；可为 null。 */
    private final String etag;

    private StorageEntry(String path, boolean directory, long size, long lastModified, String etag) {
        this.path = StoragePaths.normalize(path);
        this.name = StoragePaths.name(this.path);
        this.directory = directory;
        this.size = size;
        this.lastModified = lastModified;
        this.etag = etag;
    }

    /**
     * 构造文件条目。
     *
     * @param path         路径
     * @param size         字节数，未知传 {@link #SIZE_UNKNOWN}
     * @param lastModified 修改时间（epoch 毫秒），未知传 {@link #MTIME_UNKNOWN}
     * @param etag         平台版本标识，可传 null
     * @return 文件条目
     */
    public static StorageEntry file(String path, long size, long lastModified, String etag) {
        return new StorageEntry(path, false, size, lastModified, etag);
    }

    /**
     * 构造目录条目。
     *
     * @param path 目录路径
     * @return 目录条目
     */
    public static StorageEntry dir(String path) {
        return new StorageEntry(path, true, 0L, MTIME_UNKNOWN, null);
    }

    /**
     * 构造带修改时间的目录条目。
     *
     * @param path         目录路径
     * @param lastModified 修改时间
     * @return 目录条目
     */
    public static StorageEntry dir(String path, long lastModified) {
        return new StorageEntry(path, true, 0L, lastModified, null);
    }

    public String path() {
        return path;
    }

    public String name() {
        return name;
    }

    public boolean isDirectory() {
        return directory;
    }

    public long size() {
        return size;
    }

    public long lastModified() {
        return lastModified;
    }

    public String etag() {
        return etag;
    }

    @Override
    public String toString() {
        return (directory ? "[D] " : "[F] ") + path + (directory ? "/" : " (" + size + "B)");
    }
}
