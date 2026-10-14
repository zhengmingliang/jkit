package com.alianga.jkit.notify;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * {@link MailMime} 渲染规则：ASCII Subject 明文、非 ASCII 仍 RFC2047。
 *
 * @author 郑明亮
 */
public class MailMimeTest {

    @Test
    public void asciiSubjectWrittenPlain() {
        Message message = Message.text("hello.txt", "body")
                .attachment(Attachment.of("hello.txt", "hi".getBytes(StandardCharsets.UTF_8)));
        ChannelConfig config = ChannelConfig.smtp("127.0.0.1", 25)
                .from("a@example.com")
                .to("b@example.com");
        String mime = new String(MailMime.render(message, "a@example.com", config), StandardCharsets.UTF_8);
        assertTrue(mime, mime.contains("Subject: hello.txt\r\n"));
        assertFalse(mime, mime.contains("Subject: =?UTF-8?B?"));
        assertTrue(mime, mime.contains("filename=\"hello.txt\""));
    }

    @Test
    public void nonAsciiSubjectStillEncodedWord() {
        String title = "测试.txt";
        Message message = Message.text(title, "body")
                .attachment(Attachment.of(title, "hi".getBytes(StandardCharsets.UTF_8)));
        ChannelConfig config = ChannelConfig.smtp("127.0.0.1", 25)
                .from("a@example.com")
                .to("b@example.com");
        String mime = new String(MailMime.render(message, "a@example.com", config), StandardCharsets.UTF_8);
        String expected = "Subject: =?UTF-8?B?"
                + Base64.getEncoder().encodeToString(title.getBytes(StandardCharsets.UTF_8)) + "?=";
        assertTrue(mime, mime.contains(expected));
        assertFalse(mime, mime.contains("Subject: 测试.txt\r\n"));
    }
}
