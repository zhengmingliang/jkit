package com.alianga.jkit.storage.cap;

/**
 * 存储能力元数据：引擎如实声明自己支持什么，上层界面按能力渲染，不按平台 id 写 if。
 *
 * <p>声明入口：引擎/预设通过 {@code FileStorages.registerCapabilities(engineId, caps)}
 * 注册引擎级能力；{@code FileStorage.capabilities()} 默认返回本引擎的注册值并叠加
 * 实例级 {@code supportsRange()}。未注册的引擎取 {@link #standard()}。</p>
 *
 * @author 郑明亮
 */
public final class StorageCapabilities {
    /** 默认能力：可读可写、无原生回收站、不支持预签名上传/断点续传、上传上限未知。 */
    private static final StorageCapabilities STANDARD = new StorageCapabilities(
            false, false, false, false, false, -1L);

    private final boolean readOnly;
    private final boolean nativeRecycleBin;
    private final boolean presignUpload;
    private final boolean supportsRange;
    private final boolean folderOpsEmulated;
    private final long maxUploadBytes;

    private StorageCapabilities(boolean readOnly, boolean nativeRecycleBin, boolean presignUpload,
                                boolean supportsRange, boolean folderOpsEmulated, long maxUploadBytes) {
        this.readOnly = readOnly;
        this.nativeRecycleBin = nativeRecycleBin;
        this.presignUpload = presignUpload;
        this.supportsRange = supportsRange;
        this.folderOpsEmulated = folderOpsEmulated;
        this.maxUploadBytes = maxUploadBytes;
    }

    /** @return 默认能力（全关，上传上限未知） */
    public static StorageCapabilities standard() {
        return STANDARD;
    }

    /** @return true 表示只读引擎（上传/删除/新建目录/重命名均不支持） */
    public boolean isReadOnly() {
        return readOnly;
    }

    /** @return true 表示平台有原生回收站（删除即可恢复，无需软删除） */
    public boolean hasNativeRecycleBin() {
        return nativeRecycleBin;
    }

    /** @return true 表示支持 {@code presignUpload} 生成直传链接 */
    public boolean supportsPresignUpload() {
        return presignUpload;
    }

    /** @return true 表示支持断点续传（{@code download(path, offset, out)}） */
    public boolean supportsRange() {
        return supportsRange;
    }

    /** @return true 表示目录级重命名/复制/移动为逐项模拟实现（非原子） */
    public boolean isFolderOpsEmulated() {
        return folderOpsEmulated;
    }

    /**
     * @return 单文件上传上限字节；未知或无限制为 -1
     */
    public long maxUploadBytes() {
        return maxUploadBytes;
    }

    /** @return 带只读标记的副本 */
    public StorageCapabilities withReadOnly() {
        return new StorageCapabilities(true, nativeRecycleBin, presignUpload, supportsRange,
                folderOpsEmulated, maxUploadBytes);
    }

    /** @return 带原生回收站标记的副本 */
    public StorageCapabilities withNativeRecycleBin() {
        return new StorageCapabilities(readOnly, true, presignUpload, supportsRange,
                folderOpsEmulated, maxUploadBytes);
    }

    /** @return 带预签名上传能力的副本 */
    public StorageCapabilities withPresignUpload() {
        return new StorageCapabilities(readOnly, nativeRecycleBin, true, supportsRange,
                folderOpsEmulated, maxUploadBytes);
    }

    /** @return 指定断点续传能力的副本 */
    public StorageCapabilities withSupportsRange(boolean range) {
        return new StorageCapabilities(readOnly, nativeRecycleBin, presignUpload, range,
                folderOpsEmulated, maxUploadBytes);
    }

    /** @return 带目录操作模拟标记的副本 */
    public StorageCapabilities withFolderOpsEmulated() {
        return new StorageCapabilities(readOnly, nativeRecycleBin, presignUpload, supportsRange,
                true, maxUploadBytes);
    }

    /** @return 指定单文件上传上限（字节）的副本 */
    public StorageCapabilities withMaxUploadBytes(long bytes) {
        return new StorageCapabilities(readOnly, nativeRecycleBin, presignUpload, supportsRange,
                folderOpsEmulated, bytes);
    }
}
