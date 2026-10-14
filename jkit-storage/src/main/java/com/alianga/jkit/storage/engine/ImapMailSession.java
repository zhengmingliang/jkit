package com.alianga.jkit.storage.engine;

import com.alianga.jkit.storage.StorageException;

import java.io.OutputStream;
import java.util.List;
import java.util.Map;

/**
 * IMAP 会话 SPI：由开发助手4实现真实连接与读写。
 *
 * <p>配置键：{@code host / user / password / imapPort / ssl / basePath / maxAttachmentBytes}。
 * 默认文件夹取 {@code basePath}，未配置时为 {@code jkit-mail-disk}。</p>
 *
 * <p>owned by 开发助手4 — Phase 1 仅提供与约定一致的公开 API 桩，方法体抛
 * {@link StorageException#unsupported(String)}，待真实 IMAP 接入后替换实现。</p>
 *
 * @author 郑明亮
 */
public final class ImapMailSession implements AutoCloseable {
    /**
     * 按配置建立 IMAP 连接并选中文件夹。
     *
     * @param cfg 配置键值对
     */
    public void connect(Map<String, String> cfg) {
        // owned by 开发助手4
        throw StorageException.unsupported("ImapMailSession 待开发助手4实现");
    }

    /**
     * 关闭会话。
     */
    @Override
    public void close() {
        // owned by 开发助手4
    }

    /**
     * 列出路径下的邮件条目；{@code name} 无路径前缀。
     *
     * @param path 相对路径（一期扁平模型多为空串）
     * @return 条目列表
     */
    public List<MailEntry> list(String path) {
        // owned by 开发助手4
        throw StorageException.unsupported("ImapMailSession 待开发助手4实现");
    }

    /**
     * 下载指定名称对应的附件内容到输出流。
     *
     * @param path 条目名称 / 路径
     * @param out  目标输出流
     */
    public void download(String path, OutputStream out) {
        // owned by 开发助手4
        throw StorageException.unsupported("ImapMailSession 待开发助手4实现");
    }

    /**
     * 删除指定名称对应的邮件。找不到时可抛 {@code NOT_FOUND}；
     * {@link MailFileStorage#delete} 会将其吞掉以保持幂等。
     *
     * @param path 条目名称 / 路径
     */
    public void delete(String path) {
        // owned by 开发助手4
        throw StorageException.unsupported("ImapMailSession 待开发助手4实现");
    }

    /**
     * 判断条目是否存在；找不到只返回 {@code false}，不抛异常。
     *
     * @param path 条目名称 / 路径
     * @return true 表示存在
     */
    public boolean exists(String path) {
        // owned by 开发助手4
        throw StorageException.unsupported("ImapMailSession 待开发助手4实现");
    }

    /**
     * 查询条目元数据。找不到抛 {@link StorageException}（{@code NOT_FOUND}）。
     *
     * @param path 条目名称 / 路径
     * @return 元数据
     */
    public MailEntry stat(String path) {
        // owned by 开发助手4
        throw StorageException.unsupported("ImapMailSession 待开发助手4实现");
    }
}
