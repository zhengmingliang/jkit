package com.alianga.jkit.storage.engine;

import com.alianga.jkit.storage.FileStorage;
import com.alianga.jkit.storage.StorageConfig;
import com.alianga.jkit.storage.StorageEntry;
import com.alianga.jkit.storage.StorageErrorCode;
import com.alianga.jkit.storage.StorageException;
import com.alianga.jkit.storage.StoragePaths;

import java.io.BufferedOutputStream;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ConnectException;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.nio.charset.Charset;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;
import java.util.regex.Pattern;

/**
 * FTP 协议引擎：基于 {@code java.net.Socket} 的纯 JDK 实现，零第三方依赖。
 *
 * <p>能力：二进制传输（TYPE I）、被动模式数据连接（PASV，亦支持主动 PORT）、控制
 * 连接复用（连接异常后自动重建并重新登录）、MLSD 机器格式列表（服务器不支持时
 * 回退 {@code LIST -la}，兼容 Unix {@code ls -l} 与 Windows IIS 两种文本格式）、
 * SIZE/MDTM 元数据、RNFR/RNTO 原生重命名（对目录同样有效）。所有对象路径统一
 * 映射到服务器端<b>绝对路径</b>：basePath 为空或 {@code /} 时挂服务器根目录
 * {@code /}（登录家目录请显式填 {@code /home/用户名} 或类似路径），配置了
 * basePath 则强制绝对化后挂其下。</p>
 *
 * <p>限制：不支持 FTPS/TLS；单实例共用一条控制连接，并发安全由内部锁保证；
 * copy 以「下载到内存 + 重新上传」实现，超大文件请注意内存占用。</p>
 *
 * @author 郑明亮
 */
public class FtpFileStorage implements FileStorage {
    /** Unix ls -l 行格式：以 d/-/l 等类型字符开头的 10 位权限串。 */
    private static final Pattern UNIX_LIST_LINE = Pattern.compile("^[dbclps-][rwxstST-]{9}\\s");
    /** Windows IIS 行格式：以 MM-dd-yy 日期开头。 */
    private static final Pattern WINDOWS_LIST_LINE = Pattern.compile("^\\d{1,2}-\\d{1,2}-\\d{2,4}\\s");

    private final String host;
    private final int port;
    private final String user;
    private final String password;
    private final boolean passive;
    private final Charset charset;
    /** 服务器端绝对根目录：basePath 为空 / {@code /} 时为 {@code /}，否则强制绝对化。 */
    private final String root;
    private final Object lock = new Object();
    private FtpClient client;
    /** 上次操作完成时间（毫秒）：空闲超过 {@link #NOOP_PROBE_IDLE_MS} 复用前先探活。 */
    private long lastUsedAt;
    /** 复用控制连接前先发 NOOP 探活的空闲阈值（毫秒）。 */
    private static final long NOOP_PROBE_IDLE_MS = 30_000L;

    /**
     * 由配置创建（配置键：host / port / user / password / basePath / passive / encoding）。
     *
     * @param config 连接配置
     */
    public FtpFileStorage(StorageConfig config) {
        this.host = config.require("host");
        this.port = normalizePort(config.intOr("port", 21));
        this.user = config.getOr("user", "");
        this.password = config.getOr("password", "");
        this.passive = config.boolOr("passive", true);
        this.root = rootOf(config.getOr("basePath", "/"));
        this.charset = resolveCharset(config.getOr("encoding", "UTF-8"));
    }

    @Override
    public String platform() {
        return "ftp";
    }

    @Override
    public boolean exists(String path) {
        String normalized = StoragePaths.normalize(path);
        if (normalized.isEmpty()) {
            return true;
        }
        return execute(normalized, ftp -> existsInternal(ftp, normalized));
    }

    @Override
    public StorageEntry stat(String path) {
        String normalized = StoragePaths.normalize(path);
        if (normalized.isEmpty()) {
            return StorageEntry.dir("", 0L);
        }
        return execute(normalized, ftp -> statInternal(ftp, normalized));
    }

