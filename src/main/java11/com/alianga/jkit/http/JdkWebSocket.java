package com.alianga.jkit.http;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;

import java.io.IOException;
import java.net.Proxy;
import java.net.ProxySelector;
import java.net.SocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.nio.ByteBuffer;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;

/**
 * JDK 11+ {@code java.net.http.WebSocket} 适配。
 *
 * @author 郑明亮
 */
public final class JdkWebSocket {
    private JdkWebSocket() {
    }

    /**
     * 建立 WebSocket 连接。
     *
     * @param url ws/wss 地址
     * @param headers 额外握手头
     * @param ignoreSsl 是否忽略证书
     * @param proxy 代理
     * @param connectTimeoutMs 连接超时
     * @param listener 回调
     * @return 会话
     * @throws IOException 握手失败
     */
    public static WebSocketSession connect(String url, Map<String, String> headers, boolean ignoreSsl,
                                           Proxy proxy, int connectTimeoutMs,
                                           WebSocketListener listener) throws IOException {
        URI uri = HttpIo.toUri(url);
        if (uri == null) {
            throw new IOException("invalid websocket url: " + url);
        }
        HttpClient.Builder clientBuilder = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(Math.max(1, connectTimeoutMs)))
                .version(HttpClient.Version.HTTP_1_1);
        if (proxy != null && proxy.type() != Proxy.Type.DIRECT) {
            final Proxy selected = proxy;
            clientBuilder.proxy(new ProxySelector() {
                @Override
                public List<Proxy> select(URI u) {
                    return Collections.singletonList(selected);
                }

                @Override
                public void connectFailed(URI u, SocketAddress sa, IOException ioe) {
                    // ignore
                }
            });
        }
        if (ignoreSsl) {
            try {
                SSLContext ctx = SSLContext.getInstance("TLS");
                ctx.init(null, SSLSocketClient.getTrustManager(), new SecureRandom());
                clientBuilder.sslContext(ctx);
                SSLParameters params = new SSLParameters();
                params.setEndpointIdentificationAlgorithm(null);
                clientBuilder.sslParameters(params);
            } catch (Exception e) {
                throw new IOException("init websocket SSL failed", e);
            }
        }
        HttpClient client = clientBuilder.build();
        WebSocket.Builder wsBuilder = client.newWebSocketBuilder();
        if (headers != null) {
            for (Map.Entry<String, String> entry : headers.entrySet()) {
                if (entry.getKey() == null || entry.getValue() == null) {
                    continue;
                }
                if (HttpIo.isRestrictedHttpClientHeader(entry.getKey())) {
                    continue;
                }
                try {
                    wsBuilder.header(entry.getKey(), entry.getValue());
                } catch (IllegalArgumentException e) {
                    // skip
                }
            }
        }
        ListenerAdapter adapter = new ListenerAdapter(listener);
        try {
            WebSocket socket = wsBuilder.buildAsync(uri, adapter).get(Math.max(1, connectTimeoutMs),
                    TimeUnit.MILLISECONDS);
            return new SessionImpl(socket);
        } catch (Exception e) {
            throw new IOException("websocket handshake failed: " + e.getMessage(), e);
        }
    }

    private static final class ListenerAdapter implements WebSocket.Listener {
        private final WebSocketListener listener;
        private final StringBuilder text = new StringBuilder();

        private ListenerAdapter(WebSocketListener listener) {
            this.listener = listener;
        }

        @Override
        public void onOpen(WebSocket webSocket) {
            webSocket.request(1);
            listener.onOpen();
        }

        @Override
        public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
            text.append(data);
            if (last) {
                listener.onText(text.toString(), true);
                text.setLength(0);
            }
            webSocket.request(1);
            return null;
        }

        @Override
        public CompletionStage<?> onBinary(WebSocket webSocket, ByteBuffer data, boolean last) {
            byte[] bytes = new byte[data.remaining()];
            data.get(bytes);
            listener.onBinary(bytes, last);
            webSocket.request(1);
            return null;
        }

        @Override
        public CompletionStage<?> onPing(WebSocket webSocket, ByteBuffer message) {
            byte[] bytes = new byte[message.remaining()];
            message.get(bytes);
            listener.onPing(bytes);
            webSocket.request(1);
            return null;
        }

        @Override
        public CompletionStage<?> onPong(WebSocket webSocket, ByteBuffer message) {
            byte[] bytes = new byte[message.remaining()];
            message.get(bytes);
            listener.onPong(bytes);
            webSocket.request(1);
            return null;
        }

        @Override
        public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
            listener.onClose(statusCode, reason);
            return null;
        }

        @Override
        public void onError(WebSocket webSocket, Throwable error) {
            listener.onError(error);
        }
    }

    private static final class SessionImpl implements WebSocketSession {
        private final WebSocket socket;

        private SessionImpl(WebSocket socket) {
            this.socket = socket;
        }

        @Override
        public void sendText(String text) throws IOException {
            try {
                socket.sendText(text == null ? "" : text, true).join();
            } catch (Exception e) {
                throw new IOException(e);
            }
        }

        @Override
        public void sendBinary(byte[] data) throws IOException {
            try {
                ByteBuffer buf = ByteBuffer.wrap(data == null ? new byte[0] : data);
                socket.sendBinary(buf, true).join();
            } catch (Exception e) {
                throw new IOException(e);
            }
        }

        @Override
        public void sendPing(ByteBuffer data) throws IOException {
            try {
                socket.sendPing(data == null ? ByteBuffer.allocate(0) : data).join();
            } catch (Exception e) {
                throw new IOException(e);
            }
        }

        @Override
        public boolean isOpen() {
            return !socket.isOutputClosed() && !socket.isInputClosed();
        }

        @Override
        public void close() throws IOException {
            close(1000, "normal");
        }

        @Override
        public void close(int statusCode, String reason) throws IOException {
            try {
                socket.sendClose(statusCode, reason == null ? "" : reason).join();
            } catch (Exception e) {
                throw new IOException(e);
            }
        }
    }
}
