package com.alianga.jkit.storage;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

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
    /** 平台扩展属性（只读，可能为空 map，不为 null），如 QQ 邮箱附件的 {@link #ATTR_COLLECTED}。 */
    private final Map<String, String> attributes;

    /** 扩展属性键：是否已收藏，值 "true"/"false"（目前仅 qqmail-attach 提供）。 */
    public static final String ATTR_COLLECTED = "collected";

    private StorageEntry(String path, boolean directory, long size, long lastModified, String etag) {
        this(path, directory, size, lastModified, etag, Collections.<String, String>emptyMap());
    }

    private StorageEntry(String path, boolean directory, long size, long lastModified, String etag,
                         Map<String, String> attributes) {
        this.attributes = attributes;
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

    /** @return 扩展属性只读视图（无则空 map） */
    public Map<String, String> attributes() {
        return attributes;
    }

    /**
     * @param key 属性键
     * @return 属性值，无则 null
     */
    public String attribute(String key) {
        return attributes.get(key);
    }

    /**
     * 返回附加了一个扩展属性的新条目（原条目不变）。
     *
     * @param key   属性键
     * @param value 属性值
     * @return 新条目
     */
    public StorageEntry withAttribute(String key, String value) {
        Map<String, String> m = new LinkedHashMap<String, String>(attributes);
        m.put(key, value);
        return new StorageEntry(path, directory, size, lastModified, etag, Collections.unmodifiableMap(m));
    }

    @Override
    public String toString() {
        return (directory ? "[D] " : "[F] ") + path + (directory ? "/" : " (" + size + "B)");
    }
}
