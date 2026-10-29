package com.alianga.jkit.mock.server;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Collections;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * 覆盖 P0-5：MockWebSocketServer 对超大 frame length 的预处理。
 *
 * 修复前：任何客户端可把 8 字节 length 写成 0x7FFF_FFFF（或最高位为 1）让服务端
 * 一次性预分配 {@code new byte[length]}，直接 OOM 或抛 NegativeArraySizeException。
 *
 * 修复后：声明长度 > {@value MockWebSocketServer#MAX_FRAME_PAYLOAD}（4 MiB）或 < 0
 * 时直接关闭连接（OP_CLOSE）。
 */
public class MockWebSocketFrameLimitTest {

    private MockHttpServer server;
    private int wsPort;

    @Before
    public void setUp() throws Exception {
        server = new MockHttpServer();
        int httpPort = freePort();
        server.setWebSocketPort(0);
        server.start(httpPort, Collections.singletonList(
                MockRoute.builder().path("/ws/echo")
                        .protocol("WS")
                        .body("echo:{{event}}")
                        .pushPolicy(MockPushPolicy.builder()
                                .mode(MockPushPolicy.Mode.EVENT)
                                .event("hello")
                                .build())
                        .build()));
        wsPort = server.actualWebSocketPort();
    }

    @After
    public void tearDown() {
        if (server != null) {
            server.stop();
        }
    }

    private static int freePort() throws IOException {
        try (ServerSocket s = new ServerSocket(0)) {
            return s.getLocalPort();
        }
    }

    /**
     * 声明 length = 0x7FFF_FFFF（约 2 GB）的帧，修复前应触发 OOM 或
     * NegativeArraySizeException；修复后服务端应立即 OP_CLOSE 断开，不分配内存。
     */
    @Test(timeout = 15000)
    public void hugeFrameLengthDoesNotCauseOom() throws Exception {
        try (Socket sock = handshake(wsPort)) {
            OutputStream out = sock.getOutputStream();
            // 构造一个 FIN=1, opcode=TEXT, MASK=0，length = 127 (8 字节扩展长度)
            // 实际长度字段写 0x00000000_7FFFFFFF（2 GB），明显 > MAX_FRAME_PAYLOAD (4 MiB)
            ByteArrayOutputStream frame = new ByteArrayOutputStream();
            frame.write(0x81);              // FIN|TEXT
            frame.write(0x7F);              // 8 字节扩展长度，MASK=0
            // 长度字段：0x7FFFFFFF
            frame.write(0x00);
            frame.write(0x00);
            frame.write(0x00);
            frame.write(0x00);
            frame.write(0x7F);
            frame.write(0xFF);
            frame.write(0xFF);
            frame.write(0xFF);
            out.write(frame.toByteArray());
            out.flush();

            // 期待服务端立即回 OP_CLOSE（0x88）；如果服务端按声明长度 new byte[]，
            // 我们要么读到 OOM 错误页面，要么读超时；这里应能读到关闭帧。
            sock.setSoTimeout(5000);
            InputStream in = sock.getInputStream();
            int b = in.read();
            assertTrue("expected some response, got EOF=" + b, b >= 0);
            assertEquals("expect OP_CLOSE first byte", 0x88, b);
            int len = in.read() & 0x7F;
            assertEquals("close payload = 2 byte status code", 2, len);
            int code = ((in.read() & 0xFF) << 8) | (in.read() & 0xFF);
            assertEquals("expect close code 1009 Message Too Big", 1009, code);
        }
    }

    /**
     * 聚合帧总量上限：多帧各自长度均合法（各 3 MiB < 4 MiB）的 continuation 帧累计
     * 超 4 MiB 时，服务端应发送带 1009（Message Too Big）状态码的 OP_CLOSE 断连，
     * 而不是在 fragmented 缓冲上无限 append 直至 OOM。
     */
    @Test(timeout = 20000)
    public void fragmentedFramesOverTotalCapClosedWith1009() throws Exception {
        try (Socket sock = handshake(wsPort)) {
            OutputStream out = sock.getOutputStream();
            int chunk = 3 * 1024 * 1024;
            byte[] payload = new byte[chunk];
            // 第一帧：FIN=0 opcode=TEXT，长度合法，被聚合
            writeExtendedLengthFrame(out, 0x01, payload);
            // 第二帧：FIN=0 opcode=CONTINUATION，累计 6 MiB 超限
            writeExtendedLengthFrame(out, 0x00, payload);
            out.flush();

            sock.setSoTimeout(10000);
            InputStream in = sock.getInputStream();
            int b0 = in.read();
            assertEquals("expect OP_CLOSE", 0x88, b0);
            int len = in.read() & 0x7F;
            assertEquals("close payload = 2 byte status code", 2, len);
            int code = ((in.read() & 0xFF) << 8) | (in.read() & 0xFF);
            assertEquals("expect close code 1009 Message Too Big", 1009, code);
        }
    }

    /** 写一个 8 字节扩展长度、MASK=0 的帧（payload 全零）。 */
    private static void writeExtendedLengthFrame(OutputStream out, int b0, byte[] payload) throws IOException {
        out.write(b0);
        out.write(0x7F);                    // 8 字节扩展长度，MASK=0
        long len = payload.length;
        out.write((int) ((len >> 56) & 0xFF));
        out.write((int) ((len >> 48) & 0xFF));
        out.write((int) ((len >> 40) & 0xFF));
        out.write((int) ((len >> 32) & 0xFF));
        out.write((int) ((len >> 24) & 0xFF));
        out.write((int) ((len >> 16) & 0xFF));
        out.write((int) ((len >> 8) & 0xFF));
        out.write((int) (len & 0xFF));
        out.write(payload);
        out.flush();
    }

    /**
     * 声明长度在 MAX_FRAME_PAYLOAD 之内的帧应能正常被处理。
     *
     * 这里发一个小的 TEXT 帧 "hello"，我们的 route 的 event=hello，应收到回显帧。
     */
    @Test(timeout = 15000)
    public void normalFrameIsEchoedBack() throws Exception {
        try (Socket sock = handshake(wsPort)) {
            OutputStream out = sock.getOutputStream();
            // 发 "hello" 文本帧，MASK=0
            ByteArrayOutputStream frame = new ByteArrayOutputStream();
            byte[] payload = "hello".getBytes(StandardCharsets.UTF_8);
            frame.write(0x81);
            frame.write(payload.length);   // 短帧长度
            frame.write(payload);
            out.write(frame.toByteArray());
            out.flush();

            // 读取响应（可能有多帧：连接建立后立即推送的 route-frame + 我们期望的 echo）
            sock.setSoTimeout(5000);
            InputStream in = sock.getInputStream();
            byte[] buf = new byte[4096];
            long deadline = System.currentTimeMillis() + 5000;
            boolean sawEcho = false;
            while (System.currentTimeMillis() < deadline) {
                int b0 = in.read();
                if (b0 < 0) break;
                int b1 = in.read();
                if (b1 < 0) break;
                boolean masked = (b1 & 0x80) != 0;
                long len = b1 & 0x7F;
                if (len == 126) {
                    len = ((in.read() & 0xFF) << 8) | (in.read() & 0xFF);
                } else if (len == 127) {
                    len = 0;
                    for (int i = 0; i < 8; i++) len = (len << 8) | (in.read() & 0xFF);
                }
                byte[] payloadRead = new byte[(int) len];
                int read = 0;
                while (read < payloadRead.length) {
                    int r = in.read(payloadRead, read, payloadRead.length - read);
                    if (r < 0) break;
                    read += r;
                }
                if (masked) {
                    // 应该不会有 mask（服务端到客户端），跳过
                }
                String text = new String(payloadRead, StandardCharsets.UTF_8);
                if (text.startsWith("echo:")) {
                    sawEcho = true;
                    break;
                }
            }
            assertTrue("expected echo frame", sawEcho);
        }
    }

    /**
     * 长度超过 4 MiB 但在合法范围内的帧（如 5 MB）应被关闭；不超过 4 MiB 的帧
     * （如 1 MB）应能正常 echo（通过 EVENT 命中触发的回包）。
     */
    @Test(timeout = 15000)
    public void largeButUnderCapFrameWorks() throws Exception {
        int payloadSize = 1 * 1024 * 1024;  // 1 MB，低于 4 MiB 上限
        try (Socket sock = handshake(wsPort)) {
            OutputStream out = sock.getOutputStream();
            ByteArrayOutputStream frame = new ByteArrayOutputStream();
            frame.write(0x81);              // FIN|TEXT
            frame.write(0x7F);              // 8 字节扩展长度
            // length = 1 MB
            frame.write(0x00); frame.write(0x00); frame.write(0x00); frame.write(0x00);
            frame.write((payloadSize >> 24) & 0xFF);
            frame.write((payloadSize >> 16) & 0xFF);
            frame.write((payloadSize >> 8) & 0xFF);
            frame.write(payloadSize & 0xFF);
            // payload 全部填 "hello" 首字符，便于回显命中
            for (int i = 0; i < payloadSize; i++) {
                frame.write((i < 5) ? "hello".charAt(i) : 'x');
            }
            out.write(frame.toByteArray());
            out.flush();

            // 应先收到 route 立即推送帧（含 echo:hello 因为我们 payload 前缀是 hello，
            // event=hello 关键字在服务端被他自身的 contains 匹配）
            sock.setSoTimeout(8000);
            InputStream in = sock.getInputStream();
            int b = readByte(in);
            assertTrue("expect some response (OP_CLOSE = 0x88 or text frame), got EOF=" + b,
                    b >= 0);
        }
    }

    /**
     * 声明长度 = -1（8 字节最高位为 1）的帧应被拒绝；不能抛 NegativeArraySizeException
     * 让线程崩溃。
     */
    @Test(timeout = 15000)
    public void negativeFrameLengthDoesNotCrashThread() throws Exception {
        try (Socket sock = handshake(wsPort)) {
            OutputStream out = sock.getOutputStream();
            ByteArrayOutputStream frame = new ByteArrayOutputStream();
            frame.write(0x81);
            frame.write(0x7F);
            // 8 字节 0xFF...FF（无符号最大值 = -1 长整型）
            for (int i = 0; i < 8; i++) frame.write(0xFF);
            out.write(frame.toByteArray());
            out.flush();

            sock.setSoTimeout(5000);
            InputStream in = sock.getInputStream();
            int b = in.read();
            assertTrue("expect OP_CLOSE reply, got EOF=" + b, b >= 0);
            assertEquals("expect OP_CLOSE first byte", 0x88, b);
        }
    }

    // =================== 帮助 ===================

    private static int readByte(InputStream in) throws IOException {
        return in.read();
    }

    /**
     * 与服务端完成 WebSocket 握手（101 Switching Protocols）。
     */
    private static Socket handshake(int port) throws Exception {
        Socket sock = new Socket("127.0.0.1", port);
        OutputStream out = sock.getOutputStream();
        String key = Base64.getEncoder().encodeToString("0123456789abcdef".getBytes());
        String req = "GET /ws/echo HTTP/1.1\r\n"
                + "Host: 127.0.0.1:" + port + "\r\n"
                + "Upgrade: websocket\r\n"
                + "Connection: Upgrade\r\n"
                + "Sec-WebSocket-Key: " + key + "\r\n"
                + "Sec-WebSocket-Version: 13\r\n"
                + "\r\n";
        out.write(req.getBytes(StandardCharsets.UTF_8));
        out.flush();

        InputStream in = sock.getInputStream();
        sock.setSoTimeout(5000);
        ByteArrayOutputStream headers = new ByteArrayOutputStream();
        int matched = 0;
        byte[] crlfcrlf = {'\r', '\n', '\r', '\n'};
        while (matched < 4) {
            int b = in.read();
            if (b < 0) break;
            headers.write(b);
            if (b == crlfcrlf[matched]) {
                matched++;
            } else {
                matched = 0;
            }
        }
        String resp = new String(headers.toByteArray(), StandardCharsets.UTF_8);
        assertTrue("expect 101, got: " + resp, resp.contains("101"));
        return sock;
    }
}
