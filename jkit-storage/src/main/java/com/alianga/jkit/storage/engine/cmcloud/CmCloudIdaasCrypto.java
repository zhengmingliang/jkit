package com.alianga.jkit.storage.engine.cmcloud;

import javax.crypto.Cipher;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.OAEPParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;

import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.MessageDigest;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.MGF1ParameterSpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;

/**
 * IDaaS 凭据加密（纯 Java）：设备指纹、PBKDF2 密钥派生、AES-256-GCM 凭据信封、
 * preAuth 数字信封（RSA-OAEP + AES-GCM）。
 *
 * <p><b>凭据信封</b>（{@code auth_info_ydrz.json} 等本地文件，与 SDK file 后端同格式）：
 * 设备指纹（hostname | username | MAC | 平台 → SHA-256）作口令，
 * PBKDF2-HMAC-SHA256 600k 迭代派生 32 字节 AES-256 密钥，随机 12 字节 IV，
 * GCM tag 128 位；文件为 {@code base64(JSON envelope)} 单行。</p>
 *
 * <p><b>preAuth 数字信封</b>（与 SDK / 服务端逐字节对齐）：随机 16 字节 AES 密钥，
 * GCM IV = 密钥前 12 字节，输出 {@code ct || tag}（不含 IV）作 {@code reqdata}；
 * AES 密钥用 RSA-OAEP（SHA-256 + MGF1-SHA256）加密作 {@code encrypted}。
 * ⚠️ Java SunJCE 的 {@code OAEPWithSHA-256AndMGF1Padding} 默认 MGF1 用 SHA-1，
 * 必须显式传 {@link OAEPParameterSpec} 强制 MGF1-SHA256，否则服务端解不开信封。</p>
 *
 * @author 郑明亮
 */
final class CmCloudIdaasCrypto {
    /** 凭据信封版本与参数（与 SDK v2 envelope 一致，改了就读不回旧文件）。 */
    static final int ENVELOPE_VERSION = 2;
    static final String ENVELOPE_KDF = "pbkdf2-sha256";
    static final int PBKDF2_ITERATIONS = 600_000;
    private static final int SALT_LEN = 16;
    private static final int IV_LEN = 12;
    private static final int TAG_BITS = 128;
    private static final int GCM_KEY_LEN = 16;

    private static final SecureRandom RANDOM = new SecureRandom();
    /** 设备指纹进程内缓存（MAC 采样有延迟，只算一次）。 */
    private static volatile byte[] fingerprintCache;

    private CmCloudIdaasCrypto() {
    }

    // ---------------------------------------------------------------- 设备指纹

    /**
     * 设备指纹：hostname | username | MAC（48-bit，去随机化位）| 平台名 → SHA-256。
     * 字段缺失就跳过（指纹本身是 best-effort）；进程内缓存。
     */
    static byte[] deviceFingerprint() {
        byte[] cached = fingerprintCache;
        if (cached != null) {
            return cached;
        }
        java.io.ByteArrayOutputStream parts = new java.io.ByteArrayOutputStream();
        try {
            byte[] hostName = java.net.InetAddress.getLocalHost().getHostName()
                    .getBytes(StandardCharsets.UTF_8);
            parts.write(hostName, 0, hostName.length);
        } catch (Exception ignored) {
            // 主机名拿不到就跳过该段
        }
        String user = System.getProperty("user.name");
        if (user != null && !Strs.isBlank(user)) {
            byte[] name = user.getBytes(StandardCharsets.UTF_8);
            parts.write(name, 0, name.length);
        }
        byte[] mac = stableMacAddress();
        if (mac != null) {
            parts.write(mac, 0, mac.length);
        }
        byte[] platform = platformName().getBytes(StandardCharsets.UTF_8);
        parts.write(platform, 0, platform.length);
        try {
            cached = MessageDigest.getInstance("SHA-256")
                    .digest(parts.toByteArray());
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 不可用，无法派生设备指纹", e);
        }
        fingerprintCache = cached;
        return cached;
    }

