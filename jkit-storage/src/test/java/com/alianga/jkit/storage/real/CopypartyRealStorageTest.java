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
    private static void exercise(FileStorage fs) throws Exception {
        byte[] data = "你好，真实服务器！real-protocol".getBytes(StandardCharsets.UTF_8);
        fs.upload("docs/中文说明.txt", data);
        assertEquals(new String(data, StandardCharsets.UTF_8),
                new String(fs.readBytes("docs/中文说明.txt"), StandardCharsets.UTF_8));
        fs.upload("docs/inner/deep.bin", new byte[]{9, 8, 7});
        fs.upload("root.txt", "r".getBytes(StandardCharsets.UTF_8));

        assertTrue(fs.exists("docs"));
        assertTrue(fs.exists("docs/中文说明.txt"));
        assertFalse(fs.exists("no/such/object"));

        StorageEntry entry = fs.stat("docs/中文说明.txt");
        assertEquals(data.length, entry.size());
        assertFalse(entry.isDirectory());

        fs.mkdir("docs/empty");
        fs.mkdir("docs/empty");
        assertTrue(fs.exists("docs/empty"));

        List<StorageEntry> list = fs.list("docs");
        assertTrue(names(list).contains("中文说明.txt"));
        assertTrue(names(list).contains("inner"));
        assertTrue(names(list).contains("empty"));

        fs.rename("docs/inner/deep.bin", "docs/inner/deep2.bin");
        assertFalse(fs.exists("docs/inner/deep.bin"));
        assertTrue(fs.exists("docs/inner/deep2.bin"));
        fs.copy("docs/inner/deep2.bin", "docs/inner/deep3.bin");
        assertEquals(3, fs.readBytes("docs/inner/deep3.bin").length);

        fs.delete("docs/inner/deep3.bin");
        assertFalse(fs.exists("docs/inner/deep3.bin"));
        fs.deleteDir("docs/inner");
        assertFalse(fs.exists("docs/inner"));

        try {
            fs.stat("ghost.txt");
            throw new AssertionError("应抛 NOT_FOUND");
        } catch (StorageException e) {
            assertEquals(StorageErrorCode.NOT_FOUND, e.code());
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
        exercise(FileStorages.of("ftp", values));
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
        exercise(FileStorages.of("sftp", values));
    }

    @Test
    public void webdavRealServer() throws Exception {
        Map<String, String> values = new LinkedHashMap<String, String>();
        values.put("endpoint", "http://127.0.0.1:" + httpPort);
        values.put("user", USER);
        values.put("password", PASSWORD);
        values.put("basePath", "/data");
        exercise(FileStorages.of("webdav", values));
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
