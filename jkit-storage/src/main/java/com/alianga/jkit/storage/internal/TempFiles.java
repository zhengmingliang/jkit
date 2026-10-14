package com.alianga.jkit.storage.internal;

import com.alianga.jkit.storage.StorageException;

import java.io.File;
import java.io.IOException;

/**
 * 中转临时文件统一入口：创建前检查临时目录剩余空间（预留 64MiB），
 * 注册 {@code deleteOnExit} 兜底进程退出时的残留；调用方仍须在 finally 中
 * 用 {@link #deleteQuietly(File)} 及时删除。
 *
 * @author 郑明亮
 */
public final class TempFiles {
    /** 剩余空间预留：写满临时盘会拖垮整个系统。 */
    static final long RESERVE_BYTES = 64L * 1024L * 1024L;

    private TempFiles() {
    }

    /**
     * 创建中转临时文件。
     *
     * @param prefix       文件名前缀
     * @param expectedSize 预计写入字节数，未知传 -1
     * @param path         关联的对象路径（异常信息用）
     * @return 临时文件
     */
    public static File create(String prefix, long expectedSize, String path) {
        File dir = new File(System.getProperty("java.io.tmpdir"));
        long usable = dir.getUsableSpace();
        // getUsableSpace 取不到时返回 0，此时不拦截
        if (usable > 0L && expectedSize > 0L && expectedSize + RESERVE_BYTES > usable) {
            throw StorageException.io("临时目录空间不足：需要 " + expectedSize + " 字节，剩余 " + usable
                    + " 字节（" + dir + "）", path, null);
        }
        try {
            File temp = File.createTempFile(prefix, ".tmp", dir);
            temp.deleteOnExit();
            return temp;
        } catch (IOException e) {
            throw StorageException.io("创建临时文件失败: " + e.getMessage(), path, e);
        }
    }

    /**
     * 静默删除临时文件。
     *
     * @param temp 临时文件，可为 null
     */
    public static void deleteQuietly(File temp) {
        if (temp != null && temp.exists() && !temp.delete()) {
            temp.deleteOnExit();
        }
    }
}