    @Override
    public List<StorageEntry> list(String dir) {
        String normalized = StoragePaths.normalize(dir);
        return execute(normalized, ftp -> listInternal(ftp, normalized));
    }

    @Override
    public void mkdir(String dir) {
        String normalized = StoragePaths.normalize(dir);
        if (normalized.isEmpty()) {
            return;
        }
        execute(normalized, ftp -> {
            mkdirInternal(ftp, normalized);
            return null;
        });
    }

    @Override
    public void upload(String path, InputStream in, long size) {
        String normalized = requireFile(path);
        execute(normalized, ftp -> {
            storeInternal(ftp, normalized, in);
            return null;
        });
    }

    @Override
    public void download(String path, OutputStream out) {
        String normalized = requireFile(path);
        execute(normalized, ftp -> {
            retrieveInternal(ftp, normalized, out);
            return null;
        });
    }

    @Override
    public boolean supportsRange() {
        return true;
    }

    @Override
    public void download(String path, long offset, OutputStream out) {
        String normalized = requireFile(path);
        if (offset <= 0) {
            download(normalized, out);
            return;
        }
        execute(normalized, ftp -> {
            // REST 指示服务器从指定偏移续传（RFC 3659）；350 表示可以继续
            int code = ftp.command("REST " + offset).code();
            if (code != 350) {
                throw StorageException.unsupported("服务器不支持断点续传（REST 被拒绝: " + code + "）");
            }
            retrieveInternal(ftp, normalized, out);
            return null;
        });
    }

    @Override
    public void delete(String path) {
        String normalized = StoragePaths.normalize(path);
        if (normalized.isEmpty()) {
            throw StorageException.invalidConfig("拒绝删除存储根目录");
        }
        execute(normalized, ftp -> {
            deleteInternal(ftp, normalized);
            return null;
        });
    }

    @Override
    public void copy(String src, String dst) {
        String source = requireFile(src);
        String target = requireFile(dst);
        execute(target, ftp -> {
            if (ftp.command("CWD " + remoteOf(source)).code() == 250) {
                copyDir(ftp, source, target);
            } else {
                copyFile(ftp, source, target);
            }
            return null;
        });
    }

    @Override
    public void rename(String src, String dst) {
        String source = requireFile(src);
        String target = requireFile(dst);
        execute(target, ftp -> {
            renameInternal(ftp, source, target);
            return null;
        });
    }

    @Override
    public void close() {
        synchronized (lock) {
            closeClientQuietly();
        }
    }

    private boolean existsInternal(FtpClient ftp, String normalized) throws IOException {
        if (ftp.command("CWD " + remoteOf(normalized)).code() == 250) {
            return true;
        }
        return ftp.command("SIZE " + remoteOf(normalized)).code() == 213;
    }

    private StorageEntry statInternal(FtpClient ftp, String normalized) throws IOException {
        String remote = remoteOf(normalized);
        FtpClient.Reply sizeReply = ftp.command("SIZE " + remote);
        if (sizeReply.code() == 213) {
            long size = parseLongSafely(sizeReply.text().trim(), StorageEntry.SIZE_UNKNOWN);
            return StorageEntry.file(normalized, size, queryMtime(ftp, remote), null);
        }
        if (ftp.command("CWD " + remote).code() == 250) {
            return StorageEntry.dir(normalized, queryMtime(ftp, remote));
        }
        throw StorageException.notFound(normalized);
    }

    private List<StorageEntry> listInternal(FtpClient ftp, String normalized) throws IOException {
        List<StorageEntry> entries = null;
        if (ftp.isMlsdSupported()) {
            entries = transferListing(ftp, "MLSD " + remoteOf(normalized), normalized, true);
        }
        if (entries == null) {
            entries = transferListing(ftp, "LIST -la " + remoteOf(normalized), normalized, false);
        }
        return entries;
    }

