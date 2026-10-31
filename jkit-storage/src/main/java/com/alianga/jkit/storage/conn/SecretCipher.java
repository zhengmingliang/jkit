package com.alianga.jkit.storage.conn;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.EnumSet;
import java.util.Set;

/**
 * 连接密钥字段加密器：AES-256-GCM + 安装级随机密钥。
 *
 * <p>密钥保存在数据目录 {@code .secret-key}（首次使用时生成，仅所有者可读写）；
 * 密文形如 {@code enc:v1:Base64(IV || 密文)}，每条 IV 独立随机。
 * 密钥文件丢失后已加密的值无法解密——{@link #decrypt} 返回空串，
 * 用户在连接编辑里重新录入密码即可。</p>
 *
 * <p><b>注意</b>：{@link #decrypt} 返回空串只代表"这次解不开"，不代表
 * "原值是空"。调用方（{@link ConnectionStore}）必须负责保留原始密文，
 * 否则一次保存就会把解不开的密文覆盖成空串、永久丢失。</p>
 *
 * @author 郑明亮
 */
public final class SecretCipher {
    /** 加密值前缀：加载时据此识别（v1 = AES-GCM）。 */
    static final String PREFIX = "enc:v1:";
    private static final int IV_BYTES = 12;
    private static final int TAG_BITS = 128;
    /** AES 合法密钥长度（字节）：128 / 192 / 256 位。 */
    private static final int[] KEY_LENGTHS = {16, 24, 32};
    /** IO 重试前的等待（杀毒软件 / NFS 的占用通常是毫秒级）。 */
    private static final long RETRY_WAIT_MILLIS = 60L;

    private final SecretKey key;
    private final SecureRandom random = new SecureRandom();

    private SecretCipher(SecretKey key) {
        this.key = key;
    }

    /**
     * 从数据目录加载密钥；不存在则生成并落盘（0600）。
     *
     * <p>两类失败必须区分对待：</p>
     * <ul>
     *   <li><b>内容确实损坏</b>（不是合法 Base64、密钥长度非法）：改名隔离后重建
     *       一把新密钥。旧密文暂时解不开，但 {@link ConnectionStore} 会原样保留
     *       它们不会覆盖成空，用户找回旧密钥文件后仍有恢复余地。</li>
     *   <li><b>暂时读不出来</b>（杀毒软件占用、NFS 抖动、权限瞬断）：重试一次，
     *       仍失败就抛异常让本次加载失败。<b>绝不能按损坏处理去换钥匙</b>——
     *       一次瞬时读失败就永久换密钥，等于所有已存密码静默失效，而且界面
     *       不会有任何提示。宁可这次打不开让用户重试。</li>
     * </ul>
     *
     * @param dir 数据目录
     * @return 加密器
     * @throws IllegalStateException 密钥文件暂时读不出来，或无法生成新密钥
     */
    public static SecretCipher load(Path dir) {
        Path keyFile = dir.resolve(".secret-key");
        try {
            if (Files.exists(keyFile)) {
                // 读失败直接抛（不换钥匙）；读到了才判断内容是否损坏
                String text = readKeyFile(keyFile);
                byte[] raw = decodeKey(text);
                if (raw != null && isLegalKeyLength(raw.length)) {
                    return new SecretCipher(new SecretKeySpec(raw, "AES"));
                }
                // SecretKeySpec 会收下长度非法的密钥，要等到下一次 encrypt 才炸，
                // 那时界面已经在改数据了——不如现在就换一把
                quarantine(keyFile);
            }
            return new SecretCipher(generateAndStore(keyFile));
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("密钥文件初始化失败: " + keyFile, e);
        }
    }

    /**
     * 读密钥文件：瞬时 IO 失败重试一次，仍失败抛可读异常（原文件保持不动）。
     *
     * @param keyFile 密钥文件
     * @return 文件内容
     * @throws IllegalStateException 读取失败（不换密钥）
     */
    private static String readKeyFile(Path keyFile) throws IOException {
        try {
            return new String(Files.readAllBytes(keyFile), StandardCharsets.UTF_8);
        } catch (IOException first) {
            try {
                Thread.sleep(RETRY_WAIT_MILLIS);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("读取密钥文件被中断: " + keyFile, first);
            }
            try {
                return new String(Files.readAllBytes(keyFile), StandardCharsets.UTF_8);
            } catch (IOException retry) {
                throw new IllegalStateException("密钥文件暂时读不出来（可能被其它程序占用）："
                        + keyFile + "。已保留原文件、未更换密钥，请稍后重试", retry);
            }
        }
    }

