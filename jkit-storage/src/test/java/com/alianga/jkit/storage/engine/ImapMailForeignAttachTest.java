package com.alianga.jkit.storage.engine;

import com.alianga.jkit.storage.StorageEntry;
import com.alianga.jkit.storage.StorageErrorCode;
import com.alianga.jkit.storage.StorageException;
import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * IMAP list：jkit 识别、Subject 含 / 不拆目录、外来邮件按附件列出（脚本化服务端应答）。
 *
 * @author 郑明亮
 */
public class ImapMailForeignAttachTest {

    private static final String FIELDS =
            "BODY[HEADER.FIELDS (SUBJECT FROM DATE MESSAGE-ID X-JKIT-SIZE X-JKIT-TOTAL-SIZE)]";
    private static final String TEXT_PART =
            "(\"TEXT\" \"PLAIN\" (\"CHARSET\" \"utf-8\") NIL NIL \"7BIT\" 5 1 NIL NIL NIL NIL)";
    private static final String JKIT_STRUCT = "(" + TEXT_PART
            + "(\"APPLICATION\" \"OCTET-STREAM\" (\"name\" \"x\") NIL NIL \"BASE64\" 8 NIL"
            + " (\"attachment\" (\"filename\" \"x\")) NIL) \"MIXED\" (\"BOUNDARY\" \"b\") NIL NIL)";
    /** 两个同名 r.pdf、RFC2231 续行中文名、RFC2047 name 的 QP 内联图。 */
    private static final String FOREIGN_STRUCT = "(" + TEXT_PART
            + "(\"APPLICATION\" \"PDF\" (\"name\" \"r.pdf\") NIL NIL \"BASE64\" 8 NIL (\"attachment\" (\"filename\" \"r.pdf\")) NIL NIL)"
            + "(\"APPLICATION\" \"PDF\" (\"name\" \"r.pdf\") NIL NIL \"BASE64\" 8 NIL (\"attachment\" (\"filename\" \"r.pdf\")) NIL NIL)"
            + "(\"APPLICATION\" \"OCTET-STREAM\" NIL NIL NIL \"BASE64\" 8 NIL (\"attachment\""
            + " (\"filename*0*\" \"utf-8''%E6%8A%A5\" \"filename*1*\" \"%E5%91%8A.docx\")) NIL NIL)"
            + "(\"IMAGE\" \"PNG\" (\"name\" \"=?UTF-8?B?5Zu+?=.png\") NIL NIL \"QUOTED-PRINTABLE\" 3 NIL NIL NIL NIL)"
            + " \"MIXED\" (\"BOUNDARY\" \"x\") NIL NIL NIL)";

    private final ByteArrayOutputStream sent = new ByteArrayOutputStream();

    private ImapMailSession session(String... serverLines) {
        StringBuilder sb = new StringBuilder();
        for (String l : serverLines) {
            sb.append(l).append("\r\n");
        }
        ImapMailSession s = new ImapMailSession();
        s.attachForTest(new ByteArrayInputStream(sb.toString().getBytes(StandardCharsets.UTF_8)),
                sent, "base", ms -> { });
        return s;
    }

    private static String fetch(String tag, long uid, String hdr, String structure) {
        int len = hdr.getBytes(StandardCharsets.UTF_8).length;
        return "* " + uid + " FETCH (UID " + uid + " INTERNALDATE \"15-Oct-2026 11:51:34 +0800\" RFC822.SIZE 100 "
                + FIELDS + " {" + len + "}\r\n" + hdr + " BODYSTRUCTURE " + structure + ")\r\n"
                + tag + " OK UID FETCH Completed";
    }

    private static final String H_JKIT_SLASH = "X-Jkit-Size: 6\r\nSubject: a/b.txt\r\n\r\n";
    private static final String H_LEGACY = "From: jkit-storage@localhost\r\nSubject: old.bin\r\n\r\n";
    private static final String H_PLAIN = "From: =?UTF-8?B?5byg5LiJ?= <z@x.com>\r\nSubject: hello\r\n"
            + "Date: Thu, 15 Oct 2026 10:00:00 +0800\r\n\r\n";
    private static final String H_FOREIGN = "From: =?UTF-8?B?5byg5LiJ?= <z@x.com>\r\n"
            + "Subject: =?UTF-8?B?5oql5ZGK?= / Q3\r\n\r\n";

