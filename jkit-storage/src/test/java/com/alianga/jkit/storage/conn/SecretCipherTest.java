package com.alianga.jkit.storage.conn;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.Base64;
import java.util.EnumSet;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * 密钥加密器测试：往返加解密、空值语义、IV 随机、解不开的行为、损坏密钥文件的隔离重建。
 *
 * @author 郑明亮
 */
public class SecretCipherTest {

    @Test
    public void roundTrip() throws Exception {
        Path dir = Files.createTempDirectory("jkit-secret-test");
        try {
            SecretCipher cipher = SecretCipher.load(dir);
            String encrypted = cipher.encrypt("P@ssw0rd-中文");
            assertTrue(encrypted.startsWith(SecretCipher.PREFIX));
            assertFalse("密文里不能出现明文", encrypted.contains("P@ssw0rd"));
            assertTrue(cipher.isEncrypted(encrypted));
            assertEquals("P@ssw0rd-中文", cipher.decrypt(encrypted));
        } finally {
            deleteRecursively(dir);
        }
    }

    @Test
    public void emptyValueRoundTripsAsEmpty() throws Exception {
        Path dir = Files.createTempDirectory("jkit-secret-test");
        try {
            SecretCipher cipher = SecretCipher.load(dir);
            assertEquals("", cipher.encrypt(""));
            assertNull("null 原样返回，不加密也不报错", cipher.encrypt(null));
            assertEquals("", cipher.decrypt(""));
            assertEquals("plain", cipher.decrypt("plain"));
            assertFalse(cipher.isEncrypted(""));
        } finally {
            deleteRecursively(dir);
        }
    }

    @Test
    public void samePlaintextYieldsDifferentCiphertext() throws Exception {
        Path dir = Files.createTempDirectory("jkit-secret-test");
        try {
            SecretCipher cipher = SecretCipher.load(dir);
            assertNotEquals("每条 IV 应独立随机", cipher.encrypt("same"), cipher.encrypt("same"));
        } finally {
            deleteRecursively(dir);
        }
    }

    @Test
    public void keyIsStableAcrossLoads() throws Exception {
        Path dir = Files.createTempDirectory("jkit-secret-test");
        try {
            String encrypted = SecretCipher.load(dir).encrypt("v");
            assertEquals("同一目录应复用同一把密钥", "v", SecretCipher.load(dir).decrypt(encrypted));
        } finally {
            deleteRecursively(dir);
        }
    }

    @Test
    public void undecryptableCiphertextYieldsEmptyNotGarbage() throws Exception {
        Path dir = Files.createTempDirectory("jkit-secret-test");
        try {
            String encrypted = SecretCipher.load(dir).encrypt("v");
            Files.deleteIfExists(dir.resolve(".secret-key"));
            // 密钥换了：解不开要返回空串，绝不能抛异常或返回乱码
            assertEquals("", SecretCipher.load(dir).decrypt(encrypted));
            assertTrue("密文前缀仍应可识别", SecretCipher.load(dir).isEncrypted(encrypted));
        } finally {
            deleteRecursively(dir);
        }
    }

    @Test
    public void corruptKeyFileIsQuarantinedAndRebuilt() throws Exception {
        Path dir = Files.createTempDirectory("jkit-secret-test");
        try {
            // 长度非法（5 字节）的密钥：SecretKeySpec 会收下它，要到 encrypt 才炸
            Files.write(dir.resolve(".secret-key"),
                    Base64.getEncoder().encodeToString("short".getBytes(StandardCharsets.UTF_8))
                            .getBytes(StandardCharsets.UTF_8));
            SecretCipher cipher = SecretCipher.load(dir);
            // 不抛异常，且新密钥可用
            assertEquals("ok", cipher.decrypt(cipher.encrypt("ok")));
            long backups = Files.list(dir)
                    .filter(path -> path.getFileName().toString().startsWith(".secret-key.corrupt-"))
                    .count();
            assertEquals("损坏的密钥文件应改名保留而非删除", 1, backups);
        } finally {
            deleteRecursively(dir);
        }
    }

    /**
     * 「读不出来」≠「损坏」：瞬时 IO 失败绝不能换钥匙。
     *
     * <p>历史 bug：IOException 与非法 Base64 被同等按损坏处理——杀毒软件短暂占用
     * 一次密钥文件，就永久生成新密钥，所有已存密码静默解不开。用目录占据
     * .secret-key（readString 对目录必抛 IOException）模拟持续读失败。</p>
     */
    @Test
    public void unreadableKeyFileFailsWithoutRotatingKey() throws Exception {
        Path dir = Files.createTempDirectory("jkit-secret-test");
        try {
            Path keyFile = dir.resolve(".secret-key");
            Files.deleteIfExists(keyFile);
            Files.createDirectory(keyFile);   // 目录：存在但读不出内容

            try {
                SecretCipher.load(dir);
                fail("读不出来必须抛异常，而不是静默换新密钥");
            } catch (IllegalStateException expected) {
                assertTrue("异常要说明是暂时读不出来（可重试）：" + expected.getMessage(),
                        expected.getMessage().contains("读不出来"));
            }
            long backups = Files.list(dir)
                    .filter(path -> path.getFileName().toString().startsWith(".secret-key.corrupt-"))
                    .count();
            assertEquals("读失败绝不能触发隔离 / 换钥匙", 0, backups);
            assertTrue("原路径保持原样", Files.isDirectory(keyFile));
        } finally {
            deleteRecursively(dir);
        }
    }

    /** 新生成的密钥文件必须创建即 0600，且不残留临时文件。 */
    @Test
    public void generatedKeyFileIsOwnerOnly() throws Exception {
        Path dir = Files.createTempDirectory("jkit-secret-test");
        try {
            SecretCipher.load(dir);
            Set<PosixFilePermission> perms;
            try {
                perms = Files.getPosixFilePermissions(dir.resolve(".secret-key"));
            } catch (UnsupportedOperationException noPosix) {
                return;   // Windows 等无 POSIX 权限位
            }
            assertEquals("密钥文件是 AES 主密钥，不能留 0644 全局可读窗口",
                    EnumSet.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE), perms);
            long tmps = Files.list(dir)
                    .filter(path -> path.getFileName().toString().contains(".tmp-"))
                    .count();
            assertEquals("原子写的临时文件不得残留", 0, tmps);
        } finally {
            deleteRecursively(dir);
        }
    }

    private static void deleteRecursively(Path root) throws Exception {
        if (!Files.exists(root)) {
            return;
        }
        try (java.util.stream.Stream<Path> walk = Files.walk(root)) {
            for (Path path : walk.sorted(java.util.Comparator.reverseOrder()).toArray(Path[]::new)) {
                Files.deleteIfExists(path);
            }
        }
    }
}
