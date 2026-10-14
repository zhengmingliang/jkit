package com.alianga.jkit.storage.engine;

import com.alianga.jkit.storage.StorageException;

import java.io.InputStream;

/**
 * IMAP APPEND 写入端：把上层已组好的完整 RFC822 MIME 消息追加到指定文件夹。
 *
 * <p>覆盖语义由上层先 {@link ImapMailSession#delete(String)} 再 append；
 * APPEND 本身不做隐式覆盖。</p>
 *
 * <p>owned by 开发助手4 — Phase 1 仅桩实现。</p>
 *
 * @author 郑明亮
 */
public final class ImapAppendStore {
    private final ImapMailSession session;

    /**
     * 绑定到已有会话。
     *
     * @param session IMAP 会话
     */
    public ImapAppendStore(ImapMailSession session) {
        this.session = session;
    }

    /**
     * 将完整 MIME 消息追加到文件夹。MIME 已由上层组好；覆盖由上层先 delete。
     *
     * @param folder      IMAP 文件夹名
     * @param mimeMessage MIME 输入流
     * @param sizeBytes   已知字节数；未知传 {@code -1}
     */
    public void append(String folder, InputStream mimeMessage, long sizeBytes) {
        // owned by 开发助手4
        throw StorageException.unsupported("ImapAppendStore 待开发助手4实现");
    }
}
