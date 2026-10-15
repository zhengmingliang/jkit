package com.alianga.jkit.storage.engine;

import com.alianga.jkit.storage.StorageErrorCode;
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
 * QQ 自建文件夹：连接不写邮箱、LIST 失败不建箱、加锁报错、整个邮箱模式、分页与附件识别。
 *
 * @author 郑明亮
 */
public class ImapQqFolderTest {
    private final ByteArrayOutputStream sent = new ByteArrayOutputStream();

    private ImapMailSession session(String base, String... lines) {
        StringBuilder sb = new StringBuilder();
        for (String l : lines) {
            sb.append(l).append("\r\n");
        }
        ImapMailSession s = new ImapMailSession();
        s.attachForTest(new ByteArrayInputStream(sb.toString().getBytes(StandardCharsets.UTF_8)), sent, base,
                ms -> { });
        return s;
    }

    private String sentText() {
        return new String(sent.toByteArray(), StandardCharsets.UTF_8);
    }

    @Test
    public void listFailureNeverCreates() throws Exception {
        ImapMailSession s = session("x", "A0001 NO LIST blocked");
        try {
            s.initAfterLogin("db-backup", false);
            fail("LIST 失败应抛");
        } catch (StorageException e) {
            assertTrue(e.getMessage(), e.getMessage().contains("未自动创建"));
        }
        assertFalse(sentText(), sentText().contains("CREATE"));
    }

    @Test
    public void listInterruptedNeverCreates() throws Exception {
        ImapMailSession s = session("x", "* LIST () \"/\" INBOX");
        try {
            s.initAfterLogin("db-backup", false);
            fail("LIST 中断应抛");
        } catch (StorageException e) {
            assertTrue(e.getMessage(), e.getMessage().contains("未自动创建"));
        }
        assertFalse(sentText().contains("CREATE"));
    }

    @Test
    public void existingUnderOtherFoldersSelectsWithoutAnyWrite() throws Exception {
        ImapMailSession s = session("x",
                "* LIST (\\NoSelect \\HasChildren) \"/\" \"&UXZO1mWHTvZZOQ-\"",
                "* LIST (\\HasNoChildren) \"/\" \"&UXZO1mWHTvZZOQ-/db-backup\"",
                "A0001 OK LIST completed",
                "A0002 OK SELECT completed");
        s.initAfterLogin("db-backup", false);
        String all = sentText();
        assertTrue(all, all.contains("SELECT \"&UXZO1mWHTvZZOQ-/db-backup\""));
        assertFalse(all, all.contains("CREATE"));
        assertFalse(all, all.contains("__jkit_hier_probe__"));
        assertFalse(all, all.contains("DELETE"));
    }

    @Test
    public void listedButSelectRejectedIsClearAuthError() throws Exception {
        ImapMailSession s = session("x",
                "* LIST () \"/\" \"&UXZO1mWHTvZZOQ-/db-backup\"",
                "A0001 OK LIST completed",
                "A0002 NO Folder locked");
        try {
            s.initAfterLogin("db-backup", false);
            fail("SELECT 被拒应抛");
        } catch (StorageException e) {
            assertEquals(StorageErrorCode.AUTH_FAILED, e.code());
            assertTrue(e.getMessage(), e.getMessage().contains("文件夹区域加锁"));
        }
        assertFalse(sentText().contains("CREATE"));
    }

    @Test
    public void missingAfterSuccessfulListIsAutoCreatedWithoutProbe() throws Exception {
        ImapMailSession s = session("x",
                "* LIST () \"/\" INBOX",
                "A0001 OK LIST completed",
                "A0002 NO no such mailbox",
                "A0003 NO no such mailbox",
                "A0004 OK CREATE completed",
                "A0005 OK SELECT completed");
        s.initAfterLogin("jkit-mail-disk", false);
        String all = sentText();
        assertTrue(all, all.contains("EXAMINE \"jkit-mail-disk\""));
        assertTrue(all, all.contains("EXAMINE \"&UXZO1mWHTvZZOQ-/jkit-mail-disk\""));
        assertTrue(all, all.indexOf("EXAMINE") < all.indexOf("CREATE"));
        assertEquals(all, 1, all.split("CREATE", -1).length - 1);
        assertTrue(all, all.contains("CREATE \"jkit-mail-disk\""));
        assertFalse(all, all.contains("__jkit_hier_probe__"));
    }

