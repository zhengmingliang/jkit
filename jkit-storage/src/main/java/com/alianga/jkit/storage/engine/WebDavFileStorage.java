package com.alianga.jkit.storage.engine;

import com.alianga.jkit.storage.FileStorage;
import com.alianga.jkit.storage.StorageConfig;
import com.alianga.jkit.storage.StorageEntry;
import com.alianga.jkit.storage.StorageErrorCode;
import com.alianga.jkit.storage.StorageException;
import com.alianga.jkit.storage.StoragePaths;
import com.alianga.jkit.storage.internal.HttpCore;
import com.alianga.jkit.storage.internal.MimeTypes;
import com.alianga.jkit.storage.internal.Urls;
import com.alianga.jkit.storage.internal.Xmls;
import org.w3c.dom.Element;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TimeZone;

/**
 * WebDAV 引擎：基于 JDK HttpURLConnection 的零依赖实现，覆盖 RFC 4918 常用子集——
 * PROPFIND（列表 / 元数据）、PUT / GET、MKCOL、MOVE / COPY（服务端目录整体搬移）、DELETE。
 * 认证为 HTTP Basic；所有失败统一抛 {@link StorageException}。
 *
 * @author 郑明亮
 */
public class WebDavFileStorage implements FileStorage {
    private static final String PROPFIND_BODY =
            "<?xml version=\"1.0\" encoding=\"utf-8\"?>"
                    + "<D:propfind xmlns:D=\"DAV:\"><D:prop>"
                    + "<D:resourcetype/><D:getcontentlength/>"
                    + "<D:getlastmodified/><D:getetag/>"
                    + "</D:prop></D:propfind>";

    private final String endpoint;
    private final String basePath;
    private final String credentials;
    private final boolean anonymous;

    /**
     * 由配置创建（配置键：endpoint / user / password / basePath）。
     *
     * @param config 连接配置
     */
    public WebDavFileStorage(StorageConfig config) {
        String raw = config.require("endpoint");
        if (!raw.startsWith("http://") && !raw.startsWith("https://")) {
            raw = "https://" + raw;
        }
        this.endpoint = raw.endsWith("/") ? raw.substring(0, raw.length() - 1) : raw;
        this.basePath = StoragePaths.normalize(config.getOr("basePath", ""));
        String user = config.getOr("user", "");
        String password = config.get("password") == null ? "" : config.get("password");
        this.anonymous = user.isEmpty();
        this.credentials = Base64.getEncoder()
                .encodeToString((user + ":" + password).getBytes(StandardCharsets.UTF_8));
    }

    @Override
    public String platform() {
        return "webdav";
    }

    @Override
    public boolean exists(String path) {
        String normalized = StoragePaths.normalize(path);
        if (normalized.isEmpty()) {
            return true;
        }
        HttpCore.Response response = propfind(normalized, 0);
        response.close();
        return response.status() < 400;
    }

    @Override
    public StorageEntry stat(String path) {
        String normalized = StoragePaths.normalize(path);
        if (normalized.isEmpty()) {
            return StorageEntry.dir("", 0L);
        }
        HttpCore.Response response = propfind(normalized, 0);
        if (response.status() == 404) {
            response.close();
            throw StorageException.notFound(normalized);
        }
        ensureSuccess(response, normalized);
        String xml = Xmls.readAll(response.body());
        response.close();
        List<StorageEntry> entries = parseMultistatus(xml, normalized, true);
        if (entries.isEmpty()) {
            throw StorageException.notFound(normalized);
        }
        return entries.get(0);
    }

    @Override
    public List<StorageEntry> list(String dir) {
        String normalized = StoragePaths.normalize(dir);
        HttpCore.Response response = propfind(normalized, 1);
        if (response.status() == 404) {
            response.close();
            throw StorageException.notFound(normalized);
        }
        ensureSuccess(response, normalized);
        String xml = Xmls.readAll(response.body());
        response.close();
        return parseMultistatus(xml, normalized, false);
    }

    @Override
    public void mkdir(String dir) {
        String normalized = StoragePaths.normalize(dir);
        if (normalized.isEmpty() || exists(normalized)) {
            return;
        }
        HttpCore.Response response = request("MKCOL", normalized, null, null, null, -1L, null, null);
        if (response.status() == 405) {
            // 已存在：mkdir 幂等
            response.close();
            return;
        }
        ensureSuccess(response, normalized);
        response.close();
    }