    /**
     * 经数据连接读取列表文本并解析；machineFormat 为 true 时按 MLSD 机器格式，
     * 否则按 Unix / Windows IIS 文本格式。服务器对 MLSD 回 500/501/502 时返回
     * null，由调用方回退 LIST。
     */
    private List<StorageEntry> transferListing(FtpClient ftp, String command, String normalized,
                                               boolean machineFormat) throws IOException {
        Socket data = ftp.startTransfer(command);
        if (data == null) {
            FtpClient.Reply rejected = ftp.lastReply();
            if (machineFormat && (rejected.code() == 500 || rejected.code() == 501 || rejected.code() == 502)) {
                return null;
            }
            throw mapReply(rejected, normalized, "列出目录");
        }
        ByteArrayOutputStream buffer = new ByteArrayOutputStream(8192);
        try {
            FtpClient.pump(data.getInputStream(), buffer);
            ftp.finishTransfer(data, "列出目录", normalized);
        } catch (IOException e) {
            FtpClient.closeQuietly(data);
            throw e;
        }
        String text = new String(buffer.toByteArray(), ftp.charset());
        return machineFormat ? parseMlsx(text, normalized) : parseList(text, normalized);
    }

    /**
     * 创建目录（MKD）：已存在视为成功（幂等）；父目录缺失时逐级补建；
     * 同名文件已存在时抛 CONFLICT。
     */
    private void mkdirInternal(FtpClient ftp, String normalized) throws IOException {
        if (normalized.isEmpty()) {
            return;
        }
        FtpClient.Reply reply = ftp.command("MKD " + remoteOf(normalized));
        if (reply.isPositive()) {
            return;
        }
        if (ftp.command("CWD " + remoteOf(normalized)).code() == 250) {
            return;
        }
        String parent = StoragePaths.parent(normalized);
        if (!parent.isEmpty() && ftp.command("CWD " + remoteOf(parent)).code() != 250) {
            mkdirInternal(ftp, parent);
            if (ftp.command("MKD " + remoteOf(normalized)).isPositive()) {
                return;
            }
        }
        if (ftp.command("SIZE " + remoteOf(normalized)).code() == 213) {
            throw StorageException.conflict("同名文件已存在，无法创建目录: " + normalized, normalized);
        }
        throw StorageException.conflict(
                "创建目录失败: " + normalized + "（" + reply.code() + " " + flatten(reply.text()) + "）", normalized);
    }

    private void storeInternal(FtpClient ftp, String normalized, InputStream in) throws IOException {
        String parent = StoragePaths.parent(normalized);
        if (!parent.isEmpty() && ftp.command("CWD " + remoteOf(parent)).code() != 250) {
            mkdirInternal(ftp, parent);
        }
        Socket data = ftp.startTransfer("STOR " + remoteOf(normalized));
        if (data == null) {
            FtpClient.Reply rejected = ftp.lastReply();
            // 部分服务器（如 proftpd AllowOverwrite off）拒绝覆盖已存在文件（550/553）：
            // 目标确实是文件时先 DELE 再重试一次，与「上传即覆盖」的引擎语义保持一致
            if ((rejected.code() == 550 || rejected.code() == 553)
                    && ftp.command("SIZE " + remoteOf(normalized)).code() == 213
                    && ftp.command("DELE " + remoteOf(normalized)).isPositive()) {
                data = ftp.startTransfer("STOR " + remoteOf(normalized));
            }
            if (data == null) {
                throw mapReply(rejected, normalized, "上传");
            }
        }
        try {
            OutputStream dout = new BufferedOutputStream(data.getOutputStream());
            FtpClient.pump(in, dout);
            ftp.finishTransfer(data, "上传", normalized);
        } catch (IOException e) {
            FtpClient.closeQuietly(data);
            throw e;
        }
    }

