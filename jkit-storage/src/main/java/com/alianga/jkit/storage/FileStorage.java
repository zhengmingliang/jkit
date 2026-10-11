package com.alianga.jkit.storage;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * 统一文件存储接口：一套方法操作所有平台（本地 / FTP / SFTP / WebDAV / S3 及其兼容云）。
 *
 * <p>路径约定见 {@link StoragePaths}：{@code /} 分隔、无前导 {@code /}、根目录为空串；
 * 所有失败统一抛 {@link StorageException}；通过 {@link FileStorages#of} 创建实例。</p>
 *
 * <p>典型一行代码：</p>
 * <pre>{@code
 * FileStorage fs = FileStorages.of("oss", StorageConfig.empty()
 *         .set("endpoint", "https://oss-cn-hangzhou.aliyuncs.com")
 *         .set("bucket", "mybucket").set("accessKey", "AK").set("secretKey", "SK"));
 * fs.upload("demo/hello.txt", "你好".getBytes(StandardCharsets.UTF_8));
 * }</pre>
 *
 * @author 郑明亮
 */
public interface FileStorage extends AutoCloseable {
    /**
     * 本实例所属平台 id（与 {@link StoragePlatform#id()} 一致）。
     *
     * @return 平台 id
     */
    String platform();

    /**
     * 判断文件或目录是否存在。
     *
     * @param path 对象路径
     * @return true 表示存在
     */
    boolean exists(String path);

    /**
     * 查询单个对象的元数据。
     *
     * @param path 对象路径
     * @return 元数据
     * @throws StorageException 不存在时抛 {@link StorageErrorCode#NOT_FOUND}
     */
    StorageEntry stat(String path);

    /**
     * 列出目录的直接子项（文件与子目录各一层，不递归）。结果顺序不作保证，
     * 上层需要排序请自行处理。
     *
     * @param dir 目录路径，根目录传空串或 null
     * @return 直接子项列表
     */
    List<StorageEntry> list(String dir);

    /**
     * 创建目录。不支持目录概念的平台（对象存储）会写入目录占位对象；
     * 已存在时视为成功（幂等）。
     *
     * @param dir 目录路径
     */
    void mkdir(String dir);

    /**
     * 上传流到指定路径。流由调用方负责关闭。
     *
     * @param path 目标路径
     * @param in   数据源
     * @param size 已知字节数；确知时务必传准确值（部分平台要求）；未知传 {@code -1}，
     *             引擎会自行缓冲后再传（内存与磁盘开销更大）
     */
    void upload(String path, InputStream in, long size);

    /**
     * 下载对象到输出流。输出流由调用方负责关闭，方法返回前不关闭输出流。
     *
     * @param path 对象路径
     * @param out  目标输出流
     */
    void download(String path, OutputStream out);

    /**
     * 删除文件或空目录。删除不存在的对象视为成功（幂等）。
     *
     * @param path 对象路径
     */
    void delete(String path);

    /**
     * 复制对象（同平台内）。目标所在目录需已存在；目标已存在时覆盖。
     *
     * @param src 源路径
     * @param dst 目标路径
     */
    void copy(String src, String dst);

    /**
     * 重命名 / 移动对象（同平台内）。平台有原生 rename 时优先使用。
     *
     * @param src 源路径
     * @param dst 目标路径
     */
    void rename(String src, String dst);

    /**
     * 上传流并携带元数据（如同步 / 迁移时保留源端修改时间）。平台支持元数据则存储，
     * 不支持则忽略；contentType 为 null 时按扩展名推断。
     *
     * @param path 目标路径
     * @param in   数据源
     * @param size 已知字节数，未知传 -1
     * @param meta 元数据，null 等价于三参 upload
     */
    default void upload(String path, InputStream in, long size, UploadMetadata meta) {
        upload(path, in, size);
    }

    /**
     * 便捷上传：内存数据并携带元数据。
     *
     * @param path 目标路径
     * @param data 数据
     * @param meta 元数据，null 等价于两参 upload
     */
    default void upload(String path, byte[] data, UploadMetadata meta) {
        upload(path, new ByteArrayInputStream(data), data == null ? 0L : data.length, meta);
    }

    /**
     * 便捷上传：内存数据。
     *
     * @param path 目标路径
     * @param data 数据
     */
    default void upload(String path, byte[] data) {
        upload(path, new ByteArrayInputStream(data), data == null ? 0L : data.length);
    }

    /**
     * 便捷上传：写文本文件（UTF-8）。用于「在线编辑保存」。
     *
     * @param path    目标路径
     * @param content 文本内容
     */
    default void writeString(String path, String content) {
        upload(path, (content == null ? "" : content).getBytes(StandardCharsets.UTF_8));
    }

    /**
     * 读取整个对象到内存。
     *
     * @param path 对象路径
     * @return 字节数组
     */
    default byte[] readBytes(String path) {
        ByteArrayOutputStream out = new ByteArrayOutputStream(8192);
        download(path, out);
        return out.toByteArray();
    }

    /**
     * 读取文本文件（UTF-8）。用于「在线编辑」读取。
     *
     * @param path 对象路径
     * @return 文本内容
     */
    default String readString(String path) {
        return new String(readBytes(path), StandardCharsets.UTF_8);
    }

    /**
     * 取对象在平台侧的绝对路径（「复制路径」等展示用）：纯字符串计算，
     * 不发起网络请求。默认返回存储内相对路径；文件服务器类引擎
     * （本地 / SFTP / FTP）覆盖为服务器端绝对路径。
     *
     * @param path 对象路径
     * @return 平台侧绝对路径
     */
    default String absolutePathOf(String path) {
        return StoragePaths.normalize(path);
    }

    /**
     * 递归删除目录及其全部内容。根目录路径或空串会抛
     * {@link StorageErrorCode#INVALID_CONFIG}（拒绝清空整个存储根）。
     *
     * @param dir 目录路径
     */
    default void deleteDir(String dir) {
        String normalized = StoragePaths.normalize(dir);
        if (normalized.isEmpty()) {
            throw StorageException.invalidConfig("deleteDir 拒绝删除存储根目录，请指定具体路径");
        }
        for (StorageEntry entry : list(normalized)) {
            if (entry.isDirectory()) {
                deleteDir(entry.path());
            } else {
                delete(entry.path());
            }
        }
        delete(normalized);
    }

    /**
     * 递归枚举目录下全部条目（含所有层级）。同步 / 迁移引擎用。
     *
     * @param dir 目录路径，根目录传空串或 null
     * @return 全部后代条目
     */
    default List<StorageEntry> walk(String dir) {
        return com.alianga.jkit.storage.internal.StorageWalker.walk(this, StoragePaths.normalize(dir));
    }

    /**
     * 释放底层连接资源。多数引擎是无状态 HTTP 连接（无需关闭），按需覆盖。
     */
    @Override
    default void close() {
    }
}
