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
                "A0004 OK SEARCH done")
                .appendMime("", new ByteArrayInputStream(mime), mime.length);
        assertEquals(1, count(" APPEND "));
        assertEquals(1, count("HEADER Message-ID"));
        assertEquals(1, sleeps.size());
    }
}