    private Map<String, MailEntry> listRoot() {
        List<MailEntry> list = session(
                "A0001 OK LIST completed",
                "A0002 OK SELECT completed",
                "* SEARCH 1 2 3 4", "A0003 OK SEARCH completed",
                fetch("A0004", 1, H_JKIT_SLASH, JKIT_STRUCT),
                fetch("A0005", 2, H_LEGACY, JKIT_STRUCT),
                fetch("A0006", 3, H_PLAIN, TEXT_PART),
                fetch("A0007", 4, H_FOREIGN, FOREIGN_STRUCT)).list("");
        Map<String, MailEntry> byName = new LinkedHashMap<String, MailEntry>();
        for (MailEntry e : list) {
            byName.put(e.name(), e);
        }
        return byName;
    }

    @Test
    public void listSplitsJkitForeignAndHidesNoAttachmentMail() {
        Map<String, MailEntry> m = listRoot();
        String id2 = ImapMailSession.shortId(4, "2");
        String id3 = ImapMailSession.shortId(4, "3");
        assertEquals(m.keySet().toString(), 6, m.size());
        // Subject 含 / → 一个文件，不出现目录 a
        MailEntry slash = m.get("a\u2215b.txt");
        assertFalse(slash.directory());
        assertFalse(slash.foreign());
        assertEquals(6L, slash.size());
        assertEquals("a/b.txt", slash.attributes().get(StorageEntry.ATTR_MAIL_SUBJECT));
        assertFalse(m.containsKey("a"));
        // 遗留 jkit（无 X-Jkit-Size，From jkit-storage@localhost）仍是 jkit 文件
        assertFalse(m.get("old.bin").foreign());
        // 同名附件各自独立
        assertTrue(m.containsKey("r__" + id2 + ".pdf"));
        assertTrue(m.containsKey("r__" + id3 + ".pdf"));
        MailEntry cn = m.get("报告__" + ImapMailSession.shortId(4, "4") + ".docx");
        assertTrue(cn.foreign());
        assertEquals(6L, cn.size());
        assertTrue(m.containsKey("图__" + ImapMailSession.shortId(4, "5") + ".png"));
        Map<String, String> a = cn.attributes();
        assertEquals("报告 / Q3", a.get(StorageEntry.ATTR_MAIL_SUBJECT));
        assertEquals("张三 <z@x.com>", a.get(StorageEntry.ATTR_MAIL_FROM));
        assertEquals("2026-10-15T11:51:34+08:00", a.get(StorageEntry.ATTR_MAIL_DATE));
        assertEquals("true", a.get(StorageEntry.ATTR_MAIL_FOREIGN));
        // 无附件的普通邮件不出现
        for (String n : m.keySet()) {
            assertFalse(n, n.startsWith("hello"));
        }
        // 每封邮件只一次 FETCH（头 + BODYSTRUCTURE 同一往返）
        String cmds = new String(sent.toByteArray(), StandardCharsets.UTF_8);
        assertEquals(4, cmds.split("UID FETCH", -1).length - 1);
    }

    @Test
    public void downloadForeignAttachmentDecodesBase64() {
        String name = "r__" + ImapMailSession.shortId(4, "3") + ".pdf";
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        session("A0001 OK SELECT completed",
                "* SEARCH 4", "A0002 OK SEARCH completed",
                fetch("A0003", 4, H_FOREIGN, FOREIGN_STRUCT),
                "* 1 FETCH (UID 4 BODY[3] {8}\r\naGVsbG8h)", "A0004 OK FETCH completed").download(name, out);
        assertArrayEquals("hello!".getBytes(StandardCharsets.UTF_8), out.toByteArray());
        assertTrue(new String(sent.toByteArray(), StandardCharsets.UTF_8).contains("BODY.PEEK[3]"));
    }

