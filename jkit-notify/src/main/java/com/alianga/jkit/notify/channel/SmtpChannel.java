package com.alianga.jkit.notify.channel;

import com.alianga.jkit.log.Log;
import com.alianga.jkit.notify.Attachment;
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
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.StringWriter;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.cert.X509Certificate;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

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
 * {@link ChannelConfig#fromName(String)} 显示名、
 * {@link ChannelConfig#to(String...)} 收件人（必填，可多个，逗号分隔）、
 * {@link ChannelConfig#cc(String...)} / {@link ChannelConfig#bcc(String...)} /
 * {@link ChannelConfig#replyTo(String)}；</li>
 * <li>{@link ChannelConfig#sslProtocols(String)}：握手协议版本钉扎（如 {@code TLSv1.2}）；</li>
 * <li>{@link ChannelConfig#trustAllCerts(boolean)}：企业自建网关自签证书时开启；</li>
 * <li>{@link ChannelConfig#autoSplit(boolean)}：超大附件按块拆成多封发送。</li>
 * </ul>
 *
 * <p>消息：TEXT 按纯文本发送；HTML 直发；MARKDOWN 经 {@link NotifyUtils#markdownToHtml(String)}
 * 转成 HTML 后按 {@code text/html} 发送。标题作为邮件主题。附件走 {@link Message#attachment}。
 * MIME 含 {@code Date} 与 {@code Message-ID}；DATA 阶段做 RFC 5321 dot-stuffing。
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
    private static final DateTimeFormatter RFC5322_DATE =
            DateTimeFormatter.ofPattern("EEE, dd MMM yyyy HH:mm:ss Z", Locale.US);
    private static final String[] USED_KEYS = {
            "smtpHost", "smtpPort", "username", "password", "from", "fromName",
            "to", "cc", "bcc", "replyTo", "ssl", "starttls", "sslProtocols",
            "trustAllCerts", "timeoutMs", "autoSplit", "maxAttachmentSize", "splitChunkSize"
    };

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
        return type == MessageType.TEXT || type == MessageType.MARKDOWN || type == MessageType.HTML;
    }

    @Override
    public SendResult send(Message message, ChannelConfig config) {
        required(config.username(), "smtp username is required");
        required(config.password(), "smtp password is required");
        if (config.to() == null || config.to().isEmpty()) {
            throw new IllegalArgumentException("smtp to is required (ChannelConfig.to)");
        }
        config.logUnused(LOG, id(), USED_KEYS);

        if (config.autoSplit() && message.attachments() != null && !message.attachments().isEmpty()) {
            return sendWithSplit(message, config);
        }
        return sendOnce(message, config, message.attachments());
    }

    /**
     * 超大附件按块拆成多封：未超限的附件随正文一封发出，超限的每个文件按 chunk 分多封。
     *
     * @param message 消息
     * @param config 渠道配置
     * @return 聚合结果
     */
    protected SendResult sendWithSplit(Message message, ChannelConfig config) {
        long maxSize = config.resolvedMaxAttachmentSize();
        long chunkSize = config.resolvedSplitChunkSize();
        List<Attachment> normal = new ArrayList<Attachment>();
        List<Attachment> oversized = new ArrayList<Attachment>();
        for (Attachment attachment : message.attachments()) {
            if (attachment.size() > maxSize) {
                oversized.add(attachment);
            } else {
                normal.add(attachment);
            }
        }
        List<SendResult> parts = new ArrayList<SendResult>();
        if (!normal.isEmpty() || oversized.isEmpty()) {
            parts.add(sendOnce(message, config, normal));
        }
        for (Attachment attachment : oversized) {
            long size = attachment.size();
            int total = (int) ((size + chunkSize - 1) / chunkSize);
            if (total < 1) {
                total = 1;
            }
            String sha = attachment.sha256Hex();
            for (int i = 0; i < total; i++) {
                String partName = attachment.filename() + ".part" + (i + 1);
                String subject = message.title("通知") + " [" + attachment.filename()
                        + " - Part " + (i + 1) + "/" + total + "]";
                String partContent = message.content() + splitInstructions(
                        attachment.filename(), i + 1, total, sha);
                Attachment slice = attachment.slice(i * chunkSize, chunkSize, partName);
                Message partMessage = typedCopy(message, subject, partContent).attachment(slice);
                parts.add(sendOnce(partMessage, config, partMessage.attachments()));
            }
        }
        return SendResult.aggregate(id(), parts);
    }

    private static Message typedCopy(Message source, String title, String content) {
        if (source.type() == MessageType.HTML) {
            return Message.html(title, content);
        }
        if (source.type() == MessageType.MARKDOWN) {
            return Message.markdown(title, content);
        }
        return Message.text(title, content);
    }

    private static String splitInstructions(String filename, int partNum, int totalParts, String sha256) {
        return "\n\n--- File Split Information ---\n"
                + "Original file: " + filename + "\n"
                + "This is part " + partNum + " of " + totalParts + "\n"
                + "SHA-256 of original: " + sha256 + "\n"
                + "To reassemble: cat " + filename + ".part1 " + filename + ".part2 ... > " + filename + "\n"
                + "-----------------------------\n";
    }

    /**
     * 发一封邮件（可带一组附件）。
     *
     * @param message 消息
     * @param config 渠道配置
     * @param attachments 附件，可为 {@code null}
     * @return 发送结果
     */
    protected SendResult sendOnce(Message message, ChannelConfig config, List<Attachment> attachments) {
        String username = required(config.username(), "smtp username is required");
        String password = required(config.password(), "smtp password is required");
        String from = config.from() != null ? config.from() : username;
        List<String> recipients = allRecipients(config);

        long start = System.currentTimeMillis();
        Socket socket = null;
        try {
            socket = connect(config);
            BufferedReader in = newReader(socket);
            BufferedWriter out = new BufferedWriter(
                    new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8));

            readReply(in);
            String ehloHost = ehloHost();
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
            for (String rcpt : recipients) {
                write(out, "RCPT TO:<" + rcpt + ">");
                readReply(in);
            }
            write(out, "DATA");
            readReply(in);
            writeMime(out, message, from, config, attachments == null
                    ? Collections.<Attachment>emptyList() : attachments);
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
            LOG.error("[{}] send error: {}", id(), e.getMessage());
            String detail = e.getClass().getSimpleName() + ": " + e.getMessage();
            if (e instanceof SocketTimeoutException && !config.ssl() && !config.starttls()) {
                detail += "; if the server expects SMTPS/STARTTLS, set ssl(true) or starttls(true)";
            }
            return SendResult.fail(id(), detail, System.currentTimeMillis() - start, FailureType.RETRYABLE);
        } finally {
            closeQuietly(socket);
        }
    }

    /**
     * RFC 5321：DATA 中以 {@code .} 开头的行要写成 {@code ..}，否则服务器会当成结束。
     *
     * @param mime MIME 报文
     * @return 转义后的报文
     */
    protected String dotStuff(String mime) {
        if (mime == null || mime.isEmpty()) {
            return "";
        }
        String normalized = mime.replace("\r\n", "\n").replace('\r', '\n');
        StringBuilder out = new StringBuilder(normalized.length() + 16);
        int i = 0;
        while (i < normalized.length()) {
            int nl = normalized.indexOf('\n', i);
            String line = nl < 0 ? normalized.substring(i) : normalized.substring(i, nl);
            if (line.startsWith(".")) {
                out.append('.');
            }
            out.append(line).append("\r\n");
            if (nl < 0) {
                break;
            }
            i = nl + 1;
        }
        if (out.length() >= 2) {
            return out.substring(0, out.length() - 2);
        }
        return out.toString();
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
            return FailureType.THROTTLED;
        }
        if (code >= 400 && code < 500) {
            return FailureType.RETRYABLE;
        }
        if (code == 530 || code == 534 || code == 535 || code == 538) {
            return FailureType.CONFIG_ERROR;
        }
        if (code == 501 && reply != null && reply.contains("修改密码")) {
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

    private static boolean implicitSsl(ChannelConfig config) {
        return config.ssl() || config.smtpPort() == 465;
    }

    /**
     * 取 SSLSocketFactory：默认用 JDK 默认信任链；trustAllCerts 时跳过校验。
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
     * 把 MIME 写入 DATA 阶段。正文仍走内存；附件按块 Base64，文件附件不整段载入。
     *
     * @param out 输出
     * @param message 消息
     * @param from 发件人
     * @param config 渠道配置
     * @param attachments 附件
     * @throws IOException 写入失败
     */
    protected void writeMime(BufferedWriter out, Message message, String from, ChannelConfig config,
                             List<Attachment> attachments) throws IOException {
        String subject = message.title("通知");
        String body = bodyOf(message);
        boolean html = message.type() == MessageType.HTML || message.type() == MessageType.MARKDOWN;
        String contentType = html ? "text/html; charset=UTF-8" : "text/plain; charset=UTF-8";
        String encodedBody = NotifyUtils.foldBase64(
                Base64.getEncoder().encodeToString(body.getBytes(StandardCharsets.UTF_8)));
        String date = ZonedDateTime.now().format(RFC5322_DATE);
        String messageId = "<" + UUID.randomUUID().toString() + "@" + ehloHost() + ">";

        StringBuilder headers = new StringBuilder();
        headers.append("From: ").append(mailbox(from, config.fromName())).append("\r\n");
        headers.append("To: ").append(mailboxList(config.to())).append("\r\n");
        if (config.cc() != null && !config.cc().isEmpty()) {
            headers.append("Cc: ").append(mailboxList(config.cc())).append("\r\n");
        }
        if (config.replyTo() != null && !config.replyTo().trim().isEmpty()) {
            headers.append("Reply-To: <").append(config.replyTo().trim()).append(">\r\n");
        }
        headers.append("Subject: ").append(encodedWord(subject)).append("\r\n");
        headers.append("Date: ").append(date).append("\r\n");
        headers.append("Message-ID: ").append(messageId).append("\r\n");
        headers.append("MIME-Version: 1.0\r\n");
        if (attachments == null || attachments.isEmpty()) {
            headers.append("Content-Type: ").append(contentType).append("\r\n");
            headers.append("Content-Transfer-Encoding: base64\r\n");
            headers.append("\r\n");
            headers.append(encodedBody);
            write(out, headers.toString());
            return;
        }
        String boundary = "----=_jkit_" + UUID.randomUUID().toString().replace("-", "");
        headers.append("Content-Type: multipart/mixed; boundary=\"").append(boundary).append("\"\r\n");
        headers.append("\r\n");
        headers.append("This is a multi-part message in MIME format.\r\n");
        headers.append("--").append(boundary).append("\r\n");
        headers.append("Content-Type: ").append(contentType).append("\r\n");
        headers.append("Content-Transfer-Encoding: base64\r\n");
        headers.append("\r\n");
        headers.append(encodedBody).append("\r\n");
        write(out, headers.toString());
        for (Attachment attachment : attachments) {
            StringBuilder partHead = new StringBuilder();
            partHead.append("--").append(boundary).append("\r\n");
            partHead.append("Content-Type: ").append(attachment.contentType())
                    .append("; name=\"").append(asciiFilename(attachment.filename())).append("\"\r\n");
            partHead.append("Content-Transfer-Encoding: base64\r\n");
            partHead.append("Content-Disposition: attachment; filename=\"")
                    .append(encodedWord(attachment.filename())).append("\"\r\n");
            partHead.append("\r\n");
            out.write(partHead.toString());
            InputStream in = attachment.openStream();
            try {
                NotifyUtils.writeFoldedBase64(in, out);
            } finally {
                in.close();
            }
            out.write("\r\n");
            out.flush();
        }
        write(out, "--" + boundary + "--");
    }

    /**
     * 组装完整 MIME（测试与无附件路径）。大附件请走 {@link #writeMime}。
     *
     * @param message 消息
     * @param from 发件人
     * @param config 渠道配置
     * @param attachments 附件
     * @return MIME 报文
     */
    protected String buildMime(Message message, String from, ChannelConfig config, List<Attachment> attachments) {
        StringWriter buffer = new StringWriter();
        try {
            writeMime(new BufferedWriter(buffer), message, from, config, attachments);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        return buffer.toString();
    }

    private static String mailbox(String address, String displayName) {
        if (displayName == null || displayName.trim().isEmpty()) {
            return "<" + address + ">";
        }
        return encodedWord(displayName.trim()) + " <" + address + ">";
    }

    private static String mailboxList(List<String> addresses) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < addresses.size(); i++) {
            if (i > 0) {
                out.append(", ");
            }
            out.append('<').append(addresses.get(i)).append('>');
        }
        return out.toString();
    }

    private static String encodedWord(String text) {
        return "=?UTF-8?B?" + Base64.getEncoder().encodeToString(text.getBytes(StandardCharsets.UTF_8)) + "?=";
    }

    private static String asciiFilename(String filename) {
        StringBuilder out = new StringBuilder();
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

    private static List<String> allRecipients(ChannelConfig config) {
        Set<String> recipients = new LinkedHashSet<String>();
        addAll(recipients, config.to());
        addAll(recipients, config.cc());
        addAll(recipients, config.bcc());
        return new ArrayList<String>(recipients);
    }

    private static void addAll(Set<String> target, List<String> source) {
        if (source == null) {
            return;
        }
        for (String item : source) {
            if (item != null && !item.isEmpty()) {
                target.add(item);
            }
        }
    }

    private static String ehloHost() {
        try {
            String host = InetAddress.getLocalHost().getCanonicalHostName();
            if (host != null && !host.trim().isEmpty()) {
                return host.trim();
            }
        } catch (Exception ignored) {
            // 回落到 localhost
        }
        return "localhost";
    }

    private static String bodyOf(Message message) {
        if (message.type() != MessageType.MARKDOWN) {
            return message.content();
        }
        return NotifyUtils.markdownToDocument(message.content(), true);
    }

    /**
     * 按 RFC 2045 要求把 Base64 正文按 76 字符折行（CRLF 分隔）。
     *
     * @param base64 单行 Base64
     * @return 折行结果
     */
    protected String foldBase64(String base64) {
        return NotifyUtils.foldBase64(base64);
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

    private static BufferedReader newReader(Socket socket) throws IOException {
        return new BufferedReader(
                new InputStreamReader(socket.getInputStream(), StandardCharsets.ISO_8859_1));
    }

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
