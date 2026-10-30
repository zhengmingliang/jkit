package com.alianga.jkit.storage.webdav.versioned;

import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Base64;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 进程内假 WebDAV：PROPFIND / MKCOL / PUT / GET / DELETE + Basic 认证。
 *
 * <p>三种覆盖模式（通用分类，不绑定产品名语义之外的行为）：</p>
 * <ul>
 *   <li>{@link Mode#STRICT_412} — 尊重 If-None-Match / If-Match，冲突 412；返回强 ETag</li>
 *   <li>{@link Mode#IGNORE_HEADERS_OVERWRITE} — 忽略条件头，静默覆盖；返回强 ETag</li>
 *   <li>{@link Mode#RENAME_ON_EXISTS} — 忽略条件头；已存在则按 copyparty 改名另存（{@code <orig>-<ts>-<rand><ext>}，201 + Location）；无 ETag；
 *       {@code replace: 1} 可强制覆盖</li>
 * </ul>
 *
 * @author 郑明亮
 */
public final class FakeWebDavServer implements AutoCloseable {

    /** PUT 对已存在文件的行为。 */
    public enum Mode {
        STRICT_412,
        IGNORE_HEADERS_OVERWRITE,
        RENAME_ON_EXISTS
    }

    private final HttpServer server;
    private final int port;
    private final String user;
    private final String password;
    private final String expectedAuth;
    private final Map<String, FileEntry> store = new ConcurrentHashMap<>();
    private volatile Mode mode = Mode.STRICT_412;
    private volatile boolean refuseProbeDelete;
    /** 模拟无删权：所有 DELETE 返回 403（残留改名文件会留在远端）。 */
    private volatile boolean refuseAllDeletes;
    private volatile String readOnlyPrefix = "/readonly";
    private volatile String noMethodPrefix = "/nomethod";
    private final AtomicLong clockMs = new AtomicLong(System.currentTimeMillis());

    static final class FileEntry {
        private final byte[] data;
        private final String etag;
        private final long mtimeMs;

        FileEntry(byte[] data, String etag, long mtimeMs) {
            this.data = data;
            this.etag = etag;
            this.mtimeMs = mtimeMs;
        }

        byte[] data() { return data; }
        String etag() { return etag; }
        long mtimeMs() { return mtimeMs; }
    }

    public FakeWebDavServer(String user, String password) throws IOException {
        this.user = user;
        this.password = password;
        this.expectedAuth = "Basic " + Base64.getEncoder()
                .encodeToString((user + ":" + password).getBytes(StandardCharsets.UTF_8));
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", this::handle);
        server.setExecutor(Executors.newCachedThreadPool());
        server.start();
        port = server.getAddress().getPort();
    }

    public static FakeWebDavServer startDefault() throws IOException {
        return new FakeWebDavServer("alice", "secret");
    }

    public int port() {
        return port;
    }

    /**
     * 兼容旧测试的可变视图：put/remove 同步到内部 Entry 存储。
     */
    public Map<String, byte[]> store() {
        return new ConcurrentHashMap<String, byte[]>() {
            {
                for (Map.Entry<String, FileEntry> e : FakeWebDavServer.this.store.entrySet()) {
                    super.put(e.getKey(), e.getValue().data());
                }
            }

            @Override
            public byte[] put(String key, byte[] value) {
                byte[] prev = super.put(key, value);
                long mt = clockMs.incrementAndGet();
                FakeWebDavServer.this.store.put(key,
                        new FileEntry(value == null ? new byte[0] : value,
                                mode == Mode.RENAME_ON_EXISTS ? "" : strongEtag(value == null ? new byte[0] : value),
                                mt));
                return prev;
            }

            @Override
            public byte[] remove(Object key) {
                byte[] prev = super.remove(key);
                FakeWebDavServer.this.store.remove(key);
                return prev;
            }

            @Override
            public void clear() {
                super.clear();
                FakeWebDavServer.this.store.clear();
            }
        };
    }

    public Map<String, FileEntry> entries() {
        return store;
    }

    public Mode mode() {
        return mode;
    }

    public void setMode(Mode mode) {
        this.mode = mode == null ? Mode.STRICT_412 : mode;
    }

    public void setRefuseProbeDelete(boolean refuse) {
        this.refuseProbeDelete = refuse;
    }

    public void setRefuseAllDeletes(boolean refuse) {
        this.refuseAllDeletes = refuse;
    }

    public WebDavEndpoint config(String remoteDirectory) {
        return new WebDavEndpoint("http://127.0.0.1:" + port + "/", user, password, remoteDirectory);
    }

    public void clear() {
        store.clear();
        refuseProbeDelete = false;
        refuseAllDeletes = false;
        mode = Mode.STRICT_412;
    }

    /** 预置文件（计算 ETag）。 */
    public void putFile(String path, byte[] data) {
        String p = path.startsWith("/") ? path : "/" + path;
        long mt = clockMs.incrementAndGet();
        store.put(p, new FileEntry(data, strongEtag(data), mt));
    }

    @Override
    public void close() {
        server.stop(0);
    }

    private void handle(HttpExchange ex) throws IOException {
        Headers headers = ex.getRequestHeaders();
        String auth = headers.getFirst("Authorization");
        if (auth == null || !auth.equals(expectedAuth)) {
            byte[] body = "unauthorized".getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(401, body.length);
            try (OutputStream os = ex.getResponseBody()) {
                os.write(body);
            }
            return;
        }
        String method = ex.getRequestMethod();
        String path = ex.getRequestURI().getPath();
        if (path.startsWith(readOnlyPrefix)) {
            if ("PROPFIND".equalsIgnoreCase(method) || "HEAD".equalsIgnoreCase(method)) {
                if ("HEAD".equalsIgnoreCase(method)) {
                    handleHead(ex, path);
                    return;
                }
                handlePropfind(ex, path);
                return;
            }
            byte[] body = "forbidden".getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(403, body.length);
            try (OutputStream os = ex.getResponseBody()) {
                os.write(body);
            }
            return;
        }
        if (path.startsWith(noMethodPrefix)) {
            if ("PROPFIND".equalsIgnoreCase(method)) {
                handlePropfind(ex, path);
                return;
            }
            if ("PUT".equalsIgnoreCase(method)) {
                ex.sendResponseHeaders(405, -1);
                ex.close();
                return;
            }
        }
        if ("PROPFIND".equalsIgnoreCase(method)) {
            handlePropfind(ex, path);
            return;
        }
        if ("HEAD".equalsIgnoreCase(method)) {
            handleHead(ex, path);
            return;
        }
        if ("MKCOL".equalsIgnoreCase(method)) {
            store.putIfAbsent(normalizeDir(path), new FileEntry(new byte[0], "\"dir\"", clockMs.incrementAndGet()));
            ex.sendResponseHeaders(201, -1);
            ex.close();
            return;
        }
        if ("PUT".equalsIgnoreCase(method)) {
            handlePut(ex, path);
            return;
        }
        if ("GET".equalsIgnoreCase(method)) {
            FileEntry ent = store.get(path);
            if (ent == null) {
                ex.sendResponseHeaders(404, -1);
                ex.close();
                return;
            }
            Headers rh = ex.getResponseHeaders();
            applyMetaHeaders(rh, ent);
            ex.sendResponseHeaders(200, ent.data().length);
            try (OutputStream os = ex.getResponseBody()) {
                os.write(ent.data());
            }
            return;
        }
        if ("DELETE".equalsIgnoreCase(method)) {
            if (refuseAllDeletes) {
                byte[] body = "delete-forbidden".getBytes(StandardCharsets.UTF_8);
                ex.sendResponseHeaders(403, body.length);
                try (OutputStream os = ex.getResponseBody()) {
                    os.write(body);
                }
                return;
            }
            // 无删权：探测文件（Client 的 .reqdesk-probe-* / Store 的 .reqdesk-cap-*）一律 403
            if (refuseProbeDelete && (path.contains(".reqdesk-probe-")
                    || path.contains(".reqdesk-cap-")
                    || path.contains(".jkit-cap-"))) {
                byte[] body = "delete-forbidden".getBytes(StandardCharsets.UTF_8);
                ex.sendResponseHeaders(403, body.length);
                try (OutputStream os = ex.getResponseBody()) {
                    os.write(body);
                }
                return;
            }
            store.remove(path);
            ex.sendResponseHeaders(204, -1);
            ex.close();
            return;
        }
        ex.sendResponseHeaders(405, -1);
        ex.close();
    }

    private void handlePut(HttpExchange ex, String path) throws IOException {
        byte[] data = ex.getRequestBody().readAllBytes();
        Headers req = ex.getRequestHeaders();
        String ifNone = firstHeader(req, "If-None-Match");
        String ifMatch = firstHeader(req, "If-Match");
        String replace = firstHeader(req, "replace");
        boolean replaceForced = "1".equals(replace);

        FileEntry existing = store.get(path);
        Mode m = mode;

        if (m == Mode.STRICT_412) {
            if (ifNone != null && "*".equals(ifNone.trim()) && existing != null) {
                sendEmpty(ex, 412);
                return;
            }
            if (ifMatch != null && existing != null) {
                if (!etagMatches(ifMatch, existing.etag())) {
                    sendEmpty(ex, 412);
                    return;
                }
            }
            if (ifMatch != null && existing == null) {
                sendEmpty(ex, 412);
                return;
            }
            FileEntry neu = new FileEntry(data, strongEtag(data), clockMs.incrementAndGet());
            store.put(path, neu);
            Headers rh = ex.getResponseHeaders();
            rh.add("ETag", neu.etag());
            applyLastModified(rh, neu.mtimeMs());
            ex.sendResponseHeaders(existing == null ? 201 : 204, -1);
            ex.close();
            return;
        }

        if (m == Mode.IGNORE_HEADERS_OVERWRITE) {
            // 忽略 If-*，直接覆盖
            FileEntry neu = new FileEntry(data, strongEtag(data), clockMs.incrementAndGet());
            store.put(path, neu);
            Headers rh = ex.getResponseHeaders();
            rh.add("ETag", neu.etag());
            applyLastModified(rh, neu.mtimeMs());
            ex.sendResponseHeaders(existing == null ? 201 : 200, -1);
            ex.close();
            return;
        }

        // RENAME_ON_EXISTS（copyparty-like）
        if (replaceForced) {
            FileEntry neu = new FileEntry(data, "", clockMs.incrementAndGet());
            store.put(path, neu);
            Headers rh = ex.getResponseHeaders();
            applyLastModified(rh, neu.mtimeMs());
            ex.sendResponseHeaders(existing == null ? 201 : 200, -1);
            ex.close();
            return;
        }
        if (existing != null) {
            String renamed = renamePath(path);
            FileEntry neu = new FileEntry(data, "", clockMs.incrementAndGet());
            store.put(renamed, neu);
            Headers rh = ex.getResponseHeaders();
            rh.add("Location", renamed);
            applyLastModified(rh, neu.mtimeMs());
            ex.sendResponseHeaders(201, -1);
            ex.close();
            return;
        }
        FileEntry neu = new FileEntry(data, "", clockMs.incrementAndGet());
        store.put(path, neu);
        Headers rh = ex.getResponseHeaders();
        applyLastModified(rh, neu.mtimeMs());
        ex.sendResponseHeaders(201, -1);
        ex.close();
    }

    private void handleHead(HttpExchange ex, String path) throws IOException {
        FileEntry ent = store.get(path);
        if (ent == null) {
            ex.sendResponseHeaders(404, -1);
            ex.close();
            return;
        }
        Headers rh = ex.getResponseHeaders();
        applyMetaHeaders(rh, ent);
        rh.add("Content-Length", String.valueOf(ent.data().length));
        ex.sendResponseHeaders(200, -1);
        ex.close();
    }

    private void applyMetaHeaders(Headers rh, FileEntry ent) {
        if (mode != Mode.RENAME_ON_EXISTS && ent.etag() != null && !ent.etag().trim().isEmpty()) {
            rh.add("ETag", ent.etag());
        }
        applyLastModified(rh, ent.mtimeMs());
    }

    private static void applyLastModified(Headers rh, long mtimeMs) {
        String lm = DateTimeFormatter.RFC_1123_DATE_TIME
                .format(Instant.ofEpochMilli(mtimeMs).atOffset(ZoneOffset.UTC));
        rh.add("Last-Modified", lm);
    }

    private void handlePropfind(HttpExchange ex, String path) throws IOException {
        String depth = ex.getRequestHeaders().getFirst("Depth");
        StringBuilder xml = new StringBuilder();
        xml.append("<?xml version=\"1.0\" encoding=\"utf-8\"?>");
        xml.append("<D:multistatus xmlns:D=\"DAV:\">");
        xml.append("<D:response><D:href>").append(path.endsWith("/") ? path : path + "/")
                .append("</D:href><D:propstat><D:prop><D:resourcetype><D:collection/></D:resourcetype>")
                .append("</D:prop><D:status>HTTP/1.1 200 OK</D:status></D:propstat></D:response>");
        if ("1".equals(depth) || depth == null) {
            String prefix = path.endsWith("/") ? path : path + "/";
            for (Map.Entry<String, FileEntry> e : store.entrySet()) {
                if (!e.getKey().startsWith(prefix) || e.getKey().equals(prefix)) {
                    continue;
                }
                if (e.getValue().data().length == 0 && e.getKey().endsWith("/")) {
                    continue;
                }
                FileEntry ent = e.getValue();
                String lm = DateTimeFormatter.RFC_1123_DATE_TIME
                        .format(Instant.ofEpochMilli(ent.mtimeMs()).atOffset(ZoneOffset.UTC));
                xml.append("<D:response><D:href>").append(e.getKey()).append("</D:href>")
                        .append("<D:propstat><D:prop>")
                        .append("<D:getcontentlength>").append(ent.data().length)
                        .append("</D:getcontentlength>")
                        .append("<D:getlastmodified>").append(lm).append("</D:getlastmodified>");
                if (mode != Mode.RENAME_ON_EXISTS && ent.etag() != null && !ent.etag().trim().isEmpty()) {
                    xml.append("<D:getetag>").append(xmlEscape(ent.etag())).append("</D:getetag>");
                }
                xml.append("<D:resourcetype/>")
                        .append("</D:prop><D:status>HTTP/1.1 200 OK</D:status></D:propstat></D:response>");
            }
        }
        xml.append("</D:multistatus>");
        byte[] body = xml.toString().getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().add("Content-Type", "application/xml");
        ex.sendResponseHeaders(207, body.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(body);
        }
    }

    /**
     * copyparty 风格：{@code <orig>-<ts>-<rand><origExt>}，
     * 例如 {@code x.zip-1730250000-a1b2c3.zip}（保留原名含扩展名再追加；rand 为全十六进制）。
     */
    static String renamePath(String path) {
        int slash = path.lastIndexOf('/');
        String dir = slash >= 0 ? path.substring(0, slash + 1) : "/";
        String name = slash >= 0 ? path.substring(slash + 1) : path;
        int dot = name.lastIndexOf('.');
        String ext = dot > 0 ? name.substring(dot) : "";
        long ts = System.currentTimeMillis() / 1000L;
        // UUID 去横线后本身即为 [0-9a-f]；再过滤确保全 hex
        String hex = UUID.randomUUID().toString().replace("-", "");
        StringBuilder sb = new StringBuilder(6);
        for (int i = 0; i < hex.length() && sb.length() < 6; i++) {
            char c = hex.charAt(i);
            if ((c >= '0' && c <= '9') || (c >= 'a' && c <= 'f')) {
                sb.append(c);
            }
        }
        while (sb.length() < 6) {
            sb.append('0');
        }
        return dir + name + "-" + ts + "-" + sb + ext;
    }

    private static String strongEtag(byte[] data) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            String hex = toHex(md.digest(data)).substring(0, 16);
            return "\"" + hex + "\"";
        } catch (Exception e) {
            return "\"" + Integer.toHexString(data.length) + "\"";
        }
    }

    private static boolean etagMatches(String ifMatch, String etag) {
        if (ifMatch == null || etag == null) {
            return false;
        }
        String want = ifMatch.trim();
        if ("*".equals(want)) {
            return true;
        }
        // 允许多值取第一个简单匹配
        for (String part : want.split(",")) {
            String p = part.trim();
            if (p.equals(etag) || unwrapEtag(p).equals(unwrapEtag(etag))) {
                return true;
            }
        }
        return false;
    }

    private static String unwrapEtag(String e) {
        String s = e.trim();
        if (s.regionMatches(true, 0, "W/", 0, 2)) {
            s = s.substring(2).trim();
        }
        if (s.length() >= 2 && s.startsWith("\"") && s.endsWith("\"")) {
            return s.substring(1, s.length() - 1);
        }
        return s;
    }

    private static String firstHeader(Headers h, String name) {
        String v = h.getFirst(name);
        if (v != null) {
            return v;
        }
        // 大小写不敏感兜底
        for (String key : h.keySet()) {
            if (key != null && key.equalsIgnoreCase(name)) {
                return h.getFirst(key);
            }
        }
        return null;
    }

    private static void sendEmpty(HttpExchange ex, int code) throws IOException {
        ex.sendResponseHeaders(code, -1);
        ex.close();
    }

    private static String normalizeDir(String path) {
        return path.endsWith("/") ? path : path + "/";
    }

    private static String xmlEscape(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\"", "&quot;");
    }

    private static String toHex(byte[] digest) {
        StringBuilder sb = new StringBuilder(digest.length * 2);
        for (int i = 0; i < digest.length; i++) {
            sb.append(String.format(java.util.Locale.ROOT, "%02x", digest[i] & 0xff));
        }
        return sb.toString();
    }
}
