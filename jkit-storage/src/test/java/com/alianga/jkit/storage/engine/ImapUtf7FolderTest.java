package com.alianga.jkit.storage.engine;

import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * 邮箱名 modified UTF-7 编解码与 basePath 解析（精确 / 其他文件夹 / 不存在则 CREATE）。
 *
 * @author 郑明亮
 */
public class ImapUtf7FolderTest {

    private final ByteArrayOutputStream sent = new ByteArrayOutputStream();

    private ImapMailSession session(String base, String... serverLines) {
        StringBuilder sb = new StringBuilder();
        for (String l : serverLines) {
            sb.append(l).append("\r\n");
        }
        ImapMailSession s = new ImapMailSession();
        s.attachForTest(new ByteArrayInputStream(sb.toString().getBytes(StandardCharsets.UTF_8)),
                sent, base, millis -> { });
        return s;
    }

    private String sentText() {
        return new String(sent.toByteArray(), StandardCharsets.UTF_8);
    }

    @Test
    public void encodeKnownVectors() {
        assertEquals("INBOX", ImapUtf7.encode("INBOX"));
        assertEquals("A&-B", ImapUtf7.encode("A&B"));
        // RFC 3501 示例
        assertEquals("~peter/mail/&U,BTFw-/&ZeVnLIqe-", ImapUtf7.encode("~peter/mail/台北/日本語"));
        assertEquals("&UXZO1mWHTvZZOQ-", ImapUtf7.encode("其他文件夹"));
    }

    @Test
    public void roundTrip() {
        String[] samples = {"", "abc", "&", "&&", "a&b", "我的文件", "jkit备份&2026/子目录 x", "😀表情", "其他文件夹/网盘"};
        for (String s : samples) {
            String wire = ImapUtf7.encode(s);
            for (char c : wire.toCharArray()) {
                assertTrue(c >= 0x20 && c <= 0x7e);
            }
            assertFalse(wire.contains("/") && !s.contains("/"));
            assertEquals(s, ImapUtf7.decode(wire));
        }
    }

    @Test
    public void exactMatchPreferredOverOtherFolders() throws Exception {
        ImapMailSession s = session("x",
                "* LIST () \"/\" \"&YhF2hGWHTvY-\"",
                "* LIST () \"/\" \"&UXZO1mWHTvZZOQ-/&YhF2hGWHTvY-\"",
                "A0001 OK LIST done");
        assertEquals("我的文件", s.resolveBaseFolder("我的文件"));
        assertTrue(sentText().contains("LIST \"\" \"*\""));
    }

    @Test
    public void matchesUnderOtherFolders() throws Exception {
        ImapMailSession s = session("x",
                "* LIST (\\HasNoChildren) \"/\" INBOX",
                "* LIST () \"/\" \"&UXZO1mWHTvZZOQ-/&YhF2hGWHTvY-\"",
                "A0001 OK LIST done");
        assertEquals("其他文件夹/我的文件", s.resolveBaseFolder("我的文件"));
    }

    @Test
    public void notFoundCreatesWithEncodedName() throws Exception {
        ImapMailSession s = session("x",
                "* LIST () \"/\" INBOX",
                "A0001 OK LIST done");
        assertEquals("新文件夹", s.resolveBaseFolder("新文件夹"));
    }

    @Test
    public void subfolderMkdirUsesEncodedName() {
        ImapMailSession s2 = session("新文件夹",
                "A0001 NO no such mailbox",
                "A0002 OK CREATE done",
                "A0003 OK SELECT done");
        s2.mkdir("子目录");
        String all = sentText();
        assertTrue(all, all.contains("CREATE \"&ZbBlh072WTk-/&W1B27l9V-\""));
        assertFalse(all, all.contains("CREATE \"新文件夹"));
    }
}
