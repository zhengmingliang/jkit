package com.alianga.jkit.storage.engine;

import com.alianga.jkit.storage.StorageConfig;
import com.alianga.jkit.storage.StorageErrorCode;
import com.alianga.jkit.storage.StorageException;
import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * Azure Blob 引擎测试：内存假服务器模拟 Blob 服务（PUT/GET/HEAD/DELETE/List/复制），
 * 并按 2015-02-21 版规则重算 SharedKey 签名与请求头逐字节比对。
 */
public class AzureBlobFileStorageTest {
    private static final String ACCOUNT = "testaccount";
    private static final String CONTAINER = "testcontainer";
    private static final String ACCOUNT_KEY =
            Base64.getEncoder().encodeToString("azure-secret-key-0001".getBytes(StandardCharsets.UTF_8));
    private static final long FIXED_LAST_MODIFIED = 1728600000000L;
    private static final String MS_VERSION = "2021-08-06";

    private HttpServer server;
    private int port;
    private AzureBlobFileStorage storage;
    /** 模拟的 blob 存储：key（可含 /，目录占位以 / 结尾）→ 内容。 */
    private final Map<String, byte[]> blobs = new ConcurrentHashMap<String, byte[]>();
    private final List<Recorded> recorded = new CopyOnWriteArrayList<Recorded>();
    /** 列表每页条数上限（默认不限制；测试翻页时改为 1 强制分页）。 */
    private final AtomicInteger maxResultsPerPage = new AtomicInteger(Integer.MAX_VALUE);
    private final AtomicInteger listRequests = new AtomicInteger();

