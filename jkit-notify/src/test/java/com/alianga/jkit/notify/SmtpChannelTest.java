package com.alianga.jkit.notify;

import com.alianga.jkit.notify.channel.SmtpChannel;

import org.junit.Test;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Locale;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * SMTP 渠道黑盒测试：每个用例起一个独立假 SMTP 服务器，验证命令序列与 MIME 报文。
 *
 * @author 郑明亮
 * @since 2.0.1
 */
public class SmtpChannelTest {

    /**
     * 单会话假 SMTP 服务器：记录客户端命令与 DATA 报文，用完即关。
     */
    private static final class FakeSmtp {
        private final ServerSocket serverSocket;
        private final List<String> commands = new ArrayList<String>();
        private volatile boolean rejectData;
        private volatile String dataReply;
        private volatile String authReply;
        private volatile byte[] mailFromReplyBytes;

        private FakeSmtp() throws Exception {
            serverSocket = new ServerSocket(0);
        }

        private int port() {
            return serverSocket.getLocalPort();
        }

        private void start() {
            Thread acceptor = new Thread(new Runnable() {
                @Override
                public void run() {
                    try {
                        Socket socket = serverSocket.accept();
                        socket.setSoTimeout(15000);
                        serve(socket);
                    } catch (Exception ignored) {
                        // 客户端异常断开时安静退出
                    }
                }
            });
            acceptor.setDaemon(true);
            acceptor.start();
        }

        private void serve(Socket socket) throws Exception {
            BufferedReader in = new BufferedReader(
                    new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
            BufferedWriter out = new BufferedWriter(
                    new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8));
            out.write("220 fake.local ESMTP\r\n");
            out.flush();
            String line;
            while ((line = in.readLine()) != null) {
                commands.add(line);
                String upper = line.toUpperCase(Locale.ROOT);
                if (upper.startsWith("EHLO")) {
                    out.write("250-fake.local\r\n250-AUTH LOGIN\r\n250 8BITMIME\r\n");
                } else if (upper.startsWith("AUTH")) {
                    out.write("334 " + Base64.getEncoder()
                            .encodeToString("Username:".getBytes(StandardCharsets.UTF_8)) + "\r\n");
                } else if (line.equals("dXNlcg==") || line.equals("cGFzcw==")) {
                    // AUTH LOGIN 的用户名/密码应答（user/pass 的 Base64）
                    out.write(authReply != null ? authReply + "\r\n" : "235 authentication successful\r\n");
                } else if (upper.startsWith("MAIL")) {
                    if (mailFromReplyBytes != null) {
                        out.flush();
                        socket.getOutputStream().write(mailFromReplyBytes);
                        socket.getOutputStream().flush();
                        continue;
                    }
                    out.write("250 ok\r\n");
                } else if (upper.startsWith("RCPT")) {
                    out.write("250 ok\r\n");
                } else if (upper.startsWith("DATA")) {
                    out.write("354 go ahead\r\n");
                    // 先 flush 再读正文：客户端在等 354，不 flush 会双方互等死锁
                    out.flush();
                    StringBuilder data = new StringBuilder();
                    while ((line = in.readLine()) != null && !".".equals(line)) {
                        data.append(line).append("\r\n");
                    }
                    commands.add("DATA:" + data);
                    if (dataReply != null) {
                        out.write(dataReply + "\r\n");
                    } else {
                        out.write(rejectData ? "550 rejected\r\n" : "250 accepted\r\n");
                    }
                } else if (upper.startsWith("QUIT")) {
                    out.write("221 bye\r\n");
                    out.flush();
                    socket.close();
                    return;
                } else {
                    out.write("250 ok\r\n");
                }
                out.flush();
            }
            socket.close();
        }

        private void stop() throws Exception {
            serverSocket.close();
        }
    }

    private static FakeSmtp smtp;