    @Test
    public void literalMailboxNamesAreRead() throws Exception {
        String raw = "&UXZO1mWHTvZZOQ-/swft-&bUuL1Q--db&WQdO,Q-";
        ImapMailSession s = session("x",
                "* LIST () \"/\" {" + raw.length() + "}",
                raw,
                "A0001 OK LIST completed",
                "A0002 OK SELECT completed");
        s.initAfterLogin("swft-测试-db备份", false);
        assertTrue(sentText(), sentText().contains("SELECT \"" + raw + "\""));
    }

    @Test
    public void wholeMailboxRootListsTopLevelAndOtherFolders() throws Exception {
        ImapMailSession s = session("",
                "* LIST () \"/\" INBOX",
                "* LIST (\\NoSelect \\HasChildren) \"/\" \"&UXZO1mWHTvZZOQ-\"",
                "A0001 OK LIST completed",
                // 其他文件夹：SELECT 被拒（\NoSelect），LIST 可见，有子箱
                "A0002 NO cannot select",
                "* LIST () \"/\" \"&UXZO1mWHTvZZOQ-\"", "A0003 OK LIST completed",
                "* LIST () \"/\" \"&UXZO1mWHTvZZOQ-/swft-&bUuL1Q--db&WQdO,Q-\"", "A0004 OK LIST completed",
                "* LIST () \"/\" \"&UXZO1mWHTvZZOQ-/swft-&bUuL1Q--db&WQdO,Q-\"", "A0005 OK LIST completed");
        s.initAfterLogin(ImapMailSession.WHOLE_MAILBOX, true);
        List<String> root = names(s.listDir(""));
        assertEquals(Arrays.asList("INBOX", "其他文件夹"), root);
        List<String> other = names(s.listDir("其他文件夹"));
        assertEquals(Arrays.asList("其他文件夹/swft-测试-db备份"), other);
        String all = sentText();
        assertTrue(all, all.contains("LIST \"\" \"%\""));
        assertTrue(all, all.contains("LIST \"\" \"&UXZO1mWHTvZZOQ-/%\""));
        assertFalse(all, all.contains("CREATE"));
    }

    @Test
    public void listDirPageFetchesNewestFirstInOneBatch() {
        ImapMailSession s = session("base",
                "A0001 OK SELECT completed",
                "A0002 OK LIST completed",
                "* SEARCH 1 2 3 4 5", "A0003 OK SEARCH completed",
                "A0004 OK UID FETCH completed");
        MailGateway.MailPage p = s.listDirPage("", 1, 2);
        assertEquals(5L, p.total);
        assertTrue(sentText(), sentText().contains("UID FETCH 3,2 (UID INTERNALDATE"));
        assertEquals(1, sentText().split("UID FETCH", -1).length - 1);
    }

    @Test
    public void newestFirstLimits() {
        assertEquals(Arrays.asList(9L, 7L), ImapMailSession.newestFirst(Arrays.asList(1L, 9L, 3L, 7L), 2));
        assertEquals(Arrays.asList(9L, 7L, 3L, 1L), ImapMailSession.newestFirst(Arrays.asList(1L, 9L, 3L, 7L), 0));
    }

    @Test
    public void forwardedRfc822AttachmentsAreFound() {
        String inner = "((\"TEXT\" \"PLAIN\" NIL NIL NIL \"7BIT\" 5 1)"
                + "(\"APPLICATION\" \"GZIP\" (\"name\" \"db.tar.gz\") NIL NIL \"BASE64\" 100 NIL NIL NIL) \"MIXED\")";
        String bs = "((\"TEXT\" \"PLAIN\" NIL NIL NIL \"7BIT\" 5 1)"
                + "(\"MESSAGE\" \"RFC822\" NIL NIL NIL \"7BIT\" 300 NIL " + inner + " 10) \"MIXED\")";
        List<ImapMailSession.AttachmentPart> parts = ImapMailSession.attachmentParts(bs);
        assertEquals(1, parts.size());
        assertEquals("2.2", parts.get(0).section);
        assertEquals("db.tar.gz", parts.get(0).filename);
    }

    @Test
    public void applicationWithoutNameAndRfc2231FilenameAreFound() {
        String bs = "((\"TEXT\" \"PLAIN\" NIL NIL NIL \"7BIT\" 5 1)"
                + "(\"APPLICATION\" \"OCTET-STREAM\" NIL NIL NIL \"BASE64\" 100 NIL NIL NIL)"
                + "(\"APPLICATION\" \"X-GZIP\" NIL NIL NIL \"BASE64\" 100 NIL"
                + " (\"attachment\" (\"filename*\" \"utf-8''%E5%A4%87%E4%BB%BD.tar.gz\")) NIL) \"MIXED\")";
        List<ImapMailSession.AttachmentPart> parts = ImapMailSession.attachmentParts(bs);
        assertEquals(2, parts.size());
        assertEquals("part-2.octet-stream", parts.get(0).filename);
        assertEquals("备份.tar.gz", parts.get(1).filename);
    }