    @Before
    public void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", new FakeBlobService());
        server.start();
        port = server.getAddress().getPort();
        storage = new AzureBlobFileStorage(config());
    }

    @After
    public void tearDown() {
        if (server != null) {
            server.stop(0);
        }
    }

    private StorageConfig config() {
        return StorageConfig.empty()
                .set("endpoint", "http://127.0.0.1:" + port)
                .set("bucket", CONTAINER)
                .set("accessKey", ACCOUNT)
                .set("secretKey", ACCOUNT_KEY);
    }

    // ------------------------------------------------------------------ 测试用例

    @Test
    public void uploadCarriesVerifiableSharedKeySignature() throws Exception {
        byte[] data = "hello azure".getBytes(StandardCharsets.UTF_8);
        storage.upload("sig/a.txt", data);
        Recorded request = findRecorded("PUT", "sig/a.txt");
        String authorization = request.header("Authorization");
        assertNotNull("缺少 Authorization 头", authorization);
        assertTrue("Authorization 应以 SharedKey 开头: " + authorization,
                authorization.startsWith("SharedKey " + ACCOUNT + ":"));
        assertEquals(MS_VERSION, request.header("x-ms-version"));
        assertNotNull("缺少 x-ms-date 头", request.header("x-ms-date"));
        assertEquals("BlockBlob", request.header("x-ms-blob-type"));
        assertEquals("text/plain", request.header("Content-Type"));
        assertEquals("签名应与按 2015-02-21 规则重算的结果一致",
                expectedAuthorization(request), authorization);
    }

    @Test
    public void listSignatureCoversQuerySubResources() throws Exception {
        blobs.put("l/a.txt", "aaa".getBytes(StandardCharsets.UTF_8));
        blobs.put("l/b.txt", "bbbb".getBytes(StandardCharsets.UTF_8));
        blobs.put("l/sub/deep.txt", "cc".getBytes(StandardCharsets.UTF_8));
        blobs.put("top.txt", "dddd".getBytes(StandardCharsets.UTF_8));
        List<com.alianga.jkit.storage.StorageEntry> entries = storage.list("l");
        Recorded request = findRecorded("GET", "");
        assertEquals("列表签名应覆盖 restype/comp/prefix 等查询子资源",
                expectedAuthorization(request), request.header("Authorization"));
        assertEquals(3, entries.size());
        com.alianga.jkit.storage.StorageEntry file = findEntry(entries, "l/a.txt");
        assertFalse(file.isDirectory());
        assertEquals(3L, file.size());
        assertEquals("0x1", file.etag());
        assertEquals(FIXED_LAST_MODIFIED, file.lastModified());
        assertTrue(findEntry(entries, "l/sub").isDirectory());
    }

    @Test
    public void listPagesUntilNextMarkerEmpty() throws Exception {
        blobs.put("p1/a.txt", "a".getBytes(StandardCharsets.UTF_8));
        blobs.put("p1/b.txt", "b".getBytes(StandardCharsets.UTF_8));
        blobs.put("p2/c.txt", "c".getBytes(StandardCharsets.UTF_8));
        blobs.put("root.txt", "d".getBytes(StandardCharsets.UTF_8));
        maxResultsPerPage.set(1);
        List<com.alianga.jkit.storage.StorageEntry> entries = storage.list("");
        assertEquals(3, entries.size());
        assertTrue(findEntry(entries, "p1").isDirectory());
        assertTrue(findEntry(entries, "p2").isDirectory());
        assertEquals(1L, findEntry(entries, "root.txt").size());
        assertEquals("服务端每页只放 1 条，引擎应翻页 3 次", 3, listRequests.get());
    }

    @Test
    public void uploadDownloadRoundTripWithChinesePath() {
        byte[] data = "你好，Azure Blob。".getBytes(StandardCharsets.UTF_8);
        storage.upload("docs/中文 说明.txt", data);
        assertArrayEquals(data, blobs.get("docs/中文 说明.txt"));
        assertArrayEquals(data, storage.readBytes("docs/中文 说明.txt"));
    }

    @Test
    public void mkdirCreatesPlaceholderAndIsIdempotent() {
        storage.mkdir("docs");
        assertTrue(blobs.containsKey("docs/"));
        assertArrayEquals(new byte[0], blobs.get("docs/"));
        storage.mkdir("docs");
        assertTrue(storage.exists("docs"));
        assertTrue(storage.stat("docs").isDirectory());
    }

    @Test
    public void statReturnsFileMetadataThenDirectoryFallback() {
        byte[] data = "meta".getBytes(StandardCharsets.UTF_8);
        storage.upload("docs/meta.txt", data);
        com.alianga.jkit.storage.StorageEntry entry = storage.stat("docs/meta.txt");
        assertEquals("meta.txt", entry.name());
        assertEquals(data.length, entry.size());
        assertEquals("0x1", entry.etag());
        assertEquals(FIXED_LAST_MODIFIED, entry.lastModified());
        storage.mkdir("docs");
        assertTrue(storage.stat("docs").isDirectory());
        try {
            storage.stat("missing.bin");
            fail("stat 不存在的对象应抛 NOT_FOUND");
        } catch (StorageException e) {
            assertEquals(StorageErrorCode.NOT_FOUND, e.code());
        }
    }

    @Test
    public void existsDetectsFilesPlaceholdersAndImplicitDirs() {
        storage.upload("imp/x.txt", "x".getBytes(StandardCharsets.UTF_8));
        assertTrue(storage.exists("imp/x.txt"));
        assertTrue("隐式目录（无占位对象）应通过列表探测", storage.exists("imp"));
        storage.mkdir("docs");
        assertTrue(storage.exists("docs"));
        assertFalse(storage.exists("nope"));
        assertFalse(storage.exists("nope/deeper"));
    }

    @Test
    public void copyDuplicatesObjectServerSide() {
        byte[] data = "copy me".getBytes(StandardCharsets.UTF_8);
        storage.upload("src/a.txt", data);
        storage.copy("src/a.txt", "dst/b.txt");
        assertArrayEquals(data, blobs.get("dst/b.txt"));
        assertArrayEquals(data, storage.readBytes("src/a.txt"));
        Recorded request = findRecorded("PUT", "dst/b.txt");
        assertEquals("http://127.0.0.1:" + port + "/" + CONTAINER + "/src/a.txt",
                request.header("x-ms-copy-source"));
    }

    @Test
    public void renameCopiesThenDeletesSource() {
        byte[] data = "move me".getBytes(StandardCharsets.UTF_8);
        storage.upload("old/名前.txt", data);
        storage.rename("old/名前.txt", "new/名称.txt");
        assertFalse(blobs.containsKey("old/名前.txt"));
        assertArrayEquals(data, storage.readBytes("new/名称.txt"));
    }

    @Test
    public void deleteIsIdempotentAndGuardsNonEmptyDirs() {
        byte[] data = "doomed".getBytes(StandardCharsets.UTF_8);
        storage.upload("tmp/gone.txt", data);
        storage.delete("tmp/gone.txt");
        assertFalse(blobs.containsKey("tmp/gone.txt"));
        assertFalse(storage.exists("tmp/gone.txt"));
        storage.delete("tmp/gone.txt");
        storage.mkdir("full");
        storage.upload("full/child.txt", data);
        try {
            storage.delete("full");
            fail("非空目录删除应抛 CONFLICT");
        } catch (StorageException e) {
            assertEquals(StorageErrorCode.CONFLICT, e.code());
        }
        storage.mkdir("empty");
        storage.delete("empty");
        assertFalse("空目录占位对象应被删除", storage.exists("empty"));
        try {
            storage.delete("");
            fail("删除根目录应抛 INVALID_CONFIG");
        } catch (StorageException e) {
            assertEquals(StorageErrorCode.INVALID_CONFIG, e.code());
        }
    }

    @Test
    public void downloadMissingObjectThrowsNotFound() {
        try {
            storage.download("missing.bin", new ByteArrayOutputStream());
            fail("下载不存在的对象应抛 NOT_FOUND");
        } catch (StorageException e) {
            assertEquals(StorageErrorCode.NOT_FOUND, e.code());
        }
    }

    // ------------------------------------------------------------------ 断言工具

    private Recorded findRecorded(String method, String key) {
        for (Recorded request : recorded) {
            if (method.equals(request.method) && key.equals(request.key)) {
                return request;
            }
        }
        fail("未找到请求 " + method + " /" + key);
        return null;
    }

    private com.alianga.jkit.storage.StorageEntry findEntry(
            List<com.alianga.jkit.storage.StorageEntry> entries, String path) {
        for (com.alianga.jkit.storage.StorageEntry entry : entries) {
            if (entry.path().equals(path)) {
                return entry;
            }
        }
        fail("列表中缺少条目: " + path);
        return null;
    }

    /** 按引擎同一套 2015-02-21 规则独立重算 SharedKey 签名。 */
    private String expectedAuthorization(Recorded request) throws Exception {
        String contentType = request.header("Content-Type");
        StringBuilder toSign = new StringBuilder();
        toSign.append(request.method).append('\n');
        toSign.append('\n');
        toSign.append('\n');
        toSign.append(request.contentLength > 0 ? String.valueOf(request.contentLength) : "").append('\n');
        toSign.append('\n');
        toSign.append(contentType == null ? "" : contentType).append('\n');
        toSign.append('\n');
        toSign.append('\n');
        toSign.append('\n');
        toSign.append('\n');
        toSign.append('\n');
        toSign.append('\n');
        TreeMap<String, String> xms = new TreeMap<String, String>();
        for (Map.Entry<String, List<String>> entry : request.headers.entrySet()) {
            if (entry.getKey() == null || entry.getValue() == null || entry.getValue().isEmpty()) {
                continue;
            }
            String name = entry.getKey().toLowerCase(Locale.US);
            if (name.startsWith("x-ms-")) {
                xms.put(name, entry.getValue().get(0));
            }
        }
        for (Map.Entry<String, String> entry : xms.entrySet()) {
            toSign.append(entry.getKey()).append(':').append(entry.getValue()).append('\n');
        }
        toSign.append('/').append(ACCOUNT).append('/').append(CONTAINER);
        if (!request.key.isEmpty()) {
            toSign.append('/').append(request.key);
        }
        for (Map.Entry<String, String> entry : request.query.entrySet()) {
            toSign.append('\n').append(entry.getKey()).append(':').append(entry.getValue());
        }
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(Base64.getDecoder().decode(ACCOUNT_KEY), "HmacSHA256"));
        byte[] signature = mac.doFinal(toSign.toString().getBytes(StandardCharsets.UTF_8));
        return "SharedKey " + ACCOUNT + ":" + Base64.getEncoder().encodeToString(signature);
    }

    // ------------------------------------------------------------------ 假 Blob 服务

    private class FakeBlobService implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            byte[] requestBody = readAll(exchange.getRequestBody());
            Recorded request = record(exchange, requestBody);
            String method = exchange.getRequestMethod();
            try {
                if (request.key == null || request.key.isEmpty()) {
                    // 容器级请求（List Blobs）
                    if ("GET".equals(method)) {
                        handleList(exchange, request);
                    } else {
                        respond(exchange, 405, errorXml("InvalidInput", "unexpected container request"));
                    }
                    return;
                }
                if ("PUT".equals(method)) {
                    handlePut(exchange, request);
                } else if ("GET".equals(method)) {
                    handleGet(exchange, request.key);
                } else if ("HEAD".equals(method)) {
                    handleHead(exchange, request.key);
                } else if ("DELETE".equals(method)) {
                    handleDelete(exchange, request.key);
                } else {
                    respond(exchange, 405, errorXml("InvalidInput", "unsupported method " + method));
                }
            } finally {
                exchange.close();
            }
        }

        private void handlePut(HttpExchange exchange, Recorded request) throws IOException {
            String copySource = request.header("x-ms-copy-source");
            if (copySource != null) {
                // URI.getPath 会解码百分号编码，URL.getPath 不会
                String sourceKey = URI.create(copySource).getPath()
                        .substring(("/" + CONTAINER + "/").length());
                byte[] source = blobs.get(sourceKey);
                if (source == null) {
                    respond(exchange, 404, errorXml("BlobNotFound", "source blob missing"));
                    return;
                }
                blobs.put(request.key, source.clone());
                respond(exchange, 202, null);
                return;
            }
            if (request.key.endsWith("/")) {
                if (blobs.containsKey(request.key)) {
                    respond(exchange, 409, errorXml("BlobAlreadyExists", "placeholder exists"));
                    return;
                }
                blobs.put(request.key, new byte[0]);
                respond(exchange, 201, null);
                return;
            }
            blobs.put(request.key, request.body);
            respond(exchange, 201, null);
        }

        private void handleGet(HttpExchange exchange, String key) throws IOException {
            byte[] content = blobs.get(key);
            if (content == null) {
                respond(exchange, 404, errorXml("BlobNotFound", "The specified blob does not exist."));
                return;
            }
            respond(exchange, 200, content);
        }

        private void handleHead(HttpExchange exchange, String key) throws IOException {
            byte[] content = blobs.get(key);
            if (content == null) {
                // HEAD 的 404 不能带响应体（JDK 会强制丢弃并告警），按状态码语义返回
                exchange.sendResponseHeaders(404, -1);
                return;
            }
            // JDK HttpServer 对 HEAD 禁止经 API 传 content length，需手工设置响应头
            Headers headers = exchange.getResponseHeaders();
            headers.set("Content-Length", String.valueOf(content.length));
            headers.set("ETag", "0x1");
            headers.set("Last-Modified", rfc1123(FIXED_LAST_MODIFIED));
            exchange.sendResponseHeaders(200, -1);
        }

        private void handleDelete(HttpExchange exchange, String key) throws IOException {
            if (blobs.remove(key) == null) {
                respond(exchange, 404, errorXml("BlobNotFound", "The specified blob does not exist."));
                return;
            }
            respond(exchange, 202, null);
        }

        private void handleList(HttpExchange exchange, Recorded request) throws IOException {
            listRequests.incrementAndGet();
            String prefix = request.query.containsKey("prefix") ? request.query.get("prefix") : "";
            String delimiter = request.query.containsKey("delimiter") ? request.query.get("delimiter") : "/";
            String marker = request.query.containsKey("marker") ? request.query.get("marker") : "";
            int pageSize = Math.max(1, maxResultsPerPage.get());
            TreeMap<String, Item> items = new TreeMap<String, Item>();
            for (Map.Entry<String, byte[]> entry : blobs.entrySet()) {
                String key = entry.getKey();
                if (!key.startsWith(prefix) || key.equals(prefix)) {
                    continue;
                }
                String relative = key.substring(prefix.length());
                int at = delimiter.isEmpty() ? -1 : relative.indexOf(delimiter);
                if (at >= 0) {
                    String dirName = prefix + relative.substring(0, at + delimiter.length());
                    items.put(dirName, new Item(dirName, true, new byte[0]));
                } else {
                    items.put(key, new Item(key, false, entry.getValue()));
                }
            }
            List<Item> remaining = new ArrayList<Item>();
            for (Item item : items.values()) {
                if (marker.isEmpty() || item.name.compareTo(marker) > 0) {
                    remaining.add(item);
                }
            }
            List<Item> page = remaining.subList(0, Math.min(pageSize, remaining.size()));
            String nextMarker = page.size() < remaining.size() ? page.get(page.size() - 1).name : "";
            StringBuilder xml = new StringBuilder();
            xml.append("<?xml version=\"1.0\" encoding=\"utf-8\"?>");
            xml.append("<EnumerationResults>");
            xml.append("<Prefix>").append(prefix).append("</Prefix>");
            xml.append("<Delimiter>").append(delimiter).append("</Delimiter>");
            if (!marker.isEmpty()) {
                xml.append("<Marker>").append(marker).append("</Marker>");
            }
            xml.append("<MaxResults>").append(pageSize).append("</MaxResults>");
            xml.append("<Blobs>");
            for (Item item : page) {
                if (item.directory) {
                    xml.append("<BlobPrefix><Name>").append(item.name).append("</Name></BlobPrefix>");
                } else {
                    xml.append("<Blob><Name>").append(item.name).append("</Name><Properties>")
                            .append("<Last-Modified>").append(rfc1123(FIXED_LAST_MODIFIED))
                            .append("</Last-Modified>")
                            .append("<Etag>0x1</Etag>")
                            .append("<Content-Length>").append(item.content.length)
                            .append("</Content-Length>")
                            .append("<Content-Type>text/plain</Content-Type>")
                            .append("</Properties></Blob>");
                }
            }
            xml.append("</Blobs>");
            xml.append("<NextMarker>").append(nextMarker).append("</NextMarker>");
            xml.append("</EnumerationResults>");
            respond(exchange, 200, xml.toString().getBytes(StandardCharsets.UTF_8));
        }
    }

    // ------------------------------------------------------------------ 记录与工具

    private Recorded record(HttpExchange exchange, byte[] body) {
        Recorded request = new Recorded();
        request.method = exchange.getRequestMethod();
        request.uri = exchange.getRequestURI();
        request.headers = exchange.getRequestHeaders();
        request.body = body;
        String contentLength = exchange.getRequestHeaders().getFirst("Content-Length");
        request.contentLength = contentLength == null ? 0L : Long.parseLong(contentLength);
        String path = request.uri.getPath();
        String containerPrefix = "/" + CONTAINER + "/";
        if (path.equals("/" + CONTAINER)) {
            request.key = "";
        } else if (path.startsWith(containerPrefix)) {
            request.key = path.substring(containerPrefix.length());
        } else {
            request.key = null;
        }
        request.query = parseQuery(request.uri.getRawQuery());
        recorded.add(request);
        return request;
    }

    private static TreeMap<String, String> parseQuery(String rawQuery) {
        TreeMap<String, String> query = new TreeMap<String, String>();
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

    private void respond(HttpExchange exchange, int status, byte[] body) throws IOException {
        if (body == null || body.length == 0) {
            exchange.sendResponseHeaders(status, -1);
        } else {
            exchange.sendResponseHeaders(status, body.length);
            exchange.getResponseBody().write(body);
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

    private static byte[] errorXml(String code, String message) {
        String xml = "<?xml version=\"1.0\" encoding=\"utf-8\"?><Error><Code>" + code
                + "</Code><Message>" + message + "</Message></Error>";
        return xml.getBytes(StandardCharsets.UTF_8);
    }

    private static String rfc1123(long epochMillis) {
        SimpleDateFormat format =
                new SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss 'GMT'", Locale.US);
        format.setTimeZone(java.util.TimeZone.getTimeZone("GMT"));
        return format.format(new Date(epochMillis));
    }

    /** 一次请求的记录（headers 为 JDK 大小写不敏感视图）。 */
    private static final class Recorded {
        private String method;
        private java.net.URI uri;
        private Headers headers;
        private byte[] body;
        private long contentLength;
        /** blob key（已解码）；容器级请求为空串；非该容器的请求为 null。 */
        private String key;
        private TreeMap<String, String> query;

        private String header(String name) {
            return headers == null ? null : headers.getFirst(name);
        }
    }

    /** 列表条目的临时结构。 */
    private static final class Item {
        private final String name;
        private final boolean directory;
        private final byte[] content;

        private Item(String name, boolean directory, byte[] content) {
            this.name = name;
            this.directory = directory;
            this.content = content;
        }
    }
}
