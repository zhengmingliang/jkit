package com.alianga.jkit.captcha.bench.behavior;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.nio.charset.StandardCharsets;

/**
 * 仅用于基准的极简 HTTP 服务：基于 {@link ServerSocket} 手写请求解析，
 * 不用 com.sun.net.httpserver（Eclipse 编译器对 com.sun.* 有 rt.jar 访问限制，
 * 会导致 IDE 内编译失败）。每连接一个线程、逐请求关闭，满足基准的串行调用足够。
 */
final class RawMiniServer {
    /** 请求处理器：入参为路径与请求体，返回响应体（HTTP 200 + JSON）。 */
    interface Handler {
        byte[] handle(String path, byte[] body) throws IOException;
    }

    private final ServerSocket serverSocket;
    private volatile boolean running = true;

    RawMiniServer(Handler handler) throws IOException {
        this.serverSocket = new ServerSocket(0, 64, InetAddress.getByName("127.0.0.1"));
        Thread acceptor = new Thread(() -> acceptLoop(handler), "raw-mini-server-acceptor");
        acceptor.setDaemon(true);
        acceptor.start();
    }

    /** 实际监听端口。 */
    int port() {
        return serverSocket.getLocalPort();
    }

    void stop() throws IOException {
        running = false;
        serverSocket.close();
    }

    private void acceptLoop(Handler handler) {
        while (running) {
            try {
                Socket socket = serverSocket.accept();
                Thread worker = new Thread(() -> serve(socket, handler), "raw-mini-server-worker");
                worker.setDaemon(true);
                worker.start();
            } catch (IOException e) {
                if (running) {
                    System.err.println("raw-mini-server accept 失败: " + e.getMessage());
                }
            }
        }
    }

    private void serve(Socket socket, Handler handler) {
        try (Socket s = socket) {
            InputStream in = s.getInputStream();
            String requestLine = readLine(in);
            if (requestLine == null || requestLine.isEmpty()) {
                return;
            }
            String path = requestLine.split(" ")[1];
            int contentLength = 0;
            String line;
            while ((line = readLine(in)) != null && !line.isEmpty()) {
                String lower = line.toLowerCase();
                if (lower.startsWith("content-length:")) {
                    contentLength = Integer.parseInt(line.substring("content-length:".length()).trim());
                }
            }
            byte[] body = new byte[Math.max(0, contentLength)];
            int read = 0;
            while (read < body.length) {
                int n = in.read(body, read, body.length - read);
                if (n < 0) {
                    break;
                }
                read += n;
            }
            byte[] response;
            try {
                response = handler.handle(path, body);
            } catch (Exception e) {
                response = ("{\"code\":500,\"msg\":\"" + e.getMessage() + "\"}").getBytes(StandardCharsets.UTF_8);
            }
            OutputStream out = s.getOutputStream();
            out.write(("HTTP/1.1 200 OK\r\nContent-Type: application/json;charset=UTF-8\r\nContent-Length: "
                    + response.length + "\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.UTF_8));
            out.write(response);
            out.flush();
        } catch (SocketException ignored) {
            // 客户端提前断开属于基准运行噪声，忽略
        } catch (IOException e) {
            System.err.println("raw-mini-server 处理失败: " + e.getMessage());
        }
    }

    private static String readLine(InputStream in) throws IOException {
        StringBuilder builder = new StringBuilder(64);
        int c = in.read();
        if (c < 0) {
            return null;
        }
        while (c >= 0 && c != '\n') {
            if (c != '\r') {
                builder.append((char) c);
            }
            c = in.read();
        }
        return builder.toString();
    }
}
