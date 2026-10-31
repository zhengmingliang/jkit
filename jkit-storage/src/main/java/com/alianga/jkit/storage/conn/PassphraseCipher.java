package com.alianga.jkit.storage.conn;

import com.alianga.jkit.json.JSON;

import javax.crypto.Cipher;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 口令加密信封：PBKDF2WithHmacSHA256 派生密钥 + AES-256-GCM，
 * 用于连接导出等「文件离开本机、只有口令保护」的场景。
 *
 * <p>信封格式（JSON）：{@code {"format":"jkit-storage-encrypted","kdf":…,
 * "iterations":210000,"salt":b64,"iv":b64,"data":b64}}；AAD 绑定 format 字段，
 * 防信封字段被挪用。口令错误或数据被篡改时解密失败——GCM 校验标签，
 * 不存在「解出乱码」的中间态。</p>
 *
 * @author 郑明亮
 */
public final class PassphraseCipher {
    /** 信封格式标识（同时作为 GCM AAD 的一部分）。 */
    static final String FORMAT = "jkit-storage-encrypted";
    /** 口令派生算法。 */
    static final String KDF = "PBKDF2WithHmacSHA256";
    /** 派生迭代次数。 */
    static final int ITERATIONS = 210_000;
    private static final int SALT_BYTES = 16;
    private static final int IV_BYTES = 12;
    private static final int TAG_BITS = 128;

    private PassphraseCipher() {
    }

    /**
     * @param text 任意文本
     * @return true 表示是本类的加密信封（导入分流用）
     */
    public static boolean isEnvelope(String text) {
        return text != null && text.contains("\"" + FORMAT + "\"");
    }

    /**
     * 加密为信封 JSON。
     *
     * @param passphrase 用户口令
     * @param plaintext  明文
     * @return 信封 JSON
     */
    public static String encrypt(String passphrase, String plaintext) {
        requirePassphrase(passphrase);
        if (plaintext == null) {
            plaintext = "";
        }
        try {
            SecureRandom random = new SecureRandom();
            byte[] salt = new byte[SALT_BYTES];
            random.nextBytes(salt);
            byte[] iv = new byte[IV_BYTES];
            random.nextBytes(iv);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, deriveKey(passphrase, salt),
                    new GCMParameterSpec(TAG_BITS, iv));
            cipher.updateAAD(FORMAT.getBytes(StandardCharsets.UTF_8));
            byte[] encrypted = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
            Map<String, Object> envelope = new LinkedHashMap<String, Object>();
            envelope.put("format", FORMAT);
            envelope.put("kdf", KDF);
            envelope.put("iterations", ITERATIONS);
            envelope.put("salt", Base64.getEncoder().encodeToString(salt));
            envelope.put("iv", Base64.getEncoder().encodeToString(iv));
            envelope.put("data", Base64.getEncoder().encodeToString(encrypted));
            return JSON.toJsonString(envelope);
        } catch (Exception e) {
            throw new IllegalStateException("口令加密失败: " + e.getMessage(), e);
        }
    }

    /**
     * 解密信封 JSON。
     *
     * @param passphrase 用户口令
     * @param envelope   信封 JSON
     * @return 明文
     * @throws IllegalArgumentException 口令错误、数据被篡改或信封不合法
     */
    public static String decrypt(String passphrase, String envelope) {
        requirePassphrase(passphrase);
        Map<String, Object> map;
        try {
            map = JSON.parseObject(envelope);
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("不是有效的加密信封", e);
        }
        if (map == null || !FORMAT.equals(String.valueOf(map.get("format")))) {
            throw new IllegalArgumentException("不是有效的加密信封");
        }
        byte[] salt = Base64.getDecoder().decode(String.valueOf(map.get("salt")));
        byte[] iv = Base64.getDecoder().decode(String.valueOf(map.get("iv")));
        byte[] data = Base64.getDecoder().decode(String.valueOf(map.get("data")));
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, deriveKey(passphrase, salt),
                    new GCMParameterSpec(TAG_BITS, iv));
            cipher.updateAAD(FORMAT.getBytes(StandardCharsets.UTF_8));
            return new String(cipher.doFinal(data), StandardCharsets.UTF_8);
        } catch (Exception e) {
            // 口令错误与篡改都走 AEADBadTagException：统一口径，不泄露区分信息
            throw new IllegalArgumentException("口令错误或数据已损坏", e);
        }
    }

    private static javax.crypto.SecretKey deriveKey(String passphrase, byte[] salt)
            throws Exception {
        PBEKeySpec spec = new PBEKeySpec(passphrase.toCharArray(), salt, ITERATIONS, 256);
        try {
            byte[] key = SecretKeyFactory.getInstance(KDF).generateSecret(spec).getEncoded();
            return new SecretKeySpec(key, "AES");
        } finally {
            spec.clearPassword();
        }
    }

    private static void requirePassphrase(String passphrase) {
        if (passphrase == null || passphrase.isEmpty()) {
            throw new IllegalArgumentException("口令不能为空");
        }
    }
}
