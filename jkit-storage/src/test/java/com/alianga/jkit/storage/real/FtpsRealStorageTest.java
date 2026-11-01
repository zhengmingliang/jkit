package com.alianga.jkit.storage.real;

import com.alianga.jkit.storage.FileStorage;
import com.alianga.jkit.storage.FileStorages;
import com.alianga.jkit.storage.StorageConfig;
import com.alianga.jkit.storage.StorageErrorCode;
import com.alianga.jkit.storage.StorageException;
import com.alianga.jkit.storage.StoragePaths;

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
 * FTPS 真实服务器集成测试（P1#20）：copyparty（内置 pyftpdlib）以显式 AUTH TLS
 * 提供真实 FTPS 服务，验证自研 {@code FtpClient} 的 TLS 控制通道 / PROT P 数据通道
 * 保护与完整行为矩阵。
 *
 * <p>证书在测试启动时由 {@code openssl} 生成自签名（有效文件，非占位）；
 * 引擎侧 {@code trustAllCerts=true} 为自签名场景的显式 opt-in——正规 CA 证书
 * 默认走 JVM 信任链并校验主机名。运行前提任一不满足时整套用例自动跳过（Assume）：</p>
 * <ul>
 *   <li>PATH 中有 {@code copyparty} 与 {@code openssl}；</li>
 *   <li>运行 copyparty 的 python3 环境装有 PyOpenSSL（FTPS 硬性依赖）。</li>
 * </ul>
 *
 * @author 郑明亮
 */
public class FtpsRealStorageTest {

    private static final String USER = "tuser";
    private static final String PASSWORD = "tpass";

    private static Process server;
    private static Path volume;
    private static Path workDir;
    private static int ftpsPort;
    private static int httpPort;

    @BeforeClass
    public static void startServer() throws Exception {
        String binary = locateBinary("copyparty");
        Assume.assumeTrue("copyparty 不在 PATH，跳过 FTPS 真实集成测试", binary != null);
        Assume.assumeTrue("openssl 不在 PATH，无法生成测试证书",
                locateBinary("openssl") != null);
        Assume.assumeTrue("python3 缺 PyOpenSSL（copyparty FTPS 硬性依赖），跳过",
                moduleAvailable("OpenSSL"));

        workDir = Files.createTempDirectory("ftps-real");
        volume = workDir.resolve("volume");
        Files.createDirectories(volume);

        // 自签名证书：CN 与连接主机一致
        String cert = workDir.resolve("server.crt").toString();
        String key = workDir.resolve("server.key").toString();
        Process openssl = new ProcessBuilder("openssl", "req", "-x509", "-newkey",
                "rsa:2048", "-nodes", "-keyout", key, "-out", cert, "-days", "2",
                "-subj", "/CN=127.0.0.1")
                .redirectErrorStream(true).start();
        openssl.getInputStream().readAllBytes();
        Assume.assumeTrue("openssl 生成自签名证书失败", openssl.waitFor() == 0);

        ftpsPort = freePort();
        httpPort = freePort();
        int passiveBegin = freePort();
        List<String> command = new ArrayList<String>();
        command.add(binary);
        command.add("-i");
        command.add("127.0.0.1");
        command.add("-p");
        command.add(String.valueOf(httpPort));
        command.add("--ftps");
        command.add(String.valueOf(ftpsPort));
        command.add("--ftp-pr");
        command.add(passiveBegin + "-" + (passiveBegin + 20));
        command.add("--cert");
        command.add(cert);
        command.add("--certkey");
        command.add(key);
        command.add("-a");
        command.add(USER + ":" + PASSWORD);
        command.add("-v");
        command.add(volume + ":/data:rwmda," + USER);
        command.add("-q");
        ProcessBuilder builder = new ProcessBuilder(command);
        builder.redirectErrorStream(true);
        builder.redirectOutput(workDir.resolve("server.log").toFile());
        server = builder.start();
        boolean up = waitPort(ftpsPort, 40_000);
        Assume.assumeTrue("FTPS 端口未就绪（40s），跳过", up);
    }

    @AfterClass
    public static void stopServer() {
        if (server != null) {
            server.destroy();
        }
    }

