package com.alianga.jkit.storage.engine;

import java.io.InputStream;
import java.io.OutputStream;
import java.util.List;
import java.util.Map;

/**
 * 邮件传输网关：隔离 {@link ImapMailSession} / {@link ImapAppendStore} 的 final 类，
 * 便于单测注入内存 Fake。生产实现见 {@link DefaultMailGateway}。
 *
 * @author 郑明亮
 */
interface MailGateway extends AutoCloseable {
    /**
     * 建立连接。
     *
     * @param cfg 配置
     */
    void connect(Map<String, String> cfg);

    /**
     * 列出条目。
     *
     * @param path 路径
     * @return 条目
     */
    List<MailEntry> list(String path);

    /**
     * 下载。
     *
     * @param path 路径
     * @param out  输出流
     */
    void download(String path, OutputStream out);

    /**
     * 删除（可抛 NOT_FOUND）。
     *
     * @param path 路径
     */
    void delete(String path);

    /**
     * 是否存在。
     *
     * @param path 路径
     * @return true 存在
     */
    boolean exists(String path);

    /**
     * 元数据；找不到抛 NOT_FOUND。
     *
     * @param path 路径
     * @return 条目
     */
    MailEntry stat(String path);

    /**
     * APPEND MIME。
     *
     * @param folder      文件夹
     * @param mimeMessage MIME 流
     * @param sizeBytes   大小
     */
    void append(String folder, InputStream mimeMessage, long sizeBytes);

    @Override
    void close();
}
