package com.alianga.jkit.storage.engine;

import com.alianga.jkit.storage.StorageConfig;
import com.alianga.jkit.storage.StorageErrorCode;
import com.alianga.jkit.storage.StorageException;
import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * QQ 邮箱附件会话续期：-20002 时用 /login/login 续一次并重试；无加密包不发空调；Cookie/sid 回推。
 *
 * @author 郑明亮
 */
public class QqMailSessionRenewTest {
    private static final String SID = "SIDSECRET123";
    private static final String COOKIE =
            "qm_sk=COOKIESECRET; xm_sid=S1; xm_skey=K1; xm_data_ticket=T1; xm_uin=U1";
    private static final byte[] RENEW_BODY = "AAABQgAAAAE5AEcBnwBBBrenewpayload=".getBytes(StandardCharsets.UTF_8);

    private static QqMailAttachFileStorage open(QqMailAttachFileStorageTest.Mock mock, String... kv) {
        StorageConfig cfg = StorageConfig.empty().set("sid", SID).set("cookie", COOKIE)
                .set("sessionCheckIntervalMs", "0").set("requestIntervalMs", "0");
        for (int i = 0; i + 1 < kv.length; i += 2) {
            cfg.set(kv[i], kv[i + 1]);
        }
        return new QqMailAttachFileStorage(cfg, mock, ms -> { }, () -> 1_760_500_000_123L);
    }

    @Test
    public void authFailureWithoutRenewBodyDoesNotCallLoginLogin() {
        QqMailAttachFileStorageTest.Mock mock = new QqMailAttachFileStorageTest.Mock()
                .json("/attach/attach_list", QqMailAttachFileStorageTest.err(-20002));
        try {
            open(mock).list("/全部");
            fail();
        } catch (StorageException e) {
            assertEquals(StorageErrorCode.AUTH_FAILED, e.code());
        }
        for (String[] c : mock.calls) {
            assertFalse("不应空调 /login/login: " + c[1], c[1].contains("/login/login"));
        }
    }

    @Test
    public void renewOnceOn20002ThenRetrySucceedsAndPersists() {
        String page = QqMailAttachFileStorageTest.page(1,
                QqMailAttachFileStorageTest.item("f1", "a.zip", 1, "m1", false));
        QqMailAttachFileStorageTest.Mock mock = new QqMailAttachFileStorageTest.Mock()
                .json("/attach/attach_list", QqMailAttachFileStorageTest.err(-20002))
                .jsonWithCookie("/login/login", "ENCRYPTED_OK_BLOB_NOT_JSON______________",
                        "xm_sid=S2NEW; Path=/; Domain=qq.com")
                .jsonWithCookie("/attach/attach_list", page, "xm_skey=K2NEW; Path=/; Domain=qq.com");
        QqMailAttachFileStorage fs = open(mock,
                "renewDs", "0123456789abcdef0123456789abcdef",
                "renewBodyBase64", java.util.Base64.getEncoder().encodeToString(RENEW_BODY));
        AtomicReference<String> gotCookie = new AtomicReference<>();
        AtomicReference<String> gotSid = new AtomicReference<>();
        fs.setSessionUpdateListener((cookie, sid) -> {
            gotCookie.set(cookie);
            gotSid.set(sid);
        });
        List<?> entries = fs.list("/全部");
        assertEquals(1, entries.size());
        assertTrue(mock.calls.size() >= 3);
        assertTrue(mock.calls.get(1)[1].contains("/login/login"));
        assertTrue(mock.calls.get(1)[1].contains("xm_req_type=web-pwd"));
        assertTrue(mock.calls.get(1)[1].contains("ds=0123456789abcdef0123456789abcdef"));
        assertEquals(new String(RENEW_BODY, StandardCharsets.UTF_8), mock.calls.get(1)[2]);
        assertEquals("S2NEW", gotSid.get());
        assertTrue(gotCookie.get(), gotCookie.get().contains("xm_sid=S2NEW"));
        assertTrue(gotCookie.get(), gotCookie.get().contains("xm_skey=K2NEW")
                || gotCookie.get().contains("xm_skey=K1"));
    }

    @Test
    public void renewFailureSurfacesOriginalAuthError() {
        QqMailAttachFileStorageTest.Mock mock = new QqMailAttachFileStorageTest.Mock()
                .json("/attach/attach_list", QqMailAttachFileStorageTest.err(-20002))
                .json("/login/login", "{\"head\":{\"ret\":-20023,\"cgi\":\"xmloginlogicsvr/login\"}}");
        try {
            open(mock,
                    "renewDs", "0123456789abcdef0123456789abcdef",
                    "renewBodyBase64", java.util.Base64.getEncoder().encodeToString(RENEW_BODY))
                    .list("/全部");
            fail();
        } catch (StorageException e) {
            assertEquals(StorageErrorCode.AUTH_FAILED, e.code());
            assertTrue(e.getMessage(), e.getMessage().contains("ret=-20002"));
        }
    }

    @Test
    public void proactiveChecksidRenewsWhenStale() {
        String ok = "{\"head\":{\"ret\":0,\"cgi\":\"xmloginlogicsvr/checksid\"}}";
        QqMailAttachFileStorageTest.Mock mock = new QqMailAttachFileStorageTest.Mock()
                .json("/attach/attach_list", QqMailAttachFileStorageTest.page(1,
                        QqMailAttachFileStorageTest.item("f1", "a.zip", 1, "m1", false)))
                .json("/login/checksid", QqMailAttachFileStorageTest.err(-20002))
                .jsonWithCookie("/login/login", "ENCRYPTED_OK_BLOB_NOT_JSON______________",
                        "xm_sid=S3; Path=/; Domain=qq.com")
                .json("/login/checksid", ok)
                .json("/attach/attach_list", QqMailAttachFileStorageTest.page(1,
                        QqMailAttachFileStorageTest.item("f1", "a.zip", 1, "m1", false)));
        // clock: first list at t=1000 → lastOk=1000; second list at t=1000+interval+1 → checksid
        long[] t = {1000L};
        StorageConfig cfg = StorageConfig.empty().set("sid", SID).set("cookie", COOKIE)
                .set("sessionCheckIntervalMs", "5000").set("requestIntervalMs", "0")
                .set("renewDs", "0123456789abcdef0123456789abcdef")
                .set("renewBodyBase64", java.util.Base64.getEncoder().encodeToString(RENEW_BODY));
        QqMailAttachFileStorage fs = new QqMailAttachFileStorage(cfg, mock, ms -> { }, () -> t[0]);
        fs.list("/全部");
        t[0] = 1000L + 5000L + 1L;
        fs.list("/全部");
        boolean sawLogin = false;
        boolean sawCheck = false;
        for (String[] c : mock.calls) {
            if (c[1].contains("/login/login")) {
                sawLogin = true;
            }
            if (c[1].contains("/login/checksid")) {
                sawCheck = true;
            }
        }
        assertTrue(sawCheck);
        assertTrue(sawLogin);
    }
}
