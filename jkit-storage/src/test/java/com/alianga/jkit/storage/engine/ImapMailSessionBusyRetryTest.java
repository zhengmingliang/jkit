package com.alianga.jkit.storage.engine;

import com.alianga.jkit.storage.StorageException;
import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * IMAP NO busy 有界重试：脚本化服务端应答，注入休眠不真睡。
 *
 * @author 郑明亮
 */
public class ImapMailSessionBusyRetryTest {

    private final List<Long> sleeps = new ArrayList<Long>();
    private final ByteArrayOutputStream sent = new ByteArrayOutputStream();

    private ImapMailSession session(String... serverLines) {
        StringBuilder sb = new StringBuilder();
        for (String l : serverLines) {
            sb.append(l).append("\r\n");
        }
        ImapMailSession s = new ImapMailSession();
        s.attachForTest(new ByteArrayInputStream(sb.toString().getBytes(StandardCharsets.UTF_8)),
                sent, "base", sleeps::add);
        return s;
    }

    private int count(String needle) {
        String all = new String(sent.toByteArray(), StandardCharsets.UTF_8);
        int n = 0;
        for (int i = all.indexOf(needle); i >= 0; i = all.indexOf(needle, i + 1)) {
            n++;
        }
        return n;
    }

    /** FETCH 应答：BODYSTRUCTURE 附件 BASE64 段长 + X-Jkit-Size 头字面量（末尾留给 ")" 行）。 */
    static String fetchSize(long uid, long xSize, long b64) {
        String hdr = "X-Jkit-Size: " + xSize + "\r\n\r\n";
        return "* 1 FETCH (UID " + uid + " BODYSTRUCTURE ((\"TEXT\" \"PLAIN\" NIL NIL NIL \"BASE64\" 40 1)"
                + "(\"APPLICATION\" \"OCTET-STREAM\" NIL NIL NIL \"BASE64\" " + b64 + ") \"MIXED\")"
                + " BODY[HEADER.FIELDS (X-JKIT-SIZE)] {" + hdr.length() + "}\r\n" + hdr + ")";
    }

    @Test
    public void busyThenSuccessRetries() {
        session("A0001 NO no such mailbox",
                "A0002 NO System busy",
                "A0003 OK LIST done",          // 检查：未建成
                "A0004 OK CREATE done",
                "A0005 OK SELECT done").mkdir("x");
        assertEquals(2, count(" CREATE "));
        assertEquals(Arrays.asList(5000L), sleeps);
    }

    @Test
    public void busyExhaustedThrows() {
        ImapMailSession s = session("A0001 NO no such mailbox",
                "A0002 NO System busy", "A0003 OK",
                "A0004 NO System BUSY", "A0005 OK",
                "A0006 NO system busy", "A0007 OK",
                "A0008 NO System busy");
        try {
            s.mkdir("x");
            fail("busy 耗尽应抛");
        } catch (StorageException e) {
            assertTrue(e.getMessage().contains("busy"));
        }
        assertEquals(4, count(" CREATE "));
        assertEquals(Arrays.asList(5000L, 10000L, 20000L), sleeps);
    }

    @Test
    public void nonBusyNoThrowsImmediately() {
        ImapMailSession s = session("A0001 NO no such mailbox",
                "A0002 NO permission denied");
        try {
            s.mkdir("x");
            fail("非 busy NO 应抛");
        } catch (StorageException e) {
            assertTrue(e.getMessage().contains("permission denied"));
        }
        assertEquals(1, count(" CREATE "));
        assertTrue(sleeps.isEmpty());
    }

    @Test
    public void createBusyButExistsDoesNotRecreate() {
        session("A0001 NO no such mailbox",
                "A0002 NO System busy",
                "* LIST () \"/\" \"base/x\"",
                "A0003 OK LIST done",
                "A0004 OK SELECT done").mkdir("x");
        assertEquals(1, count(" CREATE "));
        assertEquals(1, sleeps.size());
    }

    @Test
    public void appendBusyButActuallySucceededDoesNotDuplicate() {
        byte[] mime = MailFileStorage.buildStoredMime("a.txt", "hi".getBytes(StandardCharsets.UTF_8));
        session("A0001 OK SELECT done",
                "+ go ahead",
                "A0002 NO System busy",
                "A0003 OK NOOP",
                "* SEARCH 7",
                "A0004 OK SEARCH done",
                fetchSize(7, 2, 4), "A0005 OK FETCH done")
                .appendMime("", new ByteArrayInputStream(mime), mime.length);
        assertEquals(1, count(" APPEND "));
        assertEquals(1, count("HEADER Message-ID"));
        assertEquals(1, sleeps.size());
    }

    private byte[] mime2() {
        return MailFileStorage.buildStoredMime("a.txt", "hi".getBytes(StandardCharsets.UTF_8));
    }

    @Test
    public void appendVerifyOkByAppendUid() {
        byte[] mime = mime2();
        session("A0001 OK SELECT done", "+ go",
                "A0002 OK [APPENDUID 1 5] APPEND completed",
                fetchSize(5, 2, 4), "A0003 OK FETCH done")
                .appendMime("", new ByteArrayInputStream(mime), mime.length);
        assertEquals(0, count("EXPUNGE"));
        assertEquals(1, count("UID FETCH 5 "));
    }

