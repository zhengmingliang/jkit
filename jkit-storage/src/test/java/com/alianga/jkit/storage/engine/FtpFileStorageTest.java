package com.alianga.jkit.storage.engine;

import com.alianga.jkit.storage.FileStorage;
import com.alianga.jkit.storage.FileStorages;
import com.alianga.jkit.storage.StorageConfig;
import com.alianga.jkit.storage.StorageEntry;
import com.alianga.jkit.storage.StorageErrorCode;
import com.alianga.jkit.storage.StorageException;

import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.TimeZone;
import java.util.concurrent.ConcurrentSkipListMap;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * {@link FtpFileStorage} 测试：内嵌一个假 FTP 服务器（内存文件表 + 控制连接
 * ServerSocket + PASV 时临时开数据 ServerSocket），覆盖上传下载字节一致性、
 * 列表（MLSD 与 Unix / IIS 文本回退）、mkdir / rename / delete / deleteDir、
 * exists、stat（SIZE/MDTM）、未登录 AUTH_FAILED、550 NOT_FOUND、
 * 目录非空 CONFLICT 与被动模式数据传输。
 *
 * @author 郑明亮
 */
public class FtpFileStorageTest {
    /** 半年的毫秒数：Unix 列表内「近六个月显示 HH:mm」的分界。 */
    private static final long SIX_MONTHS_MS = 183L * 24 * 60 * 60 * 1000;

    private static FakeFtpServer server;

    @BeforeClass
    public static void startServer() throws IOException {
        server = new FakeFtpServer("test", "secret", true, false);
        server.start();
    }

    @AfterClass
    public static void stopServer() {
        if (server != null) {
            server.stop();
        }
    }

    private static StorageConfig config(String basePath) {
        return StorageConfig.empty()
                .set("host", "127.0.0.1")
                .set("port", String.valueOf(server.port()))
                .set("user", "test")
                .set("password", "secret")
                .set("basePath", "/" + basePath);
    }

    private static FileStorage newStorage(String basePath) {
        server.addDirectory(basePath);
        return new FtpFileStorage(config(basePath));
    }

    private static FileStorage storageOn(FakeFtpServer target, String basePath) {
        target.addDirectory(basePath);
        return new FtpFileStorage(StorageConfig.empty()
                .set("host", "127.0.0.1")
                .set("port", String.valueOf(target.port()))
                .set("user", "test")
                .set("password", "secret")
                .set("basePath", "/" + basePath));
    }

    private static byte[] patternBytes(int size) {
        byte[] data = new byte[size];
        new Random(42).nextBytes(data);
        return data;
    }

    @Test
    public void platformIsFtpAndRegisteredInFileStorages() {
        FileStorage storage = FileStorages.of("ftp", config("case-platform"));
        assertEquals("ftp", storage.platform());
        storage.close();
    }

    @Test
    public void absolutePathMapsToServerRootWhenBasePathMissing() {
        FileStorage storage = new FtpFileStorage(StorageConfig.empty()
                .set("host", "127.0.0.1")
                .set("port", String.valueOf(server.port()))
                .set("user", "test")
                .set("password", "secret"));
        try {
            assertEquals("basePath 未配置时存储根即服务器根 /",
                    "/docs/a.txt", storage.absolutePathOf("docs/a.txt"));
            assertEquals("/", storage.absolutePathOf(""));
        } finally {
            storage.close();
        }
    }

    @Test
    public void absolutePathHonorsConfiguredBasePath() {
        FileStorage storage = new FtpFileStorage(config("case-abspath"));
        try {
            assertEquals("/case-abspath/files/blob", storage.absolutePathOf("files/blob"));
        } finally {
            storage.close();
        }
    }

    @Test
    public void uploadAndDownloadKeepsBytesConsistent() {
        FileStorage storage = newStorage("case-transfer");
        byte[] data = patternBytes(512 * 1024);
        storage.upload("files/blob.bin", data);
        assertArrayEquals(data, storage.readBytes("files/blob.bin"));
        storage.upload("files/empty.bin", new byte[0]);
        assertEquals(0, storage.readBytes("files/empty.bin").length);
        storage.close();
        storage.upload("files/reconnect.txt", "again".getBytes(StandardCharsets.UTF_8));
        assertEquals("again", storage.readString("files/reconnect.txt"));
    }