    private void retrieveInternal(FtpClient ftp, String normalized, OutputStream out) throws IOException {
        Socket data = ftp.startTransfer("RETR " + remoteOf(normalized));
        if (data == null) {
            throw mapReply(ftp.lastReply(), normalized, "下载");
        }
        try {
            FtpClient.pump(data.getInputStream(), out);
            ftp.finishTransfer(data, "下载", normalized);
        } catch (IOException e) {
            FtpClient.closeQuietly(data);
            throw e;
        }
    }

    /**
     * 删除文件或空目录：先 DELE；失败时若目标是目录则 RMD（非空 → CONFLICT）；
     * 目标不存在则幂等返回。
     */
    private void deleteInternal(FtpClient ftp, String normalized) throws IOException {
        String remote = remoteOf(normalized);
        FtpClient.Reply reply = ftp.command("DELE " + remote);
        if (reply.isPositive()) {
            return;
        }
        if (ftp.command("CWD " + remote).code() == 250) {
            FtpClient.Reply rmd = ftp.command("RMD " + remote);
            if (rmd.isPositive()) {
                return;
            }
            throw StorageException.conflict("目录非空或删除被拒绝: " + normalized
                    + "（" + rmd.code() + " " + flatten(rmd.text()) + "），请用 deleteDir", normalized);
        }
        if (ftp.command("SIZE " + remote).code() == 213) {
            throw mapReply(reply, normalized, "删除");
        }
    }

    private void renameInternal(FtpClient ftp, String source, String target) throws IOException {
        FtpClient.Reply from = ftp.command("RNFR " + remoteOf(source));
        if (from.code() != 350) {
            throw mapReply(from, source, "重命名源");
        }
        FtpClient.Reply to = ftp.command("RNTO " + remoteOf(target));
        if (to.isPositive()) {
            return;
        }
        String targetRemote = remoteOf(target);
        if (ftp.command("SIZE " + targetRemote).code() == 213
                || ftp.command("CWD " + targetRemote).code() == 250) {
            throw StorageException.conflict("目标已存在且无法覆盖: " + target, target);
        }
        throw mapReply(to, target, "重命名");
    }

    /** 目录复制：建目标目录后逐项递归（list + copy）。 */
    private void copyDir(FtpClient ftp, String source, String target) throws IOException {
        mkdirInternal(ftp, target);
        for (StorageEntry entry : listInternal(ftp, source)) {
            String childTarget = StoragePaths.join(target, entry.name());
            if (entry.isDirectory()) {
                copyDir(ftp, entry.path(), childTarget);
            } else {
                copyFile(ftp, entry.path(), childTarget);
            }
        }
    }

