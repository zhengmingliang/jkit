package com.alianga.jkit.storage.engine;

import com.alianga.jkit.storage.StorageConfig;
import com.alianga.jkit.storage.StorageErrorCode;
import com.alianga.jkit.storage.StorageException;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * qqmail-attach 请求形态（对齐网页 HAR）：表单字段顺序、func、头、checksid 查询串、-20002、调试日志、Cookie 清除。
 *
 * @author 郑明亮
 */
public class QqMailWebRequestShapeTest {
    private static final String SID = "SIDSECRET123";
    private static final String COOKIE = "qm_sk=COOKIESECRET; xm_fld_key=FLDSECRET";

    private QqMailAttachFileStorage open(QqMailAttachFileStorageTest.Mock mock) {
        StorageConfig cfg = StorageConfig.empty().set("sid", SID).set("cookie", COOKIE)
                .set("requestIntervalMs", "0");
        return new QqMailAttachFileStorage(cfg, mock, ms -> { }, () -> 1760500000123L);
    }

    private static List<String> names(String body) {
        List<String> out = new ArrayList<String>();
        for (String kv : body.split("&")) {
            out.add(kv.substring(0, kv.indexOf('=')));
        }
        return out;
    }

    @Test
    public void attachListFormOrderFuncOneNoQuery() {
        QqMailAttachFileStorageTest.Mock mock = new QqMailAttachFileStorageTest.Mock()
                .json("/attach/attach_list", QqMailAttachFileStorageTest.page(0));
        open(mock).list("/文档");
        String[] call = mock.calls.get(0);
        assertEquals(QqMailWebClient.BASE_URL + "/attach/attach_list", call[1]);
        assertEquals(Arrays.asList("page_size", "page_now", "sort_type", "func", "attach_type",
                "sort_direction", "language", "r", "sid"), names(call[2]));
        String body = mock.body(0);
        assertTrue(body.contains("func=1&"));
        assertTrue(body.contains("language=en&r=1760500000123&sid=" + SID));
    }

    @Test
    public void favoritesUseFuncTwo() {
        QqMailAttachFileStorageTest.Mock mock = new QqMailAttachFileStorageTest.Mock()
                .json("/attach/attach_list", QqMailAttachFileStorageTest.page(0));
        open(mock).list("/收藏");
        assertTrue(mock.body(0).contains("func=2&"));
        assertFalse(mock.body(0).contains("func=1"));
    }

    @Test
    public void browserHeadersPresentNoCustomX() {
        QqMailAttachFileStorageTest.Mock mock = new QqMailAttachFileStorageTest.Mock()
                .json("/attach/attach_list", QqMailAttachFileStorageTest.page(0));
        open(mock).list("/全部");
        Map<String, String> h = mock.headers.get(0);
        assertEquals("https://wx.mail.qq.com", h.get("Origin"));
        assertEquals("https://wx.mail.qq.com/", h.get("Referer"));
        assertEquals("same-origin", h.get("Sec-Fetch-Site"));
        assertEquals("cors", h.get("Sec-Fetch-Mode"));
        assertTrue(h.get("User-Agent").contains("Chrome/"));
        for (String k : h.keySet()) {
            assertFalse(k, k.toLowerCase().startsWith("x-"));
        }
    }

    @Test
    public void checkSidParamsInUrl() {
        QqMailAttachFileStorageTest.Mock mock = new QqMailAttachFileStorageTest.Mock()
                .json("/login/checksid", QqMailAttachFileStorageTest.ok());
        open(mock).checkSid();
        String[] call = mock.calls.get(0);
        assertEquals("POST", call[0]);
        assertEquals(QqMailWebClient.BASE_URL + "/login/checksid?language=en&r=1760500000123&sid=" + SID, call[1]);
        assertEquals("", call[2]);
    }

    @Test
    public void ret20002IsAuthFailedWithPathOnly() {
        QqMailAttachFileStorageTest.Mock mock = new QqMailAttachFileStorageTest.Mock()
                .json("/attach/attach_list", QqMailAttachFileStorageTest.err(-20002));
        try {
            open(mock).list("/全部");
            fail("应 AUTH_FAILED");
        } catch (StorageException e) {
            assertEquals(StorageErrorCode.AUTH_FAILED, e.code());
            assertTrue(e.getMessage(), e.getMessage().contains("登录态无效或 cookie 不完整"));
            assertTrue(e.getMessage().contains("/attach/attach_list"));
            assertFalse(e.getMessage().contains(SID));
            assertFalse(e.getMessage().contains("COOKIESECRET"));
        }
    }

    @Test
    public void debugLogHasNamesNoValues() {
        Logger log = Logger.getLogger(QqMailWebClient.class.getName());
        final List<String> lines = new ArrayList<String>();
        Handler h = new Handler() {
            @Override
            public void publish(LogRecord r) {
                lines.add(r.getMessage());
            }

            @Override
            public void flush() {
            }

            @Override
            public void close() {
            }
        };
        Level old = log.getLevel();
        log.setLevel(Level.FINE);
        h.setLevel(Level.FINE);
        log.addHandler(h);
        try {
            QqMailAttachFileStorageTest.Mock mock = new QqMailAttachFileStorageTest.Mock()
                    .json("/login/checksid", QqMailAttachFileStorageTest.ok())
                    .json("/attach/attach_list", QqMailAttachFileStorageTest.page(0));
            QqMailAttachFileStorage fs = open(mock);
            fs.checkSid();
            fs.list("/全部");
        } finally {
            log.removeHandler(h);
            log.setLevel(old);
        }
        assertEquals(2, lines.size());
        String all = String.join("\n", lines);
        assertTrue(all, all.contains("/attach/attach_list") && all.contains("sid") && all.contains("Cookie"));
        for (String secret : new String[]{SID, "COOKIESECRET", "FLDSECRET", "1760500000123", "Chrome/"}) {
            assertFalse(secret, all.contains(secret));
        }
    }

    @Test
    public void setCookieClearingRemovesFromJar() {
        QqMailCookieJar jar = new QqMailCookieJar(COOKIE);
        jar.absorb("wx.mail.qq.com", Collections.singletonList(
                "xm_fld_key=; Domain=.qq.com; Path=/; Expires=Thu, 01 Jan 1970 00:00:01 GMT"));
        assertFalse(jar.snapshot().contains("xm_fld_key"));
        assertTrue(jar.snapshot().contains("qm_sk=COOKIESECRET"));
        jar.absorb("wx.mail.qq.com", Collections.singletonList(
                "qm_sk=OLD; Domain=.qq.com; Expires=Thu, 01 Jan 1970 00:00:01 GMT"));
        assertFalse(jar.snapshot().contains("qm_sk"));
    }
}
