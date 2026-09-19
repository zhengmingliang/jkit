package com.alianga.jkit.crypto;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

import java.security.Key;
import java.security.SecureRandom;

/**
 * AES 加密工具类，同时支持 CBC 和 ECB 两种工作模式。
 *
 * <p>{@link #AESCrypt(byte[], byte[])} 构造器提供 <b>CBC/PKCS5Padding</b> 模式的实例化用法，
 * 适合需要 IV 的场景（继承自 {@link CipherCrpyt}，提供流式加密接口）。
 *
 * <p>静态工具方法 {@link #encrypt(byte[], byte[])}/{@link #decrypt(byte[], byte[])} 提供
 * <b>ECB/PKCS5Padding</b> 模式的便捷调用，无需提供 IV，适合简单加密场景。
 *
 * <p><b>GCM 模式（推荐）</b>：{@link #encryptGcm(byte[], byte[])} / {@link #decryptGcm(byte[], byte[])}
     * 提供带完整性校验的认证加密（AEAD），每次随机 12 字节 IV，密文自带 IV 前缀，
     * 可直接存库或传输。ECB / CBC 只提供机密性、不防篡改，新代码优先用 GCM。
     *
     * <p>密钥长度支持 128 / 192 / 256 位。
     *
     * @author 郑明亮
     * @version 1.0
     * @see CipherCrpyt
     */
public class AESCrypt extends CipherCrpyt {
    /**
     * AES 密钥算法名称。
     */
    public static final String KEY_ALGORITHM = "AES";

    /**
     * ECB 模式加密/解密算法（无需 IV）。
     */
    public static final String ECB_CIPHER_ALGORITHM = "AES/ECB/PKCS5Padding";

    /**
     * GCM 模式加密/解密算法（AEAD，推荐）。
     *
     * @since 2.0.2
     */
    public static final String GCM_CIPHER_ALGORITHM = "AES/GCM/NoPadding";

    /**
     * GCM 模式推荐的 IV 长度（12 字节 / 96 位）。
     *
     * @since 2.0.2
     */
    public static final int GCM_IV_LENGTH = 12;

    /**
     * GCM 认证标签长度（位），128 位是 JDK 支持的最强档。
     *
     * @since 2.0.2
     */
    public static final int GCM_TAG_BITS = 128;

    /**
     * GCM 随机 IV 生成器。
     */
    private static final SecureRandom GCM_RANDOM = new SecureRandom();

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
     * 构造 AES CBC 加密器。
     *
     * @param key 16 字节(128位)、24 字节(192位)或 32 字节(256位)的密钥
     * @param iv 16 字节的初始化向量
     */
    public AESCrypt(final byte[] key, final byte[] iv) {
        if (key == null || iv == null) {
            throw new RuntimeException("Need a key and an initialization vector to construct an AESCrypt object!");
        }

        final int keyLength = key.length;
        if (keyLength != 16 && keyLength != 24 && keyLength != 32) {
            throw new RuntimeException(
                    "The AES key must be 16 bytes(128 bits), 24 bytes(192 bits) or 32 bytes(256 bits)!");
        }

        final int ivLength = iv.length;
        if (ivLength != 16) {
            throw new RuntimeException("The IV must be 16 bytes(128 bits)!");
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
            cipher = Cipher.getInstance("AES/CBC/PKCS5Padding");
        } catch (final Exception ex) {
            throw new RuntimeException(ex.getMessage());
        }
    }

    // -----ECB 模式静态工具方法-----

    /**
     * 将密钥字节转换为 {@link Key} 对象。
     *
     * @param key 二进制密钥
     * @return 密钥对象
     */
    private static Key toKey(final byte[] key) {
        return new SecretKeySpec(key, KEY_ALGORITHM);
    }

    /**
     * 使用 ECB 模式加密数据（无需 IV）。
     *
     * @param data 待加密数据
     * @param key 密钥（16/24/32 字节）
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
            throw new RuntimeException("AES ECB encrypt failed: " + e.getMessage(), e);
        }
    }

    /**
     * 使用 ECB 模式解密数据（无需 IV）。
     *
     * @param data 待解密数据
     * @param key 密钥（16/24/32 字节）
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
            throw new RuntimeException("AES ECB decrypt failed: " + e.getMessage(), e);
        }
    }

    // -----GCM 模式（AEAD，推荐）-----

    /**
     * 生成 GCM 模式用的随机 IV（12 字节）。
     *
     * @return 随机 IV
     * @since 2.0.2
     */
    public static byte[] generateIv() {
        final byte[] iv = new byte[GCM_IV_LENGTH];
        GCM_RANDOM.nextBytes(iv);
        return iv;
    }

    /**
     * 使用 GCM 模式加密，随机生成 IV 并拼在密文前面（{@code IV || ciphertext}）。
     *
     * <p>密文自带 IV，解密时用 {@link #decryptGcm(byte[], byte[])} 即可，调用方无需保存 IV。
     *
     * @param data 待加密数据
     * @param key 密钥（16/24/32 字节）
     * @return {@code IV || ciphertext} 形式的密文
     * @throws RuntimeException 如果加密失败
     * @since 2.0.2
     */
    public static byte[] encryptGcm(final byte[] data, final byte[] key) {
        final byte[] iv = generateIv();
        final byte[] body = encryptGcm(data, key, iv, null);
        if (body == null) {
            return null;
        }
        final byte[] out = new byte[iv.length + body.length];
        System.arraycopy(iv, 0, out, 0, iv.length);
        System.arraycopy(body, 0, out, iv.length, body.length);
        return out;
    }