    @Test
    public void listDirectoryAndSubDirectory() {
        FileStorage storage = newStorage("case-list");
        storage.upload("docs/readme.txt", "hello".getBytes(StandardCharsets.UTF_8));
        storage.upload("docs/logo.png", new byte[]{(byte) 0xCA, (byte) 0xFE, 0x00, 0x01});
        storage.mkdir("docs/sub");
        storage.upload("docs/sub/nested.txt", "deep".getBytes(StandardCharsets.UTF_8));

        List<StorageEntry> root = storage.list("");
        assertEquals(1, root.size());
        assertTrue(root.get(0).isDirectory());
        assertEquals("docs", root.get(0).path());

        Map<String, StorageEntry> byName = new LinkedHashMap<String, StorageEntry>();
        for (StorageEntry entry : storage.list("docs")) {
            byName.put(entry.name(), entry);
        }
        assertEquals(3, byName.size());
        assertEquals("docs/logo.png", byName.get("logo.png").path());
        assertFalse(byName.get("logo.png").isDirectory());
        assertEquals(4, byName.get("logo.png").size());
        assertEquals(5, byName.get("readme.txt").size());
        assertTrue(byName.get("sub").isDirectory());
        // MLSD 机器格式携带属主 / 组事实
        assertEquals("ftp", byName.get("logo.png").attribute(StorageEntry.ATTR_OWNER));
        assertEquals("staff", byName.get("logo.png").attribute(StorageEntry.ATTR_GROUP));
        assertEquals("ftp", byName.get("sub").attribute(StorageEntry.ATTR_OWNER));

        List<StorageEntry> sub = storage.list("docs/sub");
        assertEquals(1, sub.size());
        assertEquals("docs/sub/nested.txt", sub.get(0).path());
    }

    @Test
    public void listCarriesOwnerAndGroupFromTextListing() throws IOException {
        FakeFtpServer legacy = new FakeFtpServer("test", "secret", false, false);
        legacy.start();
        try {
            FileStorage storage = storageOn(legacy, "owner-case");
            storage.upload("owner-case/plain.txt", "abc".getBytes(StandardCharsets.UTF_8));
            storage.mkdir("owner-case/subdir");
            StorageEntry file = null;
            StorageEntry dir = null;
            for (StorageEntry entry : storage.list("owner-case")) {
                if ("plain.txt".equals(entry.name())) {
                    file = entry;
                }
                if ("subdir".equals(entry.name())) {
                    dir = entry;
                }
            }
            assertNotNull(file);
            assertNotNull(dir);
            // Unix 文本 LIST 第 3/4 列
            assertEquals("ftp", file.attribute(StorageEntry.ATTR_OWNER));
            assertEquals("ftp", file.attribute(StorageEntry.ATTR_GROUP));
            assertEquals("ftp", dir.attribute(StorageEntry.ATTR_OWNER));
            // stat 经父目录列表补全属主
            assertEquals("ftp", storage.stat("owner-case/plain.txt")
                    .attribute(StorageEntry.ATTR_OWNER));
        } finally {
            legacy.stop();
        }
    }

    @Test
    public void existsAndStatMetadata() {
        FileStorage storage = newStorage("case-meta");
        storage.upload("meta/data.bin", new byte[32]);
        storage.mkdir("meta/dir");

        assertTrue(storage.exists("meta/data.bin"));
        assertTrue(storage.exists("meta/dir"));
        assertTrue(storage.exists(""));
        assertFalse(storage.exists("meta/nope.bin"));

        StorageEntry file = storage.stat("meta/data.bin");
        assertFalse(file.isDirectory());
        assertEquals(32, file.size());
        assertEquals(server.mtimeOf("case-meta/meta/data.bin"), file.lastModified());
        assertNull(file.etag());
        assertEquals("data.bin", file.name());

        StorageEntry dir = storage.stat("meta/dir");
        assertTrue(dir.isDirectory());
        assertEquals(server.mtimeOf("case-meta/meta/dir"), dir.lastModified());
    }

