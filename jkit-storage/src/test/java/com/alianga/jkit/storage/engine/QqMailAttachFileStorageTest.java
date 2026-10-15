package com.alianga.jkit.storage.engine;

import com.alianga.jkit.storage.FileStorage;
import com.alianga.jkit.storage.FileStorages;
import com.alianga.jkit.storage.StorageConfig;
import com.alianga.jkit.storage.StorageEntry;
import com.alianga.jkit.storage.StorageErrorCode;
import com.alianga.jkit.storage.StorageException;
import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * qqmail-attach 只读引擎：Mock HTTP，不访问真实 QQ。
 *
 * @author 郑明亮
 */
public class QqMailAttachFileStorageTest {
    private static final String SID = "SIDSECRET123";
    private static final String COOKIE = "qm_sk=COOKIESECRET; wx_sid=W1";
    private static final String BIG_FILEID = "{\"key\":\"KEYSECRET\",\"code\":\"CODESECRET\"}";

    /** 记录请求；按 (方法 + 路径前缀) 依次弹出预置响应。 */
    static final class Mock implements QqMailTransport {
        final List<String[]> calls = new ArrayList<String[]>();
        final List<Map<String, String>> headers = new ArrayList<Map<String, String>>();
        private final Map<String, Deque<QqMailTransport.Response>> routes =
                new LinkedHashMap<String, Deque<QqMailTransport.Response>>();

        Mock json(String path, String body) {
            return add("POST " + QqMailWebClient.BASE_URL + path, resp(200, body, null));
        }

        Mock jsonWithCookie(String path, String body, String setCookie) {
            Map<String, List<String>> h = new LinkedHashMap<String, List<String>>();
            h.put("Set-Cookie", Collections.singletonList(setCookie));
            return add("POST " + QqMailWebClient.BASE_URL + path,
                    new QqMailTransport.Response(200, h, new ByteArrayInputStream(bytes(body)), null));
        }

        Mock redirect(String urlPrefix, String location) {
            Map<String, List<String>> h = new LinkedHashMap<String, List<String>>();
            h.put("Location", Collections.singletonList(location));
            return add("GET " + urlPrefix, new QqMailTransport.Response(302, h, null, null));
        }

        Mock file(String urlPrefix, byte[] data, String type, String disposition) {
            Map<String, List<String>> h = new LinkedHashMap<String, List<String>>();
            h.put("Content-Type", Collections.singletonList(type));
            h.put("Content-Length", Collections.singletonList(String.valueOf(data.length)));
            if (disposition != null) {
                h.put("Content-Disposition", Collections.singletonList(disposition));
            }
            return add("GET " + urlPrefix, new QqMailTransport.Response(200, h, new ByteArrayInputStream(data), null));
        }

        Mock add(String key, QqMailTransport.Response r) {
            Deque<QqMailTransport.Response> q = routes.get(key);
            if (q == null) {
                q = new ArrayDeque<QqMailTransport.Response>();
                routes.put(key, q);
            }
            q.add(r);
            return this;
        }

        static QqMailTransport.Response resp(int status, String body, Map<String, List<String>> h) {
            return new QqMailTransport.Response(status, h, new ByteArrayInputStream(bytes(body)), null);
        }

        @Override
        public Response exchange(String method, String url, Map<String, String> h, byte[] body) {
            calls.add(new String[]{method, url, body == null ? "" : new String(body, StandardCharsets.UTF_8)});
            headers.add(new LinkedHashMap<String, String>(h));
            String best = null;
            for (String k : routes.keySet()) {
                if ((method + " " + url).startsWith(k) && !routes.get(k).isEmpty()
                        && (best == null || k.length() > best.length())) {
                    best = k;
                }
            }
            if (best == null) {
                throw new AssertionError("无预置响应: " + method + " " + url);
            }
            return routes.get(best).poll();
        }

        String body(int i) {
            return decode(calls.get(i)[2]);
        }
    }

    final List<Long> sleeps = new ArrayList<Long>();

