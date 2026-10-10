package com.alianga.jkit.storage.real;

import com.alianga.jkit.storage.FileStorage;
import com.alianga.jkit.storage.FileStorages;
import com.alianga.jkit.storage.StorageEntry;
import com.alianga.jkit.storage.StorageErrorCode;
import com.alianga.jkit.storage.StorageException;

import org.junit.AfterClass;
import org.junit.Assume;
import org.junit.BeforeClass;
import org.junit.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * 真实服务器集成测试（P0 联调）：用 <a href="https://github.com/9001/copyparty">copyparty</a>
 * 单进程同时提供 FTP / SFTP / WebDAV 三个真实协议实现，验证 jkit-storage 三个引擎
 * 在非模拟服务器上的行为。
 *
 * <p>运行前提（本机）：PATH 中有 {@code copyparty}，且 Python 环境装有
 * {@code pyftpdlib}（FTP）与 {@code paramiko}（SFTP）。任一前提不满足时对应测试
 * 自动跳过（Assume），CI 不受影响。</p>
 *
 * @author 郑明亮
 */
public class CopypartyRealStorageTest {

    private static final String USER = "tester";
    private static final String PASSWORD = "testpw";

    private static Process server;
    private static Path volume;
    private static int ftpPort;
    private static int sftpPort;
    private static int httpPort;
    private static boolean sftpUp;

    /** 三个引擎共用的行为矩阵：上传下载 / 列表 / 目录 / 重命名复制 / 删除。 */
    private static void exercise(FileStorage fs, String root) throws Exception {
        byte[] data = "你好，真实服务器！real-protocol".getBytes(StandardCharsets.UTF_8);
        fs.upload(root + "/docs/中文说明.txt", data);
        assertEquals(new String(data, StandardCharsets.UTF_8),
                new String(fs.readBytes(root + "/docs/中文说明.txt"), StandardCharsets.UTF_8));
        fs.upload(root + "/docs/inner/deep.bin", new byte[]{9, 8, 7});
        fs.upload(root + "/root.txt", "r".getBytes(StandardCharsets.UTF_8));

        assertTrue(fs.exists(root + "/docs"));
        assertTrue(fs.exists(root + "/docs/中文说明.txt"));
        assertFalse(fs.exists(root + "/no/such/object"));

        StorageEntry entry = fs.stat(root + "/docs/中文说明.txt");
        assertEquals(data.length, entry.size());
        assertFalse(entry.isDirectory());

        fs.mkdir(root + "/docs/empty");
        fs.mkdir(root + "/docs/empty");
        assertTrue(fs.exists(root + "/docs/empty"));

        List<StorageEntry> list = fs.list(root + "/docs");
        assertTrue(names(list).contains("中文说明.txt"));
        assertTrue(names(list).contains("inner"));
        assertTrue(names(list).contains("empty"));

        fs.rename(root + "/docs/inner/deep.bin", root + "/docs/inner/deep2.bin");
        assertFalse(fs.exists(root + "/docs/inner/deep.bin"));
        assertTrue(fs.exists(root + "/docs/inner/deep2.bin"));
        fs.copy(root + "/docs/inner/deep2.bin", root + "/docs/inner/deep3.bin");
        assertEquals(3, fs.readBytes(root + "/docs/inner/deep3.bin").length);

        // 目录级 rename / copy（服务端整体搬移或递归实现）
        fs.rename(root + "/docs/inner", root + "/docs/inner2");
        assertTrue(fs.exists(root + "/docs/inner2/deep2.bin"));
        assertFalse(fs.exists(root + "/docs/inner"));
        fs.copy(root + "/docs/inner2", root + "/docs/inner3");
        assertTrue(fs.exists(root + "/docs/inner3/deep2.bin"));

        // 空文件与 1MB 二进制
        fs.upload(root + "/empty.txt", new byte[0]);
        assertEquals(0, fs.stat(root + "/empty.txt").size());
        byte[] big = new byte[1024 * 1024];
        for (int i = 0; i < big.length; i++) {
            big[i] = (byte) (i * 31);
        }
        fs.upload(root + "/big/large.bin", big);
        byte[] roundTrip = fs.readBytes(root + "/big/large.bin");
        assertEquals(big.length, roundTrip.length);
        assertEquals(big[123456], roundTrip[123456]);

        fs.delete(root + "/docs/inner3/deep2.bin");
        assertFalse(fs.exists(root + "/docs/inner3/deep2.bin"));
        fs.deleteDir(root + "/docs/inner3");
        assertFalse(fs.exists(root + "/docs/inner3"));

        try {
            fs.stat(root + "/ghost.txt");
            throw new AssertionError("应抛 NOT_FOUND");
        } catch (StorageException e) {
            assertEquals(StorageErrorCode.NOT_FOUND, e.code());
        }
    }

