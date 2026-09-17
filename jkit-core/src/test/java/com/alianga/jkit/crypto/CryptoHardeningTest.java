package com.alianga.jkit.crypto;

import com.alianga.jkit.EncryptUtils;

import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

import org.junit.Test;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * 加密能力加固的回归：AES-GCM（AEAD）与 RSA（默认 2048 位 + OAEP 填充）。
 *
 * <p>覆盖点：GCM 往返 / 篡改检测 / AAD 参与校验 / 参数校验；RSA 密钥长度默认值与显式指定、
 * OAEP 往返、OAEP 与 PKCS#1 v1.5 互不相通、字符串 base64 便捷方法。
 */
public class CryptoHardeningTest {

    private static final byte[] KEY_256 = AESCrypt.generateKey(256);
    private static final byte[] PLAIN = "jkit 加密加固回归：中文 + emoji 之外的普通文本".getBytes(StandardCharsets.UTF_8);

    @Test
    public void gcmRoundTripWithPackedIv() {
        byte[] packed = AESCrypt.encryptGcm(PLAIN, KEY_256);
        // IV(12) + 明文长度 + 认证标签(16)
        assertEquals(AESCrypt.GCM_IV_LENGTH + PLAIN.length + 16, packed.length);
        assertArrayEquals(PLAIN, AESCrypt.decryptGcm(packed, KEY_256));
    }

    @Test
    public void gcmRoundTripWithExplicitIv() {
        byte[] iv = AESCrypt.generateIv();
        assertEquals(12, iv.length);
        byte[] cipher = AESCrypt.encryptGcm(PLAIN, KEY_256, iv, null);
        assertArrayEquals(PLAIN, AESCrypt.decryptGcm(cipher, KEY_256, iv, null));
    }

    @Test
    public void gcmEmptyAndLargePayload() {
        byte[] empty = new byte[0];
        assertArrayEquals(empty, AESCrypt.decryptGcm(AESCrypt.encryptGcm(empty, KEY_256), KEY_256));

        byte[] big = new byte[1024 * 256];
        for (int i = 0; i < big.length; i++) {
            big[i] = (byte) i;
        }
        assertArrayEquals(big, AESCrypt.decryptGcm(AESCrypt.encryptGcm(big, KEY_256), KEY_256));
    }

    @Test
    public void gcmIvIsRandomPerCall() {
        byte[] first = AESCrypt.encryptGcm(PLAIN, KEY_256);
        byte[] second = AESCrypt.encryptGcm(PLAIN, KEY_256);
        assertFalse("同一明文两次加密不应产生相同密文（IV 随机）", Arrays.equals(first, second));

        Set<String> ivs = new HashSet<String>();
        for (int i = 0; i < 200; i++) {
            ivs.add(Arrays.toString(AESCrypt.generateIv()));
        }
        assertEquals("200 次 IV 不应重复", 200, ivs.size());
    }

    @Test
    public void gcmDetectsCiphertextTampering() {
        byte[] packed = AESCrypt.encryptGcm(PLAIN, KEY_256);
        byte[] tampered = packed.clone();
        tampered[tampered.length - 1] ^= 0x01;
        try {
            AESCrypt.decryptGcm(tampered, KEY_256);
            fail("密文被篡改后 GCM 应认证失败");
        } catch (RuntimeException expected) {
            assertTrue(expected.getMessage(), expected.getMessage().contains("GCM decrypt failed"));
        }
    }

    @Test
    public void gcmDetectsWrongKey() {
        byte[] packed = AESCrypt.encryptGcm(PLAIN, KEY_256);
        try {
            AESCrypt.decryptGcm(packed, AESCrypt.generateKey(256));
            fail("用错误密钥解密应失败");
        } catch (RuntimeException expected) {
            assertTrue(expected.getMessage(), expected.getMessage().contains("GCM decrypt failed"));
        }
    }

    @Test
    public void gcmAadIsAuthenticated() {
        byte[] iv = AESCrypt.generateIv();
        byte[] aad = "orderId=20260918001".getBytes(StandardCharsets.UTF_8);
        byte[] cipher = AESCrypt.encryptGcm(PLAIN, KEY_256, iv, aad);
        assertArrayEquals(PLAIN, AESCrypt.decryptGcm(cipher, KEY_256, iv, aad));

        byte[] otherAad = "orderId=20260918002".getBytes(StandardCharsets.UTF_8);
        try {
            AESCrypt.decryptGcm(cipher, KEY_256, iv, otherAad);
            fail("AAD 不一致应认证失败");
        } catch (RuntimeException expected) {
            assertTrue(expected.getMessage(), expected.getMessage().contains("GCM decrypt failed"));
        }
    }

