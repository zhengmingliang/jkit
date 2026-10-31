package com.alianga.jkit.storage.internal;

import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Base64;
import java.util.Locale;
import java.util.UUID;

/**
 * 存储用最小 MIME 渲染器：一封邮件 = 固定文本正文 + 单附件（MailFileStorage 邮件盘专用）。
 *
 * <p>字节格式与 jkit-notify SmtpChannel 的组装规则一致：Subject/附件名纯可打印 ASCII 原样写入
 * （便于按明文文件名查找），含非 ASCII 时走 RFC2047 encoded-word；Base64 按 76 字符 CRLF 折行；
 * 全文 CRLF 行尾。保证读侧 ImapMailSession 解析与邮箱客户端展示不受渲染器来源影响。</p>
 *
 * @author 郑明亮
 */
public final class StoredMime {
    /** 固定正文（读侧不解析正文，仅占位说明）。 */
    static final String BODY_TEXT = "Stored by jkit-storage MailFileStorage.";
    private static final String SENDER = "jkit-storage@localhost";
    private static final String FALLBACK_HOST = "localhost";
    private static final DateTimeFormatter RFC5322_DATE =
            DateTimeFormatter.ofPattern("EEE, dd MMM yyyy HH:mm:ss Z", Locale.US);

    private StoredMime() {
    }

    /**
     * 渲染完整 RFC822：multipart/mixed，正文一段 + 附件一个。
     *
     * @param fileName 附件名（同时作为 Subject；空则回退 attachment.bin）
     * @param content  附件字节（null 视为空）
     * @return RFC822 字节（UTF-8，CRLF 行尾）
     */
    public static byte[] render(String fileName, byte[] content) {
        byte[] data = content == null ? new byte[0] : content;
        String name = fileName == null || fileName.trim().isEmpty() ? "attachment.bin" : fileName.trim();
        String boundary = "----=_jkit_" + UUID.randomUUID().toString().replace("-", "");
        String asciiName = asciiFilename(name);
        String disposition = asciiName.equals(name) ? asciiName : encodedWord(name);
        StringBuilder out = new StringBuilder(1024 + data.length / 3 * 4 + 128);
        out.append("From: <").append(SENDER).append(">\r\n");
        out.append("To: <").append(SENDER).append(">\r\n");
        out.append("Subject: ").append(subjectHeader(name)).append("\r\n");
        out.append("Date: ").append(RFC5322_DATE.format(ZonedDateTime.now())).append("\r\n");
        out.append("Message-ID: <").append(UUID.randomUUID()).append('@')
                .append(localHost()).append(">\r\n");
        out.append("MIME-Version: 1.0\r\n");
        out.append("Content-Type: multipart/mixed; boundary=\"").append(boundary).append("\"\r\n");
        out.append("\r\n");
        out.append("This is a multi-part message in MIME format.\r\n");
        out.append("--").append(boundary).append("\r\n");
        out.append("Content-Type: text/plain; charset=UTF-8\r\n");
        out.append("Content-Transfer-Encoding: base64\r\n");
        out.append("\r\n");
        out.append(foldedBase64(BODY_TEXT.getBytes(StandardCharsets.UTF_8))).append("\r\n");
        out.append("--").append(boundary).append("\r\n");
        out.append("Content-Type: ").append(MimeTypes.of(name)).append("; name=\"")
                .append(asciiName).append("\"\r\n");
        out.append("Content-Transfer-Encoding: base64\r\n");
        out.append("Content-Disposition: attachment; filename=\"").append(disposition).append("\"\r\n");
        out.append("\r\n");
        out.append(foldedBase64(data)).append("\r\n");
        out.append("--").append(boundary).append("--\r\n");
        return out.toString().getBytes(StandardCharsets.UTF_8);
    }

    /**
     * Subject 头值：剥离 CR/LF 防头注入；纯可打印 ASCII 原样（邮件盘按明文文件名查找），
     * 含非 ASCII 时 RFC2047 encoded-word。
     */
    static String subjectHeader(String text) {
        String cleaned = text == null ? "" : text.replace("\r", "").replace("\n", "");
        return isPrintableAscii(cleaned) ? cleaned : encodedWord(cleaned);
    }

    private static boolean isPrintableAscii(String text) {
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c < 0x20 || c > 0x7e) {
                return false;
            }
        }
        return true;
    }

    private static String encodedWord(String text) {
        return "=?UTF-8?B?" + Base64.getEncoder().encodeToString(text.getBytes(StandardCharsets.UTF_8)) + "?=";
    }

    /** name=/Content-Disposition 的 ASCII 兜底名：引号、反斜杠与非可打印字符替换为下划线。 */
    private static String asciiFilename(String filename) {
        StringBuilder out = new StringBuilder(filename.length());
        for (int i = 0; i < filename.length(); i++) {
            char c = filename.charAt(i);
            if (c == '"' || c == '\\' || c < 0x20 || c > 0x7e) {
                out.append('_');
            } else {
                out.append(c);
            }
        }
        return out.length() == 0 ? "attachment.bin" : out.toString();
    }

    /** Base64 按 RFC 2045 以 76 字符折行，行间 CRLF、行尾不带。 */
    private static String foldedBase64(byte[] data) {
        String base64 = Base64.getEncoder().encodeToString(data);
        StringBuilder out = new StringBuilder(base64.length() + base64.length() / 76 * 2 + 2);
        for (int i = 0; i < base64.length(); i += 76) {
            if (i > 0) {
                out.append("\r\n");
            }
            out.append(base64, i, Math.min(i + 76, base64.length()));
        }
        return out.toString();
    }

    private static String localHost() {
        try {
            String host = InetAddress.getLocalHost().getCanonicalHostName();
            if (host != null && !host.trim().isEmpty()) {
                return host.trim();
            }
        } catch (Exception ignored) {
            // 本机名不可得时回落 localhost
        }
        return FALLBACK_HOST;
    }
}
