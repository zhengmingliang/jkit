package com.alianga.jkit.storage.engine;

import com.alianga.jkit.json.JSON;
import com.alianga.jkit.storage.StorageConfig;
import com.alianga.jkit.storage.StorageErrorCode;
import com.alianga.jkit.storage.StorageException;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * ticket + xmtls 助手续期：mock 助手 + mock 服务端。-20002 → refreshSid → 解密取新 sid → 重试；
 * -20047 冲突重试；明文错误头不解密；助手不可用给明确原因；从不空调。
 *
 * @author 郑明亮
 */
public class QqMailXmtlsRenewTest {
    private static final String SID = "SIDSECRET123";
    private static final String COOKIE =
            "qm_sk=COOKIESECRET; xm_sid=S1; xm_skey=K1; xm_data_ticket=T1; xm_uin=U1";
    private static final String TICKET_INNER =
            "{\"s_uin\":\"10001\",\"sid\":\"OLD\",\"pwd_key\":\"PKSECRET\",\"pwd_ticket_key\":\"PTKSECRET\"}";
    private static final String TICKET = "{\"uin\":\"10001\",\"sid\":\"OLD\",\"ticketFromType\":\"qq\",\"encryptTicket\":\""
            + jsonEscape(QqMailTicket.shift(TICKET_INNER)) + "\"}";

    /** shift 结果可能含 \：嵌进 JSON 字面量须按 JSON 规则转义（与浏览器写盘一致）。 */
    private static String jsonEscape(String enc) {
        StringBuilder sb = new StringBuilder(enc.length() + 4);
        for (int i = 0; i < enc.length(); i++) {
            char ch = enc.charAt(i);
            if (ch == '\\' || ch == '"') {
                sb.append('\\');
            }
            sb.append(ch);
        }
        return sb.toString();
    }
    private static final String DS = "0123456789abcdef0123456789abcdef";

    /** 记录调用的假助手：密文 = "CIPHER&lt;n&gt;"，解密查表。 */
    static final class FakeXmtls implements QqMailXmtls {
        final List<String> keys = new ArrayList<String>();
        final List<String> plains = new ArrayList<String>();
        final List<String> decrypted = new ArrayList<String>();
        final List<Long> released = new ArrayList<Long>();
        final List<String> replies = new ArrayList<String>();
        RuntimeException failEncrypt;
        long next = 1;

        FakeXmtls reply(String plain) {
            replies.add(plain);
            return this;
        }

        @Override
        public Sealed encrypt(String pwdKey, String plainJson) {
            if (failEncrypt != null) {
                throw failEncrypt;
            }
            keys.add(pwdKey);
            plains.add(plainJson);
            long h = next++;
            return new Sealed(h, "CIPHER" + h);
        }

        @Override
        public String decrypt(long handle, String cipherText) {
            decrypted.add(cipherText);
            if (replies.isEmpty()) {
                return null;
            }
            return replies.remove(0);
        }

        @Override
        public void release(long handle) {
            released.add(handle);
        }

        @Override
        public void close() {
        }
    }

    private QqMailAttachFileStorage open(QqMailAttachFileStorageTest.Mock mock, FakeXmtls x, String... kv) {
        StorageConfig cfg = StorageConfig.empty().set("sid", SID).set("cookie", COOKIE)
                .set("sessionCheckIntervalMs", "0").set("requestIntervalMs", "0")
                .set("ticket", TICKET).set("ds", DS);
        for (int i = 0; i + 1 < kv.length; i += 2) {
            cfg.set(kv[i], kv[i + 1]);
        }
        QqMailAttachFileStorage fs = new QqMailAttachFileStorage(cfg, mock, ms -> { }, () -> 1_760_500_000_123L);
        fs.setXmtls(x);
        return fs;
    }

    private static String okJson(String sid) {
        return "{\"head\":{\"ret\":0},\"body\":{\"sid\":\"" + sid + "\"}}";
    }

    @Test
    public void renewViaTicketDecryptsSidPersistsAndRetries() {
        String page = QqMailAttachFileStorageTest.page(1,
                QqMailAttachFileStorageTest.item("f1", "a.zip", 1, "m1", false));
        FakeXmtls x = new FakeXmtls().reply(okJson("S2NEW"));
        QqMailAttachFileStorageTest.Mock mock = new QqMailAttachFileStorageTest.Mock()
                .json("/attach/attach_list", QqMailAttachFileStorageTest.err(-20002))
                .jsonWithCookie("/login/login", "SERVERCIPHERBLOB________________",
                        "xm_sid=S2NEW; Path=/; Domain=qq.com")
                .jsonWithCookie("/attach/attach_list", page, "xm_skey=K2NEW; Path=/; Domain=qq.com");
        AtomicReference<String> gotCookie = new AtomicReference<>();
        AtomicReference<String> gotSid = new AtomicReference<>();
        QqMailAttachFileStorage fs = open(mock, x);
        fs.setSessionUpdateListener((c, s) -> {
            gotCookie.set(c);
            gotSid.set(s);
        });
        assertEquals(1, fs.list("/全部").size());
        assertEquals(1, x.keys.size());
        assertEquals("PKSECRET", x.keys.get(0));
        @SuppressWarnings("unchecked")
        Map<String, Object> plain = (Map<String, Object>) JSON.parse(x.plains.get(0));
        assertEquals(6, ((Number) plain.get("auth_type")).intValue());
        assertEquals("PKSECRET", plain.get("pwd_key"));
        assertEquals("PTKSECRET", plain.get("pwd_ticket_key"));
        assertTrue(plain.get("filter_param") instanceof Map);
        assertEquals(1, x.decrypted.size());
        assertEquals("SERVERCIPHERBLOB________________", x.decrypted.get(0));
        assertTrue(mock.calls.get(1)[1].contains("xm_req_type=web-pwd"));
        assertTrue(mock.calls.get(1)[1].contains("ds=" + DS));
        assertEquals("CIPHER1", mock.calls.get(1)[2]);
        assertEquals("S2NEW", gotSid.get());
        assertTrue(gotCookie.get().contains("xm_sid=S2NEW"));
        assertTrue(gotCookie.get().contains("xm_skey=K2NEW") || gotCookie.get().contains("xm_skey=K1"));
    }