    private static List<String> names(List<MailEntry> list) {
        List<String> out = new ArrayList<String>();
        for (MailEntry e : list) {
            out.add(e.name());
        }
        return out;
    }

    @Test
    public void hiddenFromListButExaminableIsNeverCreated() throws Exception {
        ImapMailSession s = session("x",
                "* LIST () \"/\" INBOX",
                "A0001 OK LIST completed",
                "A0002 NO no such mailbox",
                "A0003 OK [READ-ONLY] EXAMINE completed",
                "A0004 OK SELECT completed");
        s.initAfterLogin("db-backup", false);
        String all = sentText();
        assertTrue(all, all.contains("EXAMINE \"db-backup\""));
        assertTrue(all, all.contains("EXAMINE \"&UXZO1mWHTvZZOQ-/db-backup\""));
        assertTrue(all, all.contains("SELECT \"&UXZO1mWHTvZZOQ-/db-backup\""));
        assertFalse(all, all.contains("CREATE"));
        assertFalse(all, all.contains("DELETE"));
    }

    @Test
    public void exactNameExaminableIsUsedDirectly() throws Exception {
        ImapMailSession s = session("x",
                "* LIST () \"/\" INBOX",
                "A0001 OK LIST completed",
                "A0002 OK [READ-ONLY] EXAMINE completed",
                "A0003 OK SELECT completed");
        s.initAfterLogin("db-backup", false);
        String all = sentText();
        assertTrue(all, all.contains("SELECT \"db-backup\""));
        assertFalse(all, all.contains("其他"));
        assertFalse(all, all.contains("&UXZO1mWHTvZZOQ-"));
        assertFalse(all, all.contains("CREATE"));
    }

    @Test
    public void examineBadNeverCreates() throws Exception {
        ImapMailSession s = session("x",
                "* LIST () \"/\" INBOX",
                "A0001 OK LIST completed",
                "A0002 NO no such mailbox",
                "A0003 BAD command syntax");
        try {
            s.initAfterLogin("db-backup", false);
            fail("EXAMINE BAD 应抛");
        } catch (StorageException e) {
            assertTrue(e.getMessage(), e.getMessage().contains("未自动创建"));
        }
        assertFalse(sentText(), sentText().contains("CREATE"));
    }

    @Test
    public void examineConnectionDropNeverCreates() throws Exception {
        ImapMailSession s = session("x",
                "* LIST () \"/\" INBOX",
                "A0001 OK LIST completed");
        try {
            s.initAfterLogin("db-backup", false);
            fail("EXAMINE 时连接中断应抛");
        } catch (StorageException e) {
            // 连接中断
        }
        assertFalse(sentText(), sentText().contains("CREATE"));
    }

    @Test
    public void hierarchyInferredFromListSkipsProbe() throws Exception {
        ImapMailSession s = session("x",
                "* LIST () \"/\" \"jkit-mail-disk\"",
                "* LIST () \"/\" \"jkit-mail-disk/a\"",
                "A0001 OK LIST completed",
                "A0002 OK SELECT completed",
                "A0003 NO no such mailbox",
                "A0004 OK CREATE completed",
                "A0005 OK SELECT completed");
        s.initAfterLogin("jkit-mail-disk", false);
        s.mkdir("b");
        String all = sentText();
        assertFalse(all, all.contains("__jkit_hier_probe__"));
        assertTrue(all, all.contains("CREATE \"jkit-mail-disk/b\""));
    }

    @Test
    public void probeDeletedWhenHierarchyUnknown() throws Exception {
        ImapMailSession s = session("x",
                "* LIST () \"/\" \"jkit-mail-disk\"",
                "A0001 OK LIST completed",
                "A0002 OK SELECT completed",
                "A0003 OK CREATE completed",
                "A0004 OK DELETE completed",
                "A0005 NO no such mailbox",
                "A0006 OK CREATE completed",
                "A0007 OK SELECT completed");
        s.initAfterLogin("jkit-mail-disk", false);
        s.mkdir("b");
        String all = sentText();
        assertTrue(all, all.contains("CREATE \"jkit-mail-disk/__jkit_hier_probe__\""));
        assertTrue(all, all.contains("DELETE \"jkit-mail-disk/__jkit_hier_probe__\""));
    }
}