    /**
     * 使用 GCM 模式加密（自定义 IV 与附加认证数据 AAD）。
     *
     * <p>AAD 不参与加密但参与完整性校验，适合把「不该加密但要防篡改」的字段
     * （如用户 ID、业务单号）绑进密文。
     *
     * @param data 待加密数据
     * @param key 密钥（16/24/32 字节）
     * @param iv 12 字节初始化向量，同一密钥下不可重复
     * @param aad 附加认证数据，可为 {@code null}
     * @return 密文（含 16 字节认证标签）
     * @throws RuntimeException 如果加密失败
     * @since 2.0.2
     */
    public static byte[] encryptGcm(final byte[] data, final byte[] key, final byte[] iv, final byte[] aad) {
        if (data == null) {
            return null;
        }
        checkKey(key);
        checkIv(iv);
        try {
            final Cipher cipher = Cipher.getInstance(GCM_CIPHER_ALGORITHM);
            cipher.init(Cipher.ENCRYPT_MODE, toKey(key), new GCMParameterSpec(GCM_TAG_BITS, iv));
            if (aad != null && aad.length > 0) {
                cipher.updateAAD(aad);
            }
            return cipher.doFinal(data);
        } catch (final Exception e) {
            throw new RuntimeException("AES GCM encrypt failed: " + e.getMessage(), e);
        }
    }

    /**
     * 解密 {@link #encryptGcm(byte[], byte[])} 产出的 {@code IV || ciphertext} 密文。
     *
     * @param data 密文，前 12 字节为 IV
     * @param key 密钥
     * @return 明文
     * @throws RuntimeException 如果密文被篡改、IV 长度不合法或密钥错误
     * @since 2.0.2
     */
    public static byte[] decryptGcm(final byte[] data, final byte[] key) {
        if (data == null) {
            return null;
        }
        if (data.length <= GCM_IV_LENGTH) {
            throw new IllegalArgumentException("GCM ciphertext too short, expect IV + body");
        }
        final byte[] iv = new byte[GCM_IV_LENGTH];
        final byte[] body = new byte[data.length - GCM_IV_LENGTH];
        System.arraycopy(data, 0, iv, 0, GCM_IV_LENGTH);
        System.arraycopy(data, GCM_IV_LENGTH, body, 0, body.length);
        return decryptGcm(body, key, iv, null);
    }

    /**
     * 使用 GCM 模式解密（自定义 IV 与 AAD），AAD 必须与加密时完全一致。
     *
     * @param data 密文（含认证标签）
     * @param key 密钥
     * @param iv 12 字节初始化向量
     * @param aad 附加认证数据，可为 {@code null}
     * @return 明文
     * @throws RuntimeException 如果认证失败（密文或 AAD 被改）或参数非法
     * @since 2.0.2
     */
    public static byte[] decryptGcm(final byte[] data, final byte[] key, final byte[] iv, final byte[] aad) {
        if (data == null) {
            return null;
        }
        checkKey(key);
        checkIv(iv);
        try {
            final Cipher cipher = Cipher.getInstance(GCM_CIPHER_ALGORITHM);
            cipher.init(Cipher.DECRYPT_MODE, toKey(key), new GCMParameterSpec(GCM_TAG_BITS, iv));
            if (aad != null && aad.length > 0) {
                cipher.updateAAD(aad);
            }
            return cipher.doFinal(data);
        } catch (final Exception e) {
            throw new RuntimeException("AES GCM decrypt failed: " + e.getMessage(), e);
        }
    }

    /**
     * 校验 GCM 密钥长度。
     *
     * @param key 密钥
     */
    private static void checkKey(final byte[] key) {
        if (key == null || key.length != 16 && key.length != 24 && key.length != 32) {
            throw new IllegalArgumentException("The AES key must be 16/24/32 bytes (128/192/256 bits)!");
        }
    }

    /**
     * 校验 GCM 的 IV 长度。
     *
     * @param iv 初始化向量
     */
    private static void checkIv(final byte[] iv) {
        if (iv == null || iv.length != GCM_IV_LENGTH) {
            throw new IllegalArgumentException("The GCM IV must be " + GCM_IV_LENGTH + " bytes (96 bits)!");
        }
    }

    /**
     * 生成随机 AES 密钥（默认 256 位）。
     *
     * @return 二进制密钥
     * @throws RuntimeException 如果密钥生成失败
     */
    public static byte[] generateKey() {
        return generateKey(256);
    }

    /**
     * 生成随机 AES 密钥。
     *
     * @param length 密钥长度，支持 128、192 或 256
     * @return 二进制密钥
     * @throws RuntimeException 如果密钥生成失败
     */
    public static byte[] generateKey(final int length) {
        try {
            final KeyGenerator kg = KeyGenerator.getInstance(KEY_ALGORITHM);
            kg.init(length);
            return kg.generateKey().getEncoded();
        } catch (final Exception e) {
            throw new RuntimeException("Failed to generate AES key: " + e.getMessage(), e);
        }
    }
}
