package com.alianga.jkit.storage.engine;

import com.alianga.jkit.storage.StorageConfig;
import com.alianga.jkit.storage.StorageErrorCode;
import com.alianga.jkit.storage.StorageException;
import org.junit.Test;

import java.io.IOException;
import java.net.ServerSocket;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * SFTP 引擎测试：不依赖 jsch 依赖与真实服务器，覆盖配置校验、懒连接语义与
 * 「classpath 无 jsch → 首次操作抛 UNSUPPORTED / 有 jsch 但无服务器 → CONNECT_FAILED」。
 */
public class SftpFileStorageTest {
    @Test
    public void missingHostThrowsInvalidConfig() {
        try {
            new SftpFileStorage(StorageConfig.empty());
            fail("缺 host 应抛 INVALID_CONFIG");
        } catch (StorageException e) {
            assertEquals(StorageErrorCode.INVALID_CONFIG, e.code());
        }
    }

    @Test
    public void missingUserThrowsInvalidConfig() {
        try {
            new SftpFileStorage(StorageConfig.empty().set("host", "127.0.0.1"));
            fail("缺 user 应抛 INVALID_CONFIG");
        } catch (StorageException e) {
            assertEquals(StorageErrorCode.INVALID_CONFIG, e.code());
        }
    }

    @Test
    public void constructorIsLazyAndDoesNotConnect() throws IOException {
        SftpFileStorage storage = new SftpFileStorage(config());
        try {
            assertEquals("sftp", storage.platform());
        } finally {
            storage.close();
        }
    }

    @Test
    public void firstOperationFailsWithoutServer() throws IOException {
        SftpFileStorage storage = new SftpFileStorage(config());
        try {
            storage.exists("a.txt");
            fail("无服务器时首次操作应失败");
        } catch (StorageException e) {
            if (jschOnClasspath()) {
                assertTrue("有 jsch 时连接失败应映射为 CONNECT_FAILED/TIMEOUT，实际: " + e.code(),
                        e.code() == StorageErrorCode.CONNECT_FAILED || e.code() == StorageErrorCode.TIMEOUT);
            } else {
                assertEquals("classpath 无 jsch 时首次操作应抛 UNSUPPORTED",
                        StorageErrorCode.UNSUPPORTED, e.code());
            }
        } finally {
            storage.close();
        }
    }

    private static StorageConfig config() throws IOException {
        return StorageConfig.empty()
                .set("host", "127.0.0.1")
                .set("port", String.valueOf(freePort()))
                .set("user", "test")
                .set("password", "secret");
    }

    /** 取一个当前未监听的端口，保证连接必然被拒绝。 */
    private static int freePort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }

    private static boolean jschOnClasspath() {
        try {
            Class.forName("com.jcraft.jsch.JSch");
            return true;
        } catch (ClassNotFoundException e) {
            return false;
        }
    }
}
