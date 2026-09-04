package com.alianga.jkit.notify.channel;

import com.alianga.jkit.log.Log;
import com.alianga.jkit.notify.ChannelConfig;
import com.alianga.jkit.notify.FailureType;
import com.alianga.jkit.notify.Message;
import com.alianga.jkit.notify.MessageType;
import com.alianga.jkit.notify.NotificationChannel;
import com.alianga.jkit.notify.NotifyUtils;
import com.alianga.jkit.notify.SendResult;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

/**
 * SMTP 邮件渠道，纯 Socket 实现（零第三方依赖）。
 *
 * <p>配置（{@link ChannelConfig#smtp(String, int)}）：
 * <ul>
 * <li>端口与加密：国内邮箱普遍用 <b>465 + {@link ChannelConfig#ssl(boolean) ssl(true)}</b>
 * （隐式 SSL；端口为 465 时即使未显式开启也会走 SMTPS，避免明文客户端与 TLS
 * 服务端互相空等到超时）；587 端口配 {@link ChannelConfig#starttls(boolean) starttls(true)}；
 * 25 明文，多数云厂商已封禁出站 25；</li>
 * <li>{@link ChannelConfig#username(String)} / {@link ChannelConfig#password(String)}：
 * 登录账号与<b>授权码</b>（不是网页登录密码，各家邮箱都要单独生成）；</li>
 * <li>{@link ChannelConfig#from(String)} 发件人（缺省取 username）、
 * {@link ChannelConfig#to(String...)} 收件人（必填，可多个）；</li>
 * <li>{@link ChannelConfig#sslProtocols(String)}：握手协议版本钉扎（如 {@code TLSv1.2}），
 * JDK 大版本调整默认协议集导致握手失败时用它；</li>
 * <li>{@link ChannelConfig#trustAllCerts(boolean)}：企业自建网关自签证书时开启。</li>
 * </ul>
 *
 * <p>消息：TEXT 按纯文本发送；HTML 直发；MARKDOWN 经 {@link NotifyUtils#markdownToHtml(String)}
 * 转成 HTML 后按 {@code text/html} 发送。标题作为邮件主题，正文作为邮件体，均按 UTF-8 + Base64 编码传输。
 *
 * <p>失败分类：SMTP 4xx 是临时故障（可重试，其中 421/450/451/452 归 THROTTLED），
 * 5xx 是永久拒绝，530/534/535/538 认证类归配置错误。
 *
 * <p>响应判定：DATA 结束后收到 {@code 250}。
 *
 * @author 郑明亮
 * @since 2.0.1
 */
public class SmtpChannel implements NotificationChannel {
    /**
     * 渠道 id。
     */
    public static final String ID = "smtp";

    private static final Log LOG = Log.get(SmtpChannel.class);

    @Override
    public String id() {
        return ID;
    }

    @Override
    public String name() {
        return "邮件 SMTP";
    }

    @Override
    public boolean supports(MessageType type) {
        return true;
    }

    @Override
    public SendResult send(Message message, ChannelConfig config) {
        // 配置校验前置：缺失时直接作为编程错误抛出，不发起网络连接
        String username = required(config.username(), "smtp username is required");
        String password = required(config.password(), "smtp password is required");
        if (config.to() == null || config.to().isEmpty()) {
            throw new IllegalArgumentException("smtp to is required (ChannelConfig.to)");
        }
        String from = config.from() != null ? config.from() : username;

        long start = System.currentTimeMillis();
        Socket socket = null;
        try {
            socket = connect(config);
            BufferedReader in = newReader(socket);
            BufferedWriter out = new BufferedWriter(
                    new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8));

            readReply(in);
            String ehloHost = "localhost";
            write(out, "EHLO " + ehloHost);
            readReply(in);
            if (config.starttls() && !implicitSsl(config)) {
                write(out, "STARTTLS");
                readReply(in);
                socket = upgradeTls(socket, config);
                in = newReader(socket);
                out = new BufferedWriter(
                        new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8));
                write(out, "EHLO " + ehloHost);
                readReply(in);
            }
            write(out, "AUTH LOGIN");
            readReply(in);
            write(out, Base64.getEncoder().encodeToString(username.getBytes(StandardCharsets.UTF_8)));
            readReply(in);
            write(out, Base64.getEncoder().encodeToString(password.getBytes(StandardCharsets.UTF_8)));
            readReply(in);

