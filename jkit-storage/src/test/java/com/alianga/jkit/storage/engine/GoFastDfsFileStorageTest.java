package com.alianga.jkit.storage.engine;

import com.alianga.jkit.storage.StorageConfig;
import com.alianga.jkit.storage.StorageEntry;
import com.alianga.jkit.storage.StorageErrorCode;
import com.alianga.jkit.storage.StorageException;
import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * go-fastdfs 引擎测试：内存假服务器实现 /group1/upload（multipart 解析）、
 * GET / DELETE / HEAD / list 接口，覆盖上传回读、中文路径、token 透传与错误映射。
 */
public class GoFastDfsFileStorageTest {
    private static final String GROUP = "group1";
    private static final String TOKEN = "t0k3n";
    private static final long FIXED_MTIME = 1728600000000L;

    private HttpServer server;
    private int port;
    private GoFastDfsFileStorage storage;
    /** 模拟存储：key → 内容。 */
    private final Map<String, byte[]> files = new ConcurrentHashMap<String, byte[]>();
    /** dir → list 接口的 JSON 响应。 */
    private final Map<String, String> listResponses = new HashMap<String, String>();
    private final StringBuilder uploads = new StringBuilder();
    private volatile String lastAuthHeader;

    @Before
    public void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", new FakeFastDfs());
        server.start();
        port = server.getAddress().getPort();
        storage = new GoFastDfsFileStorage(config(TOKEN));
    }

    @After
    public void tearDown() {
        if (server != null) {
            server.stop(0);
        }
    }

    private StorageConfig config(String token) {
        StorageConfig config = StorageConfig.empty()
                .set("endpoint", "http://127.0.0.1:" + port)
                .set("group", GROUP);
        if (token != null) {
            config.set("token", token);
        }
        return config;
    }

    private static String md5Of(String key) {
        return "md5-" + Integer.toHexString(key.hashCode());
    }

    // ------------------------------------------------------------------ 测试用例

    @Test
    public void uploadStoresMultipartFileAndRoundTrips() {
        byte[] data = "中文内容abc123".getBytes(StandardCharsets.UTF_8);
        storage.upload("docs/中文 文件.txt", data);
        assertArrayEquals("服务端应按 path + filename 落盘", data,
                files.get("docs/中文 文件.txt"));
        assertArrayEquals("下载回读应一致", data, storage.readBytes("docs/中文 文件.txt"));
        assertTrue(uploads.toString().contains("name=\"path\""));
        assertTrue(uploads.toString().contains("output"));
    }

    @Test
    public void uploadAtRootUsesEmptyPathField() {
        byte[] data = "root file".getBytes(StandardCharsets.UTF_8);
        storage.upload("root.txt", data);
        assertArrayEquals(data, files.get("root.txt"));
        assertArrayEquals(data, storage.readBytes("root.txt"));
    }

    @Test
    public void uploadWithUnknownSizeSpoolsToFixedLength() {
        byte[] data = "unknown length body".getBytes(StandardCharsets.UTF_8);
        storage.upload("docs/spooled.txt", new java.io.ByteArrayInputStream(data), -1L);
        assertArrayEquals(data, storage.readBytes("docs/spooled.txt"));
    }

    @Test
    public void listReturnsFilesAndDirectories() {
        listResponses.put("", "[{\"name\":\"docs/\",\"path\":\"/docs/\"},"
                + "{\"name\":\"root.txt\",\"size\":5,\"mtime\":1728600000,\"md5\":\"md5-root\","
                + "\"path\":\"/root.txt\"}]");
        listResponses.put("docs", "[{\"name\":\"a.txt\",\"size\":3,\"mtime\":1728600000,"
                + "\"md5\":\"md5-a\",\"path\":\"/docs/a.txt\"},"
                + "{\"name\":\"b.txt\",\"size\":0,\"mtime\":\"2026-01-02 03:04:05\","
                + "\"path\":\"/docs/b.txt\"}]");
        java.util.List<StorageEntry> root = storage.list("");
        assertEquals(2, root.size());
        StorageEntry docs = root.get(0).path().equals("docs") ? root.get(0) : root.get(1);
        StorageEntry rootFile = root.get(0).path().equals("root.txt") ? root.get(0) : root.get(1);
        assertTrue(docs.isDirectory());
        assertEquals("docs", docs.name());
        assertFalse(rootFile.isDirectory());
        assertEquals(5L, rootFile.size());
        assertEquals("md5-root", rootFile.etag());
        assertEquals(1728600000000L, rootFile.lastModified());
        java.util.List<StorageEntry> docsList = storage.list("docs");
        assertEquals(2, docsList.size());
        StorageEntry bTxt = docsList.get(0).path().endsWith("b.txt")
                ? docsList.get(0) : docsList.get(1);
        assertEquals("b.txt", bTxt.name());
        assertEquals(0L, bTxt.size());
        assertTrue("字符串 mtime 应被解析", bTxt.lastModified() > 0);
        assertEquals("md5-a", (docsList.get(0).path().endsWith("a.txt")
                ? docsList.get(0) : docsList.get(1)).etag());
    }

    @Test
    public void deleteRemovesFileAndIsIdempotent() {
        byte[] data = "doomed".getBytes(StandardCharsets.UTF_8);
        storage.upload("tmp/gone.txt", data);
        storage.delete("tmp/gone.txt");
        assertFalse(files.containsKey("tmp/gone.txt"));
        storage.delete("tmp/gone.txt");
        try {
            storage.delete("");
            fail("删除根目录应抛 INVALID_CONFIG");
        } catch (StorageException e) {
            assertEquals(StorageErrorCode.INVALID_CONFIG, e.code());
        }
    }

    @Test
    public void existsFallsBackToListingForImplicitDirs() {
        byte[] data = "x".getBytes(StandardCharsets.UTF_8);
        storage.upload("docs/x.txt", data);
        assertTrue(storage.exists("docs/x.txt"));
        assertTrue("目录是隐式的，应通过 list 探测", storage.exists("docs"));
        assertFalse(storage.exists("missing.txt"));
    }

    @Test
    public void statUsesHeadAndCachedMd5Etag() {
        byte[] data = "stat me".getBytes(StandardCharsets.UTF_8);
        storage.upload("docs/counts.txt", data);
        StorageEntry entry = storage.stat("docs/counts.txt");
        assertEquals(data.length, entry.size());
        assertEquals(md5Of("docs/counts.txt"), entry.etag());
        assertEquals(FIXED_MTIME, entry.lastModified());
        try {
            storage.stat("missing.bin");
            fail("stat 不存在的对象应抛 NOT_FOUND");
        } catch (StorageException e) {
            assertEquals(StorageErrorCode.NOT_FOUND, e.code());
        }
    }

    @Test
    public void tokenIsSentAsBearerOnEveryRequest() {
        storage.upload("docs/t.txt", "1".getBytes(StandardCharsets.UTF_8));
        storage.exists("docs/t.txt");
        storage.delete("docs/t.txt");
        assertEquals("Bearer " + TOKEN, lastAuthHeader);
        GoFastDfsFileStorage anonymous = new GoFastDfsFileStorage(config(null));
        anonymous.exists("anything.txt");
        assertNull("未配置 token 时不应带 Authorization 头", lastAuthHeader);
    }

    @Test
    public void downloadMissingObjectThrowsNotFound() {
        try {
            storage.download("missing.txt", new ByteArrayOutputStream());
            fail("下载不存在的对象应抛 NOT_FOUND");
        } catch (StorageException e) {
            assertEquals(StorageErrorCode.NOT_FOUND, e.code());
        }
    }

    @Test
    public void copyAndRenameMoveBytes() {
        byte[] data = "clone".getBytes(StandardCharsets.UTF_8);
        storage.upload("src/a.txt", data);
        storage.copy("src/a.txt", "dst/b.txt");
        assertArrayEquals(data, storage.readBytes("src/a.txt"));
        assertArrayEquals(data, storage.readBytes("dst/b.txt"));
        storage.rename("src/a.txt", "dst/c.txt");
        assertFalse(files.containsKey("src/a.txt"));
        assertArrayEquals(data, storage.readBytes("dst/c.txt"));
    }

    // ------------------------------------------------------------------ 假 go-fastdfs 服务

    private class FakeFastDfs implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            lastAuthHeader = exchange.getRequestHeaders().getFirst("Authorization");
            String method = exchange.getRequestMethod();
            String path = exchange.getRequestURI().getPath();
            try {
                if ("POST".equals(method) && path.equals("/" + GROUP + "/upload")) {
                    handleUpload(exchange);
                } else if ("GET".equals(method) && path.equals("/" + GROUP + "/list")) {
                    handleList(exchange);
                } else if ("GET".equals(method)) {
                    handleGet(exchange, objectKeyOf(exchange));
                } else if ("HEAD".equals(method)) {
                    handleHead(exchange, objectKeyOf(exchange));
                } else if ("DELETE".equals(method)) {
                    handleDelete(exchange, objectKeyOf(exchange));
                } else {
                    respond(exchange, 405, "unsupported " + method);
                }
            } finally {
                exchange.close();
            }
        }

        private void handleUpload(HttpExchange exchange) throws IOException {
            byte[] raw = readAll(exchange.getRequestBody());
            String contentType = exchange.getRequestHeaders().getFirst("Content-Type");
            String body = new String(raw, StandardCharsets.UTF_8);
            String boundary = contentType.substring(contentType.indexOf("boundary=") + "boundary=".length());
            String pathField = "";
            String filename = "";
            String fileContent = null;
            for (String section : body.split(Pattern.quote("--" + boundary))) {
                if (section.startsWith("--")) {
                    break;
                }
                int headerEnd = section.indexOf("\r\n\r\n");
                if (headerEnd < 0) {
                    continue;
                }
                String partHeaders = section.substring(2, headerEnd);
                String value = section.substring(headerEnd + 4);
                if (value.endsWith("\r\n")) {
                    value = value.substring(0, value.length() - 2);
                }
                if (partHeaders.contains("name=\"path\"")) {
                    pathField = value;
                } else if (partHeaders.contains("name=\"file\"")) {
                    filename = valueBetween(partHeaders, "filename=\"");
                    fileContent = value;
                }
            }
            if (fileContent == null) {
                respond(exchange, 400, "missing file part");
                return;
            }
            String key = pathField.isEmpty() ? filename : pathField + "/" + filename;
            files.put(key, fileContent.getBytes(StandardCharsets.UTF_8));
            uploads.append(body);
            String url = "http://127.0.0.1:" + port + "/" + GROUP + "/" + key;
            String json = "{\"url\":\"" + url + "\",\"md5\":\"" + md5Of(key)
                    + "\",\"path\":\"/" + key + "\"}";
            respond(exchange, 200, json);
        }

        private void handleList(HttpExchange exchange) throws IOException {
            Map<String, String> query = parseQuery(exchange.getRequestURI().getRawQuery());
            String dir = query.containsKey("dir") ? query.get("dir") : "";
            String json = listResponses.containsKey(dir)
                    ? listResponses.get(dir) : deriveListing(dir);
            respond(exchange, 200, json);
        }

        /** 无预置响应时，按存储内容推导目录列表（目录 = 含 / 的公共前缀）。 */
        private String deriveListing(String dir) {
            String prefix = dir.isEmpty() ? "" : dir + "/";
            Map<String, Boolean> items = new HashMap<String, Boolean>();
            for (String key : files.keySet()) {
                if (!key.startsWith(prefix)) {
                    continue;
                }
                String relative = key.substring(prefix.length());
                int at = relative.indexOf('/');
                if (at >= 0) {
                    items.put(relative.substring(0, at) + "/", Boolean.TRUE);
                } else {
                    items.put(relative, Boolean.FALSE);
                }
            }
            StringBuilder json = new StringBuilder("[");
            for (Map.Entry<String, Boolean> item : items.entrySet()) {
                if (json.length() > 1) {
                    json.append(',');
                }
                if (item.getValue()) {
                    json.append("{\"name\":\"").append(item.getKey()).append("\",\"isDir\":true}");
                } else {
                    byte[] content = files.get(prefix + item.getKey());
                    json.append("{\"name\":\"").append(item.getKey())
                            .append("\",\"size\":").append(content == null ? 0 : content.length)
                            .append(",\"md5\":\"").append(md5Of(prefix + item.getKey())).append("\"}");
                }
            }
            return json.append(']').toString();
        }

        private void handleGet(HttpExchange exchange, String key) throws IOException {
            byte[] content = files.get(key);
            if (content == null) {
                respond(exchange, 404, "no such file: " + key);
                return;
            }
            respond(exchange, 200, new String(content, StandardCharsets.UTF_8));
        }

        private void handleHead(HttpExchange exchange, String key) throws IOException {
            byte[] content = files.get(key);
            if (content == null) {
                exchange.sendResponseHeaders(404, -1);
                return;
            }
            // HEAD 响应体不可发送，Content-Length 需手工设置在响应头里
            Headers headers = exchange.getResponseHeaders();
            headers.set("Content-Length", String.valueOf(content.length));
            headers.set("Last-Modified", rfc1123(FIXED_MTIME));
            exchange.sendResponseHeaders(200, -1);
        }

        private void handleDelete(HttpExchange exchange, String key) throws IOException {
            files.remove(key);
            respond(exchange, 200, "");
        }
    }

    // ------------------------------------------------------------------ 工具

    private String objectKeyOf(HttpExchange exchange) {
        String path = exchange.getRequestURI().getPath();
        return path.substring(("/" + GROUP + "/").length());
    }

    private static String valueBetween(String text, String marker) {
        int at = text.indexOf(marker);
        if (at < 0) {
            return "";
        }
        int from = at + marker.length();
        int end = text.indexOf('"', from);
        return end < 0 ? text.substring(from) : text.substring(from, end);
    }

    private static Map<String, String> parseQuery(String rawQuery) {
        Map<String, String> query = new HashMap<String, String>();
        if (rawQuery == null || rawQuery.isEmpty()) {
            return query;
        }
        for (String pair : rawQuery.split("&")) {
            int at = pair.indexOf('=');
            String name = at < 0 ? pair : pair.substring(0, at);
            String value = at < 0 ? "" : pair.substring(at + 1);
            query.put(URLDecoder.decode(name, StandardCharsets.UTF_8),
                    URLDecoder.decode(value, StandardCharsets.UTF_8));
        }
        return query;
    }

    private void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body == null ? new byte[0] : body.getBytes(StandardCharsets.UTF_8);
        if (bytes.length == 0) {
            exchange.sendResponseHeaders(status, -1);
        } else {
            exchange.sendResponseHeaders(status, bytes.length);
            exchange.getResponseBody().write(bytes);
        }
    }

    private static byte[] readAll(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] chunk = new byte[8192];
        int read;
        while ((read = in.read(chunk)) >= 0) {
            out.write(chunk, 0, read);
        }
        return out.toByteArray();
    }

    private static String rfc1123(long epochMillis) {
        SimpleDateFormat format =
                new SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss 'GMT'", Locale.US);
        format.setTimeZone(java.util.TimeZone.getTimeZone("GMT"));
        return format.format(new Date(epochMillis));
    }
}
