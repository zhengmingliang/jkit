package com.alianga.jkit.storage.engine;

import java.io.InputStream;
import java.io.OutputStream;
import java.util.List;
import java.util.Map;

/**
 * 生产网关：委托给 {@link ImapMailSession} 与 {@link ImapAppendStore}。
 *
 * @author 郑明亮
 */
final class DefaultMailGateway implements MailGateway {
    private final ImapMailSession session;
    private final ImapAppendStore appendStore;

    DefaultMailGateway(ImapMailSession session, ImapAppendStore appendStore) {
        this.session = session;
        this.appendStore = appendStore;
    }

    @Override
    public void connect(Map<String, String> cfg) {
        session.connect(cfg);
    }

    @Override
    public List<MailEntry> list(String path) {
        return session.list(path);
    }

    @Override
    public void download(String path, OutputStream out) {
        session.download(path, out);
    }

    @Override
    public void delete(String path) {
        session.delete(path);
    }

    @Override
    public boolean exists(String path) {
        return session.exists(path);
    }

    @Override
    public MailEntry stat(String path) {
        return session.stat(path);
    }

    @Override
    public void mkdir(String dir) {
        // phase2 logical path: 真 IMAP CREATE 子邮箱待协议层；占位 .keep 由 MailFileStorage 写入
    }

    @Override
    public void append(String folder, InputStream mimeMessage, long sizeBytes) {
        appendStore.append(folder, mimeMessage, sizeBytes);
    }

    @Override
    public void close() {
        session.close();
    }
}