    @Test
    public void mkdirRenameDeleteFlow() {
        FileStorage storage = newStorage("case-flow");
        storage.mkdir("tree/branch");
        assertTrue(storage.exists("tree/branch"));
        storage.mkdir("tree/branch");
        storage.upload("tree/branch/leaf.txt", "leaf".getBytes(StandardCharsets.UTF_8));

        try {
            storage.mkdir("tree/branch/leaf.txt");
            fail("对同名文件 mkdir 应报冲突");
        } catch (StorageException e) {
            assertEquals(StorageErrorCode.CONFLICT, e.code());
        }

        storage.rename("tree/branch/leaf.txt", "tree/branch/foliage.txt");
        assertFalse(storage.exists("tree/branch/leaf.txt"));
        assertTrue(storage.exists("tree/branch/foliage.txt"));

        storage.rename("tree", "grove");
        assertTrue(storage.exists("grove/branch/foliage.txt"));
        assertFalse(storage.exists("tree"));

        storage.delete("grove/branch/foliage.txt");
        storage.delete("grove/branch/foliage.txt");
        storage.deleteDir("grove");
        assertFalse(storage.exists("grove"));
    }

    @Test
    public void overLimitReadKeepsSessionUsable() {
        FileStorage storage = newStorage("case-overlimit");
        byte[] data = new byte[256 * 1024];
        new Random(7).nextBytes(data);
        storage.upload("big.bin", data);
        storage.upload("small.txt", "ok".getBytes(StandardCharsets.UTF_8));
        // 1) 大小已知：stat 后直接拒绝，不开传输
        try {
            storage.readBytes("big.bin", 1024);
            fail("应因超限拒绝");
        } catch (StorageException e) {
            assertEquals(StorageErrorCode.CONFLICT, e.code());
        }
        assertEquals(2, storage.list("").size());
        // 2) 调用方输出流中途中止：连接作废后下一次操作自动重连，list 与读取都正常
        try {
            storage.download("big.bin", new OutputStream() {
                private int count;

                @Override
                public void write(int b) {
                    write(new byte[]{(byte) b}, 0, 1);
                }

                @Override
                public void write(byte[] b, int off, int len) {
                    count += len;
                    if (count > 4096) {
                        throw StorageException.conflict("中止", "big.bin");
                    }
                }
            });
            fail("应中止");
        } catch (StorageException e) {
            assertEquals(StorageErrorCode.CONFLICT, e.code());
        }
        assertEquals(2, storage.list("").size());
        assertArrayEquals("ok".getBytes(StandardCharsets.UTF_8), storage.readBytes("small.txt", 1024));
        storage.close();
    }

    @Test
    public void copyKeepsContent() {
        FileStorage storage = newStorage("case-copy");
        byte[] data = patternBytes(4096);
        storage.upload("src/blob.bin", data);
        storage.copy("src/blob.bin", "dst/blob.bin");
        assertArrayEquals(data, storage.readBytes("dst/blob.bin"));
        assertTrue(storage.exists("src/blob.bin"));

        storage.mkdir("src/sub");
        storage.upload("src/sub/inner.txt", "inner".getBytes(StandardCharsets.UTF_8));
        storage.copy("src", "src-copy");
        assertArrayEquals(data, storage.readBytes("src-copy/blob.bin"));
        assertEquals("inner", storage.readString("src-copy/sub/inner.txt"));
    }

    @Test
    public void deleteNonEmptyDirectoryConflicts() {
        FileStorage storage = newStorage("case-conflict");
        storage.mkdir("busy");
        storage.upload("busy/inner.txt", "x".getBytes(StandardCharsets.UTF_8));
        try {
            storage.delete("busy");
            fail("对非空目录 delete 应报冲突");
        } catch (StorageException e) {
            assertEquals(StorageErrorCode.CONFLICT, e.code());
            assertEquals("busy", e.path());
        }
    }

    @Test
    public void authFailureMapsToAuthFailed() {
        FileStorage storage = new FtpFileStorage(config("case-auth").set("password", "wrong"));
        try {
            storage.exists("anything.txt");
            fail("密码错误应报鉴权失败");
        } catch (StorageException e) {
            assertEquals(StorageErrorCode.AUTH_FAILED, e.code());
        }
    }

