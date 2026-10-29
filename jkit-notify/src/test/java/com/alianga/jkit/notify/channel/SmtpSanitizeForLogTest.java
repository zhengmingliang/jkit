package com.alianga.jkit.notify.channel;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

/**
 * 覆盖 P0-14：SmtpChannel.sanitizeForLog 在保留 SMTP 响应可读性的同时，
 * 把 base64 凭证与超长 token 替换为 "***"。
 */
public class SmtpSanitizeForLogTest {

    @Test
    public void nullInputReturnsNull() {
        assertNull(SmtpChannel.sanitizeForLog(null));
    }

    @Test
    public void emptyStringReturnsEmpty() {
        assertEquals("", SmtpChannel.sanitizeForLog(""));
    }

    /**
     * 常见英文 535 响应应保留可读性（不会被误杀）。
     */
    @Test
    public void plainEnglishResponseUnchanged() {
        String s = "535 5.7.8 Error: authentication failed: authentication failure";
        assertEquals(s, SmtpChannel.sanitizeForLog(s));
    }

    /**
     * AUTH PLAIN base64（>=24 字符）必须被替换。
     */
    @Test
    public void authPlainBase64Masked() {
        // 真实场景：base64("user@example.com\0user@example.com\0password")
        // 计算：base64("user@example.com\0user@example.com\0password")
        String plain = "user@example.com\0user@example.com\0password";
        String base64 = java.util.Base64.getEncoder().encodeToString(plain.getBytes());
        // 预期是 36 字符，确认 >= 24
        org.junit.Assert.assertTrue("base64 length must be >= 24, got: " + base64.length(),
                base64.length() >= 24);
        String reply = "535 5.7.8 Error: authentication failed: " + base64;
        String out = SmtpChannel.sanitizeForLog(reply);
        org.junit.Assert.assertFalse("base64 leaked: " + out, out.contains(base64));
        org.junit.Assert.assertTrue("should still contain readable part: " + out,
                out.contains("535 5.7.8 Error: authentication failed: ***"));
    }

    /**
     * 长度 >= 24 的纯 base64 串（模拟 AUTH PLAIN 凭证）必须被规则 1 替换。
     * 注：若 base64 串紧贴 = 等号前缀（如 token=xxx），规则 2 会连同前缀一起吃掉，
     * 更保守安全，这里只验证 base64 内容本身已不在输出。
     */
    @Test
    public void longBase64TokenMasked() {
        // 一个 44 字符的纯 base64 串
        String longToken = java.util.Base64.getEncoder()
                .encodeToString("0123456789abcdef0123456789abcdef".getBytes());
        org.junit.Assert.assertEquals(44, longToken.length());
        String reply = "535 5.7.8 authentication failed, token=" + longToken;
        String out = SmtpChannel.sanitizeForLog(reply);
        org.junit.Assert.assertFalse("long base64 leaked: " + out, out.contains(longToken));
        // 至少 "535 5.7.8 authentication failed, " 应保留
        org.junit.Assert.assertTrue(out.contains("535 5.7.8 authentication failed, "));
    }

    /**
     * 超长连续无空白 token（>=48）被规则 2 替换。模拟服务器返回长 session id。
     */
    @Test
    public void veryLongContinuousTokenMasked() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 64; i++) {
            sb.append((char) ('a' + i % 26));
        }
        String longStr = sb.toString();
        String reply = "535 5.7.8 auth failed: " + longStr;
        String out = SmtpChannel.sanitizeForLog(reply);
        org.junit.Assert.assertFalse("long token leaked: " + out, out.contains(longStr));
        org.junit.Assert.assertTrue(out.contains("535 5.7.8 auth failed: ***"));
    }

    /**
     * 数字响应码应保留（不被规则误杀）。
     */
    @Test
    public void smtpCodePreserved() {
        String s = "550 5.1.1 User unknown";
        assertEquals(s, SmtpChannel.sanitizeForLog(s));
    }

    /**
     * 多行响应（每行独立净化）。
     */
    @Test
    public void multilineSanitized() {
        String longToken = java.util.Base64.getEncoder()
                .encodeToString("0123456789abcdef0123456789abcdef".getBytes());
        String s = "250-OK first\r\n535 5.7.8 auth failed: " + longToken;
        String out = SmtpChannel.sanitizeForLog(s);
        org.junit.Assert.assertFalse("leaked: " + out, out.contains(longToken));
        org.junit.Assert.assertTrue(out.contains("250-OK first\r\n535 5.7.8 auth failed: ***"));
    }

    /**
     * SmtpReplyException 携带<b>原始响应</b>（向后兼容契约：reply()/getMessage()
     * 与 2.0.4 及之前一致，调用方可能依赖原始响应做协议排查）；净化由本库的
     * 日志边界（LOG.warn 处 sanitizeForLog）负责，而不是改写异常数据。
     */
    @Test
    public void smtpReplyExceptionCarriesRawReply() throws Exception {
        String longToken = java.util.Base64.getEncoder()
                .encodeToString("0123456789abcdef0123456789abcdef".getBytes());
        String raw = "535 5.7.8 auth failed: " + longToken;

        // SmtpReplyException 是 protected 内部类，通过反射构造
        Class<?> cls = Class.forName("com.alianga.jkit.notify.channel.SmtpChannel$SmtpReplyException");
        java.lang.reflect.Constructor<?> ctor = cls.getDeclaredConstructor(String.class, int.class,
                Class.forName("com.alianga.jkit.notify.FailureType"));
        ctor.setAccessible(true);
        Object ex = ctor.newInstance(raw, 535, Enum.valueOf(
                (Class<? extends Enum>) Class.forName("com.alianga.jkit.notify.FailureType"),
                "CONFIG_ERROR"));

        java.lang.reflect.Method replyMethod = cls.getMethod("reply");
        String reply = (String) replyMethod.invoke(ex);
        org.junit.Assert.assertEquals("reply() must carry the raw server response", raw, reply);

        String msg = ((Throwable) ex).getMessage();
        org.junit.Assert.assertEquals("smtp error: " + raw, msg);

        // 日志边界：sanitizeForLog 应能把同一响应净化到可安全落盘
        String forLog = SmtpChannel.sanitizeForLog(raw);
        org.junit.Assert.assertFalse("log form leaked token: " + forLog, forLog.contains(longToken));
        org.junit.Assert.assertTrue(forLog.contains("535 5.7.8 auth failed: ***"));
    }

    /**
     * 一个标准英文短语 + 数字 + 空格的串不应被误杀（可读性保留）。
     */
    @Test
    public void readabilityPreserved() {
        String s = "451 4.3.0 Temporary server error, please try again later";
        assertEquals(s, SmtpChannel.sanitizeForLog(s));
    }
}