    /** 文件复制：下载到内存缓冲后重新上传，避免临时文件。 */
    private void copyFile(FtpClient ftp, String source, String target) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream(8192);
        retrieveInternal(ftp, source, buffer);
        storeInternal(ftp, target, new ByteArrayInputStream(buffer.toByteArray()));
    }

    private long queryMtime(FtpClient ftp, String remote) throws IOException {
        FtpClient.Reply reply = ftp.command("MDTM " + remote);
        if (reply.code() != 213) {
            return StorageEntry.MTIME_UNKNOWN;
        }
        return parseFtpTimestamp(reply.text());
    }

    @Override
    public String absolutePathOf(String path) {
        return remoteOf(StoragePaths.normalize(path));
    }

    /** 存储内相对路径 → 服务器绝对路径；根目录即 {@code /}。 */
    private String remoteOf(String normalized) {
        if (normalized.isEmpty()) {
            return root;
        }
        return root.endsWith("/") ? root + normalized : root + "/" + normalized;
    }

    /**
     * 计算服务器端绝对根：空 / {@code /} → {@code /}；其余值折叠冗余分隔符后
     * 强制补前导 {@code /}，与 SFTP 引擎保持同一套根目录语义。
     */
    private static String rootOf(String configured) {
        String trimmed = configured == null ? "" : configured.trim();
        if (trimmed.isEmpty()) {
            return "/";
        }
        return "/" + StoragePaths.normalize(trimmed);
    }

    private FtpClient ensureConnected() throws IOException {
        if (client != null && client.isConnected()) {
            // 服务器会踢掉长时间空闲的控制连接（vsftpd 默认 300s）：socket 侧
            // 看不出，下一次读写才炸。空闲较久时先花一个 RTT 发 NOOP 探活，
            // 死了就重建，操作本身不用跟着失败一次
            if (System.currentTimeMillis() - lastUsedAt > NOOP_PROBE_IDLE_MS) {
                try {
                    if (client.command("NOOP").isPositive()) {
                        lastUsedAt = System.currentTimeMillis();
                        return client;
                    }
                } catch (IOException ignored) {
                    // 探活失败走重建
                }
                closeClientQuietly();
            } else {
                lastUsedAt = System.currentTimeMillis();
                return client;
            }
        }
        closeClientQuietly();
        FtpClient fresh = new FtpClient(host, port, user, password, passive, charset);
        fresh.connect();
        client = fresh;
        lastUsedAt = System.currentTimeMillis();
        return client;
    }

    /**
     * 在锁内取一条可用控制连接并执行操作：socket 异常时关闭连接（下次操作自动
     * 重连）并映射为统一异常；鉴权 / 连接类失败同样关闭连接等待重建。
     */
    private <T> T execute(String path, FtpOperation<T> operation) {
        synchronized (lock) {
            FtpClient ftp;
            try {
                ftp = ensureConnected();
            } catch (IOException e) {
                throw mapIo(e, path);
            }
            try {
                return operation.run(ftp);
            } catch (IOException e) {
                closeClientQuietly();
                throw mapIo(e, path);
            } catch (StorageException e) {
                if (e.code() == StorageErrorCode.AUTH_FAILED
                        || e.code() == StorageErrorCode.CONNECT_FAILED
                        || e.code() == StorageErrorCode.TIMEOUT) {
                    closeClientQuietly();
                }
                throw e;
            }
        }
    }

    private void closeClientQuietly() {
        if (client != null) {
            client.close();
            client = null;
        }
    }

    private String requireFile(String path) {
        String normalized = StoragePaths.normalize(path);
        if (normalized.isEmpty()) {
            throw StorageException.invalidConfig("路径不能为存储根目录");
        }
        return normalized;
    }

    /**
     * 应答码 → 统一错误码：530/532 → 鉴权失败，421 → 连接失败，
     * 550/553 → 默认按目标不存在，其余 5xx → 服务端错误，4xx → I/O 错误。
     */
    private StorageException mapReply(FtpClient.Reply reply, String path, String action) {
        StorageErrorCode code;
        if (reply.code() == 530 || reply.code() == 532) {
            code = StorageErrorCode.AUTH_FAILED;
        } else if (reply.code() == 421) {
            code = StorageErrorCode.CONNECT_FAILED;
        } else if (reply.code() == 550 || reply.code() == 553) {
            code = StorageErrorCode.NOT_FOUND;
        } else if (reply.code() >= 500) {
            code = StorageErrorCode.SERVER_ERROR;
        } else {
            code = StorageErrorCode.IO_ERROR;
        }
        return new StorageException(code,
                action + "失败: " + path + "（" + reply.code() + " " + flatten(reply.text()) + "）", path);
    }

    /**
     * socket 异常 → 统一错误码：超时 → TIMEOUT，连接拒绝 / DNS → CONNECT_FAILED，
     * 其余 → IO_ERROR。
     */
    private StorageException mapIo(IOException e, String path) {
        String target = host + ":" + port;
        if (e instanceof SocketTimeoutException) {
            return new StorageException(StorageErrorCode.TIMEOUT,
                    "FTP 连接超时: " + target + "（" + e.getMessage() + "）", path);
        }
        if (e instanceof ConnectException || e instanceof UnknownHostException) {
            return new StorageException(StorageErrorCode.CONNECT_FAILED,
                    "FTP 连接失败: " + target + "（" + e.getMessage() + "）", path);
        }
        return StorageException.io("FTP 读写异常: " + target + "（" + e.getMessage() + "）", path, e);
    }

    /**
     * 解析 MLSD 机器格式行："type=file;size=12;modify=20240102030405; name"。
     * type=dir 为目录；cdir/pdir 跳过；link 及 OS.unix=symlink 等 best effort 按文件。
     */
    private List<StorageEntry> parseMlsx(String text, String normalized) {
        List<StorageEntry> entries = new ArrayList<StorageEntry>();
        for (String line : text.split("\n")) {
            String trimmed = line.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            int at = trimmed.indexOf(' ');
            if (at <= 0) {
                continue;
            }
            String name = trimmed.substring(at + 1).trim();
            if (name.isEmpty() || ".".equals(name) || "..".equals(name)) {
                continue;
            }
            boolean directory = false;
            boolean skip = false;
            long size = StorageEntry.SIZE_UNKNOWN;
            long mtime = StorageEntry.MTIME_UNKNOWN;
            for (String fact : trimmed.substring(0, at).split(";")) {
                int eq = fact.indexOf('=');
                if (eq <= 0) {
                    continue;
                }
                String key = fact.substring(0, eq).trim().toLowerCase(Locale.ROOT);
                String value = fact.substring(eq + 1).trim();
                if ("type".equals(key)) {
                    if ("dir".equals(value)) {
                        directory = true;
                    } else if ("cdir".equals(value) || "pdir".equals(value)) {
                        skip = true;
                    }
                } else if ("size".equals(key)) {
                    size = parseLongSafely(value, StorageEntry.SIZE_UNKNOWN);
                } else if ("modify".equals(key)) {
                    mtime = parseFtpTimestamp(value);
                }
            }
            if (skip) {
                continue;
            }
            String child = childPath(normalized, name);
            entries.add(directory ? StorageEntry.dir(child, mtime)
                    : StorageEntry.file(child, size, mtime, null));
        }
        return entries;
    }

    /**
     * 解析 LIST -la 文本格式：逐行识别 Unix ls -l 与 Windows IIS，
     * 无法识别的行（如 "total 24"）跳过。
     */
    private List<StorageEntry> parseList(String text, String normalized) {
        List<StorageEntry> entries = new ArrayList<StorageEntry>();
        for (String line : text.split("\n")) {
            String trimmed = line.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            StorageEntry entry = parseUnixListLine(trimmed, normalized);
            if (entry == null) {
                entry = parseWindowsListLine(trimmed, normalized);
            }
            if (entry != null) {
                entries.add(entry);
            }
        }
        return entries;
    }

    /**
     * Unix ls -l 行："drwxr-xr-x 2 ftp ftp 4096 Oct 11 09:30 name"。
     * 文件名可含空格（取日期之后的整段）；符号链接 "name -> target" 取箭头前的名称，
     * best effort 按文件处理。
     */
    private StorageEntry parseUnixListLine(String line, String normalized) {
        if (!UNIX_LIST_LINE.matcher(line).find()) {
            return null;
        }
        String[] tokens = line.split("\\s+", 9);
        if (tokens.length < 9) {
            return null;
        }
        boolean directory = tokens[0].charAt(0) == 'd';
        String name = tokens[8].trim();
        int arrow = name.indexOf(" -> ");
        if (arrow > 0) {
            name = name.substring(0, arrow).trim();
        }
        if (name.isEmpty() || ".".equals(name) || "..".equals(name)) {
            return null;
        }
        long size = parseLongSafely(tokens[4], StorageEntry.SIZE_UNKNOWN);
        long mtime = parseLsDate(tokens[5], tokens[6], tokens[7]);
        String child = childPath(normalized, name);
        return directory ? StorageEntry.dir(child, mtime) : StorageEntry.file(child, size, mtime, null);
    }

    /**
     * Windows IIS 行："10-11-24 09:30AM <DIR> name" 或 "10-11-24 09:30AM 1234 name"。
     * 文件名可含空格（取第 4 列之后的整段）。
     */
    private StorageEntry parseWindowsListLine(String line, String normalized) {
        if (!WINDOWS_LIST_LINE.matcher(line).find()) {
            return null;
        }
        String[] tokens = line.split("\\s+", 4);
        if (tokens.length < 4) {
            return null;
        }
        boolean directory = "<dir>".equalsIgnoreCase(tokens[2]);
        String name = tokens[3].trim();
        if (name.isEmpty() || ".".equals(name) || "..".equals(name)) {
            return null;
        }
        long size = directory ? 0L : parseLongSafely(tokens[2], StorageEntry.SIZE_UNKNOWN);
        long mtime = parseWindowsDate(tokens[0] + " " + tokens[1]);
        String child = childPath(normalized, name);
        return directory ? StorageEntry.dir(child, mtime) : StorageEntry.file(child, size, mtime, null);
    }

    /** FTP 时间戳（MDTM / MLSD 的 modify）：UTC 的 yyyyMMddHHmmss，可带毫秒小数。 */
    private static long parseFtpTimestamp(String value) {
        String trimmed = value.trim();
        int dot = trimmed.indexOf('.');
        if (dot > 0) {
            trimmed = trimmed.substring(0, dot);
        }
        if (trimmed.length() < 14) {
            return StorageEntry.MTIME_UNKNOWN;
        }
        try {
            SimpleDateFormat format = new SimpleDateFormat("yyyyMMddHHmmss");
            format.setTimeZone(TimeZone.getTimeZone("GMT"));
            format.setLenient(false);
            return format.parse(trimmed).getTime();
        } catch (ParseException e) {
            return StorageEntry.MTIME_UNKNOWN;
        }
    }

    /**
     * Unix 列表时间："Oct 11 09:30"（近六个月，无年份，补当前年）或
     * "Oct 11 2023"（更早，时刻未知按零点）。服务器本地时区未知，按本机时区 best effort。
     */
    private static long parseLsDate(String month, String day, String timeOrYear) {
        try {
            if (timeOrYear.indexOf(':') >= 0) {
                SimpleDateFormat format = new SimpleDateFormat("MMM dd HH:mm", Locale.ENGLISH);
                Calendar calendar = Calendar.getInstance();
                calendar.setTime(format.parse(month + " " + day + " " + timeOrYear));
                calendar.set(Calendar.YEAR, Calendar.getInstance().get(Calendar.YEAR));
                return calendar.getTimeInMillis();
            }
            SimpleDateFormat format = new SimpleDateFormat("MMM dd yyyy", Locale.ENGLISH);
            return format.parse(month + " " + day + " " + timeOrYear).getTime();
        } catch (ParseException e) {
            return StorageEntry.MTIME_UNKNOWN;
        }
    }

    /** Windows IIS 时间："10-11-24 09:30AM"。 */
    private static long parseWindowsDate(String value) {
        try {
            SimpleDateFormat format = new SimpleDateFormat("MM-dd-yy hh:mma", Locale.ENGLISH);
            return format.parse(value).getTime();
        } catch (ParseException e) {
            return StorageEntry.MTIME_UNKNOWN;
        }
    }

    private static long parseLongSafely(String value, long fallback) {
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static String childPath(String dir, String name) {
        return dir.isEmpty() ? name : dir + "/" + name;
    }

    private static String flatten(String text) {
        return text.replace('\n', ' ');
    }

    private static int normalizePort(int port) {
        if (port <= 0 || port > 65535) {
            throw StorageException.invalidConfig("FTP 端口非法: " + port);
        }
        return port;
    }

    private static Charset resolveCharset(String name) {
        try {
            return Charset.forName(name);
        } catch (IllegalArgumentException e) {
            throw StorageException.invalidConfig("FTP 控制连接编码非法: " + name);
        }
    }

    /**
     * 在已建立的控制连接上执行的一次操作：只抛 socket 异常，
     * 连接重建与错误映射由 {@link #execute} 统一处理。
     */
    private interface FtpOperation<T> {
        T run(FtpClient ftp) throws IOException;
    }
}
