package com.alianga.jkit.storage.engine;

import com.alianga.jkit.storage.StorageConfig;
import com.alianga.jkit.storage.StorageErrorCode;
import com.alianga.jkit.storage.StorageException;
import org.junit.Test;

import java.io.IOException;
import java.net.ServerSocket;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
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
    public void absolutePathMapsToServerRootWhenBasePathMissing() throws IOException {
        SftpFileStorage storage = new SftpFileStorage(StorageConfig.empty()
                .set("host", "127.0.0.1").set("user", "test"));
        try {
            assertEquals("basePath 未配置时存储根即服务器根 /",
                    "/docs/a.txt", storage.absolutePathOf("docs/a.txt"));
            assertEquals("/", storage.absolutePathOf(""));
            assertEquals("/", storage.absolutePathOf("/"));
        } finally {
            storage.close();
        }
    }

    @Test
    public void absolutePathHonorsConfiguredBasePath() throws IOException {
        SftpFileStorage storage = new SftpFileStorage(StorageConfig.empty()
                .set("host", "127.0.0.1").set("user", "test")
                .set("basePath", "/data/backup"));
        SftpFileStorage messy = new SftpFileStorage(StorageConfig.empty()
                .set("host", "127.0.0.1").set("user", "test")
                .set("basePath", "/data//tmp/"));
        try {
            assertEquals("/data/backup/docs", storage.absolutePathOf("docs"));
            assertEquals("/data/backup", storage.absolutePathOf(""));
            assertEquals("冗余分隔符与尾随 / 折叠后强制绝对", "/data/tmp/a", messy.absolutePathOf("a"));
        } finally {
            storage.close();
            messy.close();
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

    // ============ strictHostKeyChecking 默认 / 显式 / 系统属性 三态 ============

    /**
     * 默认（无显式配置、无系统属性）：strictHostKeyChecking = false（向后兼容）。
     */
    @Test
    public void strictHostKeyCheckingDefaultIsFalse() throws IOException {
        SftpFileStorage storage = new SftpFileStorage(config());
        try {
            assertFalse("strictHostKeyChecking 默认应为 false", isStrict(storage));
        } finally {
            storage.close();
        }
    }

    /**
     * 显式配置 strictHostKeyChecking=true：开启。
     */
    @Test
    public void strictHostKeyCheckingExplicitTrue() throws IOException {
        SftpFileStorage storage = new SftpFileStorage(config()
                .set("strictHostKeyChecking", "true"));
        try {
            assertTrue("显式 strictHostKeyChecking=true 应生效", isStrict(storage));
        } finally {
            storage.close();
        }
    }

    /**
     * 显式配置 strictHostKeyChecking=false：关闭（即使系统属性已设 true）。
     */
    @Test
    public void strictHostKeyCheckingExplicitFalseOverridesSysProp() throws IOException {
        String prop = SftpFileStorage.SYS_PROP_DEFAULT_STRICT_HOST_KEY_CHECKING;
        String old = System.getProperty(prop);
        System.setProperty(prop, "true");
        try {
            SftpFileStorage storage = new SftpFileStorage(config()
                    .set("strictHostKeyChecking", "false"));
            try {
                assertFalse("显式 false 应覆盖系统属性 true", isStrict(storage));
            } finally {
                storage.close();
            }
        } finally {
            if (old == null) {
                System.clearProperty(prop);
            } else {
                System.setProperty(prop, old);
            }
        }
    }

    /**
     * 未显式配置 + 系统属性 true：strictHostKeyChecking = true（运维全局强制）。
     */
    @Test
    public void strictHostKeyCheckingSysPropTrueAppliesWhenNotExplicit() throws IOException {
        String prop = SftpFileStorage.SYS_PROP_DEFAULT_STRICT_HOST_KEY_CHECKING;
        String old = System.getProperty(prop);
        System.setProperty(prop, "true");
        try {
            SftpFileStorage storage = new SftpFileStorage(config());
            try {
                assertTrue("系统属性 true + 未显式配置应启用 strictHostKeyChecking", isStrict(storage));
            } finally {
                storage.close();
            }
        } finally {
            if (old == null) {
                System.clearProperty(prop);
            } else {
                System.setProperty(prop, old);
            }
        }
    }

    /**
     * 通过反射读取 private strictHostKeyChecking 字段（测试专用）。
     */
    private static boolean isStrict(SftpFileStorage storage) {
        try {
            java.lang.reflect.Field f = SftpFileStorage.class.getDeclaredField("strictHostKeyChecking");
            f.setAccessible(true);
            return f.getBoolean(storage);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