    /**
     * 全流程：命令序列、AUTH LOGIN 的 Base64 应答、MIME 报文内容。
     */
    @Test
    public void fullFlowCommandsAndMime() throws Exception {
        smtp = new FakeSmtp();
        smtp.start();
        try {
            SendResult result = NotificationManager.send(SmtpChannel.ID,
                    Message.text("告警", "CPU 95%"), config());
            assertTrue(result.toString(), result.isSuccess());
            assertEquals(250, result.status());

            assertTrue(smtp.commands.contains("EHLO localhost"));
            assertTrue("AUTH LOGIN sequence missing: " + smtp.commands,
                    smtp.commands.contains("AUTH LOGIN"));
            int authIndex = smtp.commands.indexOf("AUTH LOGIN");
            assertEquals("dXNlcg==", smtp.commands.get(authIndex + 1));
            assertEquals("cGFzcw==", smtp.commands.get(authIndex + 2));
            assertTrue(smtp.commands.contains("MAIL FROM:<user>"));
            assertTrue(smtp.commands.contains("RCPT TO:<ops@example.com>"));

            String data = null;
            for (String command : smtp.commands) {
                if (command.startsWith("DATA:")) {
                    data = command.substring("DATA:".length());
                }
            }
            assertNotNull(data);
            assertTrue(data, data.contains("From: <user>"));
            assertTrue(data, data.contains("To: <ops@example.com>"));
            assertTrue(data, data.contains("MIME-Version: 1.0"));
            assertTrue(data, data.contains("text/plain; charset=UTF-8"));
            assertTrue(data, data.contains("Content-Transfer-Encoding: base64"));
            assertTrue(data, data.contains("Subject: =?UTF-8?B?"
                    + Base64.getEncoder().encodeToString("告警".getBytes(StandardCharsets.UTF_8)) + "?="));
            String expectedBody = Base64.getEncoder()
                    .encodeToString("CPU 95%".getBytes(StandardCharsets.UTF_8));
            assertTrue(data, data.contains(expectedBody));
        } finally {
            smtp.stop();
        }
    }

    /**
     * MARKDOWN 转成 HTML 后按 text/html 发送，不再当纯文本。
     */
    @Test
    public void markdownSentAsHtml() throws Exception {
        smtp = new FakeSmtp();
        smtp.start();
        try {
            SendResult result = NotificationManager.send(SmtpChannel.ID,
                    Message.markdown("周报", "## 本周完成\n- 模块 A"), config());
            assertTrue(result.toString(), result.isSuccess());
            String data = mimeData();
            assertTrue(data, data.contains("text/html; charset=UTF-8"));
            String body = decodeMimeBody(data);
            assertTrue(body, body.contains("<h2>本周完成</h2>"));
            assertTrue(body, body.contains("<li>模块 A</li>"));
            assertTrue(body, body.contains("<!DOCTYPE html>"));
        } finally {
            smtp.stop();
        }
    }

    /**
     * HTML 消息保持原样，不做 markdown 转换。
     */
    @Test
    public void htmlPassedThrough() throws Exception {
        smtp = new FakeSmtp();
        smtp.start();
        try {
            SendResult result = NotificationManager.send(SmtpChannel.ID,
                    Message.html("报表", "<b>营收</b> 100 万"), config());
            assertTrue(result.toString(), result.isSuccess());
            String data = mimeData();
            assertTrue(data, data.contains("text/html; charset=UTF-8"));
            assertEquals("<b>营收</b> 100 万", decodeMimeBody(data));
        } finally {
            smtp.stop();
        }
    }

    /**
     * DATA 被拒（550 永久拒绝）：失败类别为 PERMANENT，不该重试。
     */
    @Test
    public void dataRejectedReturnsFail() throws Exception {
        smtp = new FakeSmtp();
        smtp.rejectData = true;
        smtp.start();
        try {
            SendResult result = NotificationManager.send(SmtpChannel.ID,
                    Message.text("标题", "内容"), config());
            assertFalse(result.isSuccess());
            assertTrue(result.error(), result.error().contains("550"));
            assertEquals(FailureType.PERMANENT, result.failureType());
            assertFalse(result.isRetryable());
        } finally {
            smtp.stop();
        }
    }