    /** 平台名（对齐 Python sys.platform：linux / darwin / win32）。 */
    private static String platformName() {
        String os = System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT);
        if (os.contains("win")) {
            return "win32";
        }
        if (os.contains("mac") || os.contains("darwin")) {
            return "darwin";
        }
        return "linux";
    }

    /**
     * 稳定 MAC：采样 3 次取众数（并列取最大），去掉 IEEE 802 随机化/组播位（bit 40）。
     * 全部拿不到返回 null（指纹少一段，仍然自洽）。
     */
    private static byte[] stableMacAddress() {
        Map<String, Integer> candidates = new HashMap<>();
        for (int sample = 0; sample < 3; sample++) {
            byte[] mac = firstUsableMac();
            if (mac != null) {
                long value = 0;
                for (byte b : mac) {
                    value = (value << 8) | (b & 0xFFL);
                }
                // 去掉随机化/组播位（对齐 uuid.getnode 的约定）
                if (((value >> 40) & 0x01) != 0) {
                    value = 0;
                }
                candidates.merge(Long.toHexString(value), 1, Integer::sum);
            }
            if (sample < 2) {
                try {
                    Thread.sleep(100);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
        String best = null;
        int bestCount = -1;
        for (Map.Entry<String, Integer> entry : candidates.entrySet()) {
            int count = entry.getValue();
            String key = entry.getKey();
            if (count > bestCount || (count == bestCount && best != null
                    && key.compareTo(best) > 0)) {
                best = key;
                bestCount = count;
            }
        }
        if (best == null || best.equals("0")) {
            return null;
        }
        long value = Long.parseUnsignedLong(best, 16);
        byte[] out = new byte[6];
        for (int i = 5; i >= 0; i--) {
            out[i] = (byte) (value & 0xFF);
            value >>= 8;
        }
        return out;
    }

    /** 第一块有物理地址的网卡 MAC（跳过回环与全零）。 */
    private static byte[] firstUsableMac() {
        try {
            for (java.net.NetworkInterface nic : java.util.Collections
                    .list(java.net.NetworkInterface.getNetworkInterfaces())) {
                if (nic.isLoopback() || !nic.isUp()) {
                    continue;
                }
                byte[] mac = nic.getHardwareAddress();
                if (mac == null || mac.length != 6) {
                    continue;
                }
                boolean allZero = true;
                for (byte b : mac) {
                    if (b != 0) {
                        allZero = false;
                        break;
                    }
                }
                if (!allZero) {
                    return mac;
                }
            }
        } catch (Exception ignored) {
            // 网卡枚举失败：指纹少一段
        }
        return null;
    }

    // ---------------------------------------------------------------- 凭据信封

    /**
     * 凭据 bundle → 信封 JSON（可直接序列化落盘）。
     * 字段与 SDK v2 envelope 一致：v / kdf / iterations / salt / fp_hint / iv / ct / tag。
     */
    static Map<String, Object> sealBundle(Map<String, Object> bundle) {
        try {
            byte[] salt = new byte[SALT_LEN];
            RANDOM.nextBytes(salt);
            byte[] fingerprint = deviceFingerprint();
            byte[] key = pbkdf2(fingerprint, salt);
            byte[] iv = new byte[IV_LEN];
            RANDOM.nextBytes(iv);
            byte[] plaintext = CmCloudIdaasJson.toJson(bundle).getBytes(StandardCharsets.UTF_8);
            byte[] ct = aesGcm(plaintext, key, iv, true);
            byte[] tag = new byte[TAG_BITS / 8];
            byte[] ciphertext = new byte[ct.length - tag.length];
            System.arraycopy(ct, 0, ciphertext, 0, ciphertext.length);
            System.arraycopy(ct, ciphertext.length, tag, 0, tag.length);

            Map<String, Object> envelope = new HashMap<>();
            envelope.put("v", ENVELOPE_VERSION);
            envelope.put("kdf", ENVELOPE_KDF);
            envelope.put("iterations", PBKDF2_ITERATIONS);
            envelope.put("salt", Base64.getEncoder().encodeToString(salt));
            envelope.put("fp_hint", fpHint(fingerprint));
            envelope.put("iv", Base64.getEncoder().encodeToString(iv));
            envelope.put("ct", Base64.getEncoder().encodeToString(ciphertext));
            envelope.put("tag", Base64.getEncoder().encodeToString(tag));
            return envelope;
        } catch (Exception e) {
            throw new IllegalStateException("本地凭据加密失败：" + e.getMessage(), e);
        }
    }

    /**
     * 信封 JSON → bundle；任何失败（版本不符 / 指纹漂移 / tag 校验失败）返回 null，
     * 上层按「无凭据」处理并触发重新授权——与 SDK 语义一致，不抛异常。
     */
    static Map<String, Object> openBundle(Map<String, Object> envelope) {
        try {
            if (!Integer.valueOf(ENVELOPE_VERSION).equals(
                    asInt(envelope.get("v"))) || !ENVELOPE_KDF.equals(envelope.get("kdf"))) {
                return null;
            }
            if (!Integer.valueOf(PBKDF2_ITERATIONS).equals(asInt(envelope.get("iterations")))) {
                return null;
            }
            byte[] salt = Base64.getDecoder().decode(String.valueOf(envelope.get("salt")));
            byte[] iv = Base64.getDecoder().decode(String.valueOf(envelope.get("iv")));
            byte[] ciphertext = Base64.getDecoder().decode(String.valueOf(envelope.get("ct")));
            byte[] tag = Base64.getDecoder().decode(String.valueOf(envelope.get("tag")));
            byte[] fingerprint = deviceFingerprint();
            byte[] key = pbkdf2(fingerprint, salt);
            byte[] plaintext = aesGcm(concat(ciphertext, tag), key, iv, false);
            return CmCloudIdaasJson.parseMap(new String(plaintext, StandardCharsets.UTF_8));
        } catch (Exception e) {
            // 指纹漂移 / 文件损坏：统一按无凭据处理
            return null;
        }
    }

    private static String fpHint(byte[] fingerprint) {
        return hex(fingerprint).substring(0, 8);
    }

    private static byte[] pbkdf2(byte[] fingerprint, byte[] salt) throws Exception {
        PBEKeySpec spec = new PBEKeySpec(toChars(fingerprint), salt,
                PBKDF2_ITERATIONS, 256);
        return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
                .generateSecret(spec).getEncoded();
    }

    /** char 化口令（PBKDF2 只吃 char[]；字节按 unsigned 8bit 截断会丢信息，转 2 字节十六进制保真）。 */
    private static char[] toChars(byte[] bytes) {
        return hex(bytes).toCharArray();
    }

    /**
     * AES-GCM 单块加解密：JDK 的 {@code AES/GCM/NoPadding} 输出/输入都是 {@code ct||tag}。
     * encrypt=true 加密；false 解密（tag 校验失败抛异常）。
     */
    private static byte[] aesGcm(byte[] input, byte[] key, byte[] iv, boolean encrypt)
            throws Exception {
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(encrypt ? Cipher.ENCRYPT_MODE : Cipher.DECRYPT_MODE,
                new SecretKeySpec(key, "AES"), new GCMParameterSpec(TAG_BITS, iv));
        return cipher.doFinal(input);
    }

    // ---------------------------------------------------------------- preAuth 数字信封

    /**
     * preAuth 请求体：{@code reqdata}=base64(ct||tag)，{@code encrypted}=base64(RSA-OAEP 加密的 AES 密钥)。
     *
     * @param payload 业务 payload（会被 JSON 序列化，非 ASCII 按原字符编码）
     */
    static Map<String, String> sealRequest(Map<String, Object> payload) {
        try {
            byte[] aesKey = new byte[GCM_KEY_LEN];
            RANDOM.nextBytes(aesKey);
            byte[] iv = java.util.Arrays.copyOf(aesKey, IV_LEN);
            byte[] plaintext = CmCloudIdaasJson.toJson(payload).getBytes(StandardCharsets.UTF_8);
            byte[] encryptedData = aesGcm(plaintext, aesKey, iv, true);
            byte[] encryptedKey = rsaOaepEncrypt(aesKey);
            Map<String, String> body = new HashMap<>();
            body.put("reqdata", Base64.getEncoder().encodeToString(encryptedData));
            body.put("encrypted", Base64.getEncoder().encodeToString(encryptedKey));
            return body;
        } catch (Exception e) {
            throw new IllegalStateException("数字信封加密失败：" + e.getMessage(), e);
        }
    }

    /** RSA-OAEP（SHA-256 + MGF1-SHA256）加密 AES 密钥；显式 OAEPParameterSpec 防 SunJCE 默认 SHA-1。 */
    private static byte[] rsaOaepEncrypt(byte[] aesKey) throws Exception {
        String pem = CmCloudIdaasProfile.EMBEDDED_PUBLIC_KEY_PEM
                .replace("-----BEGIN PUBLIC KEY-----", "")
                .replace("-----END PUBLIC KEY-----", "")
                .replaceAll("\\s", "");
        byte[] der = Base64.getDecoder().decode(pem);
        PublicKey publicKey = KeyFactory.getInstance("RSA")
                .generatePublic(new X509EncodedKeySpec(der));
        Cipher cipher = Cipher.getInstance("RSA/ECB/OAEPWithSHA-256AndMGF1Padding");
        cipher.init(Cipher.ENCRYPT_MODE, (RSAPublicKey) publicKey,
                new OAEPParameterSpec("SHA-256", "MGF1", MGF1ParameterSpec.SHA256,
                        javax.crypto.spec.PSource.PSpecified.DEFAULT));
        return cipher.doFinal(aesKey);
    }

    // ---------------------------------------------------------------- 工具

    private static Integer asInt(Object value) {
        if (value instanceof Number) {
            return ((Number) value).intValue();
        }
        if (value instanceof String) {
            try {
                return Integer.parseInt(((String) value).trim());
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }

    private static byte[] concat(byte[] a, byte[] b) {
        byte[] out = new byte[a.length + b.length];
        System.arraycopy(a, 0, out, 0, a.length);
        System.arraycopy(b, 0, out, a.length, b.length);
        return out;
    }

    private static String hex(byte[] bytes) {
        StringBuilder out = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            out.append(Character.forDigit((b >> 4) & 0xF, 16));
            out.append(Character.forDigit(b & 0xF, 16));
        }
        return out.toString();
    }
}