    @Test
    public void missingObjectMapsToNotFound() {
        FileStorage storage = newStorage("case-missing");
        storage.delete("never-existed.txt");
        assertFalse(storage.exists("never-existed.txt"));
        try {
            storage.download("never-existed.txt", new ByteArrayOutputStream());
            fail("下载不存在的对象应报 NOT_FOUND");
        } catch (StorageException e) {
            assertEquals(StorageErrorCode.NOT_FOUND, e.code());
            assertEquals("never-existed.txt", e.path());
        }
        try {
            storage.stat("never-existed.txt");
            fail("stat 不存在的对象应报 NOT_FOUND");
        } catch (StorageException e) {
            assertEquals(StorageErrorCode.NOT_FOUND, e.code());
        }
        try {
            storage.rename("never-existed.txt", "target.txt");
            fail("重命名不存在的对象应报 NOT_FOUND");
        } catch (StorageException e) {
            assertEquals(StorageErrorCode.NOT_FOUND, e.code());
        }
    }

    @Test
    public void listFallbackToUnixTextFormat() throws IOException {
        FakeFtpServer legacy = new FakeFtpServer("test", "secret", false, false);
        legacy.start();
        try {
            FileStorage storage = storageOn(legacy, "legacy");
            long before = System.currentTimeMillis();
            storage.upload("legacy/plain.txt", "abc".getBytes(StandardCharsets.UTF_8));
            storage.upload("legacy/with space.txt", "de".getBytes(StandardCharsets.UTF_8));
            storage.mkdir("legacy/subdir");

            Map<String, StorageEntry> byName = new LinkedHashMap<String, StorageEntry>();
            for (StorageEntry entry : storage.list("legacy")) {
                byName.put(entry.name(), entry);
            }
            assertEquals(3, byName.size());
            assertEquals(3, byName.get("plain.txt").size());
            assertEquals("legacy/with space.txt", byName.get("with space.txt").path());
            assertTrue(byName.get("subdir").isDirectory());
            long mtime = byName.get("plain.txt").lastModified();
            assertTrue("列表时间应落在近期（当前年 + HH:mm）",
                    mtime >= before - 90_000L && mtime <= System.currentTimeMillis() + 90_000L);
        } finally {
            legacy.stop();
        }
    }

    @Test
    public void listFallbackToWindowsIisFormat() throws IOException {
        FakeFtpServer iis = new FakeFtpServer("test", "secret", false, true);
        iis.start();
        try {
            FileStorage storage = storageOn(iis, "www");
            storage.upload("www/index.html", "<html>".getBytes(StandardCharsets.UTF_8));
            storage.mkdir("www/images");

            Map<String, StorageEntry> byName = new LinkedHashMap<String, StorageEntry>();
            for (StorageEntry entry : storage.list("www")) {
                byName.put(entry.name(), entry);
            }
            assertEquals(2, byName.size());
            assertEquals(6, byName.get("index.html").size());
            assertFalse(byName.get("index.html").isDirectory());
            assertEquals("www/images", byName.get("images").path());
            assertTrue(byName.get("images").isDirectory());
        } finally {
            iis.stop();
        }
    }

    /**
     * 内嵌假 FTP 服务器：文件表用 {@link ConcurrentSkipListMap} 保存（key 为规范化
     * 路径），控制连接每条一线程，PASV 时临时开一个数据 ServerSocket，数据传输
     * 串行完成后回 226。实现引擎用到的命令子集。
     */
    private static final class FakeFtpServer {
        private final String user;
        private final String password;
        private final boolean supportMlsd;
        private final boolean iisList;
        private final ConcurrentSkipListMap<String, Entry> entries =
                new ConcurrentSkipListMap<String, Entry>();
        private ServerSocket controlSocket;
        private volatile boolean running;

        FakeFtpServer(String user, String password, boolean supportMlsd, boolean iisList) {
            this.user = user;
            this.password = password;
            this.supportMlsd = supportMlsd;
            this.iisList = iisList;
        }

        void start() throws IOException {
            controlSocket = new ServerSocket(0);
            running = true;
            Thread acceptor = new Thread(this::acceptLoop, "fake-ftp-acceptor");
            acceptor.setDaemon(true);
            acceptor.start();
        }

        void stop() {
            running = false;
            closeQuietly(controlSocket);
        }