            write(out, "MAIL FROM:<" + from + ">");
            readReply(in);
            for (String to : config.to()) {
                write(out, "RCPT TO:<" + to + ">");
                readReply(in);
            }
            write(out, "DATA");
            readReply(in);
            write(out, buildMime(message, from, config.to()));
            write(out, ".");
            String finalReply = readReply(in);
            write(out, "QUIT");
            readReply(in);

            long elapsed = System.currentTimeMillis() - start;
            if (finalReply.startsWith("250")) {
                return SendResult.ok(id(), 250, finalReply, elapsed);
            }
            return SendResult.fail(id(), replyCode(finalReply), finalReply,
                    "smtp rejected: " + finalReply, elapsed, classifyReply(finalReply));
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (SmtpReplyException e) {
            LOG.warn("[{}] smtp rejected({}): {}", id(), e.failureType(), e.getMessage());
            return SendResult.fail(id(), e.code(), e.reply(), e.getMessage(),
                    System.currentTimeMillis() - start, e.failureType());
        } catch (Exception e) {
            // 网络层异常（超时、连接重置）按可重试处理
            LOG.error("[{}] send error: {}", id(), e.getMessage());
            String detail = e.getClass().getSimpleName() + ": " + e.getMessage();
            if (e instanceof SocketTimeoutException && !config.ssl() && !config.starttls()) {
                detail += "; if the server expects SMTPS/STARTTLS, set ssl(true) or starttls(true)";
            }
            return SendResult.fail(id(), detail, FailureType.RETRYABLE);
        } finally {
            closeQuietly(socket);
        }
    }

    /**
     * 按 SMTP 响应码判定失败类别：4xx 是临时故障（邮箱满、服务不可用、灰名单）可重试，
     * 5xx 是永久拒绝；535/530/534 这类认证问题归为配置错误；421/450/451/452 常见于限流。
     *
     * @param reply SMTP 响应行
     * @return 失败类别
     */
    protected FailureType classifyReply(String reply) {
        int code = replyCode(reply);
        if (code == 421 || code == 450 || code == 451 || code == 452) {
            // 服务不可用 / 邮箱忙 / 本地错误 / 存储不足，多为限流或临时资源问题
            return FailureType.THROTTLED;
        }
        if (code >= 400 && code < 500) {
            return FailureType.RETRYABLE;
        }
        if (code == 530 || code == 534 || code == 535 || code == 538) {
            // 需要认证 / 认证机制不支持 / 认证失败
            return FailureType.CONFIG_ERROR;
        }
        if (code == 501 && reply != null && reply.contains("修改密码")) {
            // 腾讯企业邮：授权码能 AUTH，但未在网页完成改密前拒绝 MAIL FROM
            return FailureType.CONFIG_ERROR;
        }
        return FailureType.PERMANENT;
    }

    private static int replyCode(String reply) {
        if (reply == null || reply.length() < 3) {
            return 0;
        }
        try {
            return Integer.parseInt(reply.substring(0, 3));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /**
     * SMTP 协议级拒绝，携带响应码与失败类别。
     */
    protected static final class SmtpReplyException extends IOException {
        private static final long serialVersionUID = 1L;

        private final String reply;
        private final int code;
        private final FailureType failureType;

        SmtpReplyException(String reply, int code, FailureType failureType) {
            super("smtp error: " + reply);
            this.reply = reply;
            this.code = code;
            this.failureType = failureType;
        }

        /**
         * @return 原始响应行
         */
        public String reply() {
            return reply;
        }

        /**
         * @return SMTP 响应码
         */
        public int code() {
            return code;
        }

        /**
         * @return 失败类别
         */
        public FailureType failureType() {
            return failureType;
        }
    }

    /**
     * 建立初始连接：按配置选择明文 Socket 或隐式 SSL Socket。
     *
     * <p>端口 465 是 SMTPS：即使未调用 {@link ChannelConfig#ssl(boolean) ssl(true)} 也会先
     * TCP 再包装 TLS。否则客户端读 220、服务端等 ClientHello，双方空等到超时。
     * 包装时带上主机名，以便 SNI 与证书主机名校验。
     *
     * @param config 渠道配置
     * @return 已连接的 Socket
     * @throws IOException 连接失败
     */
    protected Socket connect(ChannelConfig config) throws IOException {
        String host = required(config.smtpHost(), "smtp host is required (ChannelConfig.smtp)");
        int port = config.smtpPort() > 0 ? config.smtpPort() : 25;
        int timeout = timeoutOf(config);
        Socket socket = new Socket();
        socket.connect(new InetSocketAddress(host, port), timeout);
        socket.setSoTimeout(timeout);
        if (implicitSsl(config)) {
            return wrapTls(socket, config);
        }
        return socket;
    }

    /**
     * 把明文 Socket 升级为 TLS Socket（STARTTLS 场景），返回新 Socket，旧的已关闭。
     *
     * @param plain 明文 Socket
     * @param config 渠道配置
     * @return TLS Socket
     * @throws IOException 升级失败
     */
    protected Socket upgradeTls(Socket plain, ChannelConfig config) throws IOException {
        return wrapTls(plain, config);
    }

    /**
     * 在已连接的 TCP Socket 上做 TLS 握手。使用 {@code createSocket(plain, host, ...)} 把
     * 主机名传给 SNI；公网证书默认做 HTTPS 主机名校验，{@code trustAllCerts} 时跳过。
     *
     * @param plain 已连接的明文 Socket
     * @param config 渠道配置
     * @return TLS Socket
     * @throws IOException 握手失败
     */
    private Socket wrapTls(Socket plain, ChannelConfig config) throws IOException {
        String host = config.smtpHost();
        SSLSocket tls = (SSLSocket) sslFactory(config).createSocket(plain, host, plain.getPort(), true);
        applyProtocols(tls, config);
        tls.setUseClientMode(true);
        tls.setSoTimeout(timeoutOf(config));
        if (!config.trustAllCerts()) {
            SSLParameters params = tls.getSSLParameters();
            params.setEndpointIdentificationAlgorithm("HTTPS");
            tls.setSSLParameters(params);
        }
        tls.startHandshake();
        return tls;
    }

    /**
     * 是否走隐式 SSL（SMTPS）：显式 {@code ssl(true)}，或端口为 465。
     *
     * @param config 渠道配置
     * @return 是否隐式 SSL
     */
    private static boolean implicitSsl(ChannelConfig config) {
        return config.ssl() || config.smtpPort() == 465;
    }

    /**
     * 取 SSLSocketFactory：默认用 JDK 默认信任链；
     * {@link ChannelConfig#trustAllCerts(boolean)} 为 {@code true} 时用信任所有证书的上下文
     * （企业自建网关自签证书场景）。
     *
     * @param config 渠道配置
     * @return SSLSocketFactory
     * @throws IOException 构造 SSLContext 失败
     */
    protected SSLSocketFactory sslFactory(ChannelConfig config) throws IOException {
        if (!config.trustAllCerts()) {
            return (SSLSocketFactory) SSLSocketFactory.getDefault();
        }
        try {
            SSLContext context = SSLContext.getInstance("TLS");
            context.init(null, new TrustManager[]{new TrustAllManager()}, null);
            return context.getSocketFactory();
        } catch (GeneralSecurityException e) {
            throw new IOException("init trust-all ssl context failed: " + e.getMessage(), e);
        }
    }

    /**
     * 应用 {@link ChannelConfig#sslProtocols(String)} 指定的协议版本。
     *
     * @param socket SSL Socket
     * @param config 渠道配置
     */
    protected void applyProtocols(SSLSocket socket, ChannelConfig config) {
        String protocols = config.sslProtocols();
        if (protocols == null || protocols.trim().isEmpty()) {
            return;
        }
        List<String> wanted = new ArrayList<String>();
        for (String item : protocols.split(",")) {
            String trimmed = item.trim();
            if (!trimmed.isEmpty()) {
                wanted.add(trimmed);
            }
        }
        if (!wanted.isEmpty()) {
            socket.setEnabledProtocols(wanted.toArray(new String[0]));
        }
    }

    private static int timeoutOf(ChannelConfig config) {
        return config.timeoutMs() > 0 ? config.timeoutMs() : 10000;
    }

    /**
     * 信任所有证书的 TrustManager，仅在显式开启 trustAllCerts 时使用。
     */
    private static final class TrustAllManager implements X509TrustManager {
        @Override
        public void checkClientTrusted(X509Certificate[] chain, String authType) {
            // 显式选择信任所有证书
        }

        @Override
        public void checkServerTrusted(X509Certificate[] chain, String authType) {
            // 显式选择信任所有证书
        }

        @Override
        public X509Certificate[] getAcceptedIssuers() {
            return new X509Certificate[0];
        }
    }

    /**
     * 组装完整 MIME 邮件报文（含头部与正文，Subject/正文均为 UTF-8 Base64）。
     *
     * @param message 消息
     * @param from 发件人
     * @param to 收件人列表
     * @return MIME 报文（行以 CRLF 分隔，不含结尾句点）
     */
    protected String buildMime(Message message, String from, List<String> to) {
        String subject = message.title("通知");
        String body = bodyOf(message);
        boolean html = message.type() == MessageType.HTML || message.type() == MessageType.MARKDOWN;
        String contentType = html ? "text/html; charset=UTF-8" : "text/plain; charset=UTF-8";
        String encodedSubject = "=?UTF-8?B?"
                + Base64.getEncoder().encodeToString(subject.getBytes(StandardCharsets.UTF_8)) + "?=";
        String encodedBody = foldBase64(
                Base64.getEncoder().encodeToString(body.getBytes(StandardCharsets.UTF_8)));

        StringBuilder mime = new StringBuilder();
        mime.append("From: <").append(from).append(">\r\n");
        mime.append("To: ");
        for (int i = 0; i < to.size(); i++) {
            if (i > 0) {
                mime.append(", ");
            }
            mime.append('<').append(to.get(i)).append('>');
        }
        mime.append("\r\n");
        mime.append("Subject: ").append(encodedSubject).append("\r\n");
        mime.append("MIME-Version: 1.0\r\n");
        mime.append("Content-Type: ").append(contentType).append("\r\n");
        mime.append("Content-Transfer-Encoding: base64\r\n");
        mime.append("\r\n");
        mime.append(encodedBody);
        return mime.toString();
    }

    /**
     * TEXT 原样；HTML 原样；MARKDOWN 转 HTML 并套一层带 charset 的文档壳，方便邮件客户端渲染。
     *
     * @param message 消息
     * @return 邮件正文
     */
    private static String bodyOf(Message message) {
        if (message.type() != MessageType.MARKDOWN) {
            return message.content();
        }
        String fragment = NotifyUtils.markdownToHtml(message.content());
        return "<!DOCTYPE html><html><head><meta charset=\"UTF-8\">"
                + "<style>"
                + "body{font-family:sans-serif;line-height:1.6;color:#222}"
                + "pre{background:#f6f8fa;padding:12px;overflow:auto;"
                + "border-radius:6px;font-size:13px;white-space:pre}"
                + "code{font-family:ui-monospace,Menlo,Consolas,monospace;"
                + "background:#f6f8fa;padding:0 .3em}"
                + "pre code{background:none;padding:0}"
                + "table{border-collapse:collapse;margin:12px 0;max-width:100%}"
                + "th,td{border:1px solid #d0d7de;padding:6px 10px;text-align:left}"
                + "th{background:#f6f8fa}"
                + "blockquote{border-left:4px solid #d0d7de;margin:0;padding:0 12px;color:#57606a}"
                + "</style></head><body>"
                + fragment
                + "</body></html>";
    }

    /**
     * 按 RFC 2045 要求把 Base64 正文按 76 字符折行（CRLF 分隔）。
     *
     * @param base64 单行 Base64
     * @return 折行结果
     */
    protected String foldBase64(String base64) {
        if (base64 == null || base64.isEmpty()) {
            return "";
        }
        StringBuilder out = new StringBuilder(base64.length() + base64.length() / 76 * 2 + 2);
        for (int i = 0; i < base64.length(); i += 76) {
            int end = Math.min(i + 76, base64.length());
            if (i > 0) {
                out.append("\r\n");
            }
            out.append(base64, i, end);
        }
        return out.toString();
    }

    /**
     * 读取一条 SMTP 多行回复（{@code 250-...} 续行、{@code 250 ...} 结束）。
     *
     * @param in 输入流
     * @return 回复首行
     * @throws IOException 读取失败
     */
    protected String readReply(BufferedReader in) throws IOException {
        String line = decodeSmtpLine(in.readLine());
        if (line == null) {
            throw new IOException("smtp connection closed");
        }
        while (line.length() >= 4 && line.charAt(3) == '-') {
            line = decodeSmtpLine(in.readLine());
            if (line == null) {
                throw new IOException("smtp connection closed");
            }
        }
        if (line.startsWith("4") || line.startsWith("5")) {
            throw new SmtpReplyException(line, replyCode(line), classifyReply(line));
        }
        return line;
    }

    /**
     * 命令通道按 ISO-8859-1 逐字节读取，避免 UTF-8 解码器把国内邮箱的 GBK 回复吞成乱码。
     *
     * @param socket 已连接 Socket
     * @return reader
     * @throws IOException 取输入流失败
     */
    private static BufferedReader newReader(Socket socket) throws IOException {
        return new BufferedReader(
                new InputStreamReader(socket.getInputStream(), StandardCharsets.ISO_8859_1));
    }

    /**
     * 把命令通道上的一行解码成可读文本：纯 ASCII 原样返回；含高位字节时优先 UTF-8，
     * 非法 UTF-8 再按 GB18030 解（163 / 腾讯企业邮等回复中文走 GBK）。
     *
     * @param latin1 ISO-8859-1 读出的一行（可能含 GBK 字节）
     * @return 解码后的一行；输入为 {@code null} 时返回 {@code null}
     */
    private static String decodeSmtpLine(String latin1) {
        if (latin1 == null) {
            return null;
        }
        byte[] raw = latin1.getBytes(StandardCharsets.ISO_8859_1);
        boolean high = false;
        for (int i = 0; i < raw.length; i++) {
            if (raw[i] < 0) {
                high = true;
                break;
            }
        }
        if (!high) {
            return latin1;
        }
        String utf8 = new String(raw, StandardCharsets.UTF_8);
        if (utf8.indexOf('\uFFFD') < 0) {
            return utf8;
        }
        try {
            return new String(raw, Charset.forName("GB18030"));
        } catch (Exception ignored) {
            return latin1;
        }
    }

    /**
     * 写一条 SMTP 命令或数据块（自动补 CRLF + flush）。
     *
     * <p>DATA 阶段的多行报文必须以 CRLF 结尾，否则结尾句点会粘连到正文最后一行，
     * 服务器无法识别报文结束。
     *
     * @param out 输出流
     * @param command 命令或多行数据块
     * @throws IOException 写入失败
     */
    protected void write(BufferedWriter out, String command) throws IOException {
        out.write(command);
        if (!command.endsWith("\r\n")) {
            out.write("\r\n");
        }
        out.flush();
    }

    private static String required(String value, String message) {
        if (value == null || value.isEmpty()) {
            throw new IllegalArgumentException(message);
        }
        return value;
    }

    private static void closeQuietly(Socket socket) {
        if (socket != null) {
            try {
                socket.close();
            } catch (IOException ignored) {
                // 关闭失败无需处理
            }
        }
    }
}