    /** 解 Base64；内容非法返回 null（交由调用方按损坏处理）。 */
    private static byte[] decodeKey(String text) {
        try {
            return Base64.getDecoder().decode(text.trim());
        } catch (IllegalArgumentException notBase64) {
            return null;
        }
    }

    /** 是否为 AES 合法密钥长度。 */
    private static boolean isLegalKeyLength(int bytes) {
        for (int length : KEY_LENGTHS) {
            if (bytes == length) {
                return true;
            }
        }
        return false;
    }

    /**
     * 生成新密钥并落盘：创建即 0600、写入走临时文件 + 原子 move。
     *
     * <p>「先写后 chmod」会在 umask 022 下留下一个 0644 的窗口——那是 AES 主密钥，
     * 全局可读等于没加密。非原子写则可能留下截断的密钥文件，下次启动被当成损坏
     * 隔离掉。</p>
     *
     * @param keyFile 目标文件
     * @return 新密钥
     * @throws Exception 生成或写入失败
     */
    private static SecretKey generateAndStore(Path keyFile) throws Exception {
        KeyGenerator generator = KeyGenerator.getInstance("AES");
        generator.init(256);
        SecretKey key = generator.generateKey();
        String text = Base64.getEncoder().encodeToString(key.getEncoded());
        Path parent = keyFile.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Path tmp = keyFile.resolveSibling(keyFile.getFileName() + ".tmp-"
                + java.util.UUID.randomUUID() + "-" + System.nanoTime());
        try {
            createOwnerOnly(tmp);
            Files.write(tmp, text.getBytes(StandardCharsets.UTF_8));
            try {
                Files.move(tmp, keyFile, StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException noAtomicMove) {
                Files.move(tmp, keyFile, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (Exception failure) {
            Files.deleteIfExists(tmp);
            throw failure;
        }
        restrictToOwner(keyFile);
        return key;
    }

    /** 以仅属主可读写创建文件（平台无 POSIX 权限位时退化为普通创建）。 */
    private static void createOwnerOnly(Path file) throws IOException {
        try {
            Files.createFile(file, PosixFilePermissions.asFileAttribute(ownerOnly()));
        } catch (UnsupportedOperationException noPosix) {
            Files.createFile(file);
        }
    }

    private static Set<PosixFilePermission> ownerOnly() {
        return EnumSet.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE);
    }

    /**
     * 判断值是否为本加密器的密文。
     *
     * @param value 原始存储值
     * @return true 是密文
     */
    public boolean isEncrypted(String value) {
        return value != null && value.startsWith(PREFIX);
    }

    /**
     * 加密：{@code enc:v1:Base64(IV || 密文)}；空串原样返回。
     *
     * @param plain 明文
     * @return 密文
     */
    public String encrypt(String plain) {
        if (plain == null || plain.isEmpty()) {
            return plain;
        }
        try {
            byte[] iv = new byte[IV_BYTES];
            random.nextBytes(iv);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
            byte[] encrypted = cipher.doFinal(plain.getBytes(StandardCharsets.UTF_8));
            byte[] combined = new byte[iv.length + encrypted.length];
            System.arraycopy(iv, 0, combined, 0, iv.length);
            System.arraycopy(encrypted, 0, combined, iv.length, encrypted.length);
            return PREFIX + Base64.getEncoder().encodeToString(combined);
        } catch (Exception e) {
            throw new IllegalStateException("加密失败", e);
        }
    }

    /**
     * 解密；密文损坏或密钥不匹配返回空串（提示用户重新录入）。
     *
     * @param stored 存储值
     * @return 明文，解不开返回空串
     */
    public String decrypt(String stored) {
        if (stored == null) {
            return "";
        }
        if (!stored.startsWith(PREFIX)) {
            return stored;
        }
        try {
            byte[] combined = Base64.getDecoder().decode(stored.substring(PREFIX.length()));
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, combined, 0, IV_BYTES));
            byte[] plain = cipher.doFinal(combined, IV_BYTES, combined.length - IV_BYTES);
            return new String(plain, StandardCharsets.UTF_8);
        } catch (Exception e) {
            return "";
        }
    }

    /** 把损坏的密钥文件改名保留（不删，留给用户找回的可能）。 */
    private static void quarantine(Path keyFile) throws IOException {
        Path backup = keyFile.resolveSibling(
                keyFile.getFileName() + ".corrupt-" + System.currentTimeMillis());
        Files.move(keyFile, backup);
    }

    private static void restrictToOwner(Path file) {
        try {
            Files.setPosixFilePermissions(file, ownerOnly());
        } catch (UnsupportedOperationException | IOException ignore) {
            // Windows 等没有 POSIX 权限位
        }
    }
}