        int port() {
            return controlSocket.getLocalPort();
        }

        long mtimeOf(String path) {
            Entry entry = entries.get(normalize(path));
            return entry == null ? StorageEntry.MTIME_UNKNOWN : entry.mtime;
        }

        void addDirectory(String path) {
            entries.put(normalize(path), Entry.directory());
        }

        private void acceptLoop() {
            while (running) {
                try {
                    Socket control = controlSocket.accept();
                    Thread worker = new Thread(() -> serve(control));
                    worker.setDaemon(true);
                    worker.start();
                } catch (IOException e) {
                    if (!running) {
                        return;
                    }
                }
            }
        }

        private void serve(Socket control) {
            try {
                BufferedReader reader = new BufferedReader(
                        new InputStreamReader(control.getInputStream(), StandardCharsets.UTF_8));
                Connection connection = new Connection(control, control.getOutputStream());
                connection.greet();
                String line;
                while ((line = reader.readLine()) != null && connection.alive) {
                    connection.dispatch(line);
                }
            } catch (IOException e) {
                // 客户端断开或停止服务器，连接结束
            } finally {
                closeQuietly(control);
            }
        }

        private boolean parentExists(String path) {
            int at = path.lastIndexOf('/');
            String parent = at < 0 ? "" : path.substring(0, at);
            if (parent.isEmpty()) {
                return true;
            }
            Entry entry = entries.get(parent);
            return entry != null && entry.directory;
        }

        private void moveEntry(String from, String to) {
            Entry entry = entries.remove(from);
            if (entry == null) {
                return;
            }
            entries.put(to, entry);
            if (!entry.directory) {
                return;
            }
            String prefix = from + "/";
            List<String> descendants = new ArrayList<String>();
            for (String key : entries.tailMap(prefix, true).keySet()) {
                if (!key.startsWith(prefix)) {
                    break;
                }
                descendants.add(key);
            }
            for (String key : descendants) {
                entries.put(to + "/" + key.substring(prefix.length()), entries.remove(key));
            }
        }

        private List<Map.Entry<String, Entry>> children(String dir) {
            List<Map.Entry<String, Entry>> result = new ArrayList<Map.Entry<String, Entry>>();
            String prefix = dir.isEmpty() ? "" : dir + "/";
            for (Map.Entry<String, Entry> item : entries.tailMap(prefix, true).entrySet()) {
                String key = item.getKey();
                if (!prefix.isEmpty() && !key.startsWith(prefix)) {
                    break;
                }
                String relative = prefix.isEmpty() ? key : key.substring(prefix.length());
                if (relative.isEmpty() || relative.contains("/")) {
                    continue;
                }
                result.add(item);
            }
            return result;
        }

        private static String normalize(String path) {
            String unified = path.replace('\\', '/');
            while (unified.startsWith("/")) {
                unified = unified.substring(1);
            }
            while (unified.endsWith("/")) {
                unified = unified.substring(0, unified.length() - 1);
            }
            return unified;
        }

        private static String nameOf(String dir, String key) {
            return dir.isEmpty() ? key : key.substring(dir.length() + 1);
        }

        private static String formatDate(long mtime) {
            SimpleDateFormat format = new SimpleDateFormat("yyyyMMddHHmmss");
            format.setTimeZone(TimeZone.getTimeZone("GMT"));
            return format.format(new Date(mtime));
        }

        private static String lsDate(long mtime) {
            String pattern = mtime > System.currentTimeMillis() - SIX_MONTHS_MS ? "MMM dd HH:mm" : "MMM dd yyyy";
            return new SimpleDateFormat(pattern, Locale.ENGLISH).format(new Date(mtime));
        }

        private static String iisDate(long mtime) {
            return new SimpleDateFormat("MM-dd-yy hh:mma", Locale.ENGLISH).format(new Date(mtime));
        }

        private static void closeQuietly(Socket socket) {
            if (socket != null) {
                try {
                    socket.close();
                } catch (IOException e) {
                    // 关闭时的异常忽略
                }
            }
        }

        private static void closeQuietly(ServerSocket socket) {
            if (socket != null) {
                try {
                    socket.close();
                } catch (IOException e) {
                    // 关闭时的异常忽略
                }
            }
        }