    @Test
    public void appendVerifyFallsBackToMessageIdSearch() {
        byte[] mime = mime2();
        session("A0001 OK SELECT done", "+ go", "A0002 OK APPEND completed",
                "A0003 OK NOOP", "* SEARCH 9", "A0004 OK SEARCH done",
                fetchSize(9, 2, 4), "A0005 OK FETCH done")
                .appendMime("", new ByteArrayInputStream(mime), mime.length);
        assertEquals(1, count("HEADER Message-ID"));
        assertEquals(1, count("UID FETCH 9 "));
    }

    @Test
    public void appendSizeMismatchDeletesAndThrowsIoError() {
        byte[] mime = mime2();
        ImapMailSession s = session("A0001 OK SELECT done", "+ go",
                "A0002 OK [APPENDUID 1 5] APPEND completed",
                fetchSize(5, 2, 0), "A0003 OK FETCH done",   // 附件被截断
                "A0004 OK STORE", "A0005 OK EXPUNGE");
        try {
            s.appendMime("", new ByteArrayInputStream(mime), mime.length);
            fail("截断应抛");
        } catch (StorageException e) {
            assertEquals(com.alianga.jkit.storage.StorageErrorCode.IO_ERROR, e.code());
        }
        assertEquals(1, count("UID STORE 5 +FLAGS.SILENT (\\Deleted)"));
        assertEquals(1, count("UID EXPUNGE 5"));
    }

    @Test
    public void appendMissingAfterOkThrowsIoError() {
        byte[] mime = mime2();
        ImapMailSession s = session("A0001 OK SELECT done", "+ go", "A0002 OK APPEND completed",
                "A0003 OK NOOP", "* SEARCH", "A0004 OK SEARCH done");
        try {
            s.appendMime("", new ByteArrayInputStream(mime), mime.length);
            fail("服务端丢邮件应抛");
        } catch (StorageException e) {
            assertEquals(com.alianga.jkit.storage.StorageErrorCode.IO_ERROR, e.code());
        }
    }

    @Test
    public void listSizeUsesJkitHeaderThenLegacyBase64NeverRfc822() {
        assertEquals(34661586L, ImapMailSession.payloadSize("X-Jkit-Size: 34661586\r\n",
                "(\"BASE64\" 1138)"));
        long enc = ImapMailSession.foldedBase64Length(1000L);
        long legacy = ImapMailSession.payloadSize("Subject: a\r\n", "(\"BASE64\" " + enc + ")");
        assertTrue("legacy=" + legacy, Math.abs(legacy - 1000L) <= 2L);
        assertEquals(-1L, ImapMailSession.payloadSize(null, null));
        // QQ 实测：3827302 原始 → BODYSTRUCTURE 5237362
        assertEquals(5237362L, ImapMailSession.foldedBase64Length(3827302L));
    }

    /** 2026-10-15 真号 QQ 抓包（临时文件夹，已删）：20B 文件，头为 {37} 字面量，其后同行接 BODYSTRUCTURE。 */
    static String qqFetchSample(String tag) {
        String hdr = "X-Jkit-Size: 20\r\nSubject: s20.txt\r\n\r\n";
        return "* 1 FETCH (UID 1 INTERNALDATE \"15-Oct-2026 11:51:34 +0800\" RFC822.SIZE 611 "
                + "BODY[HEADER.FIELDS (SUBJECT X-JKIT-SIZE)] {" + hdr.length() + "}\r\n" + hdr
                + " BODYSTRUCTURE ((\"TEXT\" \"PLAIN\" (\"CHARSET\" \"us-ascii\") NIL NIL NIL 3 1 NIL NIL NIL)"
                + "(\"APPLICATION\" \"OCTET-STREAM\" (\"name\" \"=?gbk?B?czIwLnR4dA==?=\") NIL NIL \"BASE64\" 28"
                + " NIL (\"attachment\" (\"filename\" \"s20.txt\")) NIL) \"MIXED\" (\"BOUNDARY\" \"b\") NIL NIL))\r\n"
                + tag + " OK UID FETCH Completed";
    }

    @Test
    public void realQqFetchSampleReadsJkitSizeExactly() {
        assertEquals(37, "X-Jkit-Size: 20\r\nSubject: s20.txt\r\n\r\n".length());
        MailEntry e = session(qqFetchSample("A0001")).fetchMetaForTest(1L);
        assertEquals("s20.txt", e.name());
        assertEquals(20L, e.size());   // base64 估算会得 21
    }

    @Test
    public void statTwentyByteFileShowsExactlyTwenty() {
        MailEntry e = session("A0001 OK LIST completed",
                "A0002 NO no such folder",
                "A0003 OK SELECT complete",
                "* SEARCH 1", "A0004 OK UID SEARCH completed",
                qqFetchSample("A0005"), qqFetchSample("A0006")).stat("s20.txt");
        assertEquals("s20.txt", e.name());
        assertEquals(20L, e.size());
        assertFalse(e.directory());
    }

    @Test
    public void caseInsensitiveHeaderName() {
        assertEquals(20L, ImapMailSession.parseHeaderLong("x-jkit-size: 20\r\n", "X-Jkit-Size"));
    }
}