    @Override
    public void upload(String path, InputStream in, long size) {
        String normalized = requireFile(path);
        HttpCore.Response response = request("PUT", normalized, null,
                MimeTypes.of(normalized), null, size, in, null);
        try {
            ensureSuccess(response, normalized);
        } finally {
            response.close();
        }
    }

    @Override
    public void download(String path, OutputStream out) {
        String normalized = requireFile(path);
        HttpCore.Response response = request("GET", normalized, null, null, null, -1L, null, null);
        if (response.status() == 404) {
            response.close();
            throw StorageException.notFound(normalized);
        }
        ensureSuccess(response, normalized);
        try {
            byte[] chunk = new byte[8192];
            int read;
            while ((read = response.body().read(chunk)) >= 0) {
                out.write(chunk, 0, read);
            }
        } catch (IOException e) {
            throw StorageException.io("下载失败: " + normalized + "（" + e.getMessage() + "）", normalized, e);
        } finally {
            response.close();
        }
    }

    @Override
    public void delete(String path) {
        String normalized = StoragePaths.normalize(path);
        if (normalized.isEmpty()) {
            throw StorageException.invalidConfig("拒绝删除存储根目录");
        }
        HttpCore.Response response = request("DELETE", normalized, null, null, null, -1L, null, null);
        if (response.status() == 404 || response.status() == 204 || response.status() == 200) {
            response.close();
            return;
        }
        ensureSuccess(response, normalized);
        response.close();
    }

    @Override
    public void copy(String src, String dst) {
        String source = requireFile(src);
        String target = requireFile(dst);
        HttpCore.Response response = request("COPY", source, null, null, null, -1L, null, target);
        try {
            ensureSuccess(response, source);
        } finally {
            response.close();
        }
    }

    @Override
    public void rename(String src, String dst) {
        String source = requireFile(src);
        String target = requireFile(dst);
        HttpCore.Response response = request("MOVE", source, null, null, null, -1L, null, target);
        try {
            ensureSuccess(response, source);
        } finally {
            response.close();
        }
    }

    // ------------------------------------------------------------------ 请求

    private String urlOf(String path) {
        String normalized = StoragePaths.normalize(path);
        String prefix = basePath.isEmpty() ? "" : "/" + Urls.encodePath(basePath);
        String encoded = normalized.isEmpty() ? "" : "/" + Urls.encodePath(normalized);
        return endpoint + prefix + encoded;
    }

    private HttpCore.Response request(String method, String path, Map<String, String> query,
                                      String contentType, byte[] body, long streamLength,
                                      InputStream stream, String destination) {
        StringBuilder url = new StringBuilder(urlOf(path));
        if (query != null && !query.isEmpty()) {
            url.append('?');
            boolean first = true;
            for (Map.Entry<String, String> entry : query.entrySet()) {
                if (!first) {
                    url.append('&');
                }
                first = false;
                url.append(Urls.encodeQuery(entry.getKey())).append('=')
                        .append(Urls.encodeQuery(entry.getValue()));
            }
        }
        HttpCore.Request request = new HttpCore.Request(method, url.toString());
        if (!anonymous) {
            request.header("Authorization", "Basic " + credentials);
        }
        if (contentType != null) {
            request.header("Content-Type", contentType);
        }
        if (destination != null) {
            request.header("Destination", urlOf(destination));
            request.header("Overwrite", "T");
        }
        if ("PROPFIND".equals(method)) {
            request.header("Depth", "1");
            request.header("Content-Type", "application/xml");
            request.body(PROPFIND_BODY.getBytes(StandardCharsets.UTF_8));
        } else if (body != null) {
            request.body(body);
        } else if (stream != null) {
            request.body(stream, streamLength);
        }
        return HttpCore.execute(request);
    }

    private HttpCore.Response propfind(String path, int depth) {
        HttpCore.Request request = new HttpCore.Request("PROPFIND", urlOf(path));
        if (!anonymous) {
            request.header("Authorization", "Basic " + credentials);
        }
        request.header("Depth", String.valueOf(depth));
        request.header("Content-Type", "application/xml");
        request.body(PROPFIND_BODY.getBytes(StandardCharsets.UTF_8));
        return HttpCore.execute(request);
    }

