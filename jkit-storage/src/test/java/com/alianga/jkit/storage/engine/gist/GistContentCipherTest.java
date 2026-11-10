package com.alianga.jkit.storage.engine.gist;

import com.alianga.jkit.storage.StorageException;
import com.alianga.jkit.storage.conn.PassphraseCipher;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * Gist 客户端加密信封的格式契约测试：前缀判定、往返、错口令、篡改、缺口令。
 *
 * <p>这是「后续恢复」的保险：格式一旦写进 gist 就长期固定，这里把它钉死。</p>
 *
 * @author 郑明亮
 */
public class GistContentCipherTest {

    @Test
    public void encryptedValueHasStablePrefixAndIsNotPlaintext() {
        String stored = GistContentCipher.encrypt("correct horse", "hello 世界", "a.txt");
        assertTrue("必须带可判定的前缀", stored.startsWith(GistContentCipher.PREFIX));
        assertTrue("前缀后应是 PassphraseCipher 信封", PassphraseCipher.isEnvelope(stored));
        assertFalse("gist 里不能出现明文", stored.contains("hello 世界"));
        assertFalse("gist 里不能是二进制标记", stored.contains(GistPaths.BINARY_MARKER));
    }

    @Test
    public void isEncryptedOnlyMatchesOurPrefix() {
        assertFalse(GistContentCipher.isEncrypted(null));
        assertFalse(GistContentCipher.isEncrypted(""));
        assertFalse(GistContentCipher.isEncrypted("plain text"));
        assertFalse(GistContentCipher.isEncrypted(GistPaths.BINARY_MARKER + "AAAA"));
        assertTrue(GistContentCipher.isEncrypted(GistContentCipher.PREFIX + "{}"));
    }

    @Test
    public void roundTripIsExactAndEachWriteUsesFreshRandomness() {
        String encoded = GistPaths.encodeContent("中文\nline2".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        String a = GistContentCipher.encrypt("pwd", encoded, "a.txt");
        String b = GistContentCipher.encrypt("pwd", encoded, "a.txt");
        assertFalse("随机 salt/IV 应让两次密文不同", a.equals(b));
        assertEquals(encoded, GistContentCipher.decrypt("pwd", a, "a.txt"));
        assertEquals(encoded, GistContentCipher.decrypt("pwd", b, "a.txt"));
    }

    @Test
    public void wrongPasswordIsRejected() {
        String stored = GistContentCipher.encrypt("right", "secret", "a.txt");
        try {
            GistContentCipher.decrypt("wrong", stored, "a.txt");
            fail("错口令必须报错，不能返回乱码或空串");
        } catch (StorageException e) {
            assertTrue("应提示口令错误: " + e.getMessage(), e.getMessage().contains("口令错误"));
        }
    }

    @Test
    public void tamperedEnvelopeIsRejected() {
        String stored = GistContentCipher.encrypt("pwd", "secret", "a.txt");
        String tampered = stored.substring(0, stored.length() - 6) + "AAAA\"";
        try {
            GistContentCipher.decrypt("pwd", tampered, "a.txt");
            fail("篡改必须被 GCM tag 校验挡住");
        } catch (StorageException e) {
            assertTrue("应提示数据损坏: " + e.getMessage(),
                    e.getMessage().contains("口令错误") || e.getMessage().contains("损坏")
                            || e.getMessage().contains("不是有效的加密信封"));
        }
    }

    @Test
    public void missingPasswordGivesActionableError() {
        String stored = GistContentCipher.encrypt("pwd", "secret", "a.txt");
        for (String missing : new String[]{null, "", "   "}) {
            try {
                GistContentCipher.decrypt(missing, stored, "a.txt");
                fail("缺口令必须明确报错，不能静默返回密文");
            } catch (StorageException e) {
                assertTrue("应提示填写口令: " + e.getMessage(), e.getMessage().contains("填写加密口令"));
            }
        }
    }

    @Test
    public void plaintextPassesThroughDetection() {
        // 旧数据 / 关了开关：没有前缀就不该走解密，也不该报错
        String plain = "just a plain text file";
        assertFalse(GistContentCipher.isEncrypted(plain));
        // 直通路径由 GistClient 判定，这里只验前缀判定不会误伤明文
        assertFalse(GistContentCipher.isEncrypted(
                GistPaths.encodeContent(plain.getBytes(java.nio.charset.StandardCharsets.UTF_8))));
    }
}