        /** 内存文件表条目：目录标记 + 内容字节 + 最后修改时间（秒级精度）。 */
        private static final class Entry {
            private final boolean directory;
            private final byte[] data;
            private final long mtime;

            Entry(boolean directory, byte[] data, long mtime) {
                this.directory = directory;
                this.data = data;
                this.mtime = mtime;
            }

            static Entry file(byte[] data) {
                return new Entry(false, data, truncatedNow());
            }

            static Entry directory() {
                return new Entry(true, new byte[0], truncatedNow());
            }

            static Entry root() {
                return new Entry(true, new byte[0], 0L);
            }

            private static long truncatedNow() {
                return System.currentTimeMillis() / 1000L * 1000L;
            }
        }

        /** 一条控制连接的会话状态与命令分派。 */
        private final class Connection {
            private final Socket control;
            private final OutputStream out;
            private boolean alive = true;
            private boolean userSeen;
            private boolean loggedIn;
            private String pendingUser;
            private String renameFrom;
            private ServerSocket pendingData;
            private String cwd = "/";

            Connection(Socket control, OutputStream out) {
                this.control = control;
                this.out = out;
            }

            void greet() throws IOException {
                reply(220, "Fake FTP server ready");
            }

            void dispatch(String line) throws IOException {
                String verb = line;
                String argument = "";
                int at = line.indexOf(' ');
                if (at >= 0) {
                    verb = line.substring(0, at);
                    argument = line.substring(at + 1).trim();
                }
                switch (verb.toUpperCase(Locale.ROOT)) {
                    case "USER":
                        userSeen = true;
                        pendingUser = argument;
                        reply(331, "Password required for " + argument);
                        break;
                    case "PASS":
                        if (!userSeen) {
                            reply(503, "Login with USER first");
                        } else if (user.equals(pendingUser) && password.equals(argument)) {
                            loggedIn = true;
                            reply(230, "User logged in");
                        } else {
                            reply(530, "Login incorrect");
                        }
                        break;
                    case "QUIT":
                        reply(221, "Goodbye");
                        alive = false;
                        break;
                    default:
                        if (!loggedIn) {
                            reply(530, "Please login with USER and PASS");
                            break;
                        }
                        handle(verb.toUpperCase(Locale.ROOT), argument);
                        break;
                }
            }

            private void handle(String verb, String argument) throws IOException {
                switch (verb) {
                    case "TYPE":
                        reply(200, "Type set");
                        break;
                    case "SYST":
                        reply(215, "UNIX Type: L8");
                        break;
                    case "FEAT":
                        replyFeat();
                        break;
                    case "OPTS":
                        reply(200, "OK");
                        break;
                    case "PASV":
                        enterPassive();
                        break;
                    case "PORT":
                        reply(200, "PORT command ok");
                        break;
                    case "PWD":
                        reply(257, "\"" + cwd + "\" is the current directory");
                        break;
                    case "CWD":
                        changeDirectory(argument);
                        break;
                    case "MKD":
                        makeDirectory(argument);
                        break;
                    case "RMD":
                        removeDirectory(argument);
                        break;
                    case "DELE":
                        deleteFile(argument);
                        break;
                    case "RNFR":
                        renameFrom(argument);
                        break;
                    case "RNTO":
                        renameTo(argument);
                        break;
                    case "SIZE":
                        sizeOf(argument);
                        break;
                    case "MDTM":
                        mtimeCommand(argument);
                        break;
                    case "MLSD":
                        listMlsd(argument);
                        break;
                    case "LIST":
                        listText(argument);
                        break;
                    case "STOR":
                        store(argument);
                        break;
                    case "RETR":
                        retrieve(argument);
                        break;
                    default:
                        reply(502, "Command not implemented");
                        break;
                }
            }

            private void reply(int code, String text) throws IOException {
                out.write((code + " " + text + "\r\n").getBytes(StandardCharsets.UTF_8));
                out.flush();
            }

            private void replyFeat() throws IOException {
                out.write("211-Features:\r\n".getBytes(StandardCharsets.UTF_8));
                out.write(" UTF8\r\n".getBytes(StandardCharsets.UTF_8));
                if (supportMlsd) {
                    out.write(" MLST type*;size*;modify*;\r\n".getBytes(StandardCharsets.UTF_8));
                }
                out.write("211 End\r\n".getBytes(StandardCharsets.UTF_8));
                out.flush();
            }