    private static void deleteRecursively(Path root) throws IOException {
        if (!Files.exists(root)) {
            return;
        }
        try (java.util.stream.Stream<Path> walk = Files.walk(root)) {
            for (Path path : walk.sorted(java.util.Comparator.reverseOrder()).toArray(Path[]::new)) {
                Files.deleteIfExists(path);
            }
        }
    }

    private static List<String> names(List<StorageEntry> entries) {
        List<String> names = new ArrayList<String>();
        for (StorageEntry entry : entries) {
            names.add(entry.name());
        }
        return names;
    }

    @BeforeClass
    public static void startServer() throws Exception {
        String binary = locateBinary();
        Assume.assumeTrue("copyparty 不在 PATH，跳过真实协议集成测试", binary != null);
        ftpPort = freePort();
        sftpPort = freePort();
        httpPort = freePort();
        volume = Files.createTempDirectory("copyparty-real");
        List<String> command = new ArrayList<String>();
        command.add(binary);
        command.add("-i");
        command.add("127.0.0.1");
        command.add("-p");
        command.add(String.valueOf(httpPort));
        command.add("--ftp");
        command.add(String.valueOf(ftpPort));
        command.add("--ftp-pr");
        command.add("12100-12200");
        command.add("--sftp");
        command.add(String.valueOf(sftpPort));
        command.add("--sftp-pw");
        Path hostKeys = volume.resolve("hostkeys");
        Files.createDirectories(hostKeys);
        command.add("--sftp-hostk");
        command.add(hostKeys.toString());
        command.add("-a");
        command.add(USER + ":" + PASSWORD);
        command.add("-v");
        command.add(volume + ":/data:rwmda," + USER);
        command.add("-q");
        ProcessBuilder builder = new ProcessBuilder(command);
        builder.redirectErrorStream(true);
        builder.redirectOutput(volume.resolve("server.log").toFile());
        server = builder.start();
        assertTrue("copyparty 未能在 40s 内就绪（HTTP 端口）",
                waitPort(httpPort, 40_000));
        boolean ftpReady = waitPort(ftpPort, 20_000);
        sftpUp = waitPort(sftpPort, 20_000);
        Assume.assumeTrue("FTP 端口未就绪（缺 pyftpdlib？），跳过 FTP 用例", ftpReady);
        Assume.assumeTrue("SFTP 端口未就绪（缺 paramiko？），跳过 SFTP 用例", sftpUp);
    }

    @AfterClass
    public static void stopServer() {
        if (server != null) {
            server.destroy();
        }
    }

