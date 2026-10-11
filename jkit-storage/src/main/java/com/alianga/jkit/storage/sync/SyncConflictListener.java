package com.alianga.jkit.storage.sync;

/**
 * 双向同步冲突回调：每个被判定冲突的文件都会回调一次（在冲突被处理时触发）。
 *
 * @author 郑明亮
 */
public interface SyncConflictListener {
    /**
     * 冲突处理通知。
     *
     * @param relativePath 冲突文件的相对路径
     * @param action       处理动作：left-wins / right-wins / keep-both
     * @param detail       人类可读的判定细节（如两侧修改时间）
     */
    void onConflict(String relativePath, String action, String detail);
}