            private void enterPassive() throws IOException {
                closePendingData();
                ServerSocket data = new ServerSocket(0);
                data.setSoTimeout(10_000);
                pendingData = data;
                int port = data.getLocalPort();
                reply(227, "Entering Passive Mode (127,0,0,1," + (port >> 8) + "," + (port & 0xFF) + ")");
            }

            private void closePendingData() {
                if (pendingData != null) {
                    closeQuietly(pendingData);
                    pendingData = null;
                }
            }

            private Socket acceptData() throws IOException {
                ServerSocket pending = pendingData;
                pendingData = null;
                if (pending == null) {
                    throw new IOException("no passive data socket");
                }
                try {
                    return pending.accept();
                } finally {
                    closeQuietly(pending);
                }
            }

            private Entry resolve(String argument) {
                String path = normalize(argument);
                return path.isEmpty() ? Entry.root() : entries.get(path);
            }

            private void changeDirectory(String argument) throws IOException {
                Entry entry = resolve(argument);
                if (entry == null || !entry.directory) {
                    reply(550, "No such directory");
                    return;
                }
                String path = normalize(argument);
                cwd = path.isEmpty() ? "/" : "/" + path;
                reply(250, "Directory changed");
            }

            private void makeDirectory(String argument) throws IOException {
                String path = normalize(argument);
                if (path.isEmpty() || resolve(path) != null) {
                    reply(550, "Directory already exists");
                } else if (!parentExists(path)) {
                    reply(550, "No such parent directory");
                } else {
                    entries.put(path, Entry.directory());
                    reply(257, "\"" + path + "\" created");
                }
            }

            private void removeDirectory(String argument) throws IOException {
                String path = normalize(argument);
                Entry entry = path.isEmpty() ? null : resolve(path);
                if (entry == null || !entry.directory) {
                    reply(550, "No such directory");
                } else if (!children(path).isEmpty()) {
                    reply(550, "Directory not empty");
                } else {
                    entries.remove(path);
                    reply(250, "Directory removed");
                }
            }

            private void deleteFile(String argument) throws IOException {
                String path = normalize(argument);
                Entry entry = entries.get(path);
                if (entry == null || entry.directory) {
                    reply(550, "File not found");
                } else {
                    entries.remove(path);
                    reply(250, "File deleted");
                }
            }

            private void renameFrom(String argument) throws IOException {
                String path = normalize(argument);
                if (resolve(path) == null) {
                    reply(550, "File not found");
                } else {
                    renameFrom = path;
                    reply(350, "Ready for RNTO");
                }
            }

            private void renameTo(String argument) throws IOException {
                if (renameFrom == null) {
                    reply(503, "RNFR required first");
                    return;
                }
                String from = renameFrom;
                renameFrom = null;
                String to = normalize(argument);
                Entry target = resolve(to);
                if (target != null && target.directory) {
                    reply(550, "Target directory exists");
                } else if (!parentExists(to)) {
                    reply(550, "No such parent directory");
                } else {
                    moveEntry(from, to);
                    reply(250, "Rename successful");
                }
            }

            private void sizeOf(String argument) throws IOException {
                Entry entry = entries.get(normalize(argument));
                if (entry == null || entry.directory) {
                    reply(550, "File not found");
                } else {
                    reply(213, String.valueOf(entry.data.length));
                }
            }

            private void mtimeCommand(String argument) throws IOException {
                Entry entry = resolve(argument);
                if (entry == null) {
                    reply(550, "File not found");
                } else {
                    reply(213, formatDate(entry.mtime));
                }
            }

            private void listMlsd(String argument) throws IOException {
                if (!supportMlsd) {
                    reply(500, "MLSD not understood");
                    return;
                }
                String path = normalize(argument);
                Entry entry = path.isEmpty() ? Entry.root() : entries.get(path);
                if (entry == null || !entry.directory) {
                    reply(550, "Not a directory");
                    return;
                }
                reply(150, "Opening data connection");
                Socket data = acceptData();
                try {
                    OutputStream dout = data.getOutputStream();
                    for (Map.Entry<String, Entry> child : children(path)) {
                        writeDataLine(dout, mlsxLine(path, child.getKey(), child.getValue()));
                    }
                    dout.flush();
                } finally {
                    closeQuietly(data);
                }
                reply(226, "Transfer complete");
            }

