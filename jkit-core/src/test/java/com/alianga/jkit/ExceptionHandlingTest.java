package com.alianga.jkit;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.PrivateKey;
import java.security.PublicKey;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * 覆盖 P0-4：FileUtils.createFile / makeDir / writeFile / nioTransferCopyFile 以及
 * EncryptUtils.encrypt / decrypt / getPublicKey / getPrivateKey 在异常路径下的行为。
 *
 * 修复的语义是：失败不再 printStackTrace 静默；但仍然保留原有的返回值契约
 * （"false / null / 空串"），保证向后兼容。
 */
public class ExceptionHandlingTest {

    private Path tempDir;

    @Before
    public void setUp() throws IOException {
        tempDir = Files.createTempDirectory("jkit-ex-");
    }

    @After
    public void tearDown() throws IOException {
        deleteRecursively(tempDir);
    }

    private static void deleteRecursively(Path p) throws IOException {
        if (p == null || !Files.exists(p)) return;
        Files.walk(p).sorted((a, b) -> b.compareTo(a)).forEach(x -> {
            try {
                Files.deleteIfExists(x);
            } catch (IOException ignore) { }
        });
    }

    // ============ FileUtils ============

    /**
     * createFile 在目标已存在时返回 false；目录会自动创建。
     */
    @Test
    public void createFileReturnsTrueForNewFile() throws Exception {
        Path target = tempDir.resolve("sub").resolve("a.txt");
        boolean ok = FileUtils.createFile(target.toFile());
        assertTrue("expected true for newly created file", ok);
        assertTrue(Files.exists(target));
    }

    /**
     * createFile 对已存在文件返回 false（File#createNewFile 的语义）。
     */
    @Test
    public void createFileReturnsFalseForExisting() throws Exception {
        Path target = tempDir.resolve("exists.txt");
        Files.write(target, "x".getBytes(StandardCharsets.UTF_8));
        boolean ok = FileUtils.createFile(target.toFile());
        assertFalse("expected false when file already exists", ok);
    }

    /**
     * createFile 在父目录为 null（比如根路径）或磁盘错误时不会吞掉异常，
     * 而是返回 false（保持原契约）但不抛未声明的异常。
     */
    @Test
    public void createFileDoesNotThrowUndeclaredOnBadParent() {
        // 在 / 下创建一个目录会导致 SecurityException 或类似；测试方法本身不应让
        // FileUtils.createFile 抛 IOException（已经声明 throws IOException）
        // —— 更重要的是验证没有 printStackTrace 副作用，这里只验证它返回。
        File target = new File("/nonexistent-root-xyz-abc/file.txt");
        try {
            boolean ok = FileUtils.createFile(target);
            // 不应因未声明异常而抛出
            // ok 可能 true（容器内以 root 运行可以创建）也可能 false，都接受。
        } catch (SecurityException | IOException e) {
            // 允许的已声明异常
        }
    }

    /**
     * makeDir 递归创建多层目录。
     */
    @Test
    public void makeDirCreatesNestedDirs() {
        File dir = tempDir.resolve("a/b/c/d").toFile();
        FileUtils.makeDir(dir);
        assertTrue("expected dir created: " + dir, dir.isDirectory());
    }

    /**
     * writeFile 覆盖写；writeFile append=true 追加。
     */
    @Test
    public void writeFileOverwritesAndAppends() {
        Path p = tempDir.resolve("w.txt");
        FileUtils.writeFile(p.toString(), "AAA", false);
        assertEquals("AAA", readAll(p));

        FileUtils.writeFile(p.toString(), "BBB", true);
        assertEquals("AAABBB", readAll(p));

        FileUtils.writeFile(p.toString(), "CCC", false);
        assertEquals("CCC", readAll(p));
    }

