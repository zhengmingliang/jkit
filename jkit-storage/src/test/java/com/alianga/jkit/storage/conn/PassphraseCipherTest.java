package com.alianga.jkit.storage.conn;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * 口令加密信封：往返、错误口令、信封不透明（密文不含明文/口令）。
 *
 * @author 郑明亮
 */
public class PassphraseCipherTest {

    @Test
    public void encryptDecryptRoundTrip() {
        String plain = "{\"connections\":[{\"name\":\"生产 OSS\",\"config\":{\"password\":\"p@ss\"}}]}";
        String envelope = PassphraseCipher.encrypt("correct-horse-battery", plain);
        assertTrue(PassphraseCipher.isEnvelope(envelope));
        assertEquals(plain, PassphraseCipher.decrypt("correct-horse-battery", envelope));
    }

    @Test
    public void wrongPassphraseRejected() {
        String envelope = PassphraseCipher.encrypt("right", "secret");
        try {
            PassphraseCipher.decrypt("wrong", envelope);
            throw new AssertionError("错误口令必须失败");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("口令错误"));
        }
    }

    @Test
    public void tamperedDataRejected() {
        String envelope = PassphraseCipher.encrypt("pw", "secret");
        String tampered = envelope.replaceAll("\"data\":\"[^\"]*\"", "\"data\":\"AAAA\"");
        try {
            PassphraseCipher.decrypt("pw", tampered);
            throw new AssertionError("篡改数据必须失败");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("口令错误或数据已损坏"));
        }
    }

    @Test
    public void envelopeIsOpaque() {
        String secret = "SUPER-SECRET-PASSWORD";
        String envelope = PassphraseCipher.encrypt("pw", "body with " + secret);
        assertFalse("密文不得包含明文内容", envelope.contains(secret));
        assertFalse("密文不得包含口令", envelope.contains("pw\""));
        // 同口令两次加密产生不同盐与 IV
        assertFalse("随机盐/IV 应使两次密文不同",
                envelope.equals(PassphraseCipher.encrypt("pw", "body with " + secret)));
    }

    @Test
    public void emptyPassphraseRejected() {
        try {
            PassphraseCipher.encrypt("", "x");
            throw new AssertionError("空口令必须拒绝");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("口令不能为空"));
        }
        assertTrue(PassphraseCipher.isEnvelope("prefix {\"format\":\"" + "jkit-storage-encrypted" + "\"}"));
        assertFalse(PassphraseCipher.isEnvelope("{\"format\":\"jkit-storage-connections\"}"));
    }
}
