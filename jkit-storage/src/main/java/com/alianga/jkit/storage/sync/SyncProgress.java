package com.alianga.jkit.storage.sync;

/**
 * 同步进度回调。回调在工作线程触发（并发 &gt; 1 时可能同时来自多个线程），
 * 实现方自行保证线程安全；图形界面里请自行切换回 UI 线程。
 *
 * @author 郑明亮
 */
public interface SyncProgress {
    /**
     * 计划确定后、传输开始前回调一次。
     *
     * @param totalFiles 待处理文件数（含复制与镜像删除）
     * @param totalBytes 待复制总字节数
     */
    void onStart(long totalFiles, long totalBytes);

    /**
     * 单个文件处理完成。
     *
     * @param relativePath 相对路径
     * @param bytes        本次传输字节数（删除动作为 0）
     * @param action       动作名：copy / delete / skip
     */
    void onFileDone(String relativePath, long bytes, String action);

    /**
     * 单个文件失败（不中止整体，除非达到 maxErrors）。
     *
     * @param relativePath 相对路径
     * @param error        异常
     */
    void onFileError(String relativePath, Exception error);
}
