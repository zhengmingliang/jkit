package com.alianga.jkit.storage.internal;

import com.alianga.jkit.storage.StorageException;

import java.io.File;
import java.io.IOException;

/**
 * 中转临时文件统一入口：创建前按预计大小检查临时目录剩余空间（余量随大小缩放），
 * 注册 {@code deleteOnExit} 兜底进程退出时的残留；调用方仍须在 finally 中
 * 用 {@link #deleteQuietly(File)} 及时删除。
 *
 * @author 郑明亮
 */
public final class TempFiles {
    /** 已知大小时的余量下限 / 上限（余量 = 大小的 1/10，夹在两者之间）。 */
    static final long MIN_MARGIN = 4L * 1024L * 1024L;
    static final long MAX_MARGIN = 64L * 1024L * 1024L;

    /** 大小未知时的最小剩余要求：小 tmpfs 上小文件仍可中转。 */
    static final long UNKNOWN_RESERVE = 4L * 1024L * 1024L;

    /**
     * 创建临时文件前要求的剩余空间：已知大小 = 大小 + min(64MiB, max(4MiB, 大小/10))；
     * 未知大小只要求 4MiB（写入过程中由文件系统自身报错兜底）。
     *
     * @param expectedSize 预计大小，未知传 -1
     * @return 要求的剩余字节数
     */
    static long requiredBytes(long expectedSize) {
        if (expectedSize <= 0L) {
            return UNKNOWN_RESERVE;
        }
        long margin = Math.min(MAX_MARGIN, Math.max(MIN_MARGIN, expectedSize / 10L));
        return expectedSize > Long.MAX_VALUE - margin ? Long.MAX_VALUE : expectedSize + margin;
    }

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
        long need = requiredBytes(expectedSize);
        if (usable > 0L && need > usable) {
            throw StorageException.io("临时目录空间不足：需要 " + need + " 字节（含预留），剩余 " + usable
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
