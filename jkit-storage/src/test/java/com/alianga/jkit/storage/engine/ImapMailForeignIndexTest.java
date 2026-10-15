package com.alianga.jkit.storage.engine;

import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * 外来附件索引：命中只发 BODYSTRUCTURE 校验 + BODY.PEEK[section]；未命中回退批量头扫描，绝不取整封。
 *
 * @author 郑明亮
 */
public class ImapMailForeignIndexTest {

    private static final String FIELDS =
            "BODY[HEADER.FIELDS (SUBJECT FROM DATE MESSAGE-ID X-JKIT-SIZE X-JKIT-TOTAL-SIZE)]";
    private static final String TEXT_PART =
            "(\"TEXT\" \"PLAIN\" (\"CHARSET\" \"utf-8\") NIL NIL \"7BIT\" 5 1 NIL NIL NIL NIL)";
    private static final String STRUCT = "(" + TEXT_PART
            + "(\"APPLICATION\" \"PDF\" (\"name\" \"r.pdf\") NIL NIL \"BASE64\" 8 NIL (\"attachment\" (\"filename\" \"r.pdf\")) NIL NIL)"
            + " \"MIXED\" (\"BOUNDARY\" \"x\") NIL NIL NIL)";
    private static final String STRUCT_OTHER = STRUCT.replace("r.pdf", "s.pdf");
    private static final String HDR = "From: z <z@x.com>\r\nSubject: hi\r\n\r\n";
    private static final String NAME = "r__" + ImapMailSession.shortId(4, "2") + ".pdf";

    private final ByteArrayOutputStream sent = new ByteArrayOutputStream();

    private ImapMailSession session(List<String> lines) {
        StringBuilder sb = new StringBuilder();
        for (String l : lines) {
            sb.append(l).append("\r\n");
        }
        ImapMailSession s = new ImapMailSession();
        s.attachForTest(new ByteArrayInputStream(sb.toString().getBytes(StandardCharsets.UTF_8)),
                sent, "base", ms -> { });
        return s;
    }

    private static String fetch(String tag, long uid, String structure) {
        return "* " + uid + " FETCH (UID " + uid + " INTERNALDATE \"15-Oct-2026 11:51:34 +0800\" RFC822.SIZE 100 "
                + FIELDS + " {" + HDR.length() + "}\r\n" + HDR + " BODYSTRUCTURE " + structure + ")\r\n"
                + tag + " OK UID FETCH Completed";
    }

    /** list 根目录（UIDVALIDITY 7），建立索引。 */
    private List<String> listScript() {
        return new ArrayList<String>(Arrays.asList("A0001 OK LIST completed",
                "* OK [UIDVALIDITY 7] ok", "A0002 OK SELECT completed",
                "* SEARCH 4", "A0003 OK SEARCH completed", fetch("A0004", 4, STRUCT)));
    }

    private String sentAfter(int from) {
        return new String(sent.toByteArray(), StandardCharsets.UTF_8).substring(from);
    }

    private static int count(String hay, String needle) {
        return hay.split(java.util.regex.Pattern.quote(needle), -1).length - 1;
    }

    private static void assertNoFullFetch(String cmds) {
        assertFalse(cmds, cmds.contains("BODY.PEEK[]"));
        assertFalse(cmds, cmds.contains("BODY[]"));
        assertFalse(cmds, cmds.contains("RFC822)") || cmds.contains("RFC822 "));
    }

    @Test
    public void indexHitFetchesStructureThenSectionOnly() {
        List<String> l = listScript();
        l.addAll(Arrays.asList("* OK [UIDVALIDITY 7] ok", "A0005 OK SELECT completed",
                "* 1 FETCH (UID 4 BODYSTRUCTURE " + STRUCT + ")", "A0006 OK FETCH completed",
                "* 1 FETCH (UID 4 BODY[2] {8}\r\naGVsbG8h)", "A0007 OK FETCH completed"));
        ImapMailSession s = session(l);
        s.list("");
        assertEquals(1, s.foreignIndexSizeForTest());
        int mark = sent.size();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        s.download(NAME, out);
        assertArrayEquals("hello!".getBytes(StandardCharsets.UTF_8), out.toByteArray());
        String cmds = sentAfter(mark);
        assertFalse(cmds, cmds.contains("SEARCH"));
        assertEquals(2, count(cmds, "UID FETCH"));
        assertTrue(cmds.contains("UID FETCH 4 (UID BODYSTRUCTURE)"));
        assertEquals(1, count(cmds, "BODY.PEEK[2]"));
        assertNoFullFetch(cmds);
    }