    @Test
    public void gcmRejectsIllegalArguments() {
        try {
            AESCrypt.encryptGcm(PLAIN, KEY_256, new byte[16], null);
            fail("IV 必须是 12 字节");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage(), expected.getMessage().contains("GCM IV"));
        }
        try {
            AESCrypt.encryptGcm(PLAIN, new byte[8]);
            fail("密钥必须是 16/24/32 字节");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage(), expected.getMessage().contains("AES key"));
        }
        try {
            AESCrypt.decryptGcm(new byte[8], KEY_256);
            fail("密文过短应直接拒绝，而不是当成空明文");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage(), expected.getMessage().contains("too short"));
        }
    }

    @Test
    public void gcmNullInNullOut() {
        assertEquals(null, AESCrypt.encryptGcm(null, KEY_256));
        assertEquals(null, AESCrypt.decryptGcm(null, KEY_256));
    }

    @Test
    public void rsaDefaultKeySizeIs2048() throws Exception {
        KeyPair pair = EncryptUtils.RSA.buildKeyPair();
        assertEquals("默认密钥长度应已上调到 2048", 2048, rsaKeySize(pair.getPublic()));
        assertEquals(EncryptUtils.RSA.DEFAULT_KEY_SIZE, rsaKeySize(pair.getPublic()));
    }

    @Test
    public void rsaExplicitKeySizeStillWorks() throws Exception {
        // 历史系统需要 1024 位时仍可显式指定，只是不再是默认值
        KeyPair legacy = EncryptUtils.RSA.buildKeyPair(1024);
        assertEquals(1024, rsaKeySize(legacy.getPublic()));
        assertEquals(3072, rsaKeySize(EncryptUtils.RSA.buildKeyPair(3072).getPublic()));
    }

    @Test
    public void rsaRejectsTooSmallKeySize() throws Exception {
        try {
            EncryptUtils.RSA.buildKeyPair(256);
            fail("小于 512 位的密钥长度应被拒绝");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage(), expected.getMessage().contains("512"));
        }
    }

    @Test
    public void rsaOaepRoundTrip() throws Exception {
        KeyPair pair = EncryptUtils.RSA.buildKeyPair();
        PublicKey pub = pair.getPublic();
        PrivateKey pri = pair.getPrivate();

        byte[] cipher = EncryptUtils.RSA.encryptOaep(PLAIN, pub);
        assertEquals("OAEP 密文长度应等于密钥长度（2048 位 = 256 字节）", 256, cipher.length);
        assertArrayEquals(PLAIN, EncryptUtils.RSA.decryptOaep(cipher, pri));
    }

    @Test
    public void rsaOaepIsRandomized() throws Exception {
        KeyPair pair = EncryptUtils.RSA.buildKeyPair();
        byte[] first = EncryptUtils.RSA.encryptOaep(PLAIN, pair.getPublic());
        byte[] second = EncryptUtils.RSA.encryptOaep(PLAIN, pair.getPublic());
        assertFalse("OAEP 带随机盐，两次密文不应相同", Arrays.equals(first, second));
    }

    @Test
    public void rsaOaepAndPkcs1AreNotInterchangeable() throws Exception {
        KeyPair pair = EncryptUtils.RSA.buildKeyPair();
        byte[] oaepCipher = EncryptUtils.RSA.encryptOaep(PLAIN, pair.getPublic());
        try {
            EncryptUtils.RSA.decrypt(oaepCipher, pair.getPrivate());
            fail("用 PKCS#1 v1.5 解 OAEP 密文不应成功");
        } catch (Exception expected) {
            // 期望失败：两种填充互不兼容
            assertNotNull(expected);
        }

        byte[] pkcs1Cipher = EncryptUtils.RSA.encrypt(PLAIN, pair.getPublic());
        try {
            EncryptUtils.RSA.decryptOaep(pkcs1Cipher, pair.getPrivate());
            fail("用 OAEP 解 PKCS#1 v1.5 密文不应成功");
        } catch (Exception expected) {
            assertNotNull(expected);
        }
        // v1.5 自身仍然可用（兼容历史密文）
        assertArrayEquals(PLAIN, EncryptUtils.RSA.decrypt(pkcs1Cipher, pair.getPrivate()));
    }

    @Test
    public void rsaOaepStringRoundTrip() throws Exception {
        KeyPair pair = EncryptUtils.RSA.buildKeyPair();
        String pub = com.alianga.jkit.Base64Utils.encodeToString(pair.getPublic().getEncoded());
        String pri = com.alianga.jkit.Base64Utils.encodeToString(pair.getPrivate().getEncoded());

        String text = "张三|13800138000|待加密的业务字段";
        String cipher = EncryptUtils.RSA.encryptOaep(text, pub);
        assertFalse(cipher.isEmpty());
        assertFalse("密文应为 base64，不应与明文相同", text.equals(cipher));
        assertEquals(text, EncryptUtils.RSA.decryptOaep(cipher, pri));
    }

    @Test
    public void rsaOaepStringReturnsEmptyOnBadKey() {
        assertEquals("", EncryptUtils.RSA.encryptOaep("x", "not-a-base64-key"));
        assertEquals("", EncryptUtils.RSA.decryptOaep("x", "not-a-base64-key"));
    }

    @SuppressWarnings("deprecation")
    @Test
    public void desStillWorksAfterDeprecation() {
        // 废弃不等于删除：历史数据仍要能解
        byte[] key = "12345678".getBytes(StandardCharsets.UTF_8);
        byte[] iv = "87654321".getBytes(StandardCharsets.UTF_8);
        byte[] data = "legacy".getBytes(StandardCharsets.UTF_8);
        byte[] cipher = DESCrypt.encrypt(data, key);
        assertArrayEquals(data, DESCrypt.decrypt(cipher, key));

        com.alianga.jkit.EncryptUtils.DES des = com.alianga.jkit.EncryptUtils.DES.newInstance("pwd", "iv");
        assertNotNull(des.getCrypt());
        assertNotNull(new DESCrypt(key, iv).getClass());
    }

    /**
     * 从 RSA 公钥里取模长度（位）。
     *
     * @param key RSA 公钥
     * @return 密钥位数
     */
    private static int rsaKeySize(PublicKey key) {
        java.security.interfaces.RSAPublicKey rsa = (java.security.interfaces.RSAPublicKey) key;
        return rsa.getModulus().bitLength();
    }
}
