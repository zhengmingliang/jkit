package com.alianga.jkit.http;

import com.alianga.jkit.HttpUtils;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.AfterClass;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * P1 性能回归：连接复用（keep-alive）、大文件 multipart 流式上传、空正文解码。
 *
 * @author 郑明亮
 */
public class HttpP1PerformanceTest {
    private static HttpServer server;
    private static String baseUrl;
    private static File workDir;
    /** 服务端观察到的不同客户端源端口，每个新 TCP 连接一个端口 */
    private static final Set<Integer> REMOTE_PORTS = ConcurrentHashMap.newKeySet();
    private static volatile long lastUploadBytes;
    private static volatile String lastUploadContentType;

    @BeforeClass
    public static void startServer() throws IOException {
        workDir = new File("target/http-p1-test");
        workDir.mkdirs();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/ping", exchange -> {
            readAll(exchange.getRequestBody());
            REMOTE_PORTS.add(exchange.getRemoteAddress().getPort());
            send(exchange, 200, "text/plain", "pong".getBytes(StandardCharsets.UTF_8));
        });
        server.createContext("/upload", exchange -> {
            byte[] body = readAll(exchange.getRequestBody());
            lastUploadBytes = body.length;
            lastUploadContentType = exchange.getRequestHeaders().getFirst("Content-Type");
            send(exchange, 200, "text/plain", ("received:" + body.length).getBytes(StandardCharsets.UTF_8));
        });
        // 声明 Content-Encoding: gzip 但正文为空：不得抛 EOFException
        server.createContext("/empty-gzip", exchange -> {
            readAll(exchange.getRequestBody());
            exchange.getResponseHeaders().set("Content-Encoding", "gzip");
            exchange.getResponseHeaders().set("Content-Type", "text/plain");
            exchange.sendResponseHeaders(200, -1);
            exchange.close();
        });
        server.createContext("/big", exchange -> {
            readAll(exchange.getRequestBody());
            byte[] payload = new byte[256 * 1024];
            send(exchange, 200, "application/octet-stream", payload);
        });
        server.start();
        baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @AfterClass
    public static void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Before
    public void reset() {
        HttpUtils.fakeIp = false;
        HttpUtils.setProxy(null);
        HttpUtils.setCookieJar(new CookieJarImpl());
        HttpUtils.supportHttps();
        HttpUtils.setConnectTimeout(60_000);
        HttpUtils.setReadTimeout(60_000);
        HttpUtils.setMaxBufferBytes(64L * 1024 * 1024);
        HttpUtils.config().setRetryPolicy(RetryPolicy.none());
        REMOTE_PORTS.clear();
    }

    @Test
    public void bufferedRequestsReuseTheSameConnection() throws IOException {
        for (int i = 0; i < 6; i++) {
            assertEquals("pong", HttpUtils.get(baseUrl + "/ping"));
        }
        assertTrue("6 次缓冲请求应复用少量连接，实际用了 " + REMOTE_PORTS.size() + " 个源端口"
                        + "（旧实现每次 disconnect() 会导致 6 个）",
                REMOTE_PORTS.size() <= 2);
    }

    @Test
    public void urlConnectionEngineReusesConnectionToo() throws IOException {
        HttpEngine previous = HttpUtils.getHttpEngine();
        HttpUtils.setEngine(HttpEngines.urlConnection());
        try {
            REMOTE_PORTS.clear();
            for (int i = 0; i < 6; i++) {
                assertEquals("pong", HttpUtils.get(baseUrl + "/ping"));
            }
            assertTrue("HttpURLConnection 引擎的缓冲路径也必须保留 keep-alive，实际源端口数 "
                            + REMOTE_PORTS.size(),
                    REMOTE_PORTS.size() <= 2);
        } finally {
            HttpUtils.setEngine(previous);
        }
    }

    @Test
    public void bigUploadIsSpooledToTempFileAndStillCorrect() throws IOException {
        File big = new File(workDir, "big-upload.bin");
        byte[] content = new byte[(int) (HttpBodies.SPOOL_THRESHOLD_BYTES + 4096)];
        for (int i = 0; i < content.length; i++) {
            content[i] = (byte) (i % 251);
        }
        Files.write(big.toPath(), content);

        UploadInfo info = new UploadInfo("file", big.getAbsolutePath(), big.getName());
        Map<String, String> extra = new LinkedHashMap<String, String>();
        extra.put("bizId", "1001");
        HttpResponse response = HttpUtils.upload(baseUrl + "/upload", info, extra, null);
        try {
            assertEquals(200, response.code());
        } finally {
            response.close();
        }
        assertTrue("服务端收到的字节数应大于文件本身（含 multipart 头）",
                lastUploadBytes > content.length);
        assertNotNull(lastUploadContentType);
        assertTrue(lastUploadContentType.startsWith("multipart/form-data; boundary="));
    }

    @Test
    public void bigMultipartUsesTempFileAndCleansUp() throws IOException {
        File big = new File(workDir, "spool-check.bin");
        Files.write(big.toPath(), new byte[(int) (HttpBodies.SPOOL_THRESHOLD_BYTES + 1)]);
        UploadInfo info = new UploadInfo("file", big.getAbsolutePath(), big.getName());
        HttpBodies.MultipartPayload payload = HttpBodies.multipart(info, null);
        assertTrue("大文件应走临时文件", payload.temporary);
        assertNotNull(payload.bodyFile);
        assertNull("走临时文件时不应再持有内存副本", payload.body);
        assertTrue(payload.bodyFile.isFile());
        assertTrue("临时文件应包含文件内容与 multipart 头",
                payload.bodyFile.length() > HttpBodies.SPOOL_THRESHOLD_BYTES);
        payload.cleanup();
        assertTrue("cleanup 后临时文件应被删除", !payload.bodyFile.exists());
    }

    @Test
    public void smallMultipartStaysInMemory() throws IOException {
        File small = new File(workDir, "small.txt");
        Files.write(small.toPath(), "hello".getBytes(StandardCharsets.UTF_8));
        UploadInfo info = new UploadInfo("file", small.getAbsolutePath(), small.getName());
        HttpBodies.MultipartPayload payload = HttpBodies.multipart(info, null);
        assertTrue("小文件仍走内存，避免临时文件开销", !payload.temporary);
        assertNull(payload.bodyFile);
        assertNotNull(payload.body);
        assertTrue(new String(payload.body, StandardCharsets.UTF_8).contains("hello"));
        payload.cleanup(); // 空操作，不应抛异常
    }

    @Test
    public void emptyBodyWithGzipEncodingDoesNotFail() throws IOException {
        assertEquals("声明 gzip 但正文为空时不应抛 EOFException", "", HttpUtils.get(baseUrl + "/empty-gzip"));
    }

    @Test
    public void maxBufferBytesStillEnforced() {
        HttpUtils.setMaxBufferBytes(1024);
        try {
            HttpUtils.get(baseUrl + "/big");
            org.junit.Assert.fail("超过 maxBufferBytes 应抛错");
        } catch (IOException e) {
            assertTrue("应提示超出缓冲上限: " + e.getMessage(),
                    e.getMessage().contains("maxBufferBytes"));
        } finally {
            HttpUtils.setMaxBufferBytes(64L * 1024 * 1024);
        }
    }

    private static void send(HttpExchange exchange, int code, String contentType, byte[] body) throws IOException {
        if (contentType != null) {
            exchange.getResponseHeaders().set("Content-Type", contentType);
        }
        exchange.sendResponseHeaders(code, body.length);
        exchange.getResponseBody().write(body);
        exchange.close();
    }

    private static byte[] readAll(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[4096];
        int n;
        while ((n = in.read(buf)) != -1) {
            out.write(buf, 0, n);
        }
        return out.toByteArray();
    }
}
