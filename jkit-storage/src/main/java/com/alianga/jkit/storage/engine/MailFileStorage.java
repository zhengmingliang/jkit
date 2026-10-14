package com.alianga.jkit.storage.engine;

import com.alianga.jkit.notify.Attachment;
import com.alianga.jkit.notify.ChannelConfig;
import com.alianga.jkit.notify.MailMime;
import com.alianga.jkit.notify.Message;
import com.alianga.jkit.storage.FileStorage;
import com.alianga.jkit.storage.StorageConfig;
import com.alianga.jkit.storage.StorageEntry;
import com.alianga.jkit.storage.StorageErrorCode;
import com.alianga.jkit.storage.StorageException;
import com.alianga.jkit.storage.StoragePaths;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * 邮件（IMAP）存储引擎一期：把邮箱文件夹当成扁平文件盘。
 *
 * <p>路径模型（一期）：{@link StoragePaths#normalize(String)} 后的整段即邮件条目
 * {@code name}（无路径前缀）；根目录 {@code ""} 视为存在的目录；含 {@code /} 的嵌套路径
 * 一律拒绝（{@code INVALID_CONFIG}）。{@code mkdir / copy / rename} 暂不支持。</p>
 *
 * <p>上传：同名先 {@code delete} 再 {@code APPEND}（APPEND 本身不做隐式覆盖）；
 * MIME 经 jkit-notify {@link Attachment} + {@link MailMime}/{@code SmtpChannel} 组装。</p>
 *
 * <p>真实 IMAP 由 {@link ImapMailSession} / {@link ImapAppendStore} 实现。</p>
 *
 * @author 郑明亮
 */
public class MailFileStorage implements FileStorage {
    /** QQ 等邮箱默认单附件上限（约 50MB，取 45MiB 留余量）。 */
    public static final long DEFAULT_MAX_ATTACHMENT_BYTES = 45L * 1024L * 1024L;
    /** 未配置 basePath 时的默认 IMAP 文件夹。 */
    public static final String DEFAULT_FOLDER = "jkit-mail-disk";

    private final MailGateway gateway;
    private final String folder;
    private final long maxAttachmentBytes;

    /**
     * 由配置创建并连接（生产路径，使用 IMAP 桩 / 真实实现）。
     *
     * @param config 连接配置
     */
    public MailFileStorage(StorageConfig config) {
        this(config, newDefaultGateway());
    }

    /**
     * 可注入网关的包内构造（单测用 Fake）。
     *
     * @param config  连接配置
     * @param gateway 邮件网关
     */
    MailFileStorage(StorageConfig config, MailGateway gateway) {
        if (config == null) {
            throw StorageException.invalidConfig("MailFileStorage 配置不能为 null");
        }
        if (gateway == null) {
            throw StorageException.invalidConfig("MailGateway 不能为 null");
        }
        this.gateway = gateway;
        this.folder = config.getOr("basePath", DEFAULT_FOLDER);
        this.maxAttachmentBytes = config.longOr("maxAttachmentBytes", DEFAULT_MAX_ATTACHMENT_BYTES);
        this.gateway.connect(config.asMap());
    }

    private static MailGateway newDefaultGateway() {
        ImapMailSession session = new ImapMailSession();
        return new DefaultMailGateway(session, new ImapAppendStore(session));
    }

    @Override
    public String platform() {
        return "mail";
    }

    @Override
    public boolean exists(String path) {
        String normalized = StoragePaths.normalize(path);
        if (normalized.isEmpty()) {
            return true;
        }
        if (isNested(normalized)) {
            return false;
        }
        return gateway.exists(normalized);
    }

    @Override
    public StorageEntry stat(String path) {
        String normalized = StoragePaths.normalize(path);
        if (normalized.isEmpty()) {
            return StorageEntry.dir("", 0L);
        }
        requireFlat(normalized);
        MailEntry entry = gateway.stat(normalized);
        return toStorageEntry(entry);
    }

    @Override
    public List<StorageEntry> list(String dir) {
        String normalized = StoragePaths.normalize(dir);
        if (isNested(normalized)) {
            throw StorageException.invalidConfig("邮件存储一期仅支持扁平路径，不支持嵌套目录: " + normalized);
        }
        if (!normalized.isEmpty()) {
            throw StorageException.notFound(normalized);
        }
        List<MailEntry> mails = gateway.list(normalized);
        List<StorageEntry> result = new ArrayList<StorageEntry>();
        if (mails == null) {
            return result;
        }
        for (MailEntry entry : mails) {
            if (entry == null) {
                continue;
            }
            result.add(toStorageEntry(entry));
        }
        return result;
    }

    @Override
    public void mkdir(String dir) {
        throw StorageException.unsupported("邮件存储一期不支持 mkdir");
    }

    @Override
    public void upload(String path, InputStream in, long size) {
        String name = requireFlatFile(path);
        byte[] payload = readPayload(in, size, name);
        if (payload.length > maxAttachmentBytes) {
            throw StorageException.conflict(
                    "附件超过上限 " + maxAttachmentBytes + " 字节: " + name, name);
        }
        if (gateway.exists(name)) {
            try {
                gateway.delete(name);
            } catch (StorageException e) {
                if (e.code() != StorageErrorCode.NOT_FOUND) {
                    throw e;
                }
            }
        }
        byte[] mime = buildStoredMime(name, payload);
        gateway.append(folder, new ByteArrayInputStream(mime), mime.length);
    }

    @Override
    public void download(String path, OutputStream out) {
        String name = requireFlatFile(path);
        gateway.download(name, out);
    }

    @Override
    public void delete(String path) {
        String normalized = StoragePaths.normalize(path);
        if (normalized.isEmpty()) {
            throw StorageException.invalidConfig("拒绝删除存储根目录");
        }
        if (isNested(normalized)) {
            return;
        }
        try {
            gateway.delete(normalized);
        } catch (StorageException e) {
            if (e.code() == StorageErrorCode.NOT_FOUND) {
                return;
            }
            throw e;
        }
    }

    @Override
    public void copy(String src, String dst) {
        throw StorageException.unsupported("邮件存储一期不支持 copy");
    }

    @Override
    public void rename(String src, String dst) {
        throw StorageException.unsupported("邮件存储一期不支持 rename");
    }

    @Override
    public void close() {
        gateway.close();
    }

    private static StorageEntry toStorageEntry(MailEntry entry) {
        String name = entry.name();
        if (entry.directory()) {
            return StorageEntry.dir(name, entry.mtimeEpochMs());
        }
        return StorageEntry.file(name, entry.size(), entry.mtimeEpochMs(), entry.uid());
    }

    private static boolean isNested(String normalized) {
        return normalized.indexOf('/') >= 0;
    }

    private static void requireFlat(String normalized) {
        if (isNested(normalized)) {
            throw StorageException.invalidConfig(
                    "邮件存储一期仅支持扁平路径，不支持嵌套目录: " + normalized);
        }
    }

    private static String requireFlatFile(String path) {
        String normalized = StoragePaths.normalize(path);
        if (normalized.isEmpty()) {
            throw StorageException.invalidConfig("文件路径不能为空");
        }
        requireFlat(normalized);
        return normalized;
    }

    private static byte[] readPayload(InputStream in, long size, String name) {
        if (in == null) {
            return new byte[0];
        }
        try {
            if (size >= 0L && size <= Integer.MAX_VALUE) {
                byte[] buf = new byte[(int) size];
                int off = 0;
                while (off < buf.length) {
                    int read = in.read(buf, off, buf.length - off);
                    if (read < 0) {
                        break;
                    }
                    off += read;
                }
                if (off == buf.length) {
                    return buf;
                }
                byte[] exact = new byte[off];
                System.arraycopy(buf, 0, exact, 0, off);
                return exact;
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream(8192);
            byte[] chunk = new byte[8192];
            int read;
            while ((read = in.read(chunk)) >= 0) {
                out.write(chunk, 0, read);
            }
            return out.toByteArray();
        } catch (IOException e) {
            throw StorageException.io("读取上传内容失败: " + name, name, e);
        }
    }

    /**
     * 经 jkit-notify {@link Attachment} + {@link MailMime} 组装存储用 RFC822。
     *
     * @param fileName 附件文件名（同时作 Subject）
     * @param payload  附件字节
     * @return 完整 RFC822 字节
     */
    static byte[] buildStoredMime(String fileName, byte[] payload) {
        Attachment attachment = Attachment.of(fileName, payload == null ? new byte[0] : payload);
        Message message = Message.text(fileName, "Stored by jkit-storage MailFileStorage.")
                .attachment(attachment);
        ChannelConfig mimeCfg = ChannelConfig.smtp("127.0.0.1", 25)
                .from("jkit-storage@localhost")
                .to("jkit-storage@localhost");
        return MailMime.render(message, "jkit-storage@localhost", mimeCfg);
    }
}