    @Test
    public void conflict20047RetriesUpToThreeThenFails() {
        FakeXmtls x = new FakeXmtls()
                .reply("{\"head\":{\"ret\":-20047}}")
                .reply("{\"head\":{\"ret\":-20047}}")
                .reply("{\"head\":{\"ret\":-20047}}")
                .reply("{\"head\":{\"ret\":-20047}}");
        List<Long> sleeps = new ArrayList<>();
        QqMailAttachFileStorageTest.Mock mock = new QqMailAttachFileStorageTest.Mock()
                .json("/attach/attach_list", QqMailAttachFileStorageTest.err(-20002));
        for (int i = 0; i < 4; i++) {
            mock.json("/login/login", "CIPHERTEXT" + i);
        }
        StorageConfig cfg = StorageConfig.empty().set("sid", SID).set("cookie", COOKIE)
                .set("sessionCheckIntervalMs", "0").set("requestIntervalMs", "0")
                .set("ticket", TICKET).set("ds", DS);
        QqMailAttachFileStorage fs = new QqMailAttachFileStorage(cfg, mock, ms -> sleeps.add(ms),
                () -> 1_760_500_000_123L);
        fs.setXmtls(x);
        try {
            fs.list("/全部");
            fail();
        } catch (StorageException e) {
            assertEquals(StorageErrorCode.AUTH_FAILED, e.code());
            assertTrue(e.getMessage(), e.getMessage().contains("ret=-20047"));
        }
        assertEquals(4, x.plains.size());
        assertEquals(3, sleeps.size());
        for (long ms : sleeps) {
            assertTrue(ms >= 800 && ms <= 1500);
        }
    }

    @Test
    public void plaintextJsonErrorReleasesSessionWithoutDecrypt() {
        FakeXmtls x = new FakeXmtls();
        QqMailAttachFileStorageTest.Mock mock = new QqMailAttachFileStorageTest.Mock()
                .json("/attach/attach_list", QqMailAttachFileStorageTest.err(-20002))
                .json("/login/login", "{\"head\":{\"ret\":-21021,\"cgi\":\"xmloginlogicsvr/login\"}}");
        try {
            open(mock, x).list("/全部");
            fail();
        } catch (StorageException e) {
            assertEquals(StorageErrorCode.AUTH_FAILED, e.code());
            assertTrue(e.getMessage(), e.getMessage().contains("ret=-21021"));
        }
        assertTrue(x.decrypted.isEmpty());
        assertEquals(1, x.released.size());
    }

    @Test
    public void helperUnavailableReportsReasonAndNeverCallsLogin() {
        FakeXmtls x = new FakeXmtls();
        x.failEncrypt = StorageException.invalidConfig("未找到 node：测试注入");
        QqMailAttachFileStorageTest.Mock mock = new QqMailAttachFileStorageTest.Mock()
                .json("/attach/attach_list", QqMailAttachFileStorageTest.err(-20002));
        try {
            open(mock, x).list("/全部");
            fail();
        } catch (StorageException e) {
            assertEquals(StorageErrorCode.AUTH_FAILED, e.code());
            assertTrue(e.getMessage(), e.getMessage().contains("未找到 node"));
        }
        for (String[] c : mock.calls) {
            assertFalse(c[1], c[1].contains("/login/login"));
        }
        QqMailAttachFileStorage fs = open(mock, x);
        assertFalse(fs.renewSession());
        assertNotNull(fs.lastRenewError());
        assertTrue(fs.lastRenewError(), fs.lastRenewError().contains("未找到 node"));
    }

    @Test
    public void badTicketConfigDoesNotCallLogin() {
        QqMailAttachFileStorageTest.Mock mock = new QqMailAttachFileStorageTest.Mock()
                .json("/attach/attach_list", QqMailAttachFileStorageTest.err(-20002));
        StorageConfig cfg = StorageConfig.empty().set("sid", SID).set("cookie", COOKIE)
                .set("sessionCheckIntervalMs", "0").set("requestIntervalMs", "0")
                .set("ticket", "NOTATICKETSECRET");
        try {
            new QqMailAttachFileStorage(cfg, mock, ms -> { }, () -> 1L).list("/全部");
            fail();
        } catch (StorageException e) {
            assertEquals(StorageErrorCode.AUTH_FAILED, e.code());
            assertFalse(e.getMessage(), e.getMessage().contains("NOTATICKETSECRET"));
        }
        for (String[] c : mock.calls) {
            assertFalse(c[1], c[1].contains("/login/login"));
        }
    }
}
