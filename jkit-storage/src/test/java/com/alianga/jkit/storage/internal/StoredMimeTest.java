package com.alianga.jkit.storage.internal;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * {@link StoredMime} 渲染格式回归：锁定与 jkit-notify SmtpChannel 一致的字节约定，
 * 保证读侧 ImapMailSession 解析与邮箱客户端展示不受影响。
 */
public class StoredMimeTest {

    @Test
    public void asciiSubjectAndFilenameStayRaw() {
        byte[] mime = StoredMime.render("hello.txt", "hi".getBytes(StandardCharsets.UTF_8));
        String text = new String(mime, StandardCharsets.UTF_8);
        assertTrue(text.contains("Subject: hello.txt\r\n"));
        assertTrue(text.contains("Content-Type: text/plain; name=\"hello.txt\"\r\n"));
        assertTrue(text.contains("Content-Disposition: attachment; filename=\"hello.txt\"\r\n"));
    }

    @Test
    public void cjkSubjectAndFilenameUseEncodedWord() {
        String name = "report-中文.txt";
        byte[] mime = StoredMime.render(name, new byte[]{0, 1, 2, 3});
        String text = new String(mime, StandardCharsets.UTF_8);
        Matcher subject = Pattern.compile("Subject: (\\S+)\r\n").matcher(text);
        assertTrue(subject.find());
        assertEquals(name, decodeWord(subject.group(1)));
        assertTrue(text.contains("Content-Type: text/plain; name=\"report-__.txt\"\r\n"));
        Matcher disposition = Pattern.compile(
                "Content-Disposition: attachment; filename=\"(\\S+?)\"\r\n").matcher(text);
        assertTrue(disposition.find());
        assertEquals(name, decodeWord(disposition.group(1)));
    }

    @Test
    public void structureAndBase64RoundTrip() {
        byte[] content = new byte[1000];
        for (int i = 0; i < content.length; i++) {
            content[i] = (byte) i;
        }
        byte[] mime = StoredMime.render("data.bin", content);
        String text = new String(mime, StandardCharsets.UTF_8);

        // 顶层结构：固定头 + multipart/mixed + 两个 part + 收尾边界
        assertTrue(text.startsWith("From: <jkit-storage@localhost>\r\n"));
        assertTrue(text.contains("To: <jkit-storage@localhost>\r\n"));
        assertTrue(text.contains("MIME-Version: 1.0\r\n"));
        assertTrue(text.contains("Message-ID: <"));
        assertTrue(text.contains("Date: "));
        Matcher boundary = Pattern.compile(
                "Content-Type: multipart/mixed; boundary=\"([^\"]+)\"\r\n").matcher(text);
        assertTrue(boundary.find());
        String b = boundary.group(1);
        assertEquals(2, countOf(text, "--" + b + "\r\n"));
        assertTrue(text.endsWith("--" + b + "--\r\n"));

        // 正文 part
        assertTrue(text.contains("Content-Type: text/plain; charset=UTF-8\r\n"));
        String bodyBase64 = String.join("", bodyLinesAfter(text, "Content-Type: text/plain; charset=UTF-8"));
        assertEquals(StoredMime.BODY_TEXT, new String(Base64.getMimeDecoder().decode(bodyBase64),
                StandardCharsets.UTF_8));

        // 附件 part：1000 字节 → 1336 字符 base64 → 18 行，每行 ≤76 字符，行间 CRLF
        List<String> partLines = bodyLinesAfter(text, "Content-Disposition: attachment");
        assertEquals(18, partLines.size());
        for (String line : partLines) {
            assertTrue(line.length() <= 76);
        }
        byte[] decoded = Base64.getMimeDecoder().decode(String.join("", partLines));
        assertEquals(1000, decoded.length);
        for (int i = 0; i < content.length; i++) {
            assertEquals(content[i], decoded[i]);
        }
    }

    @Test
    public void crlfOnlyLineEndings() {
        byte[] mime = StoredMime.render("a.txt", new byte[300]);
        String text = new String(mime, StandardCharsets.UTF_8);
        assertTrue(text.contains("\r\n"));
        assertEquals(-1, text.replace("\r\n", "").indexOf('\n'));
        assertEquals(-1, text.replace("\r\n", "").indexOf('\r'));
    }

    @Test
    public void headerInjectionStrippedFromSubject() {
        byte[] mime = StoredMime.render("a\r\nBcc: x@y\r\n.txt", new byte[0]);
        String text = new String(mime, StandardCharsets.UTF_8);
        // CR/LF 已剥离：Subject 值合并为单行明文，不会新起注入头行
        assertFalse(Pattern.compile("(?m)^Bcc:").matcher(text).find());
        assertEquals(1, countOf(text, "Subject: "));
        assertTrue(text.contains("Subject: aBcc: x@y.txt\r\n"));
    }

    @Test
    public void quoteInFilenameFallsBackToEncodedWord() {
        byte[] mime = StoredMime.render("a\"b.txt", new byte[1]);
        String text = new String(mime, StandardCharsets.UTF_8);
        assertTrue(text.contains("Content-Type: text/plain; name=\"a_b.txt\"\r\n"));
        assertTrue(text.contains("Content-Disposition: attachment; filename=\"=?UTF-8?B?"));
    }

    @Test
    public void emptyNameAndContent() {
        byte[] mime = StoredMime.render("  ", null);
        String text = new String(mime, StandardCharsets.UTF_8);
        assertTrue(text.contains("Subject: attachment.bin\r\n"));
        String attachmentBase64 = String.join("", bodyLinesAfter(text, "Content-Disposition: attachment"));
        assertEquals("", attachmentBase64);
    }

    private static String decodeWord(String encoded) {
        assertTrue(encoded.startsWith("=?UTF-8?B?") && encoded.endsWith("?="));
        return new String(Base64.getDecoder().decode(
                encoded.substring("=?UTF-8?B?".length(), encoded.length() - 2)),
                StandardCharsets.UTF_8);
    }

    /** 取 marker 所在 part 的空行之后的 base64 体（按折行拆成行，直到下一边界）。 */
    private static List<String> bodyLinesAfter(String text, String marker) {
        int at = text.indexOf(marker);
        assertTrue(at >= 0);
        String[] lines = text.substring(at).split("\r\n", -1);
        List<String> out = new ArrayList<String>();
        boolean started = false;
        for (String line : lines) {
            if (!started) {
                if (line.isEmpty()) {
                    started = true;
                }
                continue;
            }
            if (line.startsWith("--")) {
                break;
            }
            out.add(line);
        }
        return out;
    }

    private static int countOf(String text, String needle) {
        int count = 0;
        int at = 0;
        while ((at = text.indexOf(needle, at)) >= 0) {
            count++;
            at += needle.length();
        }
        return count;
    }
}