    /** 严格模式负例：默认（信任 CA 链）连接自签名服务器必须失败——证明默认确实在验证书。 */
    @Test
    public void strictModeRejectsSelfSignedCertificate() {
        FileStorage ftps = FileStorages.of("ftp", config(false));
        try {
            ftps.list("");
            throw new AssertionError("自签名证书在默认信任链下必须连接失败");
        } catch (StorageException e) {
            assertTrue("应映射为连接/IO 类错误，实际: " + e.code(),
                    e.code() == StorageErrorCode.CONNECT_FAILED
                            || e.code() == StorageErrorCode.IO_ERROR);
        } finally {
            ftps.close();
        }
    }

    /** FTPS 行为矩阵：登录即 TLS、上传 / 下载 / 重命名 / 递归删除 / 中文路径。 */
    @Test
    public void ftpsRealServerBehaviorMatrix() throws Exception {
        FileStorage ftps = FileStorages.of("ftp", config(true));
        try {
            String root = "jkit-ftps-" + System.nanoTime();
            ftps.mkdir(root);
            ftps.upload(StoragePaths.join(root, "hello.txt"), "你好 FTPS".getBytes(StandardCharsets.UTF_8));
            ftps.upload(StoragePaths.join(root, "中文/数据.bin"), new byte[]{1, 2, 3, 4, 5});

            List<com.alianga.jkit.storage.StorageEntry> list = ftps.list(root);
            assertTrue("应列出两个条目: " + list.size(), list.size() == 2);

            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            ftps.download(StoragePaths.join(root, "hello.txt"), out);
            assertEquals("你好 FTPS", new String(out.toByteArray(), StandardCharsets.UTF_8));

            ftps.rename(StoragePaths.join(root, "hello.txt"),
                    StoragePaths.join(root, "renamed.txt"));
            assertFalse(ftps.exists(StoragePaths.join(root, "hello.txt")));
            assertTrue(ftps.exists(StoragePaths.join(root, "renamed.txt")));

            ftps.deleteDir(root);
            assertFalse(ftps.exists(root));
        } finally {
            ftps.close();
        }
    }

    /** 未知长度上传（引擎内部走流式读取路径）在 TLS 数据通道上的往返。 */
    @Test
    public void unknownSizeUploadOverTlsDataChannel() throws Exception {
        FileStorage ftps = FileStorages.of("ftp", config(true));
        try {
            String path = "stream-" + System.nanoTime() + ".bin";
            byte[] payload = new byte[256 * 1024];
            for (int i = 0; i < payload.length; i++) {
                payload[i] = (byte) (i % 251);
            }
            ftps.upload(path, new java.io.ByteArrayInputStream(payload), -1L);
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            ftps.download(path, out);
            org.junit.Assert.assertArrayEquals(payload, out.toByteArray());
            ftps.delete(path);
        } finally {
            ftps.close();
        }
    }

    private static StorageConfig config(boolean trustAll) {
        Map<String, String> values = new LinkedHashMap<String, String>();
        values.put("host", "127.0.0.1");
        values.put("port", String.valueOf(ftpsPort));
        values.put("user", USER);
        values.put("password", PASSWORD);
        values.put("basePath", "/data");
        values.put("passive", "true");
        values.put("ftps", "explicit");
        values.put("trustAllCerts", String.valueOf(trustAll));
        return StorageConfig.of(values);
    }

    private static String locateBinary(String name) {
        for (String dir : System.getenv("PATH").split(java.io.File.pathSeparator)) {
            java.io.File candidate = new java.io.File(dir, name);
            if (candidate.canExecute()) {
                return candidate.getAbsolutePath();
            }
        }
        return null;
    }

    private static boolean moduleAvailable(String module) {
        try {
            Process process = new ProcessBuilder("python3", "-c", "import " + module)
                    .redirectErrorStream(true).start();
            process.getInputStream().readAllBytes();
            return process.waitFor() == 0;
        } catch (IOException | InterruptedException e) {
            return false;
        }
    }

    private static int freePort() throws IOException {
        try (ServerSocket socket = new ServerSocket()) {
            socket.bind(new InetSocketAddress("127.0.0.1", 0));
            return socket.getLocalPort();
        }
    }

    private static boolean waitPort(int port, long timeoutMillis) {
        long deadline = System.currentTimeMillis() + timeoutMillis;
        while (System.currentTimeMillis() < deadline) {
            try {
                Socket socket = new Socket();
                socket.connect(new InetSocketAddress("127.0.0.1", port), 500);
                socket.close();
                return true;
            } catch (IOException e) {
                try {
                    Thread.sleep(300);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    return false;
                }
            }
        }
        return false;
    }
}