            private void listText(String argument) throws IOException {
                String path = argument;
                while (path.startsWith("-")) {
                    int at = path.indexOf(' ');
                    path = at < 0 ? "" : path.substring(at + 1).trim();
                }
                String normalized = normalize(path);
                Entry entry = normalized.isEmpty() ? Entry.root() : entries.get(normalized);
                if (entry == null || !entry.directory) {
                    reply(550, "Not a directory");
                    return;
                }
                reply(150, "Opening data connection");
                Socket data = acceptData();
                try {
                    writeTextListing(data.getOutputStream(), normalized, entry);
                } finally {
                    closeQuietly(data);
                }
                reply(226, "Transfer complete");
            }

            private void writeTextListing(OutputStream dout, String path, Entry entry) throws IOException {
                if (iisList) {
                    for (Map.Entry<String, Entry> child : children(path)) {
                        writeDataLine(dout, iisLine(child.getValue(), nameOf(path, child.getKey())));
                    }
                } else {
                    String stamp = lsDate(entry.mtime);
                    writeDataLine(dout, "drwxr-xr-x 2 ftp ftp 4096 " + stamp + " .");
                    writeDataLine(dout, "drwxr-xr-x 2 ftp ftp 4096 " + stamp + " ..");
                    for (Map.Entry<String, Entry> child : children(path)) {
                        writeDataLine(dout, unixLine(child.getValue(), nameOf(path, child.getKey())));
                    }
                }
                dout.flush();
            }

            private void writeDataLine(OutputStream dout, String line) throws IOException {
                dout.write((line + "\r\n").getBytes(StandardCharsets.UTF_8));
            }

            private void store(String argument) throws IOException {
                String path = normalize(argument);
                Entry existing = entries.get(path);
                if (path.isEmpty() || (existing != null && existing.directory) || !parentExists(path)) {
                    reply(550, "Cannot store file");
                    return;
                }
                reply(150, "Ok to send data");
                Socket data = acceptData();
                byte[] bytes;
                try {
                    bytes = readAll(data.getInputStream());
                } finally {
                    closeQuietly(data);
                }
                entries.put(path, Entry.file(bytes));
                reply(226, "Transfer complete");
            }

            private void retrieve(String argument) throws IOException {
                Entry entry = entries.get(normalize(argument));
                if (entry == null || entry.directory) {
                    reply(550, "File not found");
                    return;
                }
                reply(150, "Opening data connection");
                Socket data = acceptData();
                try {
                    OutputStream dout = data.getOutputStream();
                    dout.write(entry.data);
                    dout.flush();
                } finally {
                    closeQuietly(data);
                }
                reply(226, "Transfer complete");
            }

            private byte[] readAll(InputStream in) throws IOException {
                ByteArrayOutputStream buffer = new ByteArrayOutputStream();
                byte[] chunk = new byte[8192];
                int read;
                while ((read = in.read(chunk)) >= 0) {
                    buffer.write(chunk, 0, read);
                }
                return buffer.toByteArray();
            }

            private String mlsxLine(String dir, String key, Entry entry) {
                String name = nameOf(dir, key);
                String modify = formatDate(entry.mtime);
                if (entry.directory) {
                    return "type=dir;modify=" + modify + ";unix.owner=ftp;unix.group=staff; " + name;
                }
                return "type=file;size=" + entry.data.length + ";modify=" + modify
                        + ";unix.owner=ftp;unix.group=staff; " + name;
            }

            private String unixLine(Entry entry, String name) {
                String perms = entry.directory ? "drwxr-xr-x" : "-rw-r--r--";
                String size = entry.directory ? "4096" : String.valueOf(entry.data.length);
                return perms + " 1 ftp ftp " + size + " " + lsDate(entry.mtime) + " " + name;
            }

            private String iisLine(Entry entry, String name) {
                String stamp = iisDate(entry.mtime);
                return entry.directory ? stamp + " <DIR> " + name : stamp + " " + entry.data.length + " " + name;
            }
        }
    }
}
