package com.alianga.jkit.mock.server;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * 零依赖的本地 Mock WebSocket 服务：纯 JDK {@link ServerSocket} + 手写 RFC 6455
 * 握手与帧编解码，与 {@link MockHttpServer} 解耦，由其在伴随端口上启停。
 *
 * <p><b>能力</b>：</p>
 * <ul>
 *   <li>路径匹配复用 {@link MockHttpServer#matchPathParams}，仅接 {@code protocol=WS}
 *       且 {@code enabled=true} 的路由；{@link #setRoutes(List)} 热更新立即生效</li>
 *   <li>推送时机由 {@link MockPushPolicy} 描述（单次 / 定时 / 按事件触发），调度复用
 *       {@link MockPushScheduler}；消息内容支持与 HTTP 同源的占位符与请求参数回显</li>
 *   <li>控制帧：客户端 ping 自动回 pong；收到 close 回 close 后断开；文本分片自动拼接</li>
 *   <li>EVENT 模式：客户端消息命中关键字（或未配关键字）即推送一帧，消息原文可用
 *       {@code {{event}}} 引用，消息体可用 {@code {{body.xxx}}} 下钻</li>
 * </ul>
 *
 * <p>不做鉴权、压缩（permessage-deflate）与二进制推送；本地联调场景足够。</p>
 *
 * @author 郑明亮
 * @since 2.0.4
 */
public final class MockWebSocketServer {
    private static final String GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11";
    private static final int OP_CONTINUATION = 0x0;
    private static final int OP_TEXT = 0x1;
    private static final int OP_CLOSE = 0x8;
    private static final int OP_PING = 0x9;
    private static final int OP_PONG = 0xA;

    private final List<Session> sessions = new CopyOnWriteArrayList<>();
    private final List<Consumer<WsEvent>> eventListeners = new CopyOnWriteArrayList<>();
    private final AtomicBoolean running = new AtomicBoolean(false);

    private volatile List<MockRoute> routes = Collections.emptyList();
    private volatile Map<String, String> variables = Collections.emptyMap();
    private volatile ServerSocket serverSocket;
    private volatile ScheduledExecutorService scheduler;
    private volatile Thread acceptThread;
    private volatile int port;

    /**
     * 启动监听；已在运行则先停。
     *
     * @param port   监听端口
     * @param routes 路由快照（仅 WS 路由参与匹配）
     * @throws IOException 绑定失败
     */
    public void start(int port, List<MockRoute> routes) throws IOException {
        stop();
        if (port < 1 || port > 65535) {
            throw new IllegalArgumentException("端口无效: " + port);
        }
        this.routes = routes == null
                ? Collections.<MockRoute>emptyList()
                : Collections.unmodifiableList(new ArrayList<>(routes));
        this.port = port;
        ServerSocket socket = new ServerSocket();
        socket.setReuseAddress(true);
        socket.bind(new InetSocketAddress("127.0.0.1", port));
        this.serverSocket = socket;
        this.scheduler = MockPushScheduler.newDaemonScheduler("jkit-mock-ws");
        this.running.set(true);
        Thread thread = new Thread(this::acceptLoop, "jkit-mock-ws-accept");
        thread.setDaemon(true);
        this.acceptThread = thread;
        thread.start();
    }

    /** 停止：断开全部连接、关闭监听与调度器；可重复调用。 */
    public void stop() {
        running.set(false);
        Thread thread = acceptThread;
        acceptThread = null;
        if (thread != null) {
            thread.interrupt();
        }
        ServerSocket socket = serverSocket;
        serverSocket = null;
        if (socket != null) {
            try {
                socket.close();
            } catch (IOException ignore) {
                // 关闭监听无需上报
            }
        }
        for (Session session : new ArrayList<>(sessions)) {
            session.closeQuietly();
        }
        sessions.clear();
        ScheduledExecutorService pool = scheduler;
        scheduler = null;
        if (pool != null) {
            pool.shutdownNow();
        }
    }

    public boolean isRunning() {
        return running.get();
    }

    public int getPort() {
        return port;
    }

    /** WS 基址；未运行返回 {@code null}。 */
    public String wsUrl() {
        return isRunning() ? "ws://127.0.0.1:" + port : null;
    }

    /** 热更新路由表；只影响新连接。 */
    public void setRoutes(List<MockRoute> routes) {
        this.routes = routes == null
                ? Collections.<MockRoute>emptyList()
                : Collections.unmodifiableList(new ArrayList<>(routes));
    }

    /** 配置模板变量（{@code {{var.name}}}）。 */
    public void setVariables(Map<String, String> variables) {
        this.variables = variables == null
                ? Collections.<String, String>emptyMap()
                : Collections.unmodifiableMap(new LinkedHashMap<>(variables));
    }

    /** 当前连接数（测试与状态展示用）。 */
    public int sessionCount() {
        return sessions.size();
    }

    /** 订阅连接生命周期事件（连接 / 事件消息 / 关闭；工作线程回调）。 */
    public void addEventListener(Consumer<WsEvent> listener) {
        if (listener != null) {
            eventListeners.add(listener);
        }
    }

    public void removeEventListener(Consumer<WsEvent> listener) {
        eventListeners.remove(listener);
    }

    private void fireEvent(WsEvent.Kind kind, String path, String text) {
        fireEvent(kind, path, text, null);
    }

    /** 带 WebSocket 握手请求头的事件派发（仅 CONNECT 需要）。 */
    private void fireEvent(WsEvent.Kind kind, String path, String text,
                           Map<String, String> handshakeHeaders) {
        WsEvent event = new WsEvent(kind, path == null ? "" : path, text == null ? "" : text,
                handshakeHeaders);
        for (Consumer<WsEvent> listener : eventListeners) {
            try {
                listener.accept(event);
            } catch (RuntimeException ignore) {
                // 监听失败不影响连接
            }
        }
    }

    /** WebSocket 连接生命周期事件。 */
    public static final class WsEvent {
        /** 事件种类。 */
        public enum Kind {
            /** 握手成功，连接建立。 */
            CONNECT,
            /** 收到客户端消息（仅 EVENT 命中时回调）。 */
            MESSAGE,
            /** 连接断开。 */
            CLOSE
        }

        public final Kind kind;
        public final String path;
        public final String text;
        /** 握手请求头快照（每名取首值、不可变）；仅 CONNECT 事件携带，其余为空表。 */
        public final Map<String, String> headers;

        WsEvent(Kind kind, String path, String text) {
            this(kind, path, text, null);
        }

        WsEvent(Kind kind, String path, String text, Map<String, String> handshakeHeaders) {
            this.kind = kind;
            this.path = path;
            this.text = text;
            this.headers = handshakeHeaders == null || handshakeHeaders.isEmpty()
                    ? Collections.<String, String>emptyMap()
                    : Collections.unmodifiableMap(new LinkedHashMap<>(handshakeHeaders));
        }
    }

    private void acceptLoop() {
        ServerSocket socket = serverSocket;
        while (running.get() && socket != null) {
            final Socket client;
            try {
                client = socket.accept();
            } catch (IOException e) {
                if (running.get()) {
                    continue;
                }
                return;
            }
            Thread worker = new Thread(() -> handle(client), "jkit-mock-ws-conn");
            worker.setDaemon(true);
            worker.start();
        }
    }

    private void handle(Socket socket) {
        Session session = null;
        try {
            socket.setTcpNoDelay(true);
            InputStream in = socket.getInputStream();
            OutputStream out = socket.getOutputStream();
            Handshake handshake = readHandshake(in);
            if (handshake == null) {
                writePlainHttpResponse(out, 426, "Upgrade Required",
                        "{\"error\":\"websocket handshake required\"}");
                return;
            }
            MockRoute route = findRoute(handshake.path);
            if (route == null) {
                writePlainHttpResponse(out, 404, "Not Found",
                        "{\"error\":\"no matching ws route\"}");
                return;
            }
            writeAccept(out, handshake, route);
            Map<String, String> pathParams = MockHttpServer.matchPathParams(route.path, handshake.path);
            MockRequestContext ctx = MockRequestContext.of("WS", handshake.path, pathParams,
                    handshake.query, handshake.headers, new byte[0], variables);
            session = new Session(socket, in, out, route, ctx);
            sessions.add(session);
            fireEvent(WsEvent.Kind.CONNECT, handshake.path, "",
                    MockHttpServer.headerSnapshot(handshake.headers));
            session.run();
        } catch (IOException | RuntimeException e) {
            // 连接级异常：安静断开（本地 mock，单连失败不应影响其他连接）
        } finally {
            if (session != null) {
                sessions.remove(session);
                session.closeQuietly();
                fireEvent(WsEvent.Kind.CLOSE, session.route.path, "");
            } else {
                try {
                    socket.close();
                } catch (IOException ignore) {
                    // 已在异常路径，忽略
                }
            }
        }
    }

    private MockRoute findRoute(String path) {
        for (MockRoute route : routes) {
            if (route.enabled && route.isWebSocket()
                    && MockHttpServer.matchesPath(route.path, path)) {
                return route;
            }
        }
        return null;
    }

    /** WS 端口收到非握手 / 未命中的普通 HTTP 请求时，回一个标准 HTTP 响应而不是裸断连接。 */
    private static void writePlainHttpResponse(OutputStream out, int status, String reason,
                                               String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        out.write(("HTTP/1.1 " + status + " " + reason + "\r\n"
                + "Content-Type: application/json; charset=utf-8\r\n"
                + "Content-Length: " + bytes.length + "\r\n"
                + "Connection: close\r\n\r\n").getBytes(StandardCharsets.UTF_8));
        out.write(bytes);
        out.flush();
    }

    private static void writeAccept(OutputStream out, Handshake handshake, MockRoute route)
            throws IOException {
        String accept = acceptKey(handshake.key);
        Map<String, String> pathParams = MockHttpServer.matchPathParams(route.path, handshake.path);
        MockRequestContext ctx = MockRequestContext.of("WS", handshake.path, pathParams,
                handshake.query, handshake.headers, new byte[0]);
        Map<String, String> rendered = MockTemplateRenderer.renderHeaders(route.headers, ctx);
        StringBuilder sb = new StringBuilder(400);
        sb.append("HTTP/1.1 101 Switching Protocols\r\n")
                .append("Upgrade: websocket\r\n")
                .append("Connection: Upgrade\r\n")
                .append("Sec-WebSocket-Accept: ").append(accept).append("\r\n");
        for (Map.Entry<String, String> e : rendered.entrySet()) {
            String name = e.getKey();
            if (isHopByHop(name)) {
                continue;
            }
            sb.append(name).append(": ").append(e.getValue() == null ? "" : e.getValue())
                    .append("\r\n");
        }
        sb.append("\r\n");
        out.write(sb.toString().getBytes(StandardCharsets.UTF_8));
        out.flush();
    }

    private static boolean isHopByHop(String name) {
        String lower = name == null ? "" : name.toLowerCase(Locale.ROOT);
        return lower.startsWith("sec-websocket") || "content-length".equals(lower)
                || "connection".equals(lower) || "upgrade".equals(lower);
    }

    private static String acceptKey(String key) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-1");
            byte[] hash = digest.digest((key + GUID).getBytes(StandardCharsets.ISO_8859_1));
            return Base64.getEncoder().encodeToString(hash);
        } catch (NoSuchAlgorithmException e) {
            // SHA-1 为 JDK 必备算法，理论不可达
            throw new IllegalStateException(e);
        }
    }

    /** 读握手请求，返回请求路径（含 query 解析）；非 WebSocket 握手返回 {@code null}。 */
    private static Handshake readHandshake(InputStream in) throws IOException {
        String requestLine = readLine(in);
        if (requestLine == null || !requestLine.startsWith("GET ")) {
            return null;
        }
        String[] parts = requestLine.split(" ");
        if (parts.length < 2) {
            return null;
        }
        String target = parts[1];
        Map<String, List<String>> headers = new LinkedHashMap<>();
        String websocketKey = null;
        boolean upgrade = false;
        String line;
        while ((line = readLine(in)) != null && !line.isEmpty()) {
            int colon = line.indexOf(':');
            if (colon <= 0) {
                continue;
            }
            String name = line.substring(0, colon).trim();
            String value = line.substring(colon + 1).trim();
            List<String> list = headers.get(name);
            if (list == null) {
                list = new ArrayList<>();
                headers.put(name, list);
            }
            list.add(value);
            if ("upgrade".equalsIgnoreCase(name) && "websocket".equalsIgnoreCase(value)) {
                upgrade = true;
            }
            if ("sec-websocket-key".equalsIgnoreCase(name)) {
                websocketKey = value;
            }
        }
        if (!upgrade || websocketKey == null) {
            return null;
        }
        String path = target;
        String query = null;
        int q = target.indexOf('?');
        if (q >= 0) {
            path = target.substring(0, q);
            query = target.substring(q + 1);
        }
        path = MockHttpServer.decodePath(path);
        return new Handshake(path, query, headers, websocketKey);
    }

    private static String readLine(InputStream in) throws IOException {
        ByteArrayOutputStream buf = new ByteArrayOutputStream(128);
        int prev = -1;
        int b;
        while ((b = in.read()) >= 0) {
            if (prev == '\r' && b == '\n') {
                String result = new String(buf.toByteArray(), 0,
                        Math.max(0, buf.size() - 1), StandardCharsets.UTF_8);
                return result;
            }
            buf.write(b);
            prev = b;
        }
        if (buf.size() == 0) {
            return null;
        }
        return new String(buf.toByteArray(), StandardCharsets.UTF_8).replace("\r", "");
    }

    /** 一条 WS 连接：读循环 + 推送任务 + 串行写出。 */
    private final class Session {
        private final Socket socket;
        private final InputStream in;
        private final OutputStream out;
        private final MockRoute route;
        private final MockRequestContext connectCtx;
        private final Object writeLock = new Object();
        private final AtomicBoolean open = new AtomicBoolean(true);
        private ScheduledFuture<?> pushFuture;

        Session(Socket socket, InputStream in, OutputStream out,
                MockRoute route, MockRequestContext connectCtx) {
            this.socket = socket;
            this.in = in;
            this.out = out;
            this.route = route;
            this.connectCtx = connectCtx;
        }

        void run() throws IOException {
            MockPushPolicy policy = route.pushPolicy == null
                    ? MockPushPolicy.once() : route.pushPolicy;
            if (policy.mode != MockPushPolicy.Mode.EVENT) {
                pushFuture = MockPushScheduler.schedule(scheduler, policy,
                        () -> sendFrame(MockBodyRenderer.routeFrame(route, connectCtx)));
            }
            ByteArrayOutputStream fragmented = new ByteArrayOutputStream();
            while (open.get()) {
                int b0 = in.read();
                if (b0 < 0) {
                    break;
                }
                int b1 = in.read();
                if (b1 < 0) {
                    break;
                }
                boolean fin = (b0 & 0x80) != 0;
                int opcode = b0 & 0x0F;
                boolean masked = (b1 & 0x80) != 0;
                long length = b1 & 0x7FL;
                if (length == 126) {
                    length = ((in.read() & 0xFF) << 8) | (in.read() & 0xFF);
                } else if (length == 127) {
                    length = 0L;
                    for (int i = 0; i < 8; i++) {
                        length = (length << 8) | (in.read() & 0xFF);
                    }
                }
                byte[] mask = null;
                if (masked) {
                    mask = new byte[4];
                    readFully(in, mask);
                }
                byte[] payload = new byte[(int) Math.min(length, Integer.MAX_VALUE)];
                readFully(in, payload);
                if (masked) {
                    for (int i = 0; i < payload.length; i++) {
                        payload[i] = (byte) (payload[i] ^ mask[i & 3]);
                    }
                }
                if (opcode == OP_CLOSE) {
                    sendControl(OP_CLOSE, payload);
                    break;
                }
                if (opcode == OP_PING) {
                    sendControl(OP_PONG, payload);
                    continue;
                }
                if (opcode == OP_PONG) {
                    continue;
                }
                if (opcode == OP_TEXT || opcode == OP_CONTINUATION) {
                    fragmented.write(payload);
                }
                if (fin && fragmented.size() > 0) {
                    String message = new String(fragmented.toByteArray(), StandardCharsets.UTF_8);
                    fragmented.reset();
                    onMessage(message);
                }
            }
        }

        private void onMessage(String message) {
            MockPushPolicy policy = route.pushPolicy == null
                    ? MockPushPolicy.once() : route.pushPolicy;
            if (!policy.matchesEvent(message)) {
                return;
            }
            MockRequestContext ctx = MockRequestContext.forMessage(connectCtx, message);
            String frame = MockBodyRenderer.routeFrame(route, ctx);
            sendFrame(frame);
            fireEvent(WsEvent.Kind.MESSAGE, route.path, message);
        }

        private void sendFrame(String text) {
            if (!open.get()) {
                return;
            }
            byte[] payload = text.getBytes(StandardCharsets.UTF_8);
            synchronized (writeLock) {
                try {
                    writeFrameHeader(OP_TEXT, payload.length);
                    out.write(payload);
                    out.flush();
                } catch (IOException e) {
                    closeQuietly();
                }
            }
        }

        private void sendControl(int opcode, byte[] payload) {
            if (!open.get()) {
                return;
            }
            synchronized (writeLock) {
                try {
                    writeFrameHeader(opcode, payload.length);
                    if (payload.length > 0) {
                        out.write(payload);
                    }
                    out.flush();
                } catch (IOException ignore) {
                    // 控制帧写出失败，连接即将关闭
                }
            }
        }

        /** 服务端帧不打掩码（RFC 6455 §5.1）；调用方已持有 {@link #writeLock}。 */
        private void writeFrameHeader(int opcode, int length) throws IOException {
            DataOutputStream data = new DataOutputStream(out);
            data.write(0x80 | opcode);
            if (length <= 125) {
                data.write(length);
            } else if (length <= 65535) {
                data.write(126);
                data.writeShort(length);
            } else {
                data.write(127);
                data.writeLong(length);
            }
        }

        void closeQuietly() {
            if (!open.compareAndSet(true, false)) {
                return;
            }
            if (pushFuture != null) {
                pushFuture.cancel(false);
            }
            try {
                socket.close();
            } catch (IOException ignore) {
                // 关闭路径无需上报
            }
        }
    }

    private static void readFully(InputStream in, byte[] buf) throws IOException {
        int offset = 0;
        while (offset < buf.length) {
            int n = in.read(buf, offset, buf.length - offset);
            if (n < 0) {
                throw new EOFException("WebSocket 帧未读完连接即断开");
            }
            offset += n;
        }
    }

    /** 握手结果。 */
    private static final class Handshake {
        private final String path;
        private final String query;
        private final Map<String, List<String>> headers;
        private final String key;

        Handshake(String path, String query, Map<String, List<String>> headers, String key) {
            this.path = path;
            this.query = query;
            this.headers = headers;
            this.key = key;
        }
    }
}
