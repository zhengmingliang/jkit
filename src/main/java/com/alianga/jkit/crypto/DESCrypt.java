package com.alianga.jkit.crypto;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.DESKeySpec;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

import java.security.InvalidKeyException;
import java.security.Key;
import java.security.NoSuchAlgorithmException;
import java.security.spec.InvalidKeySpecException;

/**
 * DES 加密工具类，同时支持 CBC 和 ECB 两种工作模式。
 *
 * <p>{@link #DESCrypt(byte[], byte[])} 构造器提供 <b>CBC/PKCS5Padding</b> 模式的实例化用法，
 * 适合需要 IV 的场景（继承自 {@link CipherCrpyt}，提供流式加密接口）。
 *
 * <p>静态工具方法 {@link #encrypt(byte[], byte[])}/{@link #decrypt(byte[], byte[])} 提供
 * <b>ECB/PKCS5Padding</b> 模式的便捷调用，无需提供 IV，适合简单加密场景。
 *
 * <p>密钥长度固定为 8 字节（64 位，其中 56 位有效）。
 *
 * @author 郑明亮
 * @version 1.0
 * @see CipherCrpyt
 */
public class DESCrypt extends CipherCrpyt {
    /**
     * DES 密钥算法名称。
     */
    public static final String KEY_ALGORITHM = "DES";

    /**
     * ECB 模式加密/解密算法（无需 IV）。
     */
    public static final String ECB_CIPHER_ALGORITHM = "DES/ECB/PKCS5Padding";

    /**
     * 用于 ECB 模式的线程安全 Cipher（encrypt 和 decrypt 各一个）。
     */
    private static final ThreadLocal<Cipher> ECB_ENCRYPT_CIPHER = ThreadLocal.withInitial(() -> {
        try {
            return Cipher.getInstance(ECB_CIPHER_ALGORITHM);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    });

    private static final ThreadLocal<Cipher> ECB_DECRYPT_CIPHER = ThreadLocal.withInitial(() -> {
        try {
            return Cipher.getInstance(ECB_CIPHER_ALGORITHM);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    });

    // -----建构子-----

    /**
     * 构造 DES CBC 加密器。
     *
     * @param key 8 字节(64位)的密钥
     * @param iv 8 字节的初始化向量
     */
    public DESCrypt(final byte[] key, final byte[] iv) {
        if (key == null || iv == null) {
            throw new RuntimeException("Need a key and an initialization vector to construct an DESCrypt object!");
        }

        final int keyLength = key.length;
        if (keyLength != 8) {
            throw new RuntimeException("The DES key must be 8 bytes(64 bits)!");
        }

        final int ivLength = iv.length;
        if (ivLength != 8) {
            throw new RuntimeException("The IV must be 8 bytes(64 bits)!");
        }

        this.key = new SecretKeySpec(key, KEY_ALGORITHM);
        this.iv = new IvParameterSpec(iv);

        init();
    }

    // -----CBC 模式（继承自 CipherCrpyt）-----

    /**
     * 初始化 CBC 模式 Cipher。
     */
    private void init() {
        try {
            cipher = Cipher.getInstance("DES/CBC/PKCS5Padding");
        } catch (final Exception ex) {
            throw new RuntimeException(ex.getMessage());
        }
    }

    // -----ECB 模式静态工具方法-----

    /**
     * 将 DES 密钥字节转换为 {@link Key} 对象。
     * 使用 {@link DESKeySpec} + {@link SecretKeyFactory} 转换，符合 Java 规范。
     *
     * @param key 8 字节二进制密钥
     * @return 密钥对象
     * @throws RuntimeException 如果密钥转换失败
     */
    private static Key toKey(final byte[] key) {
        try {
            final DESKeySpec dks = new DESKeySpec(key);
            final SecretKeyFactory keyFactory = SecretKeyFactory.getInstance(KEY_ALGORITHM);
            return keyFactory.generateSecret(dks);
        } catch (final InvalidKeyException | InvalidKeySpecException | NoSuchAlgorithmException e) {
            throw new RuntimeException("Failed to convert DES key: " + e.getMessage(), e);
        }
    }

    /**
     * 使用 ECB 模式加密数据（无需 IV）。
     *
     * @param data 待加密数据
     * @param key 密钥（8 字节）
     * @return 加密后的字节数组
     * @throws RuntimeException 如果加密失败
     */
    public static byte[] encrypt(final byte[] data, final byte[] key) {
        if (data == null) {
            return null;
        }
        final Cipher cipher = ECB_ENCRYPT_CIPHER.get();
        try {
            cipher.init(Cipher.ENCRYPT_MODE, toKey(key));
            return cipher.doFinal(data);
        } catch (final Exception e) {
            throw new RuntimeException("DES ECB encrypt failed: " + e.getMessage(), e);
        }
    }

    /**
     * 使用 ECB 模式解密数据（无需 IV）。
     *
     * @param data 待解密数据
     * @param key 密钥（8 字节）
     * @return 解密后的字节数组
     * @throws RuntimeException 如果解密失败
     */
    public static byte[] decrypt(final byte[] data, final byte[] key) {
        if (data == null) {
            return null;
        }
        final Cipher cipher = ECB_DECRYPT_CIPHER.get();
        try {
            cipher.init(Cipher.DECRYPT_MODE, toKey(key));
            return cipher.doFinal(data);
        } catch (final Exception e) {
            throw new RuntimeException("DES ECB decrypt failed: " + e.getMessage(), e);
        }
    }

    /**
     * 生成随机 DES 密钥（默认 56 位有效密钥，8 字节）。
     *
     * @return 二进制密钥
     * @throws RuntimeException 如果密钥生成失败
     */
    public static byte[] generateKey() {
        return generateKey(56);
    }

    /**
     * 生成随机 DES 密钥。
     * <p>Java 7 默认只支持 56 位；若使用 Bouncy Castle 可提供 64 位。
     *
     * @param length 密钥位数，通常为 56（Java 默认）或 64（需 BC 提供程序）
     * @return 二进制密钥
     * @throws RuntimeException 如果密钥生成失败
     */
    public static byte[] generateKey(final int length) {
        try {
            final KeyGenerator kg = KeyGenerator.getInstance(KEY_ALGORITHM);
            kg.init(length);
            final SecretKey secretKey = kg.generateKey();
            return secretKey.getEncoded();
        } catch (final Exception e) {
            throw new RuntimeException("Failed to generate DES key: " + e.getMessage(), e);
        }
    }
}