    private static String locateBinary() throws IOException {
        Process which = new ProcessBuilder("bash", "-c", "command -v copyparty")
                .redirectErrorStream(true).start();
        try {
            if (which.waitFor() == 0) {
                return new String(which.getInputStream().readAllBytes(),
                        StandardCharsets.UTF_8).trim();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return null;
    }

    private static int freePort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }

    private static boolean waitPort(int port, long timeoutMs) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            if (server != null && !server.isAlive()) {
                return false;
            }
            try (Socket socket = new Socket()) {
                socket.connect(new InetSocketAddress("127.0.0.1", port), 500);
                return true;
            } catch (IOException e) {
                Thread.sleep(400);
            }
        }
        return false;
    }

    @Test
    public void ftpRealServer() throws Exception {
        Map<String, String> values = new LinkedHashMap<String, String>();
        values.put("host", "127.0.0.1");
        values.put("port", String.valueOf(ftpPort));
        values.put("user", USER);
        values.put("password", PASSWORD);
        values.put("basePath", "/data");
        values.put("passive", "true");
        String root = "jkit-ftp-" + System.nanoTime();
        try {
            exercise(FileStorages.of("ftp", values), root);
        } finally {
            FileStorages.of("ftp", values).deleteDir(root);
        }
    }

    @Test
    public void sftpRealServer() throws Exception {
        Assume.assumeTrue(sftpUp);
        Map<String, String> values = new LinkedHashMap<String, String>();
        values.put("host", "127.0.0.1");
        values.put("port", String.valueOf(sftpPort));
        values.put("user", USER);
        values.put("password", PASSWORD);
        values.put("basePath", "/data");
        String root = "jkit-sftp-" + System.nanoTime();
        try {
            exercise(FileStorages.of("sftp", values), root);
        } finally {
            FileStorages.of("sftp", values).deleteDir(root);
        }
    }

    @Test
    public void webdavRealServer() throws Exception {
        Map<String, String> values = new LinkedHashMap<String, String>();
        values.put("endpoint", "http://127.0.0.1:" + httpPort);
        values.put("user", USER);
        values.put("password", PASSWORD);
        values.put("basePath", "/data");
        String root = "jkit-dav-" + System.nanoTime();
        try {
            exercise(FileStorages.of("webdav", values), root);
        } finally {
            FileStorages.of("webdav", values).deleteDir(root);
        }
    }

    @Test
    public void crossStorageSyncAcrossRealProtocols() throws Exception {
        Path localRoot = Files.createTempDirectory("copyparty-sync");
        String runRoot = "jkit-sync-" + System.nanoTime();
        String stage1 = runRoot + "/stage1";
        String stage2 = runRoot + "/stage2";
        String stage3 = runRoot + "/stage3";
        try {
            Map<String, String> localValues = new LinkedHashMap<String, String>();
            localValues.put("basePath", localRoot.toString());
            FileStorage local = FileStorages.of("local", localValues);
            local.upload("sync-src/a.txt", "A".getBytes(StandardCharsets.UTF_8));
            local.upload("sync-src/sub/b.txt", "BB".getBytes(StandardCharsets.UTF_8));

            Map<String, String> ftpValues = new LinkedHashMap<String, String>();
            ftpValues.put("host", "127.0.0.1");
            ftpValues.put("port", String.valueOf(ftpPort));
            ftpValues.put("user", USER);
            ftpValues.put("password", PASSWORD);
            ftpValues.put("basePath", "/data");
            FileStorage ftp = FileStorages.of("ftp", ftpValues);

            // 本地 → FTP（真实协议）
            com.alianga.jkit.storage.sync.SyncResult first =
                    com.alianga.jkit.storage.sync.SyncEngine.sync(local, "sync-src", ftp, stage1,
                            com.alianga.jkit.storage.sync.SyncOptions.incremental(), null);
            assertEquals(2L, first.copied());
            assertEquals("BB", new String(ftp.readBytes(stage1 + "/sub/b.txt"),
                    StandardCharsets.UTF_8));

            // FTP → WebDAV
            Map<String, String> davValues = new LinkedHashMap<String, String>();
            davValues.put("endpoint", "http://127.0.0.1:" + httpPort);
            davValues.put("user", USER);
            davValues.put("password", PASSWORD);
            davValues.put("basePath", "/data");
            FileStorage dav = FileStorages.of("webdav", davValues);
            com.alianga.jkit.storage.sync.SyncResult second =
                    com.alianga.jkit.storage.sync.SyncEngine.sync(ftp, stage1, dav, stage2,
                            com.alianga.jkit.storage.sync.SyncOptions.incremental(), null);
            assertEquals(2L, second.copied());

            // WebDAV → SFTP（镜像：多出的文件被删除）
            Map<String, String> sftpValues = new LinkedHashMap<String, String>();
            sftpValues.put("host", "127.0.0.1");
            sftpValues.put("port", String.valueOf(sftpPort));
            sftpValues.put("user", USER);
            sftpValues.put("password", PASSWORD);
            sftpValues.put("basePath", "/data");
            FileStorage sftp = FileStorages.of("sftp", sftpValues);
            sftp.upload(stage3 + "/stale.txt", "old".getBytes(StandardCharsets.UTF_8));
            dav.upload(stage2 + "/new.txt", "new".getBytes(StandardCharsets.UTF_8));
            com.alianga.jkit.storage.sync.SyncResult third =
                    com.alianga.jkit.storage.sync.SyncEngine.sync(dav, stage2, sftp, stage3,
                            com.alianga.jkit.storage.sync.SyncOptions.mirror(),
null
            assertEquals(3L, third.copied());
            assertEquals(1L, third.deleted());
            assertFalse(sftp.exists(stage3 + "/stale.txt"));
            assertEquals("new", sftp.readString(stage3 + "/new.txt"));
            sftp.deleteDir(runRoot);
        } finally {
            deleteRecursively(localRoot);
        }
    }

    @Test
    public void ftpWrongPassword() {
        Map<String, String> values = new LinkedHashMap<String, String>();
        values.put("host", "127.0.0.1");
        values.put("port", String.valueOf(ftpPort));
        values.put("user", USER);
        values.put("password", "wrong-password");
        values.put("basePath", "/data");
        try {
            FileStorages.of("ftp", values).list("");
            throw new AssertionError("错误密码应抛 AUTH_FAILED");
        } catch (StorageException e) {
            assertEquals(StorageErrorCode.AUTH_FAILED, e.code());
        }
    }
}
