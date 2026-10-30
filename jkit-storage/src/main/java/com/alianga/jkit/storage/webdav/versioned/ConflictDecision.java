package com.alianga.jkit.storage.webdav.versioned;

/**
 * 上传冲突时的用户决策。
 *
 * @author 郑明亮
 */
public enum ConflictDecision {
    /** 以本机内容上传为新版本（基于当前远端 head 递增）。 */
    UPLOAD_AS_NEW_VERSION,
    /** 先恢复远端 head 到本机，再继续。 */
    RESTORE_REMOTE_FIRST,
    /** 取消本次操作。 */
    CANCEL
}
