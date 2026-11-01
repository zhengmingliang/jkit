package com.alianga.jkit.storage.engine;

import com.alianga.jkit.storage.StorageErrorCode;
import com.alianga.jkit.storage.StorageException;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

import java.io.BufferedOutputStream;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.Charset;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 极简 FTP 客户端：基于 {@code java.net.Socket} 的纯 JDK 实现，零第三方依赖，
 * 供 {@link FtpFileStorage} 专用。
 *
 * <p>负责：控制连接建立与登录（USER/PASS/TYPE I/FEAT/PWD）、应答解析（单行
 * "250 text" 与 "211-text" 多行，读到 "211 " 结束）、被动模式 PASV 地址解析与
 * 数据连接建立（亦支持主动 PORT）、传输命令后的流式泵送与完成应答读取。</p>
 *
 * <p>登录失败统一抛 {@link StorageException}（{@link StorageErrorCode#AUTH_FAILED}）；
 * 其余 socket 级问题以 {@link IOException} 向上抛出，由引擎映射为统一错误码。</p>
 *
 * @author 郑明亮
 */
final class FtpClient {
    /** 控制连接与数据连接的建立超时（毫秒）。 */
    static final int CONNECT_TIMEOUT_MS = 10_000;
    /** 控制连接与数据连接的读写超时（毫秒）。 */
    static final int SO_TIMEOUT_MS = 30_000;
    /** 匿名登录使用的用户名。 */
    private static final String ANONYMOUS_USER = "anonymous";
    /** 匿名登录口令（惯例填邮箱，这里填占位值）。 */
    private static final String ANONYMOUS_PASSWORD = "jkit@local";
    /** PASV 应答中的地址段：h1,h2,h3,h4,p1,p2。 */
    private static final Pattern PASV_ADDRESS = Pattern.compile(
            "(\\d{1,3}),(\\d{1,3}),(\\d{1,3}),(\\d{1,3}),(\\d{1,3}),(\\d{1,3})");

    /** FTPS 模式。 */
    enum TlsMode {
        /** 明文 FTP（不加密）。 */
        OFF,
        /** 显式 FTPS：明文连上后 AUTH TLS 升级（RFC 4217，常用 21 口）。 */
        EXPLICIT,
        /** 隐式 FTPS：连上即 TLS 握手（惯例 990 口）。 */
        IMPLICIT
    }

    private final String host;
    private final int port;
    private final String user;
    private final String password;
    private final boolean passive;
    private final Charset charset;
    private final TlsMode tlsMode;
    private final boolean trustAllCerts;
    private Socket control;
    private BufferedReader reader;
    private OutputStream writer;
    private ServerSocket activeListener;
    private Reply lastReply;
    private boolean mlsdSupported;
    private String home = "";

    /**
     * 构造客户端（不建立连接，需随后调用 {@link #connect()}）。
     *
     * @param host     服务器地址
     * @param port     服务器端口
     * @param user     用户名，空白视为匿名
     * @param password 口令
     * @param passive  true 为被动模式，false 为主动模式
     * @param charset  控制连接编码
     * @param tlsMode  FTPS 模式（OFF / EXPLICIT / IMPLICIT）
     * @param trustAllCerts true 信任全部服务器证书（自签名场景；默认应走 CA 校验）
     */
    FtpClient(String host, int port, String user, String password, boolean passive, Charset charset,
              TlsMode tlsMode, boolean trustAllCerts) {
        this.host = host;
        this.port = port;
        this.user = user;
        this.password = password;
        this.passive = passive;
        this.charset = charset;
        this.tlsMode = tlsMode;
        this.trustAllCerts = trustAllCerts;
    }

    /**
     * 一条 FTP 应答：三位状态码 + 文本（多行应答的文本按行拼接）。
     */
    static final class Reply {
        private final int code;
        private final String text;

        Reply(int code, String text) {
            this.code = code;
            this.text = text;
        }

        int code() {
            return code;
        }

        String text() {
            return text;
        }

        /** 1yz 积极预备应答：传输开始。 */
        boolean isPreliminary() {
            return code >= 100 && code < 200;
        }

        /** 2yz 应答：操作成功。 */
        boolean isPositive() {
            return code >= 200 && code < 300;
        }
    }

    /**
     * 建立控制连接并完成登录：问候语 → USER/PASS → TYPE I → FEAT → PWD。
     */
    void connect() throws IOException {
        Socket socket = new Socket();
        try {
            socket.connect(new InetSocketAddress(host, port), CONNECT_TIMEOUT_MS);
        } catch (IOException e) {
            closeQuietly(socket);
            throw e;
        }
        socket.setSoTimeout(SO_TIMEOUT_MS);
        if (tlsMode == TlsMode.IMPLICIT) {
            // 隐式 FTPS：TCP 建连后立即 TLS 握手，问候语在加密通道内
            control = wrapTls(socket);
        } else {
            control = socket;
        }
        resetStreams();
        Reply greeting = readReply();
        if (greeting.code() != 220) {
            close();
            throw new StorageException(StorageErrorCode.CONNECT_FAILED,
                    "FTP 服务未就绪（" + greeting.code() + " " + greeting.text() + "）", "-");
        }
        if (tlsMode == TlsMode.EXPLICIT) {
            upgradeControl();
        }
        login();
        if (tlsMode != TlsMode.OFF) {
            protectDataChannel();
        }
    }

    /**
     * 显式 FTPS：在明文控制连接上发 AUTH TLS（期望 234），随后整个控制通道
     * 升级为 TLS——reader/writer 必须基于新流重建。服务器拒绝时抛 UNSUPPORTED。
     */
    private void upgradeControl() throws IOException {
        Reply auth = command("AUTH TLS");
        if (auth.code() != 234) {
            close();
            throw StorageException.unsupported("服务器不支持 FTPS（AUTH TLS 回 "
                    + auth.code() + " " + flatten(auth.text()) + "）；如服务器只支持明文 FTP，"
                    + "请把连接配置的「加密」设为不加密");
        }
        control = wrapTls(control);
        resetStreams();
    }

    /** 数据通道保护：PBSZ 0 → PROT P（RFC 4217 固定顺序）。 */
    private void protectDataChannel() throws IOException {
        Reply pbsz = command("PBSZ 0");
        if (!pbsz.isPositive()) {
            throw new IOException("PBSZ 失败（" + pbsz.code() + " " + flatten(pbsz.text()) + "）");
        }
        Reply prot = command("PROT P");
        if (!prot.isPositive()) {
            throw new IOException("数据通道保护失败（" + prot.code() + " " + flatten(prot.text()) + "）");
        }
    }

    /**
     * 把既有明文 socket 升级为 TLS（控制与数据通道共用）：trustAllCerts 时信任全部
     * 证书，否则走 JVM 默认信任链并开启主机名校验。
     */
    private Socket wrapTls(Socket plain) throws IOException {
        SSLSocketFactory factory;
        if (trustAllCerts) {
            try {
                SSLContext context = SSLContext.getInstance("TLS");
                context.init(null, new TrustManager[]{new TrustAllManager()}, null);
                factory = context.getSocketFactory();
            } catch (Exception e) {
                throw new IOException("初始化信任全部证书的 SSL 上下文失败: " + e.getMessage(), e);
            }
        } else {
            factory = (SSLSocketFactory) SSLSocketFactory.getDefault();
        }
        SSLSocket tls = (SSLSocket) factory.createSocket(plain, host, port, true);
        if (!trustAllCerts) {
            // 主机名校验：SSLSocket 需显式开启端点识别
            javax.net.ssl.SSLParameters parameters = tls.getSSLParameters();
            parameters.setEndpointIdentificationAlgorithm("HTTPS");
            tls.setSSLParameters(parameters);
        }
        tls.startHandshake();
        return tls;
    }

    /** reader/writer 基于（可能已升级的）控制连接流重建。 */
    private void resetStreams() throws IOException {
        reader = new BufferedReader(new InputStreamReader(control.getInputStream(), charset), 8192);
        writer = new BufferedOutputStream(control.getOutputStream());
    }

    /** 信任全部证书的 TrustManager（自签名服务器场景；仅显式 opt-in 时使用）。 */
    private static final class TrustAllManager implements X509TrustManager {
        @Override
        public void checkClientTrusted(java.security.cert.X509Certificate[] chain, String authType) {
            // 显式选择信任所有证书
        }

        @Override
        public void checkServerTrusted(java.security.cert.X509Certificate[] chain, String authType) {
            // 显式选择信任所有证书
        }

        @Override
        public java.security.cert.X509Certificate[] getAcceptedIssuers() {
            return new java.security.cert.X509Certificate[0];
        }
    }

    /**
     * 发送一条命令并读取一条应答。
     */
    Reply command(String command) throws IOException {
        sendLine(command);
        Reply reply = readReply();
        lastReply = reply;
        return reply;
    }

    /**
     * 结束一次数据传输：先关闭数据连接（STOR 需先关闭服务器才能收到 EOF 并回 226），
     * 再读取完成应答；非 2yz 抛 {@link StorageErrorCode#SERVER_ERROR}。
     */
    void finishTransfer(Socket data, String action, String path) throws IOException {
        discardData(data);
        Reply reply = readReply();
        if (!reply.isPositive()) {
            throw new StorageException(StorageErrorCode.SERVER_ERROR,
                    action + "失败: " + path + "（" + reply.code() + " " + flatten(reply.text()) + "）", path);
        }
    }

    /**
     * 发送传输类命令（STOR/RETR/MLSD/LIST…）并就绪数据连接。
     *
     * @return 已连接的数据 Socket，可直接读写；命令被拒绝时返回 null，
     *         此时应答可经 {@link #lastReply()} 取得
     */
    Socket startTransfer(String transferCommand) throws IOException {
        Socket data = openData();
        Reply reply = command(transferCommand);
        if (!reply.isPreliminary()) {
            discardData(data);
            return null;
        }
        return passive ? data : acceptData();
    }

    /** 最近一次读到的应答（传输命令被拒时用于映射错误码）。 */
    Reply lastReply() {
        return lastReply;
    }

    /** 服务器是否支持 MLSD 列表（以 FEAT 结果为准）。 */
    boolean isMlsdSupported() {
        return mlsdSupported;
    }

    /** 登录后的主目录（所有操作路径的基准）。 */
    String home() {
        return home;
    }

    /** 控制连接编码。 */
    Charset charset() {
        return charset;
    }

    /** 当前控制连接是否可用。 */
    boolean isConnected() {
        return control != null && !control.isClosed();
    }

    /**
     * 礼貌登出（QUIT）并释放控制连接与主动模式监听。
     */
    void close() {
        if (isConnected()) {
            try {
                sendLine("QUIT");
            } catch (IOException e) {
                // 连接可能已断开，忽略
            }
        }
        closeQuietly(activeListener);
        activeListener = null;
        closeQuietly(control);
        control = null;
        reader = null;
        writer = null;
    }

    /**
     * 流式泵送：从 {@code in} 读到 EOF 并全部写入 {@code out}。
     *
     * @return 累计传输字节数
     */
    static long pump(InputStream in, OutputStream out) throws IOException {
        byte[] chunk = new byte[65536];
        long total = 0L;
        int read;
        while ((read = in.read(chunk)) >= 0) {
            out.write(chunk, 0, read);
            total += read;
        }
        out.flush();
        return total;
    }

    static void closeQuietly(Socket socket) {
        if (socket != null) {
            try {
                socket.close();
            } catch (IOException e) {
                // 关闭时的异常忽略
            }
        }
    }

    static void closeQuietly(ServerSocket socket) {
        if (socket != null) {
            try {
                socket.close();
            } catch (IOException e) {
                // 关闭时的异常忽略
            }
        }
    }

    private void login() throws IOException {
        String name = user == null || user.isEmpty() ? ANONYMOUS_USER : user;
        Reply userReply = command("USER " + name);
        if (userReply.code() == 331) {
            Reply passReply = command("PASS " + passwordOf(name));
            if (passReply.code() != 230 && passReply.code() != 232) {
                throw new StorageException(StorageErrorCode.AUTH_FAILED,
                        "FTP 登录失败: " + host + "（" + passReply.code() + " " + flatten(passReply.text()) + "）", "-");
            }
        } else if (userReply.code() != 230) {
            throw new StorageException(StorageErrorCode.AUTH_FAILED,
                    "FTP 登录失败: " + host + "（" + userReply.code() + " " + flatten(userReply.text()) + "）", "-");
        }
        Reply typeReply = command("TYPE I");
        if (!typeReply.isPositive()) {
            throw new StorageException(StorageErrorCode.SERVER_ERROR,
                    "设置二进制传输模式失败（" + typeReply.code() + " " + flatten(typeReply.text()) + "）", "-");
        }
        readFeatures();
        Reply pwdReply = command("PWD");
        home = pwdReply.isPositive() ? parseQuotedPath(pwdReply.text()) : "";
    }

    private String passwordOf(String name) {
        if (password != null && !password.isEmpty()) {
            return password;
        }
        return ANONYMOUS_USER.equals(name) ? ANONYMOUS_PASSWORD : "";
    }

    /**
     * 探测服务器能力集：FEAT 中的 MLST/MLSD 决定列表方式；配置 UTF-8 且服务器
     * 声明 UTF8 时补发 OPTS UTF8 ON（应答码不关心）。
     */
    private void readFeatures() throws IOException {
        Reply reply = command("FEAT");
        boolean utf8 = false;
        if (reply.code() == 211) {
            for (String line : reply.text().split("\n")) {
                String feature = line.trim().toUpperCase(Locale.ROOT);
                if (feature.startsWith("MLST") || feature.startsWith("MLSD")) {
                    mlsdSupported = true;
                }
                if (feature.startsWith("UTF8")) {
                    utf8 = true;
                }
            }
        }
        if (utf8 && "UTF-8".equalsIgnoreCase(charset.name())) {
            command("OPTS UTF8 ON");
        }
        if (mlsdSupported) {
            // 请求 MLSD/MLST 带上属主 / 组事实：pyftpdlib 等按 OPTS 相交后输出
            // unix.uid/gid（部分服务端还给名字）；不支持的服务器回 500/502，应答忽略
            command("OPTS MLST type;size;modify;perm;unix.owner;unix.group;unix.uid;unix.gid;");
        }
    }

    private void sendLine(String line) throws IOException {
        writer.write((line + "\r\n").getBytes(charset));
        writer.flush();
    }

    /**
     * 读取一条应答：三位码 + 空格为单行；三位码 + '-' 为多行，
     * 读到以「三位码 + 空格」开头的行为止。
     */
    private Reply readReply() throws IOException {
        String first = readLine();
        int code = parseCode(first);
        if (first.length() > 3 && first.charAt(3) == '-') {
            String terminator = first.substring(0, 3) + " ";
            StringBuilder text = new StringBuilder(first.substring(4));
            while (true) {
                String line = readLine();
                if (line.startsWith(terminator)) {
                    if (line.length() > 4) {
                        text.append('\n').append(line.substring(4));
                    }
                    break;
                }
                text.append('\n').append(line);
            }
            return new Reply(code, text.toString());
        }
        return new Reply(code, first.length() > 4 ? first.substring(4) : "");
    }

    private String readLine() throws IOException {
        String line = reader.readLine();
        if (line == null) {
            throw new IOException("控制连接已被服务器关闭: " + host + ":" + port);
        }
        return line;
    }

    private static int parseCode(String line) throws IOException {
        if (line.length() >= 3) {
            boolean digits = true;
            for (int i = 0; i < 3; i++) {
                if (!Character.isDigit(line.charAt(i))) {
                    digits = false;
                    break;
                }
            }
            if (digits) {
                return Integer.parseInt(line.substring(0, 3));
            }
        }
        throw new IOException("非法的 FTP 应答: " + line);
    }

    /**
     * 建立数据连接：被动模式发 PASV 并直接连接；主动模式开本地监听并发 PORT
     * （返回 null，调用方在收到 1xx 应答后调用 {@link #acceptData()} 接受）。
     */
    private Socket openData() throws IOException {
        if (passive) {
            Reply reply = command("PASV");
            if (reply.code() != 227) {
                throw new IOException("进入被动模式失败（" + reply.code() + " " + flatten(reply.text()) + "）");
            }
            return connectData(parsePasvAddress(reply.text()));
        }
        ServerSocket listener = new ServerSocket(0);
        activeListener = listener;
        int port = listener.getLocalPort();
        String ip = control.getLocalAddress().getHostAddress();
        Reply reply = command("PORT " + ip.replace('.', ',') + "," + (port >> 8) + "," + (port & 0xFF));
        if (!reply.isPositive()) {
            closeQuietly(listener);
            activeListener = null;
            throw new IOException("进入主动模式失败（" + reply.code() + " " + flatten(reply.text()) + "）");
        }
        return null;
    }

    private Socket connectData(int[] address) throws IOException {
        String ip = address[0] + "." + address[1] + "." + address[2] + "." + address[3];
        if ("0.0.0.0".equals(ip)) {
            ip = host;
        }
        int port = address[4] * 256 + address[5];
        Socket socket = new Socket();
        try {
            socket.connect(new InetSocketAddress(ip, port), CONNECT_TIMEOUT_MS);
        } catch (IOException e) {
            closeQuietly(socket);
            throw new IOException("数据连接失败: " + ip + ":" + port + "（" + e.getMessage() + "）");
        }
        socket.setSoTimeout(SO_TIMEOUT_MS);
        return tlsMode == TlsMode.OFF ? socket : wrapTls(socket);
    }

    private int[] parsePasvAddress(String text) throws IOException {
        Matcher matcher = PASV_ADDRESS.matcher(text);
        if (!matcher.find()) {
            throw new IOException("无法解析 PASV 应答地址: " + text);
        }
        int[] address = new int[6];
        for (int i = 0; i < 6; i++) {
            address[i] = Integer.parseInt(matcher.group(i + 1));
        }
        return address;
    }

    private Socket acceptData() throws IOException {
        ServerSocket listener = activeListener;
        activeListener = null;
        if (listener == null) {
            throw new IOException("主动模式数据监听未就绪");
        }
        try {
            Socket socket = listener.accept();
            socket.setSoTimeout(SO_TIMEOUT_MS);
            return tlsMode == TlsMode.OFF ? socket : wrapTls(socket);
        } finally {
            closeQuietly(listener);
        }
    }

    private void discardData(Socket data) {
        closeQuietly(data);
        closeQuietly(activeListener);
        activeListener = null;
    }

    private static String parseQuotedPath(String text) {
        int begin = text.indexOf('"');
        if (begin < 0) {
            return "";
        }
        int end = text.indexOf('"', begin + 1);
        return end < 0 ? "" : text.substring(begin + 1, end);
    }

    private static String flatten(String text) {
        return text.replace('\n', ' ');
    }
}
