package com.alianga.jkit.storage.real;

import com.alianga.jkit.storage.FileStorage;
import com.alianga.jkit.storage.FileStorages;
import com.alianga.jkit.storage.StorageErrorCode;
import com.alianga.jkit.storage.StorageException;

import org.junit.After;
import org.junit.Assume;
import org.junit.Before;
import org.junit.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * 临时本机 copyparty：验证 {@code replace: 1} 在有删除权限（rwmda）与无删除权限（rwma）下的差异。
 *
 * <p>工作目录固定在 {@code /tmp}，用例结束后杀进程并清理。copyparty 不在 PATH 时跳过。</p>
 *
 * @author 郑明亮
 */
public class CopypartyReplacePermissionIT {
    private static final String USER = "demo";
    private static final String PASSWORD = "demo";

    private Path workDir;
    private Process server;
    private int httpPort;

    @Before
    public void start() throws Exception {
        String binary = locateBinary();
        Assume.assumeTrue("copyparty 不在 PATH，跳过", binary != null);
        workDir = Files.createTempDirectory(Paths.get("/tmp"), "copyparty-replace-");
        Path volRwmd = workDir.resolve("vol-rwmd");
        Path volRwm = workDir.resolve("vol-rwm");
        Files.createDirectories(volRwmd);
        Files.createDirectories(volRwm);
        httpPort = freePort();
        List<String> command = new ArrayList<String>();
        command.add(binary);
        command.add("-i");
        command.add("127.0.0.1");
        command.add("-p");
        command.add(String.valueOf(httpPort));
        command.add("-a");
        command.add(USER + ":" + PASSWORD);
        command.add("-v");
        command.add(volRwmd + ":/rwmd:rwmda," + USER);
        command.add("-v");
        command.add(volRwm + ":/rwm:rwma," + USER);
        command.add("-q");
        ProcessBuilder builder = new ProcessBuilder(command);
        builder.redirectErrorStream(true);
        builder.redirectOutput(workDir.resolve("server.log").toFile());
        server = builder.start();
        assertTrue("copyparty HTTP 未在 40s 内就绪", waitPort(httpPort, 40_000));
    }

    @After
    public void stop() throws Exception {
        if (server != null) {
            server.destroy();
            try {
                server.waitFor();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        if (workDir != null) {
            deleteRecursive(workDir);
        }
    }

    @Test
    public void replaceHeaderOverwritesWhenDeletePermitted() {
        FileStorage dav = webdav("/rwmd");
        dav.upload("file.txt", "v1".getBytes(StandardCharsets.UTF_8));
        dav.upload("file.txt", "v2".getBytes(StandardCharsets.UTF_8));
        assertEquals("v2", new String(dav.readBytes("file.txt"), StandardCharsets.UTF_8));
        assertEquals(1, dav.list("").size());
    }

    /**
     * 实测结论（copyparty v1.x）：无 {@code d} 权限时 {@code replace: 1} 仍改名另存，
     * WebDavFileStorage 的 MOVE/DELETE 恢复亦失败 → CONFLICT；原文件内容不变。
     */
    @Test
    public void replaceHeaderIgnoredWithoutDeletePermission() {
        FileStorage dav = webdav("/rwm");
        dav.upload("file.txt", "v1".getBytes(StandardCharsets.UTF_8));
        try {
            dav.upload("file.txt", "v2".getBytes(StandardCharsets.UTF_8));
            fail("expected CONFLICT without delete permission on copyparty");
        } catch (StorageException e) {
            assertEquals(StorageErrorCode.CONFLICT, e.code());
        }
        assertEquals("v1", new String(dav.readBytes("file.txt"), StandardCharsets.UTF_8));
    }

    private FileStorage webdav(String basePath) {
        Map<String, String> values = new LinkedHashMap<String, String>();
        values.put("endpoint", "http://127.0.0.1:" + httpPort);
        values.put("user", USER);
        values.put("password", PASSWORD);
        values.put("basePath", basePath);
        return FileStorages.of("webdav", values);
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
        try (ServerSocket socket = new ServerSocket()) {
            socket.bind(new InetSocketAddress("127.0.0.1", 0));
            return socket.getLocalPort();
        }
    }

    private static boolean waitPort(int port, long timeoutMs) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            try (Socket socket = new Socket()) {
                socket.connect(new InetSocketAddress("127.0.0.1", port), 500);
                return true;
            } catch (IOException ignored) {
                Thread.sleep(200L);
            }
        }
        return false;
    }

    private static void deleteRecursive(Path root) throws IOException {
        if (root == null || !Files.exists(root)) {
            return;
        }
        Files.walk(root)
                .sorted((a, b) -> b.compareTo(a))
                .forEach(path -> {
                    try {
                        Files.deleteIfExists(path);
                    } catch (IOException ignored) {
                        // best-effort cleanup
                    }
                });
    }
}