    private List<StorageEntry> parseMultistatus(String xml, String dir, boolean includeSelf) {
        Element root = Xmls.parse(xml);
        List<Element> responses = Xmls.deepAll(root, "response");
        List<StorageEntry> entries = new ArrayList<StorageEntry>();
        String prefix = StoragePaths.normalize(dir);
        for (Element response : responses) {
            String href = Xmls.text(response, "href");
            if (href == null || href.isEmpty()) {
                continue;
            }
            String decoded = decodeHref(href);
            String relative = toRelative(decoded, prefix);
            if (relative == null) {
                continue;
            }
            boolean collection = Xmls.first(response, "collection") != null
                    || relative.endsWith("/");
            if (relative.endsWith("/")) {
                relative = relative.substring(0, relative.length() - 1);
            }
            if (relative.isEmpty()) {
                // 自身条目：stat（includeSelf）时作为返回值，list 时跳过
                if (includeSelf) {
                    entries.add(buildEntry(response, prefix, true));
                }
                continue;
            }
            if (includeSelf && !entries.isEmpty()) {
                continue;
            }
            entries.add(buildEntry(response, StoragePaths.join(prefix, relative), collection));
        }
        return entries;
    }

    private StorageEntry buildEntry(Element response, String path, boolean collection) {
        if (collection) {
            return StorageEntry.dir(path, parseHttpDate(Xmls.text(response, "getlastmodified")));
        }
        long size = StorageEntry.SIZE_UNKNOWN;
        String length = Xmls.text(response, "getcontentlength");
        if (length != null) {
            try {
                size = Long.parseLong(length.trim());
            } catch (NumberFormatException ignore) {
                // 部分服务器对目录返回空串，保持未知
            }
        }
        return StorageEntry.file(path, size,
                parseHttpDate(Xmls.text(response, "getlastmodified")),
                Xmls.text(response, "getetag"));
    }

    /** href 可能是完整 URL 或路径，且逐段百分号编码。 */
    private String decodeHref(String href) {
        String path = href;
        int scheme = href.indexOf("://");
        if (scheme >= 0) {
            int at = href.indexOf('/', scheme + 3);
            path = at < 0 ? "/" : href.substring(at);
        }
        StringBuilder decoded = new StringBuilder();
        for (String segment : path.split("/")) {
            if (segment.isEmpty()) {
                continue;
            }
            decoded.append('/').append(Urls.decode(segment));
        }
        return decoded.toString();
    }

    private String toRelative(String decodedHref, String prefix) {
        String prefixWithSlash = "/" + prefix + (prefix.isEmpty() ? "" : "/");
        if (!decodedHref.startsWith(prefixWithSlash) && !decodedHref.equals(prefixWithSlash)
                && !decodedHref.equals("/" + prefix)) {
            return null;
        }
        if (decodedHref.equals("/" + prefix) || decodedHref.equals(prefixWithSlash)) {
            return "";
        }
        return decodedHref.substring(prefixWithSlash.length());
    }

    private void ensureSuccess(HttpCore.Response response, String path) {
        if (response.status() < 400) {
            return;
        }
        int status = response.status();
        String message = response.message();
        response.close();
        StorageErrorCode code = status == 401 || status == 403 ? StorageErrorCode.AUTH_FAILED
                : status == 404 ? StorageErrorCode.NOT_FOUND
                : status == 405 ? StorageErrorCode.ALREADY_EXISTS
                : status == 409 || status == 423 || status == 424 || status == 502
                        ? StorageErrorCode.CONFLICT
                : status == 429 ? StorageErrorCode.RATE_LIMITED
                : status >= 500 ? StorageErrorCode.SERVER_ERROR
                : StorageErrorCode.IO_ERROR;
        throw new StorageException(code, "WebDAV " + status + "（" + message + "）", path);
    }

    private String requireFile(String path) {
        String normalized = StoragePaths.normalize(path);
        if (normalized.isEmpty()) {
            throw StorageException.invalidConfig("路径不能为存储根目录");
        }
        return normalized;
    }

    private static long parseHttpDate(String value) {
        if (value == null) {
            return StorageEntry.MTIME_UNKNOWN;
        }
        SimpleDateFormat format =
                new SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss zzz", Locale.US);
        format.setTimeZone(TimeZone.getTimeZone("GMT"));
        try {
            return format.parse(value).getTime();
        } catch (ParseException e) {
            return StorageEntry.MTIME_UNKNOWN;
        }
    }
}