    @Test
    public void indexMissUsesBatchedHeaderOnlyScan() {
        ImapMailSession s = session(Arrays.asList("* OK [UIDVALIDITY 7] ok", "A0001 OK SELECT completed",
                "* SEARCH 3 4", "A0002 OK SEARCH completed",
                fetch("A0003", 3, TEXT_PART).replace("\r\nA0003 OK UID FETCH Completed", ""),
                fetch("A0003", 4, STRUCT),
                "* 1 FETCH (UID 4 BODY[2] {8}\r\naGVsbG8h)", "A0004 OK FETCH completed"));
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        s.download(NAME, out);
        assertArrayEquals("hello!".getBytes(StandardCharsets.UTF_8), out.toByteArray());
        String cmds = sentAfter(0);
        assertTrue(cmds, cmds.contains("UID FETCH 3,4 (UID INTERNALDATE RFC822.SIZE BODY.PEEK[HEADER.FIELDS"));
        assertEquals(2, count(cmds, "UID FETCH"));
        assertNoFullFetch(cmds);
        assertEquals(1, s.foreignIndexSizeForTest());
    }

    @Test
    public void staleEntryEmptyBodyFallsBackToScan() {
        List<String> l = listScript();
        l.addAll(Arrays.asList("* OK [UIDVALIDITY 7] ok", "A0005 OK SELECT completed",
                "* 1 FETCH (UID 4 BODYSTRUCTURE " + STRUCT + ")", "A0006 OK FETCH completed",
                "A0007 NO message gone",
                "* SEARCH 4", "A0008 OK SEARCH completed", fetch("A0009", 4, STRUCT),
                "* 1 FETCH (UID 4 BODY[2] {8}\r\naGVsbG8h)", "A0010 OK FETCH completed"));
        ImapMailSession s = session(l);
        s.list("");
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        s.download(NAME, out);
        assertArrayEquals("hello!".getBytes(StandardCharsets.UTF_8), out.toByteArray());
        assertNoFullFetch(sentAfter(0));
    }

    @Test
    public void structureMismatchDropsEntryAndRescans() {
        List<String> l = listScript();
        l.addAll(Arrays.asList("* OK [UIDVALIDITY 7] ok", "A0005 OK SELECT completed",
                "* 1 FETCH (UID 4 BODYSTRUCTURE " + STRUCT_OTHER + ")", "A0006 OK FETCH completed",
                "* SEARCH 4", "A0007 OK SEARCH completed", fetch("A0008", 4, STRUCT_OTHER),
                "* SEARCH", "A0009 OK SEARCH completed", "* SEARCH", "A0010 OK SEARCH completed"));
        ImapMailSession s = session(l);
        s.list("");
        int mark = sent.size();
        try {
            s.download(NAME, new ByteArrayOutputStream());
            org.junit.Assert.fail("应 NOT_FOUND");
        } catch (com.alianga.jkit.storage.StorageException e) {
            assertEquals(com.alianga.jkit.storage.StorageErrorCode.NOT_FOUND, e.code());
        }
        String cmds = sentAfter(mark);
        assertTrue(cmds, cmds.contains("UID SEARCH ALL"));
        assertFalse(cmds, cmds.contains("BODY.PEEK[2]"));
        assertNoFullFetch(cmds);
    }

    @Test
    public void uidValidityChangeDropsFolderIndex() {
        List<String> l = listScript();
        l.addAll(Arrays.asList("* OK [UIDVALIDITY 8] ok", "A0005 OK SELECT completed",
                "* SEARCH 4", "A0006 OK SEARCH completed", fetch("A0007", 4, STRUCT),
                "* 1 FETCH (UID 4 BODY[2] {8}\r\naGVsbG8h)", "A0008 OK FETCH completed"));
        ImapMailSession s = session(l);
        s.list("");
        int mark = sent.size();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        s.download(NAME, out);
        String cmds = sentAfter(mark);
        assertTrue(cmds, cmds.contains("UID SEARCH ALL"));
        assertFalse(cmds, cmds.contains("(UID BODYSTRUCTURE)"));
        assertArrayEquals("hello!".getBytes(StandardCharsets.UTF_8), out.toByteArray());
    }
}
