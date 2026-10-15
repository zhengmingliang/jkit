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
}