    private QqMailAttachFileStorage open(Mock mock, String... kv) {
        StorageConfig cfg = StorageConfig.empty().set("sid", SID).set("cookie", COOKIE)
                .set("requestIntervalMs", "300");
        for (int i = 0; i + 1 < kv.length; i += 2) {
            cfg = cfg.set(kv[i], kv[i + 1]);
        }
        return new QqMailAttachFileStorage(cfg, mock, sleeps::add, () -> 1000L);
    }

    static byte[] bytes(String s) {
        return s == null ? new byte[0] : s.getBytes(StandardCharsets.UTF_8);
    }

    static String decode(String s) {
        try {
            return URLDecoder.decode(s, "UTF-8");
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    static String item(String fileid, String name, long size, String mailid, boolean collect) {
        return "{\"fileid\":" + jsonQuote(fileid) + ",\"name\":" + jsonQuote(name) + ",\"size\":" + size
                + ",\"md5\":\"m" + size + "\",\"mtime\":1760000000,\"mailid\":\"" + mailid
                + "\",\"is_collect\":" + collect + "}";
    }

    static String bigItem(String name, long size, String mailid) {
        return "{\"fileid\":" + jsonQuote(BIG_FILEID) + ",\"name\":\"" + name + "\",\"size\":" + size
                + ",\"mailid\":\"" + mailid + "\",\"big_attach_key\":\"KEYSECRET\","
                + "\"big_attach_code\":\"CODESECRET\",\"etime\":1762592000}";
    }

    static String jsonQuote(String s) {
        return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    static String page(int total, String... items) {
        return "{\"head\":{\"ret\":0,\"msg\":\"\",\"cgi\":\"attach_list\",\"time\":1760500000},"
                + "\"body\":{\"attach_list\":{\"attach\":[" + String.join(",", items) + "]},\"total\":" + total
                + ",\"file_total\":" + total + ",\"mail_total\":1},\"param\":{}}";
    }

    static String err(long ret) {
        return "{\"head\":{\"ret\":" + ret + ",\"msg\":\"fail\",\"show_err_msg\":\"出错了\","
                + "\"stack\":\"at secret.Stack(SIDSECRET123)\"},\"body\":{}}";
    }

    static String ok() {
        return "{\"head\":{\"ret\":0},\"body\":{}}";
    }

    private static String pathOf(String dir, String name, String mailid, String fileid) {
        String sid8 = QqMailAttachFileStorage.shortId(mailid, fileid);
        int dot = name.lastIndexOf('.');
        return dot <= 0 ? dir + "/" + name + "__" + sid8
                : dir + "/" + name.substring(0, dot) + "__" + sid8 + name.substring(dot);
    }

    @Test
    public void rootListsVirtualDirsAndEngineRegistered() {
        Mock mock = new Mock();
        List<StorageEntry> root = open(mock).list("/");
        List<String> names = new ArrayList<String>();
        for (StorageEntry e : root) {
            assertTrue(e.isDirectory());
            names.add(e.path());
        }
        assertEquals(Arrays.asList("全部", "图片", "文档", "音频", "视频", "压缩包", "其他", "收藏"), names);
        assertTrue(mock.calls.isEmpty());
        assertEquals("qqmail-attach", FileStorages.platform("qq-mail-attach").engine());
    }

    @Test
    public void listPerTypePaginatesFullyWithThrottle() {
        Mock mock = new Mock()
                .json("/attach/attach_list", page(3, item("f1", "a.png", 10, "m1", false),
                        item("f2", "b.png", 20, "m1", false)))
                .json("/attach/attach_list", page(3, item("f3", "c.png", 30, "m2", false)));
        List<StorageEntry> list = open(mock, "pageSize", "2").list("/图片");
        assertEquals(3, list.size());
        assertEquals(2, mock.calls.size());
        assertTrue(mock.body(0).contains("page_now=0"));
        assertTrue(mock.body(1).contains("page_now=1"));
        assertTrue(mock.body(0).contains("attach_type=2"));
        assertTrue(mock.body(0).contains("sort_type=1") && mock.body(0).contains("sort_direction=1"));
        assertTrue(mock.body(0).startsWith("sid=" + SID));
        assertEquals(Collections.singletonList(300L), sleeps);
        StorageEntry first = list.get(0);
        assertEquals(10L, first.size());
        assertEquals(1760000000000L, first.lastModified());
        assertEquals("POST", mock.calls.get(0)[0]);
        assertEquals(QqMailWebClient.REFERER, mock.headers.get(0).get("Referer"));
        assertEquals(QqMailWebClient.BASE_URL, mock.headers.get(0).get("Origin"));
    }

    @Test
    public void missingAttachListStructureThrowsFormatError() {
        String[] bad = {
            "{\"ret\":0,\"body\":{\"attach_list\":{\"attach\":[]}}}",
            "{\"head\":{},\"body\":{\"attach_list\":{\"attach\":[]}}}",
            "{\"head\":{\"ret\":0}}",
            "{\"head\":{\"ret\":0},\"body\":{}}",
            "{\"head\":{\"ret\":0},\"body\":{\"attach_list\":{\"attach\":{}}}}",
            "{\"head\":{\"ret\":0},\"body\":{\"attach_list\":[]}}"
        };
        for (String b : bad) {
            Mock mock = new Mock().json("/attach/attach_list", b).json("/attach/search", b);
            QqMailAttachFileStorage fs = open(mock);
            try {
                fs.list("/全部");
                fail("应抛格式错误: " + b);
            } catch (StorageException e) {
                assertEquals(StorageErrorCode.IO_ERROR, e.code());
                assertEquals("unexpected response format: /attach/attach_list", e.getMessage());
            }
            try {
                new QqMailAttachFileStorage(StorageConfig.empty().set("sid", SID),
                        new Mock().json("/attach/set_collect", b), sleeps::add, () -> 1000L)
                        .call("/attach/set_collect", new LinkedHashMap<String, String>());
                if (!b.contains("\"ret\":0}") || b.startsWith("{\"ret")) {
                    fail("缺 head.ret 应抛: " + b);
                }
            } catch (StorageException e) {
                assertEquals(StorageErrorCode.IO_ERROR, e.code());
            }
            try {
                fs.stat(pathOf("全部", "a.txt", "m1", "f1"));
                fail("search 也应抛格式错误");
            } catch (StorageException e) {
                assertEquals(StorageErrorCode.IO_ERROR, e.code());
                assertTrue(e.getMessage().contains("/attach/search"));
            }
        }
    }

    @Test
    public void emptySearchAttachListObjectIsEmptyNotError() {
        Mock mock = new Mock().json("/attach/search",
                "{\"head\":{\"ret\":0},\"body\":{\"attach_list\":{},\"total\":0},\"param\":{}}");
        QqMailAttachFileStorage fs = open(mock);
        assertFalse(fs.exists(pathOf("全部", "nothing.txt", "m1", "f1")));
        assertEquals(1, mock.calls.size());
    }

    @Test
    public void favoritesUseFunc2() {
        Mock mock = new Mock().json("/attach/attach_list", page(1, item("f1", "fav.doc", 5, "m1", true)));
        List<StorageEntry> list = open(mock).list("/收藏");
        assertEquals(1, list.size());
        assertTrue(mock.body(0).contains("func=2"));
        assertTrue(mock.body(0).contains("attach_type=1"));
    }

    @Test
    public void namingDistinguishesDuplicatesAndHandlesNoExtension() {
        Mock mock = new Mock().json("/attach/attach_list", page(3,
                item("f1", "dup.txt", 1, "m1", false), item("f2", "dup.txt", 2, "m2", false),
                item("f3", "README", 3, "m3", false)));
        List<StorageEntry> list = open(mock).list("/全部");
        String p1 = list.get(0).path();
        String p2 = list.get(1).path();
        assertNotEquals(p1, p2);
        assertEquals(pathOf("全部", "dup.txt", "m1", "f1"), p1);
        assertTrue(p1.matches("全部/dup__[0-9a-f]{8}\\.txt"));
        assertTrue(list.get(2).path().matches("全部/README__[0-9a-f]{8}"));
    }

    @Test
    public void statAndDownloadViaIndexFollowRedirect() {
        byte[] data = bytes("hello attachment");
        Mock mock = new Mock().json("/attach/attach_list", page(1, item("f1", "a.txt", data.length, "m1", false)))
                .redirect(QqMailWebClient.BASE_URL + "/attach/download?",
                        "https://wx.mail.qq.com/download/download?target=TARGETSECRET&fname=a.txt")
                .file("https://wx.mail.qq.com/download/download", data, "application/octet-stream", null);
        QqMailAttachFileStorage fs = open(mock);
        String path = fs.list("/文档").get(0).path();
        assertEquals(data.length, fs.stat(path).size());
        assertArrayEquals(data, fs.readBytes(path));
        String first = mock.calls.get(1)[1];
        assertTrue(first.startsWith(QqMailWebClient.BASE_URL + "/attach/download?mailid=m1&fileid=f1&name=a.txt&sid="));
        assertTrue(mock.headers.get(2).get("Cookie").contains("qm_sk=COOKIESECRET"));
        assertEquals(QqMailWebClient.REFERER, mock.headers.get(2).get("Referer"));
    }

    @Test
    public void coldStartStatRebuildsIndexViaSearch() {
        Mock mock = new Mock().json("/attach/search", page(2, item("f9", "冷启动.pdf", 99, "m9", false),
                item("f8", "冷启动.pdf", 88, "m8", false)));
        QqMailAttachFileStorage fs = open(mock);
        StorageEntry e = fs.stat(pathOf("文档", "冷启动.pdf", "m9", "f9"));
        assertEquals(99L, e.size());
        assertTrue(mock.body(0).contains("key=冷启动.pdf"));
        assertTrue(mock.body(0).contains("attach_type=3"));
        mock.json("/attach/search", page(0));
        assertFalse(fs.exists(pathOf("文档", "冷启动.pdf", "m0", "nope")));
    }

    @Test
    public void writeOpsUnsupported() {
        FileStorage fs = open(new Mock());
        for (Runnable r : Arrays.<Runnable>asList(
                () -> fs.upload("全部/a.txt", new byte[1]), () -> fs.delete("全部/a.txt"),
                () -> fs.mkdir("x"), () -> fs.rename("a", "b"), () -> fs.copy("a", "b"))) {
            try {
                r.run();
                fail();
            } catch (StorageException e) {
                assertEquals(StorageErrorCode.UNSUPPORTED, e.code());
            }
        }
    }

    @Test
    public void retMappingAuthAndUnknown() {
        Mock mock = new Mock().json("/login/checksid", err(-101))
                .json("/attach/attach_list", err(12345))
                .json("/attach/search", err(-21619));
        QqMailAttachFileStorage fs = open(mock);
        try {
            fs.checkSid();
            fail();
        } catch (StorageException e) {
            assertEquals(StorageErrorCode.AUTH_FAILED, e.code());
            assertTrue(e.getMessage().contains("扫码"));
        }
        try {
            fs.list("/全部");
            fail();
        } catch (StorageException e) {
            assertEquals(StorageErrorCode.IO_ERROR, e.code());
            assertTrue(e.getMessage().contains("ret=12345"));
            assertFalse(e.getMessage().contains(SID));
            assertFalse(e.getMessage().contains("stack") || e.getMessage().contains("出错了"));
        }
        try {
            fs.stat(pathOf("全部", "a.txt", "m1", "f1"));
            fail();
        } catch (StorageException e) {
            assertEquals(StorageErrorCode.UNSUPPORTED, e.code());
            assertTrue(e.getMessage().contains("-21619"));
        }
        assertEquals(3, mock.calls.size()); // 鉴权失败不自动重试
    }

    @Test
    public void setCollectRequestBody() {
        Mock mock = new Mock().json("/attach/attach_list", page(1, item("f1", "a\"b.txt", 1, "m1", false)))
                .json("/attach/set_collect", ok());
        QqMailAttachFileStorage fs = open(mock);
        String path = fs.list("/全部").get(0).path();
        fs.setCollect(path, true);
        String body = mock.body(1);
        assertTrue(body.contains("is_collect=true"));
        assertTrue(body.contains("filter={\"fileid\":\"f1\",\"name\":\"a\\\"b.txt\",\"mailid\":\"m1\"}"));
        assertTrue(fs.requireItem(path).collect());
    }

    @Test
    public void bigAttachmentUsesSameGetAndFtnWithoutLeakingSecrets() {
        byte[] data = bytes("BIGDATA");
        Mock mock = new Mock().json("/attach/attach_list", page(1, bigItem("huge.zip", data.length, "mb")))
                .redirect(QqMailWebClient.BASE_URL + "/attach/download?",
                        "https://cqc-download.ftn.qq.com/ftn_handler/HEXSECRETabcdef?fname=huge.zip")
                .file("https://cqc-download.ftn.qq.com/ftn_handler/", data, "application/octet-stream", null);
        QqMailAttachFileStorage fs = open(mock);
        StorageEntry e = fs.list("/全部").get(0);
        assertFalse(e.path().contains("KEYSECRET") || e.path().contains("CODESECRET"));
        assertFalse(fs.requireItem(e.path()).toString().contains("SECRET"));
        assertFalse(fs.toString().contains("SECRET"));
        assertArrayEquals(data, fs.readBytes(e.path()));
        String url = mock.calls.get(1)[1];
        assertTrue(decode(url).contains("fileid=" + BIG_FILEID));
        assertFalse(url.contains("/ftn/download"));
        assertTrue(mock.headers.get(2).get("Cookie").contains("COOKIESECRET")); // ftn.qq.com 也需 cookie
    }

    @Test
    public void redirectToNonQqHostGetsNoCookie() {
        Mock mock = new Mock().json("/attach/attach_list", page(1, item("f1", "a.txt", 3, "m1", false)))
                .redirect(QqMailWebClient.BASE_URL + "/attach/download?", "https://evil.example.com/x?target=T")
                .file("https://evil.example.com/x", bytes("abc"), "application/octet-stream", null);
        QqMailAttachFileStorage fs = open(mock);
        fs.readBytes(fs.list("/全部").get(0).path());
        assertTrue(mock.headers.get(1).containsKey("Cookie"));
        assertNull(mock.headers.get(2).get("Cookie"));
    }

    @Test
    public void tooManyRedirectsFailsWithoutLeakingUrl() {
        Mock mock = new Mock().json("/attach/attach_list", page(1, item("f1", "a.txt", 3, "m1", false)));
        for (int i = 0; i < 7; i++) {
            mock.redirect("https://wx.mail.qq.com/", "https://wx.mail.qq.com/download/download?target=TARGETSECRET" + i);
        }
        QqMailAttachFileStorage fs = open(mock);
        try {
            fs.readBytes(fs.list("/全部").get(0).path());
            fail();
        } catch (StorageException e) {
            assertTrue(e.getMessage().contains("重定向次数"));
            assertFalse(e.getMessage().contains("TARGETSECRET"));
            assertFalse(e.getMessage().contains(SID));
        }
        assertEquals(1 + 1 + QqMailWebClient.MAX_REDIRECTS, mock.calls.size());
    }

    @Test
    public void contentDispositionFilenameStarPreferred() {
        assertEquals("报告 v2.pdf", QqMailWebClient.filenameFromDisposition(
                "attachment; filename=\"fallback.pdf\"; filename*=UTF-8''%E6%8A%A5%E5%91%8A%20v2.pdf"));
        assertEquals("plain.txt", QqMailWebClient.filenameFromDisposition("attachment; filename=\"plain.txt\""));
        assertEquals("bare.txt", QqMailWebClient.filenameFromDisposition("attachment; filename=bare.txt"));
        assertNull(QqMailWebClient.filenameFromDisposition(null));
    }

    @Test
    public void cookieUpdateListenerOnSetCookie() {
        Mock mock = new Mock().jsonWithCookie("/attach/attach_list", page(0),
                "qm_sk=NEWVALUE; Domain=.qq.com; Path=/; HttpOnly");
        QqMailAttachFileStorage fs = open(mock);
        List<String> updates = new ArrayList<String>();
        fs.setCookieUpdateListener(updates::add);
        fs.list("/全部");
        assertEquals(1, updates.size());
        assertTrue(updates.get(0).contains("qm_sk=NEWVALUE"));
        assertTrue(updates.get(0).contains("wx_sid=W1"));
        assertFalse(updates.get(0).contains("COOKIESECRET"));
    }

    @Test
    public void foreignSetCookieIgnored() {
        QqMailCookieJar jar = new QqMailCookieJar("a=1");
        jar.absorb("evil.example.com", Collections.singletonList("a=2"));
        jar.absorb("wx.mail.qq.com", Collections.singletonList("b=3; Domain=example.com"));
        assertEquals("a=1", jar.snapshot());
        assertNull(jar.headerFor("evil.example.com"));
        assertFalse(jar.toString().contains("a=1"));
    }

    @Test
    public void previewReturnsStreamWithTypeAndFilename() throws IOException {
        Mock mock = new Mock().json("/attach/attach_list", page(1, item("f1", "pic.png", 4, "m1", false)))
                .redirect(QqMailWebClient.BASE_URL + "/attach/preview?",
                        "https://wx.mail.qq.com/download/download?target=T&fname=pic.png")
                .file("https://wx.mail.qq.com/download/download", bytes("PNG!"), "image/png",
                        "attachment; filename*=UTF-8''pic.png")
                .redirect(QqMailWebClient.BASE_URL + "/attach/thumbnail?",
                        "https://wx.mail.qq.com/download/download?target=T&fname=pic.png&pictype=scale&size=200")
                .file("https://wx.mail.qq.com/download/download", bytes("TH"), "image/jpeg", null);
        QqMailAttachFileStorage fs = open(mock);
        String path = fs.list("/图片").get(0).path();
        try (QqMailAttachStream s = fs.openPreview(path)) {
            assertEquals("image/png", s.contentType());
            assertEquals("pic.png", s.filename());
            assertEquals(4L, s.contentLength());
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[16];
            int n;
            while ((n = s.read(buf)) >= 0) {
                out.write(buf, 0, n);
            }
            assertEquals("PNG!", new String(out.toByteArray(), StandardCharsets.UTF_8));
        }
        try (QqMailAttachStream t = fs.openThumbnail(path)) {
            assertEquals("image/jpeg", t.contentType());
        }
        assertTrue(mock.calls.get(1)[1].startsWith(QqMailWebClient.BASE_URL + "/attach/preview?mailid=m1&fileid=f1"));
    }

    @Test
    public void imapFallbackMatchingAndUse() {
        Mock mock = new Mock().json("/attach/attach_list", page(1, item("f1", "a.txt", 5, "m1", false)))
                .add("GET " + QqMailWebClient.BASE_URL + "/attach/download?", Mock.resp(500, "", null));
        QqMailAttachFileStorage fs = open(mock);
        String path = fs.list("/全部").get(0).path();
        final QqMailAttachItem item = fs.requireItem(path);
        List<AttachImapFallback.Candidate> cands = Arrays.asList(
                new AttachImapFallback.Candidate("a.txt", 4, null, "wrong-size"),
                new AttachImapFallback.Candidate("A.TXT", 5, "M5", "hit"),
                new AttachImapFallback.Candidate("a.txt", 5, "other", "md5-mismatch"));
        assertEquals("hit", AttachImapFallback.match(item, cands).handle());
        try {
            AttachImapFallback.match(item, Collections.singletonList(
                    new AttachImapFallback.Candidate("a.txt", 6, null, "x")));
            fail();
        } catch (StorageException e) {
            assertEquals(StorageErrorCode.NOT_FOUND, e.code());
        }
        fs.setImapFallback((it, out) -> {
            try {
                out.write(bytes("IMAP!"));
            } catch (IOException e) {
                throw new IllegalStateException(e);
            }
        });
        assertEquals("IMAP!", new String(fs.readBytes(path), StandardCharsets.UTF_8));
    }

    @Test
    public void bigAttachmentNeverFallsBackToImap() {
        Mock mock = new Mock().json("/attach/attach_list", page(1, bigItem("huge.zip", 9, "mb")));
        QqMailAttachFileStorage fs = open(mock);
        QqMailAttachItem big = fs.requireItem(fs.list("/全部").get(0).path());
        try {
            AttachImapFallback.match(big, Collections.<AttachImapFallback.Candidate>emptyList());
            fail();
        } catch (StorageException e) {
            assertEquals(StorageErrorCode.UNSUPPORTED, e.code());
            assertFalse(e.getMessage().contains("SECRET"));
        }
    }
}