    private static String readAll(Path p) {
        try {
            return new String(Files.readAllBytes(p), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * nioTransferCopyFile 成功复制。
     */
    @Test
    public void nioTransferCopyFileCopiesContent() throws Exception {
        Path src = tempDir.resolve("src.txt");
        Files.write(src, "hello-world".getBytes(StandardCharsets.UTF_8));
        Path dst = tempDir.resolve("dst.txt");
        FileUtils.nioTransferCopyFile(src.toFile(), dst.toFile());
        assertEquals("hello-world", readAll(dst));
    }

    /**
     * nioTransferCopyFile 在源不存在时静默失败（不抛），但目标文件不会被创建。
     * 修复后不再 printStackTrace；行为兼容（返回 void，调用方无法感知失败，这是原有契约）。
     */
    @Test
    public void nioTransferCopyFileQuietlyFailsOnMissingSource() {
        Path src = tempDir.resolve("missing.txt");
        Path dst = tempDir.resolve("dst.txt");
        FileUtils.nioTransferCopyFile(src.toFile(), dst.toFile());
        assertFalse("dst should not be created when source missing", Files.exists(dst));
    }

    // ============ EncryptUtils ============

    /**
     * 原实现契约精确还原：bad base64 会触发 Base64Utils.decode 抛 IllegalArgumentException
     * （unchecked），which 不被原代码 catch(NoSuchAlgorithmException|InvalidKeySpecException)
     * 捕获，因此直接抛给调用方。修复没有改变该契约。
     */
    @Test(expected = IllegalArgumentException.class)
    public void getPublicKeyThrowsForBadBase64() {
        EncryptUtils.RSA.getPublicKey("not-a-valid-base64!!");
    }

    @Test(expected = IllegalArgumentException.class)
    public void getPrivateKeyThrowsForBadBase64() {
        EncryptUtils.RSA.getPrivateKey("not-a-valid-base64!!");
    }

    /**
     * encrypt/decrypt 的 catch(Exception) 会捕获 IllegalArgumentException，bad base64 时
     * 返回空串（修复后仍保留该契约），不再 printStackTrace 而是 log.error。
     */
    @Test
    public void encryptReturnsEmptyStringOnBadKey() {
        String result = EncryptUtils.RSA.encrypt("hello", "bad-key");
        assertEquals("", result);
    }

    @Test
    public void decryptReturnsEmptyStringOnBadKey() {
        String result = EncryptUtils.RSA.decrypt("cipher-text", "bad-key");
        assertEquals("", result);
    }

    /**
     * 成功路径的健全性：生成一对真实密钥，加密 → 解密，验证往返一致。
     * （这同时验证我们对异常路径的修改没破坏正常路径。）
     */
    @Test
    public void rsaRoundTrip() throws Exception {
        java.security.KeyPairGenerator kpg =
                java.security.KeyPairGenerator.getInstance("RSA");
        kpg.initialize(2048);
        java.security.KeyPair kp = kpg.generateKeyPair();

        String pubB64 = java.util.Base64.getEncoder()
                .encodeToString(kp.getPublic().getEncoded());
        String priB64 = java.util.Base64.getEncoder()
                .encodeToString(kp.getPrivate().getEncoded());

        PublicKey pub = EncryptUtils.RSA.getPublicKey(pubB64);
        PrivateKey pri = EncryptUtils.RSA.getPrivateKey(priB64);
        assertNotNull(pub);
        assertNotNull(pri);

        String plain = "jkit-p0-4-exception-handling";
        // 注意：RSA.encrypt(String,String) 走 new String(cipherBytes) 用平台默认字符集承载
        // 二进制密文，是原 API 的固有缺陷（会损坏密文），不属于本次回归范围。
        // 这里用安全的 byte[] 重载 + Base64 中转，只验证异常路径改动没有破坏成功路径。
        byte[] cipherBytes = EncryptUtils.RSA.encrypt(plain.getBytes(StandardCharsets.UTF_8), pub);
        String cipherB64 = java.util.Base64.getEncoder().encodeToString(cipherBytes);
        byte[] plainBytes = EncryptUtils.RSA.decrypt(
                java.util.Base64.getDecoder().decode(cipherB64), pri);
        assertEquals("round-trip must match", plain,
                new String(plainBytes, StandardCharsets.UTF_8));
    }
}
