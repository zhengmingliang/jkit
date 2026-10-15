package com.alianga.jkit.storage.engine;

import com.alianga.jkit.storage.StorageEntry;
import com.alianga.jkit.storage.StorageErrorCode;
import com.alianga.jkit.storage.StorageException;

import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TimeZone;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 纯 JDK IMAP 会话：基于 {@link Socket}/{@link SSLSocket}，零 JavaMail 依赖，
 * 供 {@link MailFileStorage} / {@link ImapAppendStore} 使用。
 *
 * <p>二期路径模型：{@code basePath} 为根邮箱；逻辑目录映射为真 IMAP 子文件夹；
 * 文件 Subject/附件名为路径最后一段。{@link MailEntry#uid()} 使用 IMAP UID。
 * 若服务端禁止 {@code CREATE base/child}（如 QQ Invalid top folder name），
 * 自动降级为 {@code base.child.seg} 扁平编码，逻辑路径仍用 {@code /}。
 * 公开方法均在会话锁下串行，避免 SELECT 交错。</p>
 *
 * @author 郑明亮
 */
public final class ImapMailSession implements AutoCloseable {
    /** 连接超时（毫秒）。 */
    static final int CONNECT_TIMEOUT_MS = 15_000;
    /** 读写超时（毫秒）。 */
    static final int SO_TIMEOUT_MS = 120_000;
    /** 未配置时的默认文件夹。 */
    static final String DEFAULT_FOLDER = MailFileStorage.DEFAULT_FOLDER;
    /** 单附件默认上限。 */
    static final long DEFAULT_MAX_ATTACHMENT_BYTES = MailFileStorage.DEFAULT_MAX_ATTACHMENT_BYTES;

    private static final Pattern UIDVALIDITY = Pattern.compile(
            "\\[UIDVALIDITY\\s+(\\d+)\\]", Pattern.CASE_INSENSITIVE);
    private static final Pattern LITERAL_AT_END = Pattern.compile("\\{(\\d+)\\}\\s*$");
    private static final Pattern FETCH_UID = Pattern.compile("\\bUID\\s+(\\d+)\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern FETCH_SIZE = Pattern.compile("\\bRFC822\\.SIZE\\s+(\\d+)\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern FETCH_INTERNALDATE = Pattern.compile(
            "\\bINTERNALDATE\\s+\"([^\"]+)\"", Pattern.CASE_INSENSITIVE);
    private static final Pattern FILENAME_ATTR = Pattern.compile(
            "FILENAME\"\\s+\"([^\"]+)\"", Pattern.CASE_INSENSITIVE);
    private static final Pattern NAME_ATTR = Pattern.compile(
            "\"NAME\"\\s+\"([^\"]+)\"", Pattern.CASE_INSENSITIVE);
    private static final Pattern ENCODED_WORD = Pattern.compile(
            "=\\?([^?]+)\\?([bBqQ])\\?([^?]*)\\?=");
    /** 两个 encoded-word 之间仅空白时应视为相邻（RFC2047）。 */
    private static final Pattern ENCODED_WORD_GAP = Pattern.compile(
            "\\?=\\s+=\\?");

    private final AtomicInteger tagSeq = new AtomicInteger(1);
    /** 会话级串行锁（单连接 SELECT 状态不可并发）。 */
    private final Object lock = new Object();
    private Socket socket;
    private InputStream rawIn;
    private OutputStream rawOut;
    private String selectedFolder;
    /** 最近一次 SELECT 应答中的 UIDVALIDITY；未见为 -1。 */
    private long lastUidValidity = -1L;
    /** 外来附件索引：已解析的 IMAP 文件夹 → (UIDVALIDITY, 显示名 → uid#section 等)。 */
    private final Map<String, FolderIndex> foreignIndex = new HashMap<String, FolderIndex>();
    /** 配置的根邮箱（basePath）。 */
    private String baseFolder = DEFAULT_FOLDER;
    /** LIST/NAMESPACE 报告的层级分隔符，默认 {@code /}。 */
    private String delimiter = "/";
    /**
     * QQ 等禁止在自建顶层箱下用 {@code /} CREATE 子箱时，改用
     * {@code base.seg1.seg2} 扁平编码（逻辑路径仍用 {@code /}）。
     */
    private boolean flatDotHierarchy;
    /** 层级方式是否已确认（首次 mkdir 时才用 CREATE 探测；连接时绝不写邮箱）。 */
    private boolean hierarchyProbed = true;
    /** list 单个文件夹时最多取最新多少封（配置键 {@code maxListMails}，0 不限）。 */
    private int maxListMails = DEFAULT_MAX_LIST_MAILS;
    /** 批量 UID FETCH 每批封数。 */
    static final int FETCH_CHUNK = 300;
    /** {@code maxListMails} 默认值。 */
    static final int DEFAULT_MAX_LIST_MAILS = 50;
    /** basePath 取该值时表示整个邮箱（根下是顶层文件夹，含 其他文件夹）。 */
    public static final String WHOLE_MAILBOX = "/";
    private long maxAttachmentBytes = DEFAULT_MAX_ATTACHMENT_BYTES;
    private boolean connected;
    /** NO 且含 busy 时的最大重试次数。 */
    private int busyMaxRetries = 3;
    /** busy 重试首个退避（毫秒），之后指数翻倍；配置键 {@code busyRetryDelayMs}。 */
    private long busyBaseDelayMs = 5000L;
    /** 可注入的休眠（单测不真睡）。 */
    private Sleeper sleeper = Thread::sleep;

    /** 退避休眠接口。 */
    interface Sleeper {
        void sleep(long millis) throws InterruptedException;
    }

    /**
     * 按配置建立 IMAP 连接并选中文件夹。
     *
     * @param cfg 配置键值对：host / user / password / imapPort / ssl / basePath / maxAttachmentBytes
     */
    public void connect(Map<String, String> cfg) {
        if (cfg == null) {
            throw StorageException.invalidConfig("ImapMailSession 配置不能为 null");
        }
        String host = trimToNull(cfg.get("host"));
        if (host == null) {
            throw StorageException.invalidConfig("缺少必填配置: host");
        }
        String user = trimToNull(cfg.get("user"));
        if (user == null) {
            throw StorageException.invalidConfig("缺少必填配置: user");
        }
        String password = cfg.get("password");
        if (password == null || password.isEmpty()) {
            throw StorageException.invalidConfig("缺少必填配置: password");
        }
        int port = parsePort(cfg.get("imapPort"), 993);
        boolean ssl = parseBool(cfg.get("ssl"), true);
        String folder = trimToNull(cfg.get("basePath"));
        if (folder == null) {
            folder = DEFAULT_FOLDER;
        }
        boolean whole = WHOLE_MAILBOX.equals(folder);
        this.maxListMails = (int) Math.max(0L, parseLong(cfg.get("maxListMails"), DEFAULT_MAX_LIST_MAILS));
        this.maxAttachmentBytes = parseLong(cfg.get("maxAttachmentBytes"), DEFAULT_MAX_ATTACHMENT_BYTES);
        this.busyBaseDelayMs = Math.max(0L, parseLong(cfg.get("busyRetryDelayMs"), 5000L));
        synchronized (lock) {
            closeQuietly();
            this.baseFolder = folder;
            this.delimiter = "/";
            try {
                openSocket(host, port, ssl);
                readGreeting();
                taggedOk("LOGIN " + quote(user) + " " + quote(password), "LOGIN");
                this.delimiter = detectDelimiter();
                initAfterLogin(folder, whole);
                connected = true;
            } catch (StorageException e) {
                closeQuietly();
                throw e;
            } catch (IOException e) {
                closeQuietly();
                throw StorageException.connectFailed("IMAP 连接失败: " + host + ":" + port + " — " + e.getMessage());
            }
        }
    }

    /**
     * 登录后的初始化：解析 basePath、选中根文件夹。只有 LIST 成功且确实不存在时才 CREATE；
     * 连接时不再 CREATE 探测子箱层级。
     *
     * @param folder 配置的 basePath
     * @param whole  是否整个邮箱模式（basePath = {@value #WHOLE_MAILBOX}）
     */
    void initAfterLogin(String folder, boolean whole) throws IOException {
        this.flatDotHierarchy = false;
        this.hierarchyProbed = false;
        if (whole) {
            this.baseFolder = "";
            this.hierarchyProbed = true;
        } else {
            BaseResolution base = resolveBase(folder);
            folder = base.folder;
            this.baseFolder = folder;
            this.flatDotHierarchy = base.flatDot;
            if (base.exists) {
                selectExistingOrThrow(folder);
            } else {
                ensureSelected(folder);
                LOG.info("IMAP basePath 已自动创建文件夹: " + folder
                        + "（LIST 中既无同名也无 " + QQ_OTHER_FOLDERS + delimiter + folder
                        + "；若邮箱开启了「文件夹区域加锁」或未勾选「收取我的文件夹」，"
                        + "已有文件夹可能对 IMAP 不可见，请检查后改用正确 basePath）");
            }
        }
    }

    /**
     * 关闭会话。
     */
    @Override
    public void close() {
        synchronized (lock) {
            if (!connected && socket == null) {
                return;
            }
            try {
                if (rawOut != null && socket != null && !socket.isClosed()) {
                    try {
                        String tag = nextTag();
                        sendRaw(tag + " LOGOUT\r\n");
                    } catch (IOException ignored) {
                        // ignore logout errors
                    }
                }
            } finally {
                closeQuietly();
                connected = false;
                selectedFolder = null;
            }
        }
    }

    /**
     * 列出指定逻辑目录下的条目：子 IMAP 邮箱（目录）+ 当前文件夹内邮件（文件）。
     * {@code path} 为空时对 {@code basePath} 做递归展开，{@link MailEntry#name()} 为相对
     * {@code basePath} 的完整逻辑路径，供 {@link MailFileStorage} 建索引。
     *
     * @param path 相对 basePath 的目录；空串表示根
     * @return 条目列表
     */
    public List<MailEntry> list(String path) {
        synchronized (lock) {
            requireConnected();
            String rel = path == null ? "" : path.trim();
            if (rel.startsWith("/")) {
                rel = rel.substring(1);
            }
            try {
                if (rel.isEmpty()) {
                    return listAllUnderBase();
                }
                return listImmediate(rel);
            } catch (IOException e) {
                throw StorageException.io("IMAP list 失败", rel, e);
            }
        }
    }

    /**
     * 只列一层（不递归子文件夹）：子文件夹作为目录条目 + 本文件夹最新 {@code maxListMails} 封邮件。
     *
     * @param path 相对 basePath 的目录；空串表示根
     * @return 条目（name 为相对 basePath 的完整逻辑路径）
     */
    public List<MailEntry> listDir(String path) {
        return listDirPage(path, 0, maxListMails).entries;
    }

    /**
     * 分页列一层：第 0 页含子文件夹；邮件按 UID 降序（新邮件在前）取第 {@code page} 页。
     *
     * @param path 相对目录
     * @param page 页号，从 0 起
     * @param size 每页封数；≤0 不分页
     * @return 一页（total 为该文件夹邮件总封数）
     */
    public MailGateway.MailPage listDirPage(String path, int page, int size) {
        synchronized (lock) {
            requireConnected();
            String rel = normalizeRel(path == null ? "" : path);
            try {
                pageNow = Math.max(0, page);
                pageSize = size;
                lastTotal = -1L;
                List<MailEntry> entries = listImmediate(rel);
                if (pageNow > 0) {
                    List<MailEntry> mailsOnly = new ArrayList<MailEntry>();
                    for (MailEntry e : entries) {
                        if (!e.directory()) {
                            mailsOnly.add(e);
                        }
                    }
                    entries = mailsOnly;
                }
                return new MailGateway.MailPage(entries, lastTotal, pageNow, size);
            } catch (IOException e) {
                throw StorageException.io("IMAP list 失败", rel, e);
            } finally {
                pageNow = 0;
                pageSize = -1;
            }
        }
    }

    /** listDirPage 进行中的页参数（-1 表示用 maxListMails）。 */
    private int pageNow;
    private int pageSize = -1;
    private long lastTotal = -1L;

    /**
     * 在 basePath 下逐级 CREATE 真 IMAP 子文件夹；已存在则幂等。
     *
     * @param dir 相对逻辑目录
     */
    public void mkdir(String dir) {
        synchronized (lock) {
            requireConnected();
            String rel = normalizeRel(dir);
            if (rel.isEmpty()) {
                return;
            }
            try {
                ensureMailboxPath(rel);
            } catch (IOException e) {
                throw StorageException.io("IMAP mkdir 失败", rel, e);
            }
        }
    }

    /**
     * 删除相对逻辑目录对应的 IMAP 邮箱（先删深层子邮箱）；不存在则忽略。
     *
     * @param dir 相对逻辑目录
     */
    public void rmdir(String dir) {
        synchronized (lock) {
            requireConnected();
            String rel = normalizeRel(dir);
            if (rel.isEmpty()) {
                return;
            }
            try {
                deleteMailboxTree(rel);
            } catch (IOException e) {
                throw StorageException.io("IMAP rmdir 失败", rel, e);
            }
        }
    }

    /**
     * 下载指定逻辑路径对应的附件内容到输出流。
     *
     * @param path 逻辑路径（可含目录）
     * @param out  目标输出流
     */
    public void download(String path, OutputStream out) {
        synchronized (lock) {
            requireConnected();
            if (out == null) {
                throw StorageException.invalidConfig("download 输出流不能为 null");
            }
            ResolvedPath resolved = resolveLogicalFile(path);
            try {
                ensureSelected(resolved.imapFolder);
                ForeignHit foreign = findForeign(resolved.mailName);
                if (foreign != null) {
                    out.write(downloadForeign(resolved.mailName, foreign));
                    out.flush();
                    return;
                }
                long uid = findUidByName(resolved.mailName);
                if (uid < 0L) {
                    throw StorageException.notFound(resolved.logical);
                }
                byte[] mime = fetchBody(uid);
                byte[] payload = extractAttachmentPayload(mime, resolved.mailName);
                out.write(payload);
                out.flush();
            } catch (StorageException e) {
                throw e;
            } catch (IOException e) {
                throw StorageException.io("IMAP download 失败: " + resolved.logical, resolved.logical, e);
            }
        }
    }

    /**
     * 删除指定逻辑路径对应的邮件。找不到时抛 {@code NOT_FOUND}。
     *
     * @param path 逻辑路径
     */
    public void delete(String path) {
        synchronized (lock) {
            requireConnected();
            ResolvedPath resolved = resolveLogicalFile(path);
            try {
                ensureSelected(resolved.imapFolder);
                if (findForeign(resolved.mailName) != null) {
                    throw StorageException.unsupported("外来邮件附件只读，不能删除: " + resolved.logical);
                }
                long uid = findUidByName(resolved.mailName);
                if (uid < 0L) {
                    throw StorageException.notFound(resolved.logical);
                }
                taggedOk("UID STORE " + uid + " +FLAGS.SILENT (\\Deleted)", "STORE");
                taggedOk("EXPUNGE", "EXPUNGE");
            } catch (StorageException e) {
                throw e;
            } catch (IOException e) {
                throw StorageException.io("IMAP delete 失败: " + resolved.logical, resolved.logical, e);
            }
        }
    }

    /**
     * 判断逻辑路径是否存在（文件或由 LIST 可见的目录）。
     *
     * @param path 逻辑路径
     * @return true 表示存在
     */
    public boolean exists(String path) {
        synchronized (lock) {
            requireConnected();
            if (path == null || path.trim().isEmpty()) {
                return true;
            }
            String logical = normalizeRel(path);
            try {
                if (mailboxExists(logical)) {
                    return true;
                }
                ResolvedPath resolved = resolveLogicalFile(logical);
                ensureSelected(resolved.imapFolder);
                return findForeign(resolved.mailName) != null || findUidByName(resolved.mailName) >= 0L;
            } catch (IOException e) {
                throw StorageException.io("IMAP exists 失败: " + logical, logical, e);
            }
        }
    }

    /**
     * 查询条目元数据。找不到抛 {@link StorageException}（{@code NOT_FOUND}）。
     *
     * @param path 逻辑路径
     * @return 元数据（目录时 {@link MailEntry#directory()} 为 true）
     */
    public MailEntry stat(String path) {
        synchronized (lock) {
            requireConnected();
            String logical = normalizeRel(path);
            try {
                if (mailboxExists(logical)) {
                    return new MailEntry(logical, true, 0L, -1L, null);
                }
                ResolvedPath resolved = resolveLogicalFile(logical);
                ensureSelected(resolved.imapFolder);
                ForeignHit foreign = findForeign(resolved.mailName);
                if (foreign != null) {
                    return foreign.entry.withName(logical);
                }
                long uid = findUidByName(resolved.mailName);
                if (uid < 0L) {
                    throw StorageException.notFound(logical);
                }
                MailEntry entry = fetchMeta(uid);
                if (entry == null) {
                    throw StorageException.notFound(logical);
                }
                return entry.withName(logical);
            } catch (StorageException e) {
                throw e;
            } catch (IOException e) {
                throw StorageException.io("IMAP stat 失败: " + logical, logical, e);
            }
        }
    }

    /**
     * 将完整 MIME 追加到指定 IMAP 文件夹（供 {@link ImapAppendStore} 调用）。
     * {@code folder} 可为绝对邮箱名，或相对 basePath 的逻辑目录（空则 basePath）。
     *
     * @param folder      IMAP 文件夹或相对目录
     * @param mimeMessage MIME 流
     * @param sizeBytes   已知字节数；未知传 {@code -1}（将整段缓冲）
     */
    void appendMime(String folder, InputStream mimeMessage, long sizeBytes) {
        synchronized (lock) {
            requireConnected();
            if (mimeMessage == null) {
                throw StorageException.invalidConfig("APPEND MIME 流不能为 null");
            }
            String target = resolveAppendFolder(folder);
            try {
                byte[] mime;
                if (sizeBytes >= 0L && sizeBytes <= Integer.MAX_VALUE) {
                    mime = readExactFrom(mimeMessage, (int) sizeBytes);
                } else {
                    mime = readAll(mimeMessage);
                }
                if (mime.length > MailChunking.maxMimeBytes(maxAttachmentBytes)) {
                    throw new StorageException(StorageErrorCode.CONFLICT,
                            "MIME 超过可接受大小: " + mime.length, target);
                }
                ensureMailboxAbsolute(target);
                if (!target.equals(selectedFolder)) {
                    ensureSelected(target);
                }
                String messageId = extractMessageId(mime);
                for (int attempt = 0; ; attempt++) {
                    TaggedResult result = appendOnce(target, mime);
                    if (result.ok) {
                        verifyAppended(target, mime, messageId, parseAppendUid(result.text));
                        return;
                    }
                    if (!isBusy(result.text) || attempt >= busyMaxRetries) {
                        throw StorageException.serverError(
                                "APPEND 失败: " + result.text, target);
                    }
                    backoff(attempt);
                    // APPEND 非幂等：上次可能已入箱，按 Message-ID 查重
                    long existing = messageId == null ? -1L : findUidByMessageId(messageId);
                    if (existing > 0L) {
                        verifyAppended(target, mime, messageId, existing);
                        return;
                    }
                }
            } catch (StorageException e) {
                throw e;
            } catch (IOException e) {
                throw StorageException.io("IMAP APPEND 失败", target, e);
            }
        }
    }

    // ------------------------------------------------------------------ hierarchy

    private List<MailEntry> listAllUnderBase() throws IOException {
        List<MailEntry> result = new ArrayList<MailEntry>();
        Set<String> dirs = new LinkedHashSet<String>();
        for (String abs : listMailboxesUnder(baseFolder, true)) {
            String rel = relativeFromBase(abs);
            if (rel == null || rel.isEmpty()) {
                continue;
            }
            dirs.add(rel);
        }
        for (String rel : dirs) {
            result.add(new MailEntry(rel, true, 0L, -1L, null));
        }
        // 根及所有子文件夹中的邮件
        Set<String> folders = new LinkedHashSet<String>();
        folders.add("");
        folders.addAll(dirs);
        for (String rel : folders) {
            String abs = toImapFolder(rel);
            if (abs.isEmpty() || !selectIfExists(abs)) {
                continue;
            }
            collectMails(rel, result, 0);
        }
        return result;
    }

    private List<MailEntry> listImmediate(String relDir) throws IOException {
        List<MailEntry> result = new ArrayList<MailEntry>();
        String abs = toImapFolder(relDir);
        boolean selectable;
        if (abs.isEmpty()) {
            selectable = false;
        } else {
            TaggedResult select = tagged("SELECT " + quoteMailbox(abs));
            selectable = select.ok;
            if (selectable) {
                onSelected(abs);
            } else if (!absMailboxListed(abs)) {
                throw StorageException.notFound(relDir);
            } else if (listMailboxesUnder(abs, false).isEmpty()) {
                // LIST 可见、SELECT 被拒且无子箱：多为文件夹加锁 / 未开放 IMAP
                throw new StorageException(StorageErrorCode.AUTH_FAILED,
                        "IMAP 文件夹无法打开: " + relDir + "（" + select.text
                                + "）；可能开启了「文件夹区域加锁」或未勾选「收取我的文件夹」", null);
            }
        }
        String expectPrefix = relDir.isEmpty() ? "" : relDir + "/";
        for (String childAbs : listMailboxesUnder(abs, false)) {
            String childRel = relativeFromBase(childAbs);
            if (childRel == null || childRel.isEmpty() || !childRel.startsWith(expectPrefix)) {
                continue;
            }
            String rest = childRel.substring(expectPrefix.length());
            if (rest.isEmpty() || rest.indexOf('/') >= 0) {
                continue;
            }
            result.add(new MailEntry(childRel, true, 0L, -1L, null));
        }
        if (selectable) {
            collectMails(relDir, result);
        }
        return result;
    }

    private void ensureMailboxPath(String relDir) throws IOException {
        if (!hierarchyProbed) {
            // 只有真的要建子文件夹时才 CREATE 探测一次层级方式
            if (!flatDotHierarchy) {
                flatDotHierarchy = !probeSlashHierarchy(baseFolder);
            }
            hierarchyProbed = true;
        }
        String current = "";
        for (String segment : relDir.split("/")) {
            if (segment.isEmpty()) {
                continue;
            }
            current = current.isEmpty() ? segment : current + "/" + segment;
            ensureMailboxAbsolute(toImapFolder(current));
        }
    }

    private void ensureMailboxAbsolute(String absFolder) throws IOException {
        if (absFolder == null || absFolder.isEmpty()) {
            return;
        }
        if (absFolder.equals(selectedFolder)) {
            return;
        }
        TaggedResult select = tagged("SELECT " + quoteMailbox(absFolder));
        if (select.ok) {
            onSelected(absFolder);
            return;
        }
        TaggedResult create = taggedCreate(absFolder);
        if (!create.ok && !looksLikeAlreadyExists(create.text)) {
            throw StorageException.serverError(
                    "无法创建 IMAP 文件夹 " + absFolder + ": " + create.text, absFolder);
        }
        TaggedResult again = tagged("SELECT " + quoteMailbox(absFolder));
        if (!again.ok) {
            throw StorageException.serverError(
                    "无法选中 IMAP 文件夹 " + absFolder + ": " + again.text, absFolder);
        }
        onSelected(absFolder);
    }

    private void deleteMailboxTree(String relDir) throws IOException {
        String abs = toImapFolder(relDir);
        List<String> all = new ArrayList<String>();
        for (String child : listMailboxesUnder(abs, true)) {
            all.add(child);
        }
        all.add(abs);
        // 深层优先
        all.sort((a, b) -> Integer.compare(b.length(), a.length()));
        // 离开可能被删的 selected
        if (selectedFolder != null) {
            for (String victim : all) {
                if (selectedFolder.equals(victim) || selectedFolder.startsWith(victim + delimiter)) {
                    if (baseFolder.isEmpty()) {
                        tagged("UNSELECT");
                        selectedFolder = null;
                    } else {
                        ensureSelected(baseFolder);
                    }
                    break;
                }
            }
        }
        for (String mailbox : all) {
            TaggedResult del = taggedDelete(mailbox);
            if (!del.ok && !looksLikeNotFound(del.text)) {
                // 非空或不支持时尽量继续
                if (!looksLikeAlreadyExists(del.text)) {
                    // ignore soft failures for missing
                }
            }
        }
    }

    private boolean mailboxExists(String relDir) throws IOException {
        if (relDir == null || relDir.isEmpty()) {
            return true;
        }
        String abs = toImapFolder(relDir);
        if (abs.equals(baseFolder)) {
            return true;
        }
        for (String found : listMailboxesUnder(baseFolder, true)) {
            if (abs.equals(found)) {
                return true;
            }
        }
        // 直接 SELECT 探测
        TaggedResult select = tagged("SELECT " + quoteMailbox(abs));
        if (select.ok) {
            onSelected(abs);
            return true;
        }
        return false;
    }

    private boolean selectIfExists(String absFolder) throws IOException {
        TaggedResult select = tagged("SELECT " + quoteMailbox(absFolder));
        if (select.ok) {
            onSelected(absFolder);
            return true;
        }
        return false;
    }

    private List<String> listMailboxesUnder(String absParent, boolean recursive) throws IOException {
        String listRef;
        String pattern;
        if (flatDotHierarchy) {
            listRef = "";
            pattern = absParent + ".*";
        } else if (absParent.isEmpty()) {
            listRef = "";
            pattern = recursive ? "*" : "%";
        } else {
            // QQ 对 LIST "<父>" "%" 返回空，必须写成 LIST "" "<父>/%"
            listRef = "";
            pattern = absParent + delimiter + (recursive ? "*" : "%");
        }
        String tag = nextTag();
        sendRaw(tag + " LIST " + quoteMailbox(listRef) + " " + quoteMailbox(pattern) + "\r\n");
        List<String> names = new ArrayList<String>();
        while (true) {
            String line = readLine();
            if (line == null) {
                throw StorageException.serverError("LIST 连接中断", absParent);
            }
            if (line.startsWith("* LIST ") || line.startsWith("* list ")) {
                String name = listLineName(line);
                if (name != null && !name.isEmpty() && !name.equals(absParent)) {
                    if (flatDotHierarchy && !isFlatDotChild(absParent, name, recursive)) {
                        continue;
                    }
                    names.add(name);
                }
                continue;
            }
            if (line.startsWith(tag + " ")) {
                if (!line.regionMatches(true, tag.length() + 1, "OK", 0, 2)) {
                    throw StorageException.serverError("LIST 失败: " + line, absParent);
                }
                break;
            }
        }
        return names;
    }

    private boolean isFlatDotChild(String absParent, String candidate, boolean recursive) {
        String prefix = absParent + ".";
        if (!candidate.startsWith(prefix)) {
            return false;
        }
        String rest = candidate.substring(prefix.length());
        if (rest.isEmpty()) {
            return false;
        }
        if (recursive) {
            return true;
        }
        return rest.indexOf('.') < 0;
    }

    /**
     * 探测 base 下能否用服务器分隔符 CREATE 真子箱。
     *
     * @param base 根邮箱
     * @return true 可用 {@code base/child}；false 需 flatDotHierarchy
     */
    private boolean probeSlashHierarchy(String base) throws IOException {
        String probe = base + delimiter + "__jkit_hier_probe__";
        TaggedResult create = taggedCreate(probe);
        if (create.ok) {
            taggedDelete(probe);
            return true;
        }
        return false;
    }

    /** QQ 网页自建文件夹在 IMAP 中挂的父目录名。 */
    static final String QQ_OTHER_FOLDERS = "其他文件夹";
    private static final java.util.logging.Logger LOG =
            java.util.logging.Logger.getLogger(ImapMailSession.class.getName());

    /**
     * 按 {@code LIST "" "*"} 把用户输入的 basePath 解析为服务器上实际存在的邮箱：
     * 先认完全一致，再认 {@code 其他文件夹<delim>输入}；都不存在则原样返回（随后自动 CREATE）。
     *
     * @param input 用户填写的 basePath（Unicode）
     * @return 实际使用的邮箱名（Unicode）
     */
    String resolveBaseFolder(String input) throws IOException {
        return resolveBase(input).folder;
    }

    /** basePath 解析结果。 */
    static final class BaseResolution {
        final String folder;
        final boolean exists;
        final boolean flatDot;

        BaseResolution(String folder, boolean exists, boolean flatDot) {
            this.folder = folder;
            this.exists = exists;
            this.flatDot = flatDot;
        }
    }

    /**
     * LIST 必须成功（OK）才可能走自动创建；LIST 失败 / 中断 / 超时一律报错，绝不 CREATE。
     * 层级方式从 LIST 结果推断（只读），不再在连接时 CREATE 探测子箱。
     */
    BaseResolution resolveBase(String input) throws IOException {
        String wanted = "/".equals(delimiter) ? input : input.replace("/", delimiter);
        java.util.Set<String> names = new java.util.LinkedHashSet<String>(listAllMailboxNames(input));
        String chosen;
        String reason;
        boolean exists = true;
        String other = QQ_OTHER_FOLDERS + delimiter + wanted;
        if (names.contains(wanted)) {
            chosen = wanted;
            reason = "完全匹配";
        } else if (names.contains(other)) {
            chosen = other;
            reason = "匹配" + QQ_OTHER_FOLDERS + "下的自建文件夹";
        } else {
            chosen = wanted;
            exists = false;
            reason = "服务器上不存在，将自动创建";
        }
        boolean dotChild = false;
        boolean delimChild = false;
        for (String n : names) {
            if (n.startsWith(chosen + ".")) {
                dotChild = true;
            }
            if (n.startsWith(chosen + delimiter)) {
                delimChild = true;
            }
        }
        boolean flat = !".".equals(delimiter) && dotChild && !delimChild;
        LOG.info("IMAP basePath 解析: 输入=" + input + " 实际=" + chosen + "（" + reason + "）");
        return new BaseResolution(chosen, exists, flat);
    }

    /** {@code LIST "" "*"}：返回全部邮箱名（Unicode）；非 OK 或连接中断抛错。 */
    private List<String> listAllMailboxNames(String context) throws IOException {
        String tag = nextTag();
        sendRaw(tag + " LIST \"\" \"*\"\r\n");
        List<String> names = new ArrayList<String>();
        while (true) {
            String line = readLine();
            if (line == null) {
                throw StorageException.serverError("LIST 连接中断（未自动创建文件夹）", context);
            }
            if (line.startsWith(tag + " ")) {
                if (!line.regionMatches(true, tag.length() + 1, "OK", 0, 2)) {
                    throw StorageException.serverError("LIST 失败（未自动创建文件夹，请检查「文件夹区域加锁」"
                            + "与「收取我的文件夹」设置）: " + line, context);
                }
                return names;
            }
            if (line.regionMatches(true, 0, "* LIST", 0, 6)) {
                String name = listLineName(line);
                if (name != null) {
                    names.add(name);
                }
            }
        }
    }

    /** 解析一行 {@code * LIST}；名字以 {@code {n}} 字面量给出时读出字面量。 */
    private String listLineName(String line) throws IOException {
        Matcher lit = LITERAL_AT_END.matcher(line);
        if (lit.find()) {
            byte[] raw = readExact(Integer.parseInt(lit.group(1)));
            readLine();
            return ImapUtf7.decode(new String(raw, StandardCharsets.US_ASCII));
        }
        return parseListMailboxName(line);
    }

    /** 选中已存在（LIST 可见）的邮箱；失败时报明确错误，绝不 CREATE。 */
    private void selectExistingOrThrow(String folder) throws IOException {
        TaggedResult select = tagged("SELECT " + quoteMailbox(folder));
        if (select.ok) {
            onSelected(folder);
            return;
        }
        throw new StorageException(StorageErrorCode.AUTH_FAILED,
                "IMAP 文件夹存在但无法打开: " + folder + "（" + select.text
                        + "）；可能开启了「文件夹区域加锁」或未勾选「收取我的文件夹」", null);
    }

    private String detectDelimiter() throws IOException {
        String tag = nextTag();
        sendRaw(tag + " LIST \"\" \"\"\r\n");
        String found = "/";
        while (true) {
            String line = readLine();
            if (line == null) {
                break;
            }
            if (line.startsWith("* LIST ") || line.startsWith("* list ")) {
                String d = parseListDelimiter(line);
                if (d != null && !d.isEmpty()) {
                    found = d;
                }
                continue;
            }
            if (line.startsWith(tag + " ")) {
                break;
            }
        }
        return found;
    }

    private static String parseListDelimiter(String line) {
        // * LIST (\Noselect) "/" ""
        int attrsEnd = line.indexOf(')');
        if (attrsEnd < 0) {
            return null;
        }
        String rest = line.substring(attrsEnd + 1).trim();
        if (rest.startsWith("\"") && rest.length() >= 3) {
            return rest.substring(1, 2);
        }
        int sp = rest.indexOf(' ');
        if (sp > 0) {
            return rest.substring(0, sp);
        }
        return null;
    }

    private static String parseListMailboxName(String line) {
        return ImapUtf7.decode(parseListMailboxNameRaw(line));
    }

    private static String parseListMailboxNameRaw(String line) {
        // * LIST (\HasNoChildren) "/" "INBOX/foo"
        // * LIST () "/" INBOX
        int attrsEnd = line.indexOf(')');
        if (attrsEnd < 0) {
            return null;
        }
        String rest = line.substring(attrsEnd + 1).trim();
        // skip delimiter token
        if (rest.startsWith("\"")) {
            int end = rest.indexOf('"', 1);
            if (end < 0) {
                return null;
            }
            rest = rest.substring(end + 1).trim();
        } else {
            int sp = rest.indexOf(' ');
            if (sp < 0) {
                return null;
            }
            rest = rest.substring(sp + 1).trim();
        }
        if (rest.startsWith("\"")) {
            StringBuilder sb = new StringBuilder();
            for (int i = 1; i < rest.length(); i++) {
                char c = rest.charAt(i);
                if (c == '\\' && i + 1 < rest.length()) {
                    sb.append(rest.charAt(++i));
                    continue;
                }
                if (c == '"') {
                    break;
                }
                sb.append(c);
            }
            return sb.toString();
        }
        int sp = rest.indexOf(' ');
        return sp < 0 ? rest : rest.substring(0, sp);
    }

    private String toImapFolder(String relDir) {
        String rel = normalizeRel(relDir);
        if (rel.isEmpty()) {
            return baseFolder;
        }
        if (baseFolder.isEmpty()) {
            return "/".equals(delimiter) ? rel : rel.replace("/", delimiter);
        }
        if (flatDotHierarchy) {
            return baseFolder + "." + rel.replace("/", ".");
        }
        if ("/".equals(delimiter)) {
            return baseFolder + "/" + rel;
        }
        return baseFolder + delimiter + rel.replace("/", delimiter);
    }

    private String relativeFromBase(String absFolder) {
        if (absFolder == null) {
            return null;
        }
        if (absFolder.equals(baseFolder)) {
            return "";
        }
        if (baseFolder.isEmpty()) {
            return "/".equals(delimiter) ? absFolder : absFolder.replace(delimiter, "/");
        }
        if (flatDotHierarchy) {
            String prefix = baseFolder + ".";
            if (absFolder.startsWith(prefix)) {
                return absFolder.substring(prefix.length()).replace(".", "/");
            }
            return null;
        }
        String prefix = baseFolder + delimiter;
        if (absFolder.startsWith(prefix)) {
            String rel = absFolder.substring(prefix.length());
            if (!"/".equals(delimiter)) {
                rel = rel.replace(delimiter, "/");
            }
            return rel;
        }
        prefix = baseFolder + "/";
        if (absFolder.startsWith(prefix)) {
            return absFolder.substring(prefix.length());
        }
        return null;
    }

    private String resolveAppendFolder(String folder) {
        String raw = trimToNull(folder);
        if (raw == null) {
            return baseFolder;
        }
        if (raw.equals(baseFolder)) {
            return baseFolder;
        }
        // 上层可能拼出 base/docs；flatDot 下需转成 base.docs
        if (raw.startsWith(baseFolder + "/")) {
            String rel = raw.substring(baseFolder.length() + 1);
            return toImapFolder(rel);
        }
        if (raw.startsWith(baseFolder + delimiter) && !"/".equals(delimiter)) {
            String rel = raw.substring(baseFolder.length() + delimiter.length());
            return toImapFolder(rel.replace(delimiter, "/"));
        }
        if (raw.startsWith(baseFolder + ".")) {
            return raw;
        }
        // 相对逻辑目录
        return toImapFolder(raw);
    }

    private ResolvedPath resolveLogicalFile(String path) {
        String logical = normalizeRel(path);
        if (logical.isEmpty()) {
            throw StorageException.invalidConfig("文件路径不能为空");
        }
        int at = logical.lastIndexOf('/');
        String parent = at < 0 ? "" : logical.substring(0, at);
        String name = at < 0 ? logical : logical.substring(at + 1);
        return new ResolvedPath(logical, toImapFolder(parent), name);
    }

    private static String normalizeRel(String path) {
        if (path == null) {
            return "";
        }
        String unified = path.replace('\\', '/').trim();
        while (unified.startsWith("/")) {
            unified = unified.substring(1);
        }
        while (unified.endsWith("/")) {
            unified = unified.substring(0, unified.length() - 1);
        }
        if (unified.isEmpty() || ".".equals(unified)) {
            return "";
        }
        return unified;
    }

    private static boolean looksLikeNotFound(String text) {
        if (text == null) {
            return false;
        }
        String lower = text.toLowerCase(Locale.ROOT);
        return lower.contains("not found") || lower.contains("doesn't exist")
                || lower.contains("does not exist") || lower.contains("no such");
    }

    private static final class ResolvedPath {
        final String logical;
        final String imapFolder;
        final String mailName;

        ResolvedPath(String logical, String imapFolder, String mailName) {
            this.logical = logical;
            this.imapFolder = imapFolder;
            this.mailName = mailName;
        }
    }

    // ------------------------------------------------------------------ wiring

    private void openSocket(String host, int port, boolean ssl) throws IOException {
        Socket s;
        if (ssl) {
            SSLSocketFactory factory = (SSLSocketFactory) SSLSocketFactory.getDefault();
            SSLSocket sslSocket = (SSLSocket) factory.createSocket();
            sslSocket.connect(new InetSocketAddress(host, port), CONNECT_TIMEOUT_MS);
            sslSocket.setSoTimeout(SO_TIMEOUT_MS);
            try {
                sslSocket.startHandshake();
            } catch (IOException e) {
                closeQuietly(sslSocket);
                throw e;
            }
            s = sslSocket;
        } else {
            s = new Socket();
            s.connect(new InetSocketAddress(host, port), CONNECT_TIMEOUT_MS);
            s.setSoTimeout(SO_TIMEOUT_MS);
        }
        this.socket = s;
        // 必须带缓冲：readLine 逐字节读，直接读 SSL 流每字节都是一次解密调用，500 封要几十秒
        this.rawIn = new java.io.BufferedInputStream(s.getInputStream(), 64 * 1024);
        this.rawOut = s.getOutputStream();
    }

    private void readGreeting() throws IOException {
        String line = readLine();
        if (line == null || (!line.startsWith("* OK") && !line.startsWith("* PREAUTH"))) {
            throw StorageException.connectFailed("IMAP 问候异常: " + line);
        }
    }

    private void ensureSelected(String folder) throws IOException {
        TaggedResult select = tagged("SELECT " + quoteMailbox(folder));
        if (select.ok) {
            onSelected(folder);
            return;
        }
        TaggedResult create = taggedCreate(folder);
        if (!create.ok && !looksLikeAlreadyExists(create.text)) {
            throw StorageException.serverError(
                    "无法创建 IMAP 文件夹 " + folder + ": " + create.text, folder);
        }
        TaggedResult again = tagged("SELECT " + quoteMailbox(folder));
        if (!again.ok) {
            throw StorageException.serverError(
                    "无法选中 IMAP 文件夹 " + folder + ": " + again.text, folder);
        }
        onSelected(folder);
    }

    private static boolean looksLikeAlreadyExists(String text) {
        if (text == null) {
            return false;
        }
        String lower = text.toLowerCase(Locale.ROOT);
        return lower.contains("already exists") || lower.contains("exists");
    }

    private List<Long> uidSearch(String criteria) throws IOException {
        String tag = nextTag();
        sendRaw(tag + " UID SEARCH " + criteria + "\r\n");
        List<Long> uids = new ArrayList<Long>();
        while (true) {
            String line = readLine();
            if (line == null) {
                throw StorageException.serverError("UID SEARCH 连接中断", selectedFolder);
            }
            if (line.startsWith("* SEARCH") || line.startsWith("* search")) {
                String rest = line.length() > 8 ? line.substring(8).trim() : "";
                if (rest.toUpperCase(Locale.ROOT).startsWith("SEARCH")) {
                    rest = rest.substring(6).trim();
                }
                if (!rest.isEmpty()) {
                    String[] parts = rest.split("\\s+");
                    for (String p : parts) {
                        if (p.isEmpty()) {
                            continue;
                        }
                        try {
                            uids.add(Long.parseLong(p));
                        } catch (NumberFormatException ignored) {
                            // skip
                        }
                    }
                }
                continue;
            }
            if (line.startsWith(tag + " ")) {
                if (!line.regionMatches(true, tag.length() + 1, "OK", 0, 2)) {
                    throw StorageException.serverError("UID SEARCH 失败: " + line, selectedFolder);
                }
                break;
            }
        }
        return uids;
    }

    private long findUidByName(String name) throws IOException {
        // 优先按 Subject 头搜索；再回退为扫描列表（兼容部分服务端 HEADER 搜索差异）
        List<Long> byHeader = uidSearch("HEADER Subject " + quote(name));
        if (!byHeader.isEmpty()) {
            // 取最大 UID（最新）
            long best = -1L;
            for (Long u : byHeader) {
                if (u != null && u.longValue() > best) {
                    best = u.longValue();
                }
            }
            if (best >= 0L) {
                MailEntry meta = fetchMeta(best);
                if (meta != null && !meta.foreign() && nameEquals(meta.name(), name)) {
                    return best;
                }
            }
            // HEADER 可能子串命中，精确过滤
            for (int i = byHeader.size() - 1; i >= 0; i--) {
                long uid = byHeader.get(i).longValue();
                MailEntry meta = fetchMeta(uid);
                if (meta != null && !meta.foreign() && nameEquals(meta.name(), name)) {
                    return uid;
                }
            }
        }
        List<Long> all = uidSearch("ALL");
        for (int i = all.size() - 1; i >= 0; i--) {
            long uid = all.get(i).longValue();
            MailEntry meta = fetchMeta(uid);
            if (meta != null && !meta.foreign() && nameEquals(meta.name(), name)) {
                return uid;
            }
        }
        return -1L;
    }

    /** 单测：直接解析一次 FETCH 元数据。 */
    MailEntry fetchMetaForTest(long uid) {
        try {
            return fetchMeta(uid);
        } catch (IOException e) {
            throw StorageException.io("fetchMeta", "uid=" + uid, e);
        }
    }

    /**
     * 单封邮件元数据。jkit 写入的邮件返回其文件条目；外来邮件返回 {@code foreign=true}
     * 的占位条目（名称不可用于匹配，调用方据此跳过）。
     */
    private MailEntry fetchMeta(long uid) throws IOException {
        FetchMessage msg = fetchRaw(uid);
        if (msg == null) {
            return null;
        }
        if (!isJkitMail(msg.headerText)) {
            return new MailEntry("", false, -1L, parseInternalDate(msg.internalDate),
                    String.valueOf(uid), true, null);
        }
        return jkitEntry(uid, msg);
    }

    /**
     * 一次 FETCH 取齐 list 所需：INTERNALDATE、识别/属性用头（SUBJECT FROM DATE MESSAGE-ID
     * X-JKIT-*）与 BODYSTRUCTURE；外来附件枚举不再额外往返。
     */
    private FetchMessage fetchRaw(long uid) throws IOException {
        String tag = nextTag();
        sendRaw(tag + " UID FETCH " + uid + " (UID INTERNALDATE RFC822.SIZE " + META_FIELDS
                + " BODYSTRUCTURE)\r\n");
        return readFetchMessage(tag, uid);
    }

    private MailEntry jkitEntry(long uid, FetchMessage msg) {
        String subject = parseSubject(msg.headerText);
        if (subject == null || subject.isEmpty()) {
            subject = parseFilenameFromStructure(msg.structureText);
        }
        if (subject == null || subject.isEmpty()) {
            subject = "uid-" + uid;
        }
        long size = payloadSize(msg.headerText, msg.structureText);
        long mtime = parseInternalDate(msg.internalDate);
        Map<String, String> attrs = new LinkedHashMap<String, String>();
        attrs.put(StorageEntry.ATTR_MAIL_SUBJECT, subject);
        String date = mailDateIso(headerValue(msg.headerText, "Date"), msg.internalDate);
        if (date != null) {
            attrs.put(StorageEntry.ATTR_MAIL_DATE, date);
        }
        // Subject 中的 / 不是路径分隔符：换成 U+2215 显示，目录只来自 IMAP 文件夹
        return new MailEntry(jkitDisplayName(subject), false, size, mtime, String.valueOf(uid), false, attrs);
    }

    /** jkit 文件名：Subject 原样，仅把 {@code /} 换成 U+2215（避免被当路径拆分）。 */
    static String jkitDisplayName(String subject) {
        return subject == null ? "" : subject.replace('/', SLASH_STANDIN);
    }

    private void collectMails(String rel, List<MailEntry> result) throws IOException {
        int size = pageSize >= 0 ? pageSize : maxListMails;
        FolderIndex index = freshIndex();
        List<Long> all = newestFirst(uidSearch("ALL"), 0);
        lastTotal = all.size();
        List<Long> uids;
        if (size <= 0) {
            uids = all;
        } else {
            int from = (int) Math.min((long) pageNow * size, all.size());
            uids = all.subList(from, Math.min(all.size(), from + size));
        }
        collectFrom(rel, result, index, uids);
    }

    /** @param limit 最多取最新多少封；0 不限（写路径的全量索引用） */
    private void collectMails(String rel, List<MailEntry> result, int limit) throws IOException {
        FolderIndex index = freshIndex();
        collectFrom(rel, result, index, newestFirst(uidSearch("ALL"), limit));
    }

    private void collectFrom(String rel, List<MailEntry> result, FolderIndex index, List<Long> uids)
            throws IOException {
        for (FetchMessage msg : fetchMetaBatched(uids)) {
            if (msg == null || msg.uid <= 0L) {
                continue;
            }
            long uid = msg.uid;
            boolean jkit = isJkitMail(msg.headerText);
            if (!jkit) {
                indexForeign(index, uid, msg);
            }
            List<MailEntry> entries = jkit
                    ? Collections.singletonList(jkitEntry(uid, msg))
                    : foreignMailEntries(uid, msg);
            for (MailEntry e : entries) {
                if (e.name().isEmpty()) {
                    continue;
                }
                result.add(e.withName(rel.isEmpty() ? e.name() : rel + "/" + e.name()));
            }
        }
    }

    /** UID 降序（新邮件在前），截取前 {@code limit} 个；limit≤0 不截。 */
    static List<Long> newestFirst(List<Long> uids, int limit) {
        List<Long> sorted = new ArrayList<Long>(uids);
        sorted.sort(Collections.reverseOrder());
        if (limit > 0 && sorted.size() > limit) {
            return new ArrayList<Long>(sorted.subList(0, limit));
        }
        return sorted;
    }

    /** 按 {@link #FETCH_CHUNK} 分批 UID FETCH 头字段 + BODYSTRUCTURE（不取正文），保持入参顺序。 */
    private List<FetchMessage> fetchMetaBatched(List<Long> uids) throws IOException {
        List<FetchMessage> out = new ArrayList<FetchMessage>(uids.size());
        for (int from = 0; from < uids.size(); from += FETCH_CHUNK) {
            List<Long> chunk = uids.subList(from, Math.min(uids.size(), from + FETCH_CHUNK));
            StringBuilder set = new StringBuilder();
            for (Long u : chunk) {
                if (set.length() > 0) {
                    set.append(',');
                }
                set.append(u.longValue());
            }
            String tag = nextTag();
            sendRaw(tag + " UID FETCH " + set + " (UID INTERNALDATE RFC822.SIZE " + META_FIELDS
                    + " BODYSTRUCTURE)\r\n");
            List<FetchMessage> msgs = readFetchMessages(tag, false);
            Map<Long, FetchMessage> byUid = new HashMap<Long, FetchMessage>();
            for (FetchMessage m : msgs) {
                byUid.put(m.uid, m);
            }
            for (Long u : chunk) {
                FetchMessage m = byUid.get(u);
                if (m != null) {
                    out.add(m);
                }
            }
        }
        return out;
    }

    /**
     * 外来（非 jkit 写入）邮件的唯一出口：按附件逐个列出，无附件的不列。
     * 名称 {@code <base>__<shortId>.<ext>}，shortId = sha256(uid + "\n" + section) 前 8 位 hex。
     */
    List<MailEntry> foreignMailEntries(long uid, FetchMessage msg) {
        List<AttachmentPart> parts = attachmentParts(msg.structureText);
        if (parts.isEmpty()) {
            return Collections.emptyList();
        }
        Map<String, String> attrs = mailAttrs(msg);
        attrs.put(StorageEntry.ATTR_MAIL_FOREIGN, "true");
        long mtime = parseInternalDate(msg.internalDate);
        List<MailEntry> out = new ArrayList<MailEntry>(parts.size());
        for (AttachmentPart p : parts) {
            out.add(new MailEntry(foreignDisplayName(p.filename, uid, p.section), false, p.decodedSize(),
                    mtime, uid + "#" + p.section, true, attrs));
        }
        return out;
    }

    private static Map<String, String> mailAttrs(FetchMessage msg) {
        Map<String, String> attrs = new LinkedHashMap<String, String>();
        String subject = parseSubject(msg.headerText);
        if (subject != null) {
            attrs.put(StorageEntry.ATTR_MAIL_SUBJECT, subject);
        }
        String from = headerValue(msg.headerText, "From");
        if (from != null) {
            attrs.put(StorageEntry.ATTR_MAIL_FROM, decodeMimeWords(from));
        }
        String date = mailDateIso(headerValue(msg.headerText, "Date"), msg.internalDate);
        if (date != null) {
            attrs.put(StorageEntry.ATTR_MAIL_DATE, date);
        }
        return attrs;
    }

    /**
     * 当前选中文件夹内按显示名找外来附件；名称不符合 {@code __xxxxxxxx} 形态直接返回 null。
     * 先查索引（命中后用一次 BODYSTRUCTURE 校验该段文件名与 shortId 仍一致，QQ 的 UID 不可靠）；
     * 未命中或校验失败时回退扫描：UID SEARCH ALL + 一次批量 FETCH 头与 BODYSTRUCTURE，绝不取整封邮件。
     */
    private ForeignHit findForeign(String name) throws IOException {
        if (name == null || !FOREIGN_NAME.matcher(name).find()) {
            return null;
        }
        ForeignHit cached = indexLookup(name);
        if (cached != null) {
            ForeignHit verified = verifyHit(name, cached);
            if (verified != null) {
                return verified;
            }
            dropIndexEntry(name);
        }
        return scanForeign(name);
    }

    /** 索引命中后校验：只取该 UID 的 BODYSTRUCTURE；段文件名或 shortId 不符返回 null。 */
    private ForeignHit verifyHit(String name, ForeignHit hit) throws IOException {
        String tag = nextTag();
        sendRaw(tag + " UID FETCH " + hit.uid + " (UID BODYSTRUCTURE)\r\n");
        List<FetchMessage> msgs = readFetchMessages(tag, true);
        if (msgs == null || msgs.isEmpty()) {
            return null;
        }
        FetchMessage msg = msgs.get(0);
        if (msg.uid > 0L && msg.uid != hit.uid) {
            return null;
        }
        for (AttachmentPart p : attachmentParts(msg.structureText)) {
            if (p.section.equals(hit.part.section)) {
                boolean same = p.filename == null ? hit.part.filename == null : p.filename.equals(hit.part.filename);
                if (same && name.equals(foreignDisplayName(p.filename, hit.uid, p.section))) {
                    return new ForeignHit(hit.uid, p, hit.entry);
                }
                return null;
            }
        }
        return null;
    }

    /** 回退扫描：UID SEARCH ALL + 单次批量 UID FETCH（头字段 + BODYSTRUCTURE），并重建本文件夹索引。 */
    private ForeignHit scanForeign(String name) throws IOException {
        FolderIndex index = freshIndex();
        List<Long> uids = uidSearch("ALL");
        if (uids.isEmpty()) {
            return null;
        }
        for (FetchMessage msg : fetchMetaBatched(uids)) {
            if (msg.uid > 0L && !isJkitMail(msg.headerText)) {
                indexForeign(index, msg.uid, msg);
            }
        }
        return index.hits.get(name);
    }

    /** 外来附件正文：只取 BODY.PEEK[section]；NO 或空（邮件已移走/删除）返回 null。 */
    private byte[] fetchForeignPayload(ForeignHit hit) throws IOException {
        String tag = nextTag();
        sendRaw(tag + " UID FETCH " + hit.uid + " (UID BODY.PEEK[" + hit.part.section + "])\r\n");
        List<FetchMessage> msgs = readFetchMessages(tag, true);
        if (msgs == null || msgs.isEmpty() || msgs.get(msgs.size() - 1).body == null) {
            return null;
        }
        return decodeTransfer(msgs.get(msgs.size() - 1).body, hit.part.encoding);
    }

    /** 下载外来附件：索引条目失效（正文取不到）时丢弃并回退扫描一次。 */
    private byte[] downloadForeign(String name, ForeignHit hit) throws IOException {
        byte[] data = fetchForeignPayload(hit);
        if (data != null) {
            return data;
        }
        dropIndexEntry(name);
        ForeignHit again = scanForeign(name);
        data = again == null ? null : fetchForeignPayload(again);
        if (data == null) {
            throw StorageException.notFound(name);
        }
        return data;
    }

    // ---- 外来附件索引

    private static final class FolderIndex {
        final long uidValidity;
        final Map<String, ForeignHit> hits = new HashMap<String, ForeignHit>();

        FolderIndex(long uidValidity) {
            this.uidValidity = uidValidity;
        }
    }

    /** SELECT 成功后记录：UIDVALIDITY 变化则丢掉该文件夹整份索引。 */
    private void onSelected(String folder) {
        selectedFolder = folder;
        FolderIndex idx = folder == null ? null : foreignIndex.get(folder);
        if (idx != null && lastUidValidity >= 0L && idx.uidValidity != lastUidValidity) {
            foreignIndex.remove(folder);
        }
    }

    /** 为当前选中文件夹新建（替换）一份索引。 */
    private FolderIndex freshIndex() {
        FolderIndex idx = new FolderIndex(lastUidValidity);
        if (selectedFolder != null) {
            foreignIndex.put(selectedFolder, idx);
        }
        return idx;
    }

    private void indexForeign(FolderIndex index, long uid, FetchMessage msg) {
        List<AttachmentPart> parts = attachmentParts(msg.structureText);
        List<MailEntry> entries = foreignMailEntries(uid, msg);
        for (int i = 0; i < entries.size() && i < parts.size(); i++) {
            index.hits.put(entries.get(i).name(), new ForeignHit(uid, parts.get(i), entries.get(i)));
        }
    }

    private ForeignHit indexLookup(String name) {
        FolderIndex idx = selectedFolder == null ? null : foreignIndex.get(selectedFolder);
        if (idx == null) {
            return null;
        }
        if (lastUidValidity >= 0L && idx.uidValidity != lastUidValidity) {
            foreignIndex.remove(selectedFolder);
            return null;
        }
        return idx.hits.get(name);
    }

    private void dropIndexEntry(String name) {
        FolderIndex idx = selectedFolder == null ? null : foreignIndex.get(selectedFolder);
        if (idx != null) {
            idx.hits.remove(name);
        }
    }

    /** 单测：当前文件夹索引条目数。 */
    int foreignIndexSizeForTest() {
        FolderIndex idx = selectedFolder == null ? null : foreignIndex.get(selectedFolder);
        return idx == null ? 0 : idx.hits.size();
    }

    /** 读取一个 tag 下的全部 FETCH 应答；{@code noIsEmpty} 时 NO/BAD 返回 null 而不抛异常。 */
    private List<FetchMessage> readFetchMessages(String tag, boolean noIsEmpty) throws IOException {
        List<FetchMessage> out = new ArrayList<FetchMessage>();
        while (true) {
            String line = readLine();
            if (line == null) {
                throw StorageException.serverError("FETCH 连接中断", selectedFolder);
            }
            if (line.startsWith("* ") && line.toUpperCase(Locale.ROOT).contains("FETCH")) {
                out.add(parseFetchLine(line));
                continue;
            }
            if (line.startsWith(tag + " ")) {
                if (!line.regionMatches(true, tag.length() + 1, "OK", 0, 2)) {
                    if (noIsEmpty) {
                        return null;
                    }
                    throw StorageException.serverError("FETCH 失败: " + line, selectedFolder);
                }
                return out;
            }
        }
    }

    private byte[] fetchBody(long uid) throws IOException {
        String tag = nextTag();
        sendRaw(tag + " UID FETCH " + uid + " (UID BODY.PEEK[])\r\n");
        FetchMessage msg = readFetchMessage(tag, uid);
        if (msg == null || msg.body == null) {
            throw StorageException.notFound("uid=" + uid);
        }
        return msg.body;
    }

    private FetchMessage readFetchMessage(String tag, long expectUid) throws IOException {
        FetchMessage current = null;
        while (true) {
            String line = readLine();
            if (line == null) {
                throw StorageException.serverError("FETCH 连接中断", selectedFolder);
            }
            if (line.startsWith("* ") && line.toUpperCase(Locale.ROOT).contains("FETCH")) {
                current = parseFetchLine(line);
                // 行内可能还有未读完的字面量，parseFetchLine 已通过 readLiteralIfNeeded 处理
                continue;
            }
            if (line.startsWith(tag + " ")) {
                if (!line.regionMatches(true, tag.length() + 1, "OK", 0, 2)) {
                    throw StorageException.serverError("FETCH 失败: " + line, selectedFolder);
                }
                break;
            }
        }
        if (current == null) {
            return null;
        }
        if (current.uid > 0L && expectUid > 0L && current.uid != expectUid) {
            // 仍返回，服务端偶发顺序差异
        }
        return current;
    }

    private FetchMessage parseFetchLine(String firstLine) throws IOException {
        // 可能形如: * 1 FETCH (UID 12 RFC822.SIZE 100 BODY[HEADER.FIELDS (SUBJECT)] {20}
        // 随后字面量，再继续 )
        StringBuilder acc = new StringBuilder();
        String line = firstLine;
        byte[] lastLiteral = null;
        byte[] headerLiteral = null;
        String lastLiteralField = null;
        while (true) {
            Matcher lit = LITERAL_AT_END.matcher(line);
            if (lit.find()) {
                int size = Integer.parseInt(lit.group(1));
                String without = line.substring(0, lit.start());
                acc.append(without);
                byte[] data = readExact(size);
                // consume trailing CRLF after literal if present is NOT automatic —
                // IMAP literal is followed by continuation of the same response line OR more.
                // After literal bytes, the next characters continue the FETCH; often ")\r\n" or more fields.
                String fieldHint = without.toUpperCase(Locale.ROOT);
                // QQ: BODY[HEADER.FIELDS (SUBJECT X-JKIT-SIZE)] {37}\r\n<头字面量> BODYSTRUCTURE (...)
                // 须先于 "BODY[" 判定，否则头字面量被当成正文，X-Jkit-Size 读不到
                int lastBody = fieldHint.lastIndexOf("BODY[");
                if (lastBody >= 0 && fieldHint.indexOf("HEADER", lastBody) >= 0) {
                    lastLiteralField = "HEADER";
                    headerLiteral = data;
                    acc.append("{").append(size).append("}");
                } else if (fieldHint.contains("BODY[")) {
                    lastLiteralField = "BODY";
                    lastLiteral = data;
                    acc.append("{").append(size).append("}");
                } else if (fieldHint.contains("HEADER")) {
                    lastLiteralField = "HEADER";
                    lastLiteral = data;
                    acc.append("{").append(size).append("}");
                } else {
                    lastLiteralField = "OTHER";
                    lastLiteral = data;
                    acc.append("{").append(size).append("}");
                }
                // continue reading the remainder of the FETCH response as text lines
                line = readLine();
                if (line == null) {
                    break;
                }
                continue;
            }
            acc.append(line);
            // FETCH 结束：括号平衡且无待续字面量
            if (parenthesesBalanced(acc.toString()) && !LITERAL_AT_END.matcher(line).find()) {
                break;
            }
            line = readLine();
            if (line == null) {
                break;
            }
            acc.append("\r\n");
        }
        String all = acc.toString();
        FetchMessage msg = new FetchMessage();
        Matcher uidM = FETCH_UID.matcher(all);
        if (uidM.find()) {
            msg.uid = Long.parseLong(uidM.group(1));
        }
        Matcher sizeM = FETCH_SIZE.matcher(all);
        if (sizeM.find()) {
            msg.rfc822Size = Long.parseLong(sizeM.group(1));
        }
        Matcher dateM = FETCH_INTERNALDATE.matcher(all);
        if (dateM.find()) {
            msg.internalDate = dateM.group(1);
        }
        int structAt = indexOfIgnoreCase(all, "BODYSTRUCTURE");
        if (structAt >= 0) {
            msg.structureText = all.substring(structAt);
        }
        if (headerLiteral != null) {
            msg.headerText = new String(headerLiteral, StandardCharsets.UTF_8);
        }
        if (msg.headerText != null) {
            // 已取到头字面量
        } else if ("HEADER".equals(lastLiteralField) && lastLiteral != null) {
            msg.headerText = new String(lastLiteral, StandardCharsets.UTF_8);
        } else if ("BODY".equals(lastLiteralField) && lastLiteral != null) {
            msg.body = lastLiteral;
        }
        // 无字面量时，Subject 可能内联（少见）
        if (msg.headerText == null) {
            int sub = indexOfIgnoreCase(all, "SUBJECT");
            if (sub >= 0) {
                msg.headerText = all.substring(sub);
            }
        }
        // BODY.PEEK[] 字面量可能被标成 BODY
        if (msg.body == null && lastLiteral != null && lastLiteral.length > 0
                && (indexOfIgnoreCase(all, "BODY[]") >= 0 || indexOfIgnoreCase(all, "BODY.PEEK[]") >= 0)) {
            msg.body = lastLiteral;
        }
        if (msg.headerText == null && lastLiteral != null
                && indexOfIgnoreCase(all, "HEADER.FIELDS") >= 0) {
            msg.headerText = new String(lastLiteral, StandardCharsets.UTF_8);
        }
        return msg;
    }

    private static boolean parenthesesBalanced(String s) {
        int depth = 0;
        boolean inQuote = false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '"') {
                inQuote = !inQuote;
                continue;
            }
            if (inQuote) {
                continue;
            }
            if (c == '(') {
                depth++;
            } else if (c == ')') {
                depth--;
            }
        }
        return depth <= 0 && s.toUpperCase(Locale.ROOT).contains("FETCH");
    }

    private TaggedResult appendOnce(String target, byte[] mime) throws IOException {
        String tag = nextTag();
        sendRaw(tag + " APPEND " + quoteMailbox(target) + " {" + mime.length + "}\r\n");
        String cont = readLine();
        if (cont != null && cont.startsWith(tag + " ")) {
            boolean ok = cont.regionMatches(true, tag.length() + 1, "OK", 0, 2);
            return new TaggedResult(ok, !ok, cont.substring(tag.length() + 1).trim());
        }
        if (cont == null || !cont.startsWith("+")) {
            throw StorageException.serverError("APPEND 未收到续传应答: " + cont, target);
        }
        rawOut.write(mime);
        rawOut.write(new byte[]{'\r', '\n'});
        rawOut.flush();
        return readUntilTagged(tag);
    }

    /** 只读/幂等命令：busy 时直接重试。 */
    private TaggedResult tagged(String command) throws IOException {
        return taggedRetry(command, null);
    }

    /** CREATE：busy 后若邮箱已存在则视为成功，不再重复 CREATE。 */
    private TaggedResult taggedCreate(final String mailbox) throws IOException {
        return taggedRetry("CREATE " + quoteMailbox(mailbox), () -> absMailboxListed(mailbox));
    }

    /** DELETE：busy 后若邮箱已不存在则视为成功。 */
    private TaggedResult taggedDelete(final String mailbox) throws IOException {
        return taggedRetry("DELETE " + quoteMailbox(mailbox), () -> !absMailboxListed(mailbox));
    }

    /** 重试前检查上次是否其实已生效。 */
    private interface AppliedCheck {
        boolean applied() throws IOException;
    }

    private TaggedResult taggedRetry(String command, AppliedCheck check) throws IOException {
        TaggedResult r = taggedOnce(command);
        for (int attempt = 0; !r.ok && isBusy(r.text) && attempt < busyMaxRetries; attempt++) {
            backoff(attempt);
            if (check != null && check.applied()) {
                return new TaggedResult(true, false, "OK (already applied after busy)");
            }
            r = taggedOnce(command);
        }
        return r;
    }

    static boolean isBusy(String text) {
        return text != null && text.toLowerCase(Locale.ROOT).contains("busy");
    }

    private void backoff(int attempt) {
        long delay = busyBaseDelayMs << Math.min(attempt, 20);
        try {
            sleeper.sleep(delay);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw StorageException.serverError("IMAP busy 重试被中断", selectedFolder);
        }
    }

    private boolean absMailboxListed(String mailbox) throws IOException {
        String tag = nextTag();
        sendRaw(tag + " LIST \"\" " + quoteMailbox(mailbox) + "\r\n");
        boolean found = false;
        while (true) {
            String line = readLine();
            if (line == null) {
                throw StorageException.serverError("LIST 连接中断", mailbox);
            }
            if (line.startsWith(tag + " ")) {
                return found && line.regionMatches(true, tag.length() + 1, "OK", 0, 2);
            }
            if (line.regionMatches(true, 0, "* LIST", 0, 6)) {
                found = true;
            }
            Matcher lit = LITERAL_AT_END.matcher(line);
            if (lit.find()) {
                readExact(Integer.parseInt(lit.group(1)));
            }
        }
    }

    private long findUidByMessageId(String messageId) throws IOException {
        taggedOnce("NOOP");
        List<Long> uids = uidSearch("HEADER Message-ID " + quote(messageId));
        return uids.isEmpty() ? -1L : uids.get(uids.size() - 1).longValue();
    }

    private static final Pattern APPEND_UID = Pattern.compile(
            "\\[APPENDUID\\s+\\d+\\s+(\\d+)\\]", Pattern.CASE_INSENSITIVE);

    static long parseAppendUid(String text) {
        if (text == null) {
            return -1L;
        }
        Matcher m = APPEND_UID.matcher(text);
        return m.find() ? Long.parseLong(m.group(1)) : -1L;
    }

    /**
     * APPEND 后校验落盘：QQ 的 RFC822.SIZE 不可信（实测大邮件也只报 ~900B），
     * 故按 APPENDUID（缺失则 Message-ID）定位，比较 {@code X-Jkit-Size} 与
     * BODYSTRUCTURE 中附件 Base64 段长度；不符则删掉该邮件并抛 IO_ERROR。
     */
    private void verifyAppended(String target, byte[] mime, String messageId, long uid)
            throws IOException {
        long expectRaw = headerLong(mime, "X-Jkit-Size");
        if (uid <= 0L) {
            if (messageId == null) {
                return;
            }
            uid = findUidByMessageId(messageId);
            if (uid <= 0L) {
                throw StorageException.io("APPEND 后找不到邮件（服务端可能丢弃）: " + messageId,
                        target, null);
            }
        }
        if (expectRaw < 0L) {
            return;
        }
        String tag = nextTag();
        sendRaw(tag + " UID FETCH " + uid
                + " (UID BODYSTRUCTURE BODY.PEEK[HEADER.FIELDS (X-JKIT-SIZE)])\r\n");
        FetchMessage msg = readFetchMessage(tag, uid);
        long gotRaw = msg == null ? -1L : parseHeaderLong(msg.headerText, "X-Jkit-Size");
        long gotEnc = msg == null ? -1L : lastBase64Size(msg.structureText);
        long wantEnc = foldedBase64Length(expectRaw);
        if (gotRaw != expectRaw || gotEnc < 0L || Math.abs(gotEnc - wantEnc) > 2L) {
            deleteUidQuiet(uid);
            throw StorageException.io("APPEND 校验失败 uid=" + uid + " X-Jkit-Size=" + gotRaw
                    + "/" + expectRaw + " base64=" + gotEnc + "/" + wantEnc, target, null);
        }
    }

    private void deleteUidQuiet(long uid) {
        try {
            taggedOnce("UID STORE " + uid + " +FLAGS.SILENT (\\Deleted)");
            TaggedResult r = taggedOnce("UID EXPUNGE " + uid);
            if (!r.ok) {
                taggedOnce("EXPUNGE");
            }
        } catch (IOException | StorageException ignored) {
            // 尽力清理，主异常由调用方抛出
        }
    }

    /** 76 列折行（行间 CRLF、末行无 CRLF）的 Base64 长度。 */
    static long foldedBase64Length(long raw) {
        if (raw <= 0L) {
            return 0L;
        }
        long b64 = (raw + 2L) / 3L * 4L;
        long lines = (b64 + 75L) / 76L;
        return b64 + 2L * (lines - 1L);
    }

    /** 由附件 Base64 段长度估算原始字节（遗留邮件无 X-Jkit-Size 时用）。 */
    static long rawFromFoldedBase64(long encoded) {
        if (encoded <= 0L) {
            return 0L;
        }
        long lines = (encoded + 2L) / 78L + 1L;
        long b64 = encoded - 2L * (lines - 1L);
        return Math.max(0L, b64 / 4L * 3L);
    }

    static long lastBase64Size(String structure) {
        if (structure == null) {
            return -1L;
        }
        Matcher m = Pattern.compile("\"BASE64\"\\s+(\\d+)", Pattern.CASE_INSENSITIVE)
                .matcher(structure);
        long last = -1L;
        while (m.find()) {
            last = Long.parseLong(m.group(1));
        }
        return last;
    }

    static long parseHeaderLong(String headerText, String name) {
        if (headerText == null) {
            return -1L;
        }
        for (String line : headerText.split("\r\n|\n")) {
            if (line.regionMatches(true, 0, name + ":", 0, name.length() + 1)) {
                try {
                    return Long.parseLong(line.substring(name.length() + 1).trim());
                } catch (NumberFormatException e) {
                    return -1L;
                }
            }
        }
        return -1L;
    }

    private static long headerLong(byte[] mime, String name) {
        int limit = Math.min(mime.length, 64 * 1024);
        String head = new String(mime, 0, limit, StandardCharsets.ISO_8859_1);
        int end = head.indexOf("\r\n\r\n");
        return parseHeaderLong(end >= 0 ? head.substring(0, end) : head, name);
    }

    /** list/stat 大小：优先 X-Jkit-Size，否则按附件 Base64 段估算；绝不用 RFC822.SIZE。 */
    static long payloadSize(String headerText, String structure) {
        long x = parseHeaderLong(headerText, "X-Jkit-Size");
        if (x >= 0L) {
            return x;
        }
        long enc = lastBase64Size(structure);
        return enc >= 0L ? rawFromFoldedBase64(enc) : -1L;
    }

    static String extractMessageId(byte[] mime) {
        int limit = Math.min(mime.length, 64 * 1024);
        String head = new String(mime, 0, limit, StandardCharsets.ISO_8859_1);
        int end = head.indexOf("\r\n\r\n");
        if (end >= 0) {
            head = head.substring(0, end);
        }
        for (String line : head.split("\r\n")) {
            if (line.regionMatches(true, 0, "Message-ID:", 0, 11)) {
                String v = line.substring(11).trim();
                return v.isEmpty() ? null : v;
            }
        }
        return null;
    }

    /** 单测：直接挂接脚本化流，跳过 socket/LOGIN。 */
    void attachForTest(InputStream in, OutputStream out, String base, Sleeper testSleeper) {
        synchronized (lock) {
            this.rawIn = in;
            this.rawOut = out;
            this.socket = new Socket();
            this.baseFolder = base;
            this.selectedFolder = null;
            this.connected = true;
            if (testSleeper != null) {
                this.sleeper = testSleeper;
            }
        }
    }

    private TaggedResult taggedOnce(String command) throws IOException {
        String tag = nextTag();
        sendRaw(tag + " " + command + "\r\n");
        return readUntilTagged(tag);
    }

    private void taggedOk(String command, String action) throws IOException {
        TaggedResult r = tagged(command);
        if (!r.ok) {
            if ("LOGIN".equals(action)) {
                throw StorageException.authFailed("IMAP 登录失败: " + r.text);
            }
            throw StorageException.serverError(action + " 失败: " + r.text, selectedFolder);
        }
    }

    private TaggedResult readUntilTagged(String tag) throws IOException {
        while (true) {
            String line = readLine();
            if (line == null) {
                throw StorageException.serverError("IMAP 连接中断，等待 " + tag, selectedFolder);
            }
            // 跳过未标记与能力行；FETCH 字面量不应出现在 LOGIN/SELECT 路径
            Matcher uv = UIDVALIDITY.matcher(line);
            if (line.startsWith("* ") && uv.find()) {
                lastUidValidity = Long.parseLong(uv.group(1));
            }
            if (line.startsWith(tag + " ")) {
                boolean ok = line.regionMatches(true, tag.length() + 1, "OK", 0, 2);
                boolean no = line.regionMatches(true, tag.length() + 1, "NO", 0, 2);
                boolean bad = line.regionMatches(true, tag.length() + 1, "BAD", 0, 3);
                String text = line.substring(tag.length() + 1).trim();
                return new TaggedResult(ok, no || bad || !ok, text);
            }
            Matcher lit = LITERAL_AT_END.matcher(line);
            if (lit.find()) {
                int size = Integer.parseInt(lit.group(1));
                readExact(size);
            }
        }
    }

    private String nextTag() {
        return "A" + String.format(Locale.ROOT, "%04d", tagSeq.getAndIncrement());
    }

    private void sendRaw(String data) throws IOException {
        rawOut.write(data.getBytes(StandardCharsets.UTF_8));
        rawOut.flush();
    }

    private String readLine() throws IOException {
        ByteArrayOutputStream buf = new ByteArrayOutputStream(256);
        int prev = -1;
        while (true) {
            int b = rawIn.read();
            if (b < 0) {
                if (buf.size() == 0) {
                    return null;
                }
                break;
            }
            if (prev == '\r' && b == '\n') {
                // drop CR already written
                byte[] all = buf.toByteArray();
                return new String(all, 0, all.length - 1, StandardCharsets.UTF_8);
            }
            buf.write(b);
            prev = b;
            if (buf.size() > 1024 * 1024) {
                throw new IOException("IMAP 响应行过长");
            }
        }
        return new String(buf.toByteArray(), StandardCharsets.UTF_8);
    }

    private byte[] readExact(int size) throws IOException {
        if (size < 0) {
            throw new IOException("非法字面量长度: " + size);
        }
        byte[] data = new byte[size];
        int off = 0;
        while (off < size) {
            int n = rawIn.read(data, off, size - off);
            if (n < 0) {
                throw new IOException("字面量读取中断，已读 " + off + "/" + size);
            }
            off += n;
        }
        return data;
    }

    private void requireConnected() {
        if (!connected || socket == null || socket.isClosed()) {
            throw StorageException.connectFailed("IMAP 会话未连接");
        }
    }

    private void closeQuietly() {
        connected = false;
        try {
            if (rawIn != null) {
                rawIn.close();
            }
        } catch (IOException ignored) {
            // ignore
        }
        try {
            if (rawOut != null) {
                rawOut.close();
            }
        } catch (IOException ignored) {
            // ignore
        }
        closeQuietly(socket);
        socket = null;
        rawIn = null;
        rawOut = null;
    }

    private static void closeQuietly(Socket s) {
        if (s == null) {
            return;
        }
        try {
            s.close();
        } catch (IOException ignored) {
            // ignore
        }
    }

    // ------------------------------------------------------------------ jkit 识别 / 外来附件

    private static final String META_FIELDS =
            "BODY.PEEK[HEADER.FIELDS (SUBJECT FROM DATE MESSAGE-ID X-JKIT-SIZE X-JKIT-TOTAL-SIZE)]";
    /** 外来附件显示名尾部：{@code __} + 8 位 hex + 可选扩展名。 */
    private static final Pattern FOREIGN_NAME = Pattern.compile("__[0-9a-f]{8}(\\.[^./]*)?$");
    private static final char SLASH_STANDIN = '\u2215';
    private static final String JKIT_FROM = "jkit-storage@localhost";
    private static final String JKIT_MSGID_SUFFIX = "@jkit-storage>";

    /**
     * 是否 jkit 写入：任一 {@code X-Jkit-*} 头；或 jkit 生成的 Message-ID（{@code <uuid@jkit-storage>}）；
     * 或遗留邮件（无 X-Jkit-Size，b85b232 之前）的 From {@code jkit-storage@localhost}。
     */
    static boolean isJkitMail(String headerText) {
        if (headerText == null || headerText.isEmpty()) {
            return false;
        }
        for (String line : headerText.split("\r\n|\n")) {
            if (line.regionMatches(true, 0, "X-Jkit-", 0, 7)) {
                return true;
            }
        }
        String msgId = headerValue(headerText, "Message-ID");
        if (msgId != null && msgId.toLowerCase(Locale.ROOT).contains(JKIT_MSGID_SUFFIX)) {
            return true;
        }
        String from = headerValue(headerText, "From");
        return from != null && from.toLowerCase(Locale.ROOT).contains(JKIT_FROM);
    }

    /** 取头字段值（不区分大小写，折行展开）；无则 null。 */
    static String headerValue(String headerText, String name) {
        if (headerText == null) {
            return null;
        }
        StringBuilder value = null;
        for (String line : headerText.split("\r\n|\n")) {
            if (value != null) {
                if (line.startsWith(" ") || line.startsWith("\t")) {
                    value.append(' ').append(line.trim());
                    continue;
                }
                break;
            }
            if (line.regionMatches(true, 0, name + ":", 0, name.length() + 1)) {
                value = new StringBuilder(line.substring(name.length() + 1).trim());
            }
        }
        return value == null ? null : value.toString();
    }

    /** 邮件时间 ISO-8601（带偏移）：优先 Date 头，其次 INTERNALDATE；都解析不了返回 null。 */
    static String mailDateIso(String dateHeader, String internalDate) {
        if (dateHeader != null) {
            String v = dateHeader.replaceAll("\\([^)]*\\)", " ").trim().replaceAll("\\s+", " ");
            String[] patterns = {"EEE, d MMM yyyy HH:mm:ss Z", "d MMM yyyy HH:mm:ss Z",
                    "EEE, d MMM yyyy HH:mm Z", "EEE, d MMM yyyy HH:mm:ss zzz"};
            for (String p : patterns) {
                try {
                    return OffsetDateTime.parse(v, DateTimeFormatter.ofPattern(p, Locale.US))
                            .format(DateTimeFormatter.ISO_OFFSET_DATE_TIME);
                } catch (DateTimeParseException ignored) {
                    // next
                }
            }
            try {
                return OffsetDateTime.parse(v, DateTimeFormatter.RFC_1123_DATE_TIME)
                        .format(DateTimeFormatter.ISO_OFFSET_DATE_TIME);
            } catch (DateTimeParseException ignored) {
                // fall back to INTERNALDATE
            }
        }
        if (internalDate != null) {
            try {
                return OffsetDateTime.parse(internalDate.trim(),
                        DateTimeFormatter.ofPattern("d-MMM-yyyy HH:mm:ss Z", Locale.US))
                        .format(DateTimeFormatter.ISO_OFFSET_DATE_TIME);
            } catch (DateTimeParseException ignored) {
                return null;
            }
        }
        return null;
    }

    /** BODYSTRUCTURE 中的一个附件段。 */
    static final class AttachmentPart {
        final String section;
        final String encoding;
        final long encodedSize;
        final String filename;

        AttachmentPart(String section, String encoding, long encodedSize, String filename) {
            this.section = section;
            this.encoding = encoding;
            this.encodedSize = encodedSize;
            this.filename = filename;
        }

        /** 解码后字节：BASE64 按折行长度精确反推（±2B）；QP 等取编码长度（近似）。 */
        long decodedSize() {
            if (encodedSize < 0L) {
                return -1L;
            }
            if ("BASE64".equalsIgnoreCase(encoding)) {
                return rawFromFoldedBase64(encodedSize);
            }
            return encodedSize;
        }
    }

    private static final class ForeignHit {
        final long uid;
        final AttachmentPart part;
        final MailEntry entry;

        ForeignHit(long uid, AttachmentPart part, MailEntry entry) {
            this.uid = uid;
            this.part = part;
            this.entry = entry;
        }
    }

    /** 解析 BODYSTRUCTURE（可带前缀 "BODYSTRUCTURE "），列出附件段；解析失败返回空。 */
    static List<AttachmentPart> attachmentParts(String structureText) {
        List<AttachmentPart> out = new ArrayList<AttachmentPart>();
        if (structureText == null) {
            return out;
        }
        int start = structureText.indexOf('(');
        if (start < 0) {
            return out;
        }
        try {
            Object root = parseSexp(structureText, new int[] {start});
            if (root instanceof List) {
                walkStructure((List<?>) root, "", out);
            }
        } catch (RuntimeException e) {
            out.clear();
        }
        return out;
    }

    private static Object parseSexp(String s, int[] pos) {
        while (pos[0] < s.length() && Character.isWhitespace(s.charAt(pos[0]))) {
            pos[0]++;
        }
        if (pos[0] >= s.length()) {
            return null;
        }
        char c = s.charAt(pos[0]);
        if (c == '(') {
            pos[0]++;
            List<Object> list = new ArrayList<Object>();
            while (true) {
                while (pos[0] < s.length() && Character.isWhitespace(s.charAt(pos[0]))) {
                    pos[0]++;
                }
                if (pos[0] >= s.length()) {
                    return list;
                }
                if (s.charAt(pos[0]) == ')') {
                    pos[0]++;
                    return list;
                }
                list.add(parseSexp(s, pos));
            }
        }
        if (c == '"') {
            pos[0]++;
            StringBuilder sb = new StringBuilder();
            while (pos[0] < s.length()) {
                char ch = s.charAt(pos[0]);
                if (ch == '\\' && pos[0] + 1 < s.length()) {
                    sb.append(s.charAt(pos[0] + 1));
                    pos[0] += 2;
                    continue;
                }
                pos[0]++;
                if (ch == '"') {
                    break;
                }
                sb.append(ch);
            }
            return sb.toString();
        }
        if (c == '{') {
            // 字面量占位：parseFetchLine 未保留结构内字面量内容
            int end = s.indexOf('}', pos[0]);
            pos[0] = end < 0 ? s.length() : end + 1;
            return null;
        }
        int begin = pos[0];
        while (pos[0] < s.length()) {
            char ch = s.charAt(pos[0]);
            if (Character.isWhitespace(ch) || ch == '(' || ch == ')') {
                break;
            }
            pos[0]++;
        }
        String atom = s.substring(begin, pos[0]);
        if (atom.isEmpty()) {
            pos[0]++;
            return null;
        }
        return "NIL".equalsIgnoreCase(atom) ? null : atom;
    }

    private static void walkStructure(List<?> node, String prefix, List<AttachmentPart> out) {
        if (node.isEmpty()) {
            return;
        }
        if (node.get(0) instanceof List) {
            for (int i = 0; i < node.size() && node.get(i) instanceof List; i++) {
                String sec = prefix.isEmpty() ? String.valueOf(i + 1) : prefix + "." + (i + 1);
                walkStructure((List<?>) node.get(i), sec, out);
            }
            return;
        }
        String section = prefix.isEmpty() ? "1" : prefix;
        String mainType = node.get(0) instanceof String ? (String) node.get(0) : "";
        String subType = node.size() > 1 && node.get(1) instanceof String ? (String) node.get(1) : "";
        String encoding = node.size() > 5 && node.get(5) instanceof String ? (String) node.get(5) : null;
        long size = -1L;
        if (node.size() > 6 && node.get(6) instanceof String) {
            try {
                size = Long.parseLong((String) node.get(6));
            } catch (NumberFormatException ignored) {
                size = -1L;
            }
        }
        List<?> params = node.size() > 2 && node.get(2) instanceof List ? (List<?>) node.get(2) : null;
        String dispType = null;
        List<?> dispParams = null;
        for (int i = 7; i < node.size(); i++) {
            Object o = node.get(i);
            if (o instanceof List && !((List<?>) o).isEmpty() && ((List<?>) o).get(0) instanceof String) {
                List<?> d = (List<?>) o;
                String t = (String) d.get(0);
                if ("attachment".equalsIgnoreCase(t) || "inline".equalsIgnoreCase(t)) {
                    dispType = t;
                    dispParams = d.size() > 1 && d.get(1) instanceof List ? (List<?>) d.get(1) : null;
                    break;
                }
            }
        }
        String filename = paramValue(dispParams, "filename");
        if (filename == null) {
            filename = paramValue(params, "name");
        }
        boolean rfc822 = "message".equalsIgnoreCase(mainType) && "rfc822".equalsIgnoreCase(subType);
        if (rfc822 && filename == null && node.size() > 8 && node.get(8) instanceof List) {
            // 转发的整封邮件：展开内层附件（内层单段正文的 section 为 N.1）
            List<?> inner = (List<?>) node.get(8);
            boolean multi = !inner.isEmpty() && inner.get(0) instanceof List;
            walkStructure(inner, multi ? section : section + ".1", out);
            return;
        }
        if (filename == null && "application".equalsIgnoreCase(mainType)) {
            // 无文件名的二进制段（备份脚本常见）：按段号合成名字
            filename = "part-" + section + "." + (subType.isEmpty() ? "bin" : subType.toLowerCase(Locale.ROOT));
        }
        if (filename == null && !"attachment".equalsIgnoreCase(dispType)) {
            return;
        }
        out.add(new AttachmentPart(section, encoding, size, filename));
    }

    /** 从 BODYSTRUCTURE 参数表取值：RFC2231（含续行、charset）优先，否则 RFC2047 解码。 */
    static String paramValue(List<?> params, String key) {
        if (params == null) {
            return null;
        }
        Map<String, String> m = new LinkedHashMap<String, String>();
        for (int i = 0; i + 1 < params.size(); i += 2) {
            Object k = params.get(i);
            Object v = params.get(i + 1);
            if (k instanceof String && v instanceof String) {
                m.put(((String) k).toLowerCase(Locale.ROOT), (String) v);
            }
        }
        return decodeRfc2231Param(m, key.toLowerCase(Locale.ROOT));
    }

    static String decodeRfc2231Param(Map<String, String> m, String key) {
        String single = m.get(key + "*");
        if (single != null) {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            String charset = extValueInto(single, true, bytes);
            return new String(bytes.toByteArray(), charsetOr(charset));
        }
        if (m.containsKey(key + "*0") || m.containsKey(key + "*0*")) {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            String charset = null;
            for (int i = 0; ; i++) {
                String ext = m.get(key + "*" + i + "*");
                String plain = m.get(key + "*" + i);
                if (ext == null && plain == null) {
                    break;
                }
                if (ext != null) {
                    String cs = extValueInto(ext, i == 0, bytes);
                    if (i == 0) {
                        charset = cs;
                    }
                } else {
                    byte[] b = plain.getBytes(charsetOr(charset));
                    bytes.write(b, 0, b.length);
                }
            }
            return decodeMimeWords(new String(bytes.toByteArray(), charsetOr(charset)));
        }
        String plain = m.get(key);
        return plain == null ? null : decodeMimeWords(plain);
    }

    /** 写入百分号解码字节；首段带 {@code charset'lang'} 前缀时返回 charset。 */
    private static String extValueInto(String value, boolean first, ByteArrayOutputStream bytes) {
        String v = value;
        String charset = null;
        if (first) {
            int q1 = v.indexOf('\'');
            int q2 = q1 < 0 ? -1 : v.indexOf('\'', q1 + 1);
            if (q2 >= 0) {
                charset = v.substring(0, q1);
                v = v.substring(q2 + 1);
            }
        }
        for (int i = 0; i < v.length(); i++) {
            char c = v.charAt(i);
            if (c == '%' && i + 2 < v.length()) {
                int hi = Character.digit(v.charAt(i + 1), 16);
                int lo = Character.digit(v.charAt(i + 2), 16);
                if (hi >= 0 && lo >= 0) {
                    bytes.write((hi << 4) | lo);
                    i += 2;
                    continue;
                }
            }
            byte[] b = String.valueOf(c).getBytes(StandardCharsets.UTF_8);
            bytes.write(b, 0, b.length);
        }
        return charset;
    }

    private static Charset charsetOr(String name) {
        if (name == null || name.trim().isEmpty()) {
            return StandardCharsets.UTF_8;
        }
        try {
            return Charset.forName(name.trim());
        } catch (RuntimeException e) {
            return StandardCharsets.UTF_8;
        }
    }

    /** 外来附件显示名：{@code <base>__<shortId><.ext>}；文件名中的 / 与 \ 换成 _。 */
    static String foreignDisplayName(String filename, long uid, String section) {
        String n = filename == null ? "" : filename.trim().replace('/', '_').replace('\\', '_');
        if (n.isEmpty()) {
            n = "attachment";
        }
        int dot = n.lastIndexOf('.');
        boolean hasExt = dot > 0 && dot < n.length() - 1;
        String base = hasExt ? n.substring(0, dot) : n;
        String ext = hasExt ? n.substring(dot) : "";
        return base + "__" + shortId(uid, section) + ext;
    }

    static String shortId(long uid, String section) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-256")
                    .digest((uid + "\n" + section).getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(8);
            for (int i = 0; i < 4; i++) {
                sb.append(String.format("%02x", d[i] & 0xff));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** 按 Content-Transfer-Encoding 解码附件段。 */
    static byte[] decodeTransfer(byte[] data, String encoding) {
        if (data == null) {
            return new byte[0];
        }
        if ("BASE64".equalsIgnoreCase(encoding)) {
            return Base64.getMimeDecoder().decode(data);
        }
        if ("QUOTED-PRINTABLE".equalsIgnoreCase(encoding)) {
            return decodeQuotedPrintable(data);
        }
        return data;
    }

    static byte[] decodeQuotedPrintable(byte[] data) {
        ByteArrayOutputStream out = new ByteArrayOutputStream(data.length);
        for (int i = 0; i < data.length; i++) {
            byte b = data[i];
            if (b == '=') {
                if (i + 1 < data.length && data[i + 1] == '\n') {
                    i += 1;
                    continue;
                }
                if (i + 2 < data.length && data[i + 1] == '\r' && data[i + 2] == '\n') {
                    i += 2;
                    continue;
                }
                if (i + 2 < data.length) {
                    int hi = Character.digit(data[i + 1], 16);
                    int lo = Character.digit(data[i + 2], 16);
                    if (hi >= 0 && lo >= 0) {
                        out.write((hi << 4) | lo);
                        i += 2;
                        continue;
                    }
                }
            }
            out.write(b);
        }
        return out.toByteArray();
    }

    // ------------------------------------------------------------------ parse helpers

    private static String requireName(String path) {
        if (path == null || path.trim().isEmpty()) {
            throw StorageException.invalidConfig("文件路径不能为空");
        }
        // 目录只来自 IMAP 文件夹；Subject 中的 / 不作路径分隔（list 显示为 U+2215）
        return path.trim();
    }

    /**
     * 逻辑文件名是否相等：两侧都先 RFC2047 解码再比；并兼容一侧仍是 encoded-word。
     */
    static boolean namesMatch(String storedOrDecoded, String requested) {
        if (storedOrDecoded == null || requested == null) {
            return false;
        }
        if (storedOrDecoded.equals(requested)) {
            return true;
        }
        String left = decodeMimeWords(storedOrDecoded);
        String right = decodeMimeWords(requested);
        if (left.equals(right)) {
            return true;
        }
        // 兼容服务端仍回传编码串、调用方给明文（或相反）
        return left.equals(requested) || storedOrDecoded.equals(right);
    }

    private static boolean nameEquals(String a, String b) {
        return namesMatch(a, b);
    }

    private static String parseSubject(String headerText) {
        if (headerText == null || headerText.isEmpty()) {
            return null;
        }
        String[] lines = headerText.split("\r\n|\n");
        StringBuilder subject = null;
        for (String line : lines) {
            if (subject != null) {
                if (line.startsWith(" ") || line.startsWith("\t")) {
                    subject.append(line.trim());
                    continue;
                }
                break;
            }
            if (line.regionMatches(true, 0, "Subject:", 0, 8)) {
                subject = new StringBuilder(line.substring(8).trim());
            }
        }
        if (subject == null) {
            return null;
        }
        return decodeMimeWords(subject.toString().trim());
    }

    private static String parseFilenameFromStructure(String structure) {
        if (structure == null) {
            return null;
        }
        Matcher m = FILENAME_ATTR.matcher(structure);
        if (m.find()) {
            return decodeMimeWords(unquoteImapString(m.group(1)));
        }
        Matcher n = NAME_ATTR.matcher(structure);
        if (n.find()) {
            return decodeMimeWords(unquoteImapString(n.group(1)));
        }
        return null;
    }

    /** 去掉 BODYSTRUCTURE 偶发包裹的反斜杠转义。 */
    private static String unquoteImapString(String raw) {
        if (raw == null || raw.isEmpty()) {
            return raw;
        }
        if (raw.indexOf('\\') < 0) {
            return raw;
        }
        StringBuilder sb = new StringBuilder(raw.length());
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if (c == '\\' && i + 1 < raw.length()) {
                sb.append(raw.charAt(++i));
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    private static long parseInternalDate(String raw) {
        if (raw == null || raw.isEmpty()) {
            return -1L;
        }
        // 形式如: 15-Oct-2026 07:00:00 +0800
        String[] patterns = {
                "dd-MMM-yyyy HH:mm:ss Z",
                "dd-MMM-yyyy HH:mm:ss z"
        };
        for (String p : patterns) {
            try {
                SimpleDateFormat fmt = new SimpleDateFormat(p, Locale.US);
                fmt.setTimeZone(TimeZone.getTimeZone("GMT"));
                Date d = fmt.parse(raw);
                return d.getTime();
            } catch (ParseException ignored) {
                // try next
            }
        }
        return -1L;
    }

    /**
     * 从完整 MIME 中提取主附件字节（与 FakeMailGateway / MailFileStorage 手写 MIME 对齐）。
     */
    static byte[] extractAttachmentPayload(byte[] mime, String expectedName) {
        if (mime == null || mime.length == 0) {
            return new byte[0];
        }
        String text = new String(mime, StandardCharsets.UTF_8);
        String marker = "Content-Transfer-Encoding: base64";
        int searchFrom = 0;
        int bestStart = -1;
        int bestEnd = -1;
        while (true) {
            int encAt = indexOfIgnoreCase(text, marker, searchFrom);
            if (encAt < 0) {
                break;
            }
            // 取该 part 头附近是否像附件
            int partHeaderStart = text.lastIndexOf("\r\n--", encAt);
            if (partHeaderStart < 0) {
                partHeaderStart = Math.max(0, encAt - 300);
            }
            String partHead = text.substring(partHeaderStart, Math.min(text.length(), encAt + 80));
            boolean looksAttachment = partHead.toLowerCase(Locale.ROOT).contains("attachment")
                    || partHead.toLowerCase(Locale.ROOT).contains("filename")
                    || (expectedName != null && partHead.contains(expectedName));
            int bodyStart = text.indexOf("\r\n\r\n", encAt);
            if (bodyStart < 0) {
                break;
            }
            bodyStart += 4;
            int bodyEnd = text.indexOf("\r\n--", bodyStart);
            if (bodyEnd < 0) {
                bodyEnd = text.length();
            }
            if (looksAttachment || bestStart < 0) {
                bestStart = bodyStart;
                bestEnd = bodyEnd;
                if (looksAttachment) {
                    // 优先最后一个像附件的 part
                }
            }
            searchFrom = encAt + marker.length();
        }
        if (bestStart < 0) {
            return new byte[0];
        }
        String b64 = text.substring(bestStart, bestEnd).replace("\r\n", "").replace("\n", "").trim();
        if (b64.isEmpty()) {
            return new byte[0];
        }
        try {
            return Base64.getDecoder().decode(b64);
        } catch (IllegalArgumentException e) {
            throw StorageException.io("附件 Base64 解码失败: " + expectedName, expectedName, e);
        }
    }

    /**
     * 解码 RFC2047 encoded-word（B/Q，UTF-8 等）。相邻 encoded-word 之间的空白按 RFC 丢弃。
     * 包可见供 Fake 网关与单测与活连共用同一套规则。
     */
    static String decodeMimeWords(String raw) {
        if (raw == null || raw.isEmpty() || raw.indexOf("=?") < 0) {
            return raw;
        }
        try {
            // 空白分隔的多个 encoded-word：先压成可匹配形式
            String normalized = ENCODED_WORD_GAP.matcher(raw).replaceAll("?==?");
            Matcher m = ENCODED_WORD.matcher(normalized);
            StringBuffer sb = new StringBuffer();
            boolean any = false;
            while (m.find()) {
                any = true;
                String charset = m.group(1);
                char enc = Character.toUpperCase(m.group(2).charAt(0));
                String data = m.group(3);
                String decoded;
                if (enc == 'B') {
                    byte[] bytes = Base64.getDecoder().decode(data);
                    decoded = new String(bytes, charset);
                } else {
                    decoded = decodeQuotedPrintableWord(data, charset);
                }
                m.appendReplacement(sb, Matcher.quoteReplacement(decoded));
            }
            if (!any) {
                return raw;
            }
            m.appendTail(sb);
            return sb.toString();
        } catch (Exception e) {
            return raw;
        }
    }

    private static String decodeQuotedPrintableWord(String data, String charset) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream(data.length());
        for (int i = 0; i < data.length(); i++) {
            char c = data.charAt(i);
            if (c == '_') {
                out.write(' ');
            } else if (c == '=' && i + 2 < data.length()) {
                int hi = Character.digit(data.charAt(i + 1), 16);
                int lo = Character.digit(data.charAt(i + 2), 16);
                if (hi >= 0 && lo >= 0) {
                    out.write((hi << 4) + lo);
                    i += 2;
                } else {
                    out.write(c);
                }
            } else {
                out.write(c > 0xff ? '?' : (int) c);
            }
        }
        return new String(out.toByteArray(), charset);
    }

    /** 邮箱名统一走 modified UTF-7 再加引号（RFC 3501 5.1.3）。 */
    static String quoteMailbox(String mailbox) {
        return quote(ImapUtf7.encode(mailbox));
    }

    private static String quote(String value) {
        if (value == null) {
            return "\"\"";
        }
        StringBuilder sb = new StringBuilder(value.length() + 2);
        sb.append('"');
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == '\\' || c == '"') {
                sb.append('\\');
            }
            sb.append(c);
        }
        sb.append('"');
        return sb.toString();
    }

    private static String trimToNull(String s) {
        if (s == null) {
            return null;
        }
        String t = s.trim();
        return t.isEmpty() ? null : t;
    }

    private static int parsePort(String raw, int defaultPort) {
        if (raw == null || raw.trim().isEmpty()) {
            return defaultPort;
        }
        try {
            int p = Integer.parseInt(raw.trim());
            return p > 0 && p <= 65535 ? p : defaultPort;
        } catch (NumberFormatException e) {
            return defaultPort;
        }
    }

    private static boolean parseBool(String raw, boolean defaultValue) {
        if (raw == null || raw.trim().isEmpty()) {
            return defaultValue;
        }
        String v = raw.trim();
        return "true".equalsIgnoreCase(v) || "1".equals(v) || "yes".equalsIgnoreCase(v);
    }

    private static long parseLong(String raw, long defaultValue) {
        if (raw == null || raw.trim().isEmpty()) {
            return defaultValue;
        }
        try {
            return Long.parseLong(raw.trim());
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    private static int indexOfIgnoreCase(String hay, String needle) {
        return indexOfIgnoreCase(hay, needle, 0);
    }

    private static int indexOfIgnoreCase(String hay, String needle, int from) {
        if (hay == null || needle == null) {
            return -1;
        }
        return hay.toLowerCase(Locale.ROOT).indexOf(needle.toLowerCase(Locale.ROOT), from);
    }

    private static byte[] readAll(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream(8192);
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) >= 0) {
            out.write(buf, 0, n);
        }
        return out.toByteArray();
    }

    private static byte[] readExactFrom(InputStream in, int size) throws IOException {
        byte[] data = new byte[size];
        int off = 0;
        while (off < size) {
            int n = in.read(data, off, size - off);
            if (n < 0) {
                byte[] partial = new byte[off];
                System.arraycopy(data, 0, partial, 0, off);
                return partial;
            }
            off += n;
        }
        return data;
    }

    private static final class TaggedResult {
        final boolean ok;
        final boolean failed;
        final String text;

        TaggedResult(boolean ok, boolean failed, String text) {
            this.ok = ok;
            this.failed = failed;
            this.text = text;
        }
    }

    private static final class FetchMessage {
        long uid;
        long rfc822Size = -1L;
        String internalDate;
        String headerText;
        String structureText;
        byte[] body;
    }
}
