package com.alianga.jkit.storage.webdav.versioned;

import com.alianga.jkit.storage.internal.HttpCore;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * WebDAV 实现的 {@link RemoteStore}，基于 jkit-storage {@link HttpCore}（HttpURLConnection）。
 *
 * <p>能力探测通用分类：supports-412 / overwrites-silently / renames-on-exists / no-etag；
 * 另探测 {@code replace: 1}。弱 ETag（{@code W/}）视为不可用。</p>
 *
 * <p>{@link #putIfAbsent} / {@link #putIfMatch}：已探测为
 * {@link StoreCapabilities.OverwritePolicy#OVERWRITES_SILENTLY} / {@code UNKNOWN} 且无条件写时
 * 返回 {@link PutResult#unsupported(String)}，禁止静默普通 PUT。
 * {@link StoreCapabilities.OverwritePolicy#RENAMES_ON_EXISTS} 仍发 {@code If-None-Match:*}，
 * 由 {@code Location ≠ 请求路径} 判定冲突（上层删除改名残留）。</p>
 *
 * @author 郑明亮
 */
public final class WebDavRemoteStore implements RemoteStore {
    private static final Pattern HREF = Pattern.compile(
            "<(?:D:)?href[^>]*>([^<]+)</(?:D:)?href>", Pattern.CASE_INSENSITIVE);
    private static final Pattern GETLENGTH = Pattern.compile(
            "<(?:D:)?getcontentlength[^>]*>([^<]*)</(?:D:)?getcontentlength>",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern GETLASTMOD = Pattern.compile(
            "<(?:D:)?getlastmodified[^>]*>([^<]*)</(?:D:)?getlastmodified>",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern GETETAG = Pattern.compile(
            "<(?:D:)?getetag[^>]*>([^<]*)</(?:D:)?getetag>",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern RESPONSE = Pattern.compile(
            "<(?:D:)?response\\b[^>]*>([\\s\\S]*?)</(?:D:)?response>",
            Pattern.CASE_INSENSITIVE);

    private static final String PROPFIND_BODY =
            "<?xml version=\"1.0\" encoding=\"utf-8\" ?>"
                    + "<D:propfind xmlns:D=\"DAV:\">"
                    + "<D:prop>"
                    + "<D:displayname/>"
                    + "<D:getcontentlength/>"
                    + "<D:getlastmodified/>"
                    + "<D:getetag/>"
                    + "<D:resourcetype/>"
                    + "</D:prop>"
                    + "</D:propfind>";

    private final WebDavEndpoint endpoint;
    private final String authHeader;
    private volatile StoreCapabilities cachedCaps;

    public WebDavRemoteStore(WebDavEndpoint endpoint) {
        this.endpoint = Objects.requireNonNull(endpoint, "endpoint");
        String token = endpoint.username() + ":" + endpoint.password();
        this.authHeader = "Basic " + Base64.getEncoder()
                .encodeToString(token.getBytes(StandardCharsets.UTF_8));
    }

    public WebDavEndpoint endpoint() {
        return endpoint;
    }

    /** @return 最近一次探测缓存；可能为 null */
    public StoreCapabilities cachedCapabilities() {
        return cachedCaps;
    }

    @Override
    public List<RemoteObject> list() throws IOException {
        URI dir = remoteDirUri();
        HttpCore.Response response = null;
        try {
            response = send(propfind(dir, "1"));
            int code = response.status();
            if (code == 404) {
                return new ArrayList<RemoteObject>();
            }
            if (code != 207 && code != 200) {
                String body = safeBody(response);
                throw new WebDavHttpException(code, "PROPFIND failed: HTTP " + code + " " + body, body);
            }
            String body = response.bodyText();
            response = null;
            return parsePropfind(body, dir);
        } finally {
            if (response != null) {
                response.close();
            }
        }
    }

    @Override
    public Optional<RemoteObject> stat(String name) throws IOException {
        String safe = sanitize(name);
        URI target = appendPath(remoteDirUri(), safe);
        HttpCore.Response hr = null;
        try {
            hr = send(base("HEAD", target));
            if (hr.status() == 200) {
                String etag = strongEtagOrEmpty(hr.header("ETag"));
                Instant lm = Instant.EPOCH;
                String lmRaw = hr.header("Last-Modified");
                if (lmRaw != null) {
                    lm = parseHttpDate(lmRaw);
                }
                long size = -1L;
                String cl = hr.header("Content-Length");
                if (cl != null) {
                    try {
                        size = Long.parseLong(cl.trim());
                    } catch (NumberFormatException ignored) {
                        size = -1L;
                    }
                }
                return Optional.of(new RemoteObject(safe, target.getPath(), size, lm, etag));
            }
            if (hr.status() == 404) {
                return Optional.empty();
            }
        } catch (IOException e) {
            // fall through to list
        } finally {
            if (hr != null) {
                hr.close();
            }
        }
        for (RemoteObject o : list()) {
            if (safe.equals(o.name())) {
                return Optional.of(o);
            }
        }
        return Optional.empty();
    }

    @Override
    public void get(String name, Path localFile) throws IOException {
        String safe = sanitize(name);
        URI target = appendPath(remoteDirUri(), safe);
        HttpCore.Response response = null;
        try {
            response = send(base("GET", target));
            int code = response.status();
            if (code != 200) {
                String body = safeBody(response);
                throw new WebDavHttpException(code, "GET failed: HTTP " + code + " " + body, body);
            }
            Path parent = localFile.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            byte[] data = readAll(response.body());
            response.close();
            response = null;
            Files.write(localFile, data);
        } finally {
            if (response != null) {
                response.close();
            }
        }
    }

    @Override
    public PutResult putIfAbsent(String name, Path localFile) throws IOException {
        return putInternal(name, localFile, PutMode.IF_ABSENT, null);
    }

    @Override
    public PutResult putIfMatch(String name, Path localFile, String expectedEtag) throws IOException {
        return putInternal(name, localFile, PutMode.IF_MATCH, expectedEtag);
    }

    @Override
    public PutResult putReplace(String name, Path localFile) throws IOException {
        return putInternal(name, localFile, PutMode.REPLACE, null);
    }

    @Override
    public void delete(String name) throws IOException {
        URI target = appendPath(remoteDirUri(), sanitize(name));
        HttpCore.Response response = null;
        try {
            response = send(base("DELETE", target));
            int code = response.status();
            if (code == 200 || code == 204 || code == 404) {
                return;
            }
            String body = safeBody(response);
            throw new WebDavHttpException(code, "DELETE failed: HTTP " + code + " " + body, body);
        } finally {
            if (response != null) {
                response.close();
            }
        }
    }

    @Override
    public StoreCapabilities probeCapabilities() throws IOException {
        ensureRemoteDir();
        String probeA = ".jkit-cap-" + UUID.randomUUID();
        Path tmp = Files.createTempFile("jkit-cap-", ".bin");
        String deleteWarning = null;
        try {
            Files.write(tmp, "probe-a".getBytes(StandardCharsets.UTF_8));
            PutResult first = putRaw(probeA, tmp, null, null, false);
            if (!first.success()) {
                cachedCaps = StoreCapabilities.readOnly();
                return cachedCaps;
            }
            String actualA = first.renamed() ? first.effectiveName() : probeA;

            try {
                delete(actualA);
                actualA = null;
            } catch (Exception e) {
                deleteWarning = e.getMessage();
                StoreCapabilities caps = new StoreCapabilities(
                        true, false, StoreCapabilities.OverwritePolicy.UNKNOWN,
                        false, false, deleteWarning);
                cachedCaps = caps;
                return caps;
            }

            Files.write(tmp, "probe-a".getBytes(StandardCharsets.UTF_8));
            PutResult again = putRaw(probeA, tmp, null, null, false);
            if (!again.success()) {
                cachedCaps = StoreCapabilities.readOnly();
                return cachedCaps;
            }
            actualA = again.renamed() ? again.effectiveName() : probeA;

            Files.write(tmp, "probe-b".getBytes(StandardCharsets.UTF_8));
            PutResult cond = putRaw(actualA, tmp, "*", null, false);
            boolean supports412 = cond.preconditionFailed();

            StoreCapabilities.OverwritePolicy policy;
            boolean strongEtag = false;
            boolean replaceHeader = false;

            if (supports412) {
                policy = StoreCapabilities.OverwritePolicy.REJECTS_OR_412;
            } else if (cond.renamed()) {
                policy = StoreCapabilities.OverwritePolicy.RENAMES_ON_EXISTS;
                try {
                    delete(cond.effectiveName());
                } catch (Exception ignored) {
                    // ignore
                }
            } else if (cond.success()) {
                policy = StoreCapabilities.OverwritePolicy.OVERWRITES_SILENTLY;
                Optional<RemoteObject> st = stat(actualA);
                if (st.isPresent() && st.get().hasStrongEtag()) {
                    strongEtag = true;
                }
            } else {
                policy = StoreCapabilities.OverwritePolicy.UNKNOWN;
            }

            Optional<RemoteObject> meta = stat(actualA);
            if (meta.isPresent() && meta.get().hasStrongEtag()) {
                strongEtag = true;
            }

            if (policy == StoreCapabilities.OverwritePolicy.RENAMES_ON_EXISTS
                    || policy == StoreCapabilities.OverwritePolicy.OVERWRITES_SILENTLY
                    || !supports412) {
                Files.write(tmp, "probe-replace".getBytes(StandardCharsets.UTF_8));
                if (!stat(actualA).isPresent()) {
                    putRaw(actualA, tmp, null, null, false);
                }
                PutResult rep = putRaw(actualA, tmp, null, null, true);
                if (rep.success() && !rep.renamed()) {
                    Path got = Files.createTempFile("jkit-cap-g-", ".bin");
                    try {
                        get(actualA, got);
                        String body = new String(Files.readAllBytes(got), StandardCharsets.UTF_8);
                        replaceHeader = body.contains("probe-replace");
                    } finally {
                        Files.deleteIfExists(got);
                    }
                }
            }

            if (supports412 && !strongEtag) {
                Files.write(tmp, "probe-etag".getBytes(StandardCharsets.UTF_8));
                try {
                    delete(actualA);
                } catch (Exception ignored) {
                    // ignore
                }
                PutResult et = putRaw(actualA, tmp, "*", null, false);
                if (et.etag() != null && !et.etag().trim().isEmpty()) {
                    strongEtag = true;
                }
                actualA = et.renamed() ? et.effectiveName() : actualA;
            }

            try {
                delete(actualA);
            } catch (Exception e) {
                deleteWarning = e.getMessage();
            }

            StoreCapabilities caps = new StoreCapabilities(
                    true, supports412, policy, strongEtag, replaceHeader, deleteWarning);
            cachedCaps = caps;
            return caps;
        } finally {
            Files.deleteIfExists(tmp);
        }
    }

    private enum PutMode { IF_ABSENT, IF_MATCH, REPLACE }

    private PutResult putInternal(String name, Path localFile, PutMode mode, String etag)
            throws IOException {
        ensureRemoteDir();
        String safe = sanitize(name);
        if (mode == PutMode.IF_ABSENT) {
            if (cachedCaps != null && !cachedCaps.conditionalPut()) {
                StoreCapabilities.OverwritePolicy policy = cachedCaps.overwritePolicy();
                // 改名服务器：恢复原子路径——带 If-None-Match:* 的 PUT；
                // Location ≠ 请求名 → 上层按冲突处理并尽力 DELETE 残留。
                if (policy == StoreCapabilities.OverwritePolicy.RENAMES_ON_EXISTS) {
                    return putRaw(safe, localFile, "*", null, false);
                }
                // 静默覆盖 / 未知：STAT 后显式 unsupported，禁止静默普通 PUT。
                // 仍有 check-then-write 窗口，由版本文件名中的 host+hash 降低碰撞。
                if (stat(safe).isPresent()) {
                    return new PutResult(412, "", "", safe, safe, false);
                }
                return PutResult.unsupported(safe);
            }
            // 未探测但已知会静默覆盖时，STAT 守卫避免裸 PUT 覆盖
            if (mustStatBeforeIfAbsent(cachedCaps) && stat(safe).isPresent()) {
                return new PutResult(412, "", "", safe, safe, false);
            }
            return putRaw(safe, localFile, "*", null, false);
        }
        if (mode == PutMode.IF_MATCH) {
            if (cachedCaps != null && !cachedCaps.conditionalPut()) {
                return PutResult.unsupported(safe);
            }
            return putRaw(safe, localFile, null, etag, false);
        }
        return putRaw(safe, localFile, null, null, true);
    }

    /**
     * 是否在 {@code putIfAbsent} 前用 STAT 守卫：静默覆盖，或已探测到不支持条件写且非改名策略。
     */
    static boolean mustStatBeforeIfAbsent(StoreCapabilities caps) {
        if (caps == null) {
            return false;
        }
        if (caps.overwritePolicy() == StoreCapabilities.OverwritePolicy.OVERWRITES_SILENTLY) {
            return true;
        }
        if (caps.overwritePolicy() == StoreCapabilities.OverwritePolicy.RENAMES_ON_EXISTS) {
            return false;
        }
        return !caps.conditionalPut();
    }

    private PutResult putRaw(String safeName, Path localFile,
                             String ifNoneMatch, String ifMatch, boolean replace)
            throws IOException {
        URI target = appendPath(remoteDirUri(), safeName);
        byte[] body = Files.readAllBytes(localFile);
        HttpCore.Request req = base("PUT", target)
                .header("Content-Type", "application/octet-stream")
                .body(body);
        if (ifNoneMatch != null && !ifNoneMatch.trim().isEmpty()) {
            req.header("If-None-Match", ifNoneMatch);
        }
        if (ifMatch != null && !ifMatch.trim().isEmpty()) {
            req.header("If-Match", ifMatch);
        }
        if (replace) {
            req.header("Overwrite", "T");
            req.header("replace", "1");
        }
        HttpCore.Response response = null;
        try {
            response = send(req);
            int code = response.status();
            String respEtag = strongEtagOrEmpty(response.header("ETag"));
            String location = response.header("Location");
            if (location == null) {
                location = "";
            }
            String effective = safeName;
            boolean renamed = false;
            if (!location.trim().isEmpty()) {
                String locName = fileNameFromLocation(location, target);
                if (locName != null && !locName.trim().isEmpty() && !locName.equals(safeName)) {
                    effective = locName;
                    renamed = true;
                }
            }
            return new PutResult(code, respEtag, location, safeName, effective, renamed);
        } finally {
            if (response != null) {
                response.close();
            }
        }
    }

    static String fileNameFromLocation(String location, URI requestTarget) {
        try {
            URI loc = URI.create(location);
            if (!loc.isAbsolute()) {
                loc = requestTarget.resolve(location);
            }
            String p = loc.getPath();
            if (p == null || p.trim().isEmpty()) {
                return "";
            }
            while (p.endsWith("/")) {
                p = p.substring(0, p.length() - 1);
            }
            int slash = p.lastIndexOf('/');
            return slash >= 0 ? p.substring(slash + 1) : p;
        } catch (Exception e) {
            int slash = location.lastIndexOf('/');
            return slash >= 0 ? location.substring(slash + 1) : location;
        }
    }

    /**
     * 弱 ETag 返回空；强 ETag 原样（含引号）剥离为裸值存储于 RemoteObject。
     */
    static String strongEtagOrEmpty(String raw) {
        if (raw == null || raw.trim().isEmpty()) {
            return "";
        }
        String s = raw.trim();
        if (s.length() >= 2 && s.regionMatches(true, 0, "W/", 0, 2)) {
            return "";
        }
        if (s.length() >= 2 && s.startsWith("\"") && s.endsWith("\"")) {
            return s.substring(1, s.length() - 1);
        }
        return s;
    }

    private void ensureRemoteDir() throws IOException {
        URI baseUri = rootUri();
        String rel = trimSlashes(endpoint.remoteDirectory());
        if (rel.isEmpty()) {
            return;
        }
        String[] parts = rel.split("/");
        StringBuilder path = new StringBuilder(trimRightSlash(baseUri.getPath()));
        for (int i = 0; i < parts.length; i++) {
            String part = parts[i];
            if (part == null || part.trim().isEmpty()) {
                continue;
            }
            path.append('/').append(encodePathSegment(part));
            URI dir = URI.create(baseUri.getScheme() + "://" + baseUri.getRawAuthority() + path + "/");
            mkcol(dir);
        }
    }

    private void mkcol(URI dir) throws IOException {
        HttpCore.Response response = null;
        try {
            response = send(base("MKCOL", dir));
            int code = response.status();
            if (code == 201 || code == 405 || code == 409 || code == 301 || code == 200) {
                return;
            }
            if (code == 403) {
                throw new WebDavHttpException(403, "MKCOL forbidden: " + dir, null);
            }
            response.close();
            response = null;
            HttpCore.Response check = null;
            try {
                check = send(propfind(dir, "0"));
                if (check.status() == 207 || check.status() == 200) {
                    return;
                }
                throw new WebDavHttpException(code, "MKCOL failed: HTTP " + code, null);
            } finally {
                if (check != null) {
                    check.close();
                }
            }
        } finally {
            if (response != null) {
                response.close();
            }
        }
    }

    private HttpCore.Request base(String method, URI uri) {
        HttpCore.Request req = new HttpCore.Request(method, uri.toString());
        req.header("Authorization", authHeader);
        req.header("Connection", "close");
        return req;
    }

    private HttpCore.Request propfind(URI uri, String depth) {
        return base("PROPFIND", uri)
                .header("Depth", depth)
                .header("Content-Type", "application/xml; charset=utf-8")
                .body(PROPFIND_BODY.getBytes(StandardCharsets.UTF_8));
    }

    private HttpCore.Response send(HttpCore.Request request) throws IOException {
        return HttpCore.execute(request);
    }

    private URI rootUri() throws IOException {
        String url = endpoint.serverUrl();
        if (url == null || url.trim().isEmpty()) {
            throw new IOException("请填写 WebDAV 服务器地址");
        }
        try {
            URI uri = URI.create(url.endsWith("/") ? url : url + "/");
            if (uri.getScheme() == null || uri.getHost() == null) {
                throw new IOException("服务器地址无效: " + url);
            }
            return uri;
        } catch (IllegalArgumentException e) {
            throw new IOException("服务器地址无效: " + url, e);
        }
    }

    private URI remoteDirUri() throws IOException {
        URI root = rootUri();
        String rel = trimSlashes(endpoint.remoteDirectory());
        if (rel.isEmpty()) {
            return root;
        }
        return appendPath(root, rel.split("/"));
    }

    private List<RemoteObject> parsePropfind(String xml, URI dir) {
        List<RemoteObject> out = new ArrayList<RemoteObject>();
        if (xml == null || xml.trim().isEmpty()) {
            return out;
        }
        String dirPath = trimRightSlash(dir.getPath());
        Matcher resp = RESPONSE.matcher(xml);
        while (resp.find()) {
            String block = resp.group(1);
            Matcher hrefM = HREF.matcher(block);
            if (!hrefM.find()) {
                continue;
            }
            String href = decodeXml(hrefM.group(1).trim());
            String lower = block.toLowerCase(Locale.ROOT);
            if (lower.contains("<d:collection") || lower.contains("<collection")) {
                continue;
            }
            String path;
            try {
                path = dir.resolve(href).getPath();
            } catch (Exception e) {
                path = href;
            }
            String normalized = trimRightSlash(path);
            if (normalized.equals(dirPath) || normalized.equals(dirPath + "/")) {
                continue;
            }
            String name = normalized.substring(normalized.lastIndexOf('/') + 1);
            if (name.trim().isEmpty()) {
                continue;
            }
            long size = -1L;
            Matcher len = GETLENGTH.matcher(block);
            if (len.find()) {
                try {
                    size = Long.parseLong(len.group(1).trim());
                } catch (NumberFormatException ignored) {
                    size = -1L;
                }
            }
            Instant modified = Instant.EPOCH;
            Matcher mod = GETLASTMOD.matcher(block);
            if (mod.find()) {
                modified = parseHttpDate(mod.group(1).trim());
            }
            String etag = "";
            Matcher em = GETETAG.matcher(block);
            if (em.find()) {
                etag = strongEtagOrEmpty(decodeXml(em.group(1).trim()));
            }
            out.add(new RemoteObject(name, href, size, modified, etag));
        }
        return out;
    }

    private static URI appendPath(URI base, String... segments) {
        StringBuilder path = new StringBuilder(trimRightSlash(base.getPath()));
        for (String seg : segments) {
            if (seg == null || seg.trim().isEmpty()) {
                continue;
            }
            String[] parts = seg.split("/");
            for (int i = 0; i < parts.length; i++) {
                String part = parts[i];
                if (part == null || part.trim().isEmpty()) {
                    continue;
                }
                path.append('/').append(encodePathSegment(part));
            }
        }
        return URI.create(base.getScheme() + "://" + base.getRawAuthority() + path);
    }

    private static String encodePathSegment(String segment) {
        try {
            return URLEncoder.encode(segment, "UTF-8").replace("+", "%20");
        } catch (java.io.UnsupportedEncodingException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String trimSlashes(String s) {
        String t = s == null ? "" : s.trim();
        while (t.startsWith("/")) {
            t = t.substring(1);
        }
        while (t.endsWith("/")) {
            t = t.substring(0, t.length() - 1);
        }
        return t;
    }

    private static String trimRightSlash(String path) {
        if (path == null || path.isEmpty() || "/".equals(path)) {
            return "";
        }
        String p = path;
        while (p.length() > 1 && p.endsWith("/")) {
            p = p.substring(0, p.length() - 1);
        }
        return p;
    }

    static String sanitize(String name) {
        String n = name == null ? "" : name.trim();
        n = n.replace('\\', '/');
        int slash = n.lastIndexOf('/');
        if (slash >= 0) {
            n = n.substring(slash + 1);
        }
        n = n.replaceAll("[\\\\:*?\"<>|\\u0000-\\u001f]+", "_");
        if (n.trim().isEmpty()) {
            n = "object.bin";
        }
        return n;
    }

    private static Instant parseHttpDate(String text) {
        try {
            return DateTimeFormatter.RFC_1123_DATE_TIME.parse(text, Instant::from);
        } catch (Exception e) {
            return Instant.EPOCH;
        }
    }

    private static String decodeXml(String text) {
        return text.replace("&amp;", "&")
                .replace("&lt;", "<")
                .replace("&gt;", ">")
                .replace("&quot;", "\"")
                .replace("&apos;", "'");
    }

    private static byte[] readAll(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) >= 0) {
            out.write(buf, 0, n);
        }
        return out.toByteArray();
    }

    private static String safeBody(HttpCore.Response response) {
        try {
            return response.bodyText();
        } catch (Exception e) {
            return "";
        }
    }
}