    /**
     * 4xx 临时故障（451）可重试；421/450/451/452 归 THROTTLED。
     */
    @Test
    public void transientReplyIsRetryable() throws Exception {
        smtp = new FakeSmtp();
        smtp.dataReply = "451 local error, try again";
        smtp.start();
        try {
            SendResult result = NotificationManager.send(SmtpChannel.ID,
                    Message.text("标题", "内容"), config());
            assertFalse(result.isSuccess());
            assertEquals(FailureType.THROTTLED, result.failureType());
            assertTrue(result.isRetryable());
        } finally {
            smtp.stop();
        }
    }

    /**
     * 国内邮箱 SMTP 回复常为 GBK：解码后应得到可读中文，并归 CONFIG_ERROR。
     */
    @Test
    public void gbkMailFromReplyIsDecoded() throws Exception {
        smtp = new FakeSmtp();
        smtp.mailFromReplyBytes = "501 请登录exmail.qq.com修改密码 \r\n"
                .getBytes(Charset.forName("GBK"));
        smtp.start();
        try {
            SendResult result = NotificationManager.send(SmtpChannel.ID,
                    Message.text("标题", "内容"), config());
            assertFalse(result.isSuccess());
            assertTrue(result.error(), result.error().contains("请登录exmail.qq.com修改密码"));
            assertEquals(FailureType.CONFIG_ERROR, result.failureType());
            assertFalse(result.isRetryable());
        } finally {
            smtp.stop();
        }
    }

    /**
     * 认证失败（535）归 CONFIG_ERROR，重试无意义。
     */
    @Test
    public void authFailureIsConfigError() throws Exception {
        smtp = new FakeSmtp();
        smtp.authReply = "535 authentication failed";
        smtp.start();
        try {
            SendResult result = NotificationManager.send(SmtpChannel.ID,
                    Message.text("标题", "内容"), config());
            assertFalse(result.isSuccess());
            assertEquals(FailureType.CONFIG_ERROR, result.failureType());
            assertFalse(result.isRetryable());
        } finally {
            smtp.stop();
        }
    }

    /**
     * 配置缺收件人属于编程错误，直接抛出（服务器不会被连接）。
     */
    @Test(expected = IllegalArgumentException.class)
    public void missingToRejected() {
        NotificationManager.send(SmtpChannel.ID, Message.text("hi"),
                ChannelConfig.smtp("127.0.0.1", 25).username("u").password("p"));
    }

    /**
     * 连接不上服务器：返回 fail 而不是抛异常。
     */
    @Test
    public void networkErrorReturnsFail() throws Exception {
        ServerSocket holder = new ServerSocket(0);
        int closedPort = holder.getLocalPort();
        holder.close();
        SendResult result = NotificationManager.send(SmtpChannel.ID,
                Message.text("hi"),
                ChannelConfig.smtp("127.0.0.1", closedPort)
                        .username("u").password("p").to("a@b.c").timeoutMs(3000));
        assertFalse(result.isSuccess());
    }

    private static String mimeData() {
        for (String command : smtp.commands) {
            if (command.startsWith("DATA:")) {
                return command.substring("DATA:".length());
            }
        }
        throw new AssertionError("DATA not captured: " + smtp.commands);
    }

    private static String decodeMimeBody(String data) {
        int blank = data.indexOf("\r\n\r\n");
        assertTrue("missing header/body separator: " + data, blank >= 0);
        String base64 = data.substring(blank + 4).replace("\r\n", "");
        return new String(Base64.getDecoder().decode(base64), StandardCharsets.UTF_8);
    }

    private static ChannelConfig config() {
        return ChannelConfig.smtp("127.0.0.1", smtp.port())
                .username("user")
                .password("pass")
                .to("ops@example.com");
    }
}