    @Test
    public void statForeignCarriesAttributes() {
        String name = "报告__" + ImapMailSession.shortId(4, "4") + ".docx";
        MailEntry e = session("A0001 OK LIST completed", "A0002 NO no such folder",
                "A0003 OK SELECT completed",
                "* SEARCH 4", "A0004 OK SEARCH completed",
                fetch("A0005", 4, H_FOREIGN, FOREIGN_STRUCT)).stat(name);
        assertEquals(name, e.name());
        assertTrue(e.foreign());
        assertEquals("张三 <z@x.com>", e.attributes().get(StorageEntry.ATTR_MAIL_FROM));
    }

    @Test
    public void deleteForeignUnsupported() {
        String name = "r__" + ImapMailSession.shortId(4, "2") + ".pdf";
        try {
            session("A0001 OK SELECT completed",
                    "* SEARCH 4", "A0002 OK SEARCH completed",
                    fetch("A0003", 4, H_FOREIGN, FOREIGN_STRUCT)).delete(name);
            fail("应 UNSUPPORTED");
        } catch (StorageException e) {
            assertEquals(StorageErrorCode.UNSUPPORTED, e.code());
        }
        assertFalse(new String(sent.toByteArray(), StandardCharsets.UTF_8).contains("STORE"));
    }

    @Test
    public void jkitDetection() {
        assertTrue(ImapMailSession.isJkitMail("x-jkit-total-size: 9\r\n"));
        assertTrue(ImapMailSession.isJkitMail("Message-ID: <0a1b@jkit-storage>\r\n"));
        assertTrue(ImapMailSession.isJkitMail(H_LEGACY));
        assertFalse(ImapMailSession.isJkitMail(H_PLAIN));
        assertFalse(ImapMailSession.isJkitMail(null));
    }

    @Test
    public void rfc2231AndRfc2047Params() {
        Map<String, String> p = new LinkedHashMap<String, String>();
        p.put("filename*", "UTF-8''%E6%8A%A5%E5%91%8A%20v2.pdf");
        assertEquals("报告 v2.pdf", ImapMailSession.decodeRfc2231Param(p, "filename"));
        p.clear();
        p.put("filename*0*", "gbk''%B1%A8");
        p.put("filename*1", ".txt");
        assertEquals("报.txt", ImapMailSession.decodeRfc2231Param(p, "filename"));
        p.clear();
        p.put("name", "=?UTF-8?B?5oql5ZGK?=.pdf");
        assertEquals("报告.pdf", ImapMailSession.decodeRfc2231Param(p, "name"));
        assertNull(ImapMailSession.decodeRfc2231Param(new LinkedHashMap<String, String>(), "name"));
    }

    @Test
    public void attachmentPartsSectionsAndDisplayNames() {
        List<ImapMailSession.AttachmentPart> parts = ImapMailSession.attachmentParts("BODYSTRUCTURE " + FOREIGN_STRUCT);
        List<String> secs = new ArrayList<String>();
        for (ImapMailSession.AttachmentPart p : parts) {
            secs.add(p.section);
        }
        assertEquals("[2, 3, 4, 5]", secs.toString());
        assertTrue(ImapMailSession.attachmentParts(TEXT_PART).isEmpty());
        assertEquals("a_b__" + ImapMailSession.shortId(1, "2"), ImapMailSession.foreignDisplayName("a/b", 1, "2"));
        assertEquals(8, ImapMailSession.shortId(7, "1.2").length());
    }

    @Test
    public void transferDecodingAndDates() {
        assertArrayEquals("a=b\u00e9".getBytes(StandardCharsets.ISO_8859_1),
                ImapMailSession.decodeTransfer("a=3Db=\r\n=E9".getBytes(StandardCharsets.US_ASCII), "QUOTED-PRINTABLE"));
        assertArrayEquals("x".getBytes(StandardCharsets.US_ASCII),
                ImapMailSession.decodeTransfer("x".getBytes(StandardCharsets.US_ASCII), "7BIT"));
        assertEquals("2026-10-15T10:00:00+08:00",
                ImapMailSession.mailDateIso("Thu, 15 Oct 2026 10:00:00 +0800 (CST)", null));
        assertEquals("2026-10-05T01:02:03Z",
                ImapMailSession.mailDateIso(null, " 5-Oct-2026 01:02:03 +0000"));
    }
}
