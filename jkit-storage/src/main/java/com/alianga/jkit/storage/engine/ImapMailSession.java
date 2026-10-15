package com.alianga.jkit.storage.engine;

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
import java.nio.charset.StandardCharsets;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TimeZone;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 纯 JDK IMAP 会话：基于 {@link Socket}/{@link SSLSocket}，零 JavaMail 依赖，
 * 供 {@link MailFileStorage} / {@link ImapAppendStore} 使用。
 *
 * <p>路径模型与一期一致：扁平文件名 = 邮件 Subject（及附件 filename）；
 * {@link MailEntry#uid()} 使用 IMAP UID 字符串。</p>
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
    private Socket socket;
    private InputStream rawIn;
    private OutputStream rawOut;
    private String selectedFolder;
    private long maxAttachmentBytes = DEFAULT_MAX_ATTACHMENT_BYTES;
    private boolean connected;

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
        this.maxAttachmentBytes = parseLong(cfg.get("maxAttachmentBytes"), DEFAULT_MAX_ATTACHMENT_BYTES);
        closeQuietly();
        try {
            openSocket(host, port, ssl);
            readGreeting();
            taggedOk("LOGIN " + quote(user) + " " + quote(password), "LOGIN");
            ensureSelected(folder);
            connected = true;
        } catch (StorageException e) {
            closeQuietly();
            throw e;
        } catch (IOException e) {
            closeQuietly();
            throw StorageException.connectFailed("IMAP 连接失败: " + host + ":" + port + " — " + e.getMessage());
        }
    }

    /**
     * 关闭会话。
     */
    @Override
    public void close() {
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

    /**
     * 列出邮箱文件夹内全部邮件条目。二期逻辑路径下 {@code name} 可含 {@code /}；子目录过滤由上层做。
     *
     * @param path 相对路径（Plan A 忽略，单 folder 全量返回）
     * @return 条目列表
     */
    public List<MailEntry> list(String path) {
        requireConnected();
        // phase2 logical path: 单 IMAP folder 返回全部；子路径过滤由 MailFileStorage 做
        try {
            List<Long> uids = uidSearch("ALL");
            if (uids.isEmpty()) {
                return Collections.emptyList();
            }
            List<MailEntry> result = new ArrayList<MailEntry>(uids.size());
            for (Long uid : uids) {
                MailEntry entry = fetchMeta(uid.longValue());
                if (entry != null && !entry.name().isEmpty()) {
                    result.add(entry);
                }
            }
            return result;
        } catch (IOException e) {
            throw StorageException.io("IMAP list 失败", path == null ? "" : path, e);
        }
    }

    /**
     * 下载指定名称对应的附件内容到输出流。
     *
     * @param path 条目名称 / 路径
     * @param out  目标输出流
     */
    public void download(String path, OutputStream out) {
        requireConnected();
        if (out == null) {
            throw StorageException.invalidConfig("download 输出流不能为 null");
        }
        String name = requireName(path);
        try {
            long uid = findUidByName(name);
            if (uid < 0L) {
                throw StorageException.notFound(name);
            }
            byte[] mime = fetchBody(uid);
            byte[] payload = extractAttachmentPayload(mime, name);
            out.write(payload);
            out.flush();
        } catch (StorageException e) {
            throw e;
        } catch (IOException e) {
            throw StorageException.io("IMAP download 失败: " + name, name, e);
        }
    }

    /**
     * 删除指定名称对应的邮件。找不到时抛 {@code NOT_FOUND}。
     *
     * @param path 条目名称 / 路径
     */
    public void delete(String path) {
        requireConnected();
        String name = requireName(path);
        try {
            long uid = findUidByName(name);
            if (uid < 0L) {
                throw StorageException.notFound(name);
            }
            taggedOk("UID STORE " + uid + " +FLAGS.SILENT (\\Deleted)", "STORE");
            taggedOk("EXPUNGE", "EXPUNGE");
        } catch (StorageException e) {
            throw e;
        } catch (IOException e) {
            throw StorageException.io("IMAP delete 失败: " + name, name, e);
        }
    }

    /**
     * 判断条目是否存在；找不到只返回 {@code false}，不抛异常。
     *
     * @param path 条目名称 / 路径
     * @return true 表示存在
     */
    public boolean exists(String path) {
        requireConnected();
        if (path == null || path.trim().isEmpty()) {
            return true;
        }
        String name = path.trim();
        // phase2 logical path: 允许 name 含 /
        try {
            return findUidByName(name) >= 0L;
        } catch (IOException e) {
            throw StorageException.io("IMAP exists 失败: " + name, name, e);
        }
    }

    /**
     * 查询条目元数据。找不到抛 {@link StorageException}（{@code NOT_FOUND}）。
     *
     * @param path 条目名称 / 路径
     * @return 元数据
     */
    public MailEntry stat(String path) {
        requireConnected();
        String name = requireName(path);
        try {
            long uid = findUidByName(name);
            if (uid < 0L) {
                throw StorageException.notFound(name);
            }
            MailEntry entry = fetchMeta(uid);
            if (entry == null) {
                throw StorageException.notFound(name);
            }
            return entry;
        } catch (StorageException e) {
            throw e;
        } catch (IOException e) {
            throw StorageException.io("IMAP stat 失败: " + name, name, e);
        }
    }

    /**
     * 将完整 MIME 追加到指定文件夹（供 {@link ImapAppendStore} 调用）。
     *
     * @param folder      IMAP 文件夹
     * @param mimeMessage MIME 流
     * @param sizeBytes   已知字节数；未知传 {@code -1}（将整段缓冲）
     */
    void appendMime(String folder, InputStream mimeMessage, long sizeBytes) {
        requireConnected();
        if (mimeMessage == null) {
            throw StorageException.invalidConfig("APPEND MIME 流不能为 null");
        }
        String target = trimToNull(folder);
        if (target == null) {
            target = selectedFolder != null ? selectedFolder : DEFAULT_FOLDER;
        }
        try {
            byte[] mime;
            if (sizeBytes >= 0L && sizeBytes <= Integer.MAX_VALUE) {
                mime = readExactFrom(mimeMessage, (int) sizeBytes);
            } else {
                mime = readAll(mimeMessage);
            }
            if (mime.length > maxAttachmentBytes + 256L * 1024L) {
                // MIME 包装略大于附件；硬上限仍由上层控制，这里防极端
                throw new StorageException(StorageErrorCode.CONFLICT,
                        "MIME 超过可接受大小: " + mime.length, target);
            }
            if (!target.equals(selectedFolder)) {
                ensureSelected(target);
            }
            String tag = nextTag();
            sendRaw(tag + " APPEND " + quote(target) + " {" + mime.length + "}\r\n");
            String cont = readLine();
            if (cont == null || !cont.startsWith("+")) {
                throw StorageException.serverError(
                        "APPEND 未收到续传应答: " + cont, target);
            }
            rawOut.write(mime);
            rawOut.write(new byte[]{'\r', '\n'});
            rawOut.flush();
            TaggedResult result = readUntilTagged(tag);
            if (!result.ok) {
                throw StorageException.serverError(
                        "APPEND 失败: " + result.text, target);
            }
        } catch (StorageException e) {
            throw e;
        } catch (IOException e) {
            throw StorageException.io("IMAP APPEND 失败", target, e);
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
        this.rawIn = s.getInputStream();
        this.rawOut = s.getOutputStream();
    }

    private void readGreeting() throws IOException {
        String line = readLine();
        if (line == null || (!line.startsWith("* OK") && !line.startsWith("* PREAUTH"))) {
            throw StorageException.connectFailed("IMAP 问候异常: " + line);
        }
    }

    private void ensureSelected(String folder) throws IOException {
        TaggedResult select = tagged("SELECT " + quote(folder));
        if (select.ok) {
            selectedFolder = folder;
            return;
        }
        TaggedResult create = tagged("CREATE " + quote(folder));
        if (!create.ok && !looksLikeAlreadyExists(create.text)) {
            throw StorageException.serverError(
                    "无法创建 IMAP 文件夹 " + folder + ": " + create.text, folder);
        }
        TaggedResult again = tagged("SELECT " + quote(folder));
        if (!again.ok) {
            throw StorageException.serverError(
                    "无法选中 IMAP 文件夹 " + folder + ": " + again.text, folder);
        }
        selectedFolder = folder;
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
                if (meta != null && nameEquals(meta.name(), name)) {
                    return best;
                }
            }
            // HEADER 可能子串命中，精确过滤
            for (int i = byHeader.size() - 1; i >= 0; i--) {
                long uid = byHeader.get(i).longValue();
                MailEntry meta = fetchMeta(uid);
                if (meta != null && nameEquals(meta.name(), name)) {
                    return uid;
                }
            }
        }
        List<Long> all = uidSearch("ALL");
        for (int i = all.size() - 1; i >= 0; i--) {
            long uid = all.get(i).longValue();
            MailEntry meta = fetchMeta(uid);
            if (meta != null && nameEquals(meta.name(), name)) {
                return uid;
            }
        }
        return -1L;
    }

    private MailEntry fetchMeta(long uid) throws IOException {
        String tag = nextTag();
        sendRaw(tag + " UID FETCH " + uid
                + " (UID INTERNALDATE RFC822.SIZE BODY.PEEK[HEADER.FIELDS (SUBJECT)] BODYSTRUCTURE)\r\n");
        FetchMessage msg = readFetchMessage(tag, uid);
        if (msg == null) {
            return null;
        }
        String subject = parseSubject(msg.headerText);
        if (subject == null || subject.isEmpty()) {
            subject = parseFilenameFromStructure(msg.structureText);
        }
        if (subject == null || subject.isEmpty()) {
            subject = "uid-" + uid;
        }
        long size = estimatePayloadSize(msg.structureText, msg.rfc822Size);
        long mtime = parseInternalDate(msg.internalDate);
        return new MailEntry(subject, false, size, mtime, String.valueOf(uid));
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
                if (fieldHint.contains("BODY[")) {
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
        if ("HEADER".equals(lastLiteralField) && lastLiteral != null) {
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

    private TaggedResult tagged(String command) throws IOException {
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

    // ------------------------------------------------------------------ parse helpers

    private static String requireName(String path) {
        if (path == null || path.trim().isEmpty()) {
            throw StorageException.invalidConfig("文件路径不能为空");
        }
        // phase2 logical path: Subject/name 可为 dir/file，不再拒绝 /
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

    private static long estimatePayloadSize(String structure, long rfc822Size) {
        if (structure != null) {
            // 粗略：找 BASE64 后的数字作为传输长度，再 *3/4
            Pattern partSize = Pattern.compile("\"BASE64\"\\s+(\\d+)", Pattern.CASE_INSENSITIVE);
            Matcher m = partSize.matcher(structure);
            long last = -1L;
            while (m.find()) {
                last = Long.parseLong(m.group(1));
            }
            if (last >= 0L) {
                return Math.max(0L, last * 3L / 4L);
            }
        }
        return rfc822Size > 0L ? rfc822Size : -1L;
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
