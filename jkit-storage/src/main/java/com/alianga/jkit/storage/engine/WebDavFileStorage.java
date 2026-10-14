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

    private final StorageConfig config;
    private final String endpoint;
    private final String basePath;
    /** endpoint 自带路径 + basePath 合成的服务器侧路径前缀（用于剥离 PROPFIND href）。 */
    private final String serverBasePath;
    private final String credentials;
    private final boolean anonymous;

    /**
     * 由配置创建（配置键：endpoint / user / password / basePath）。
     *
     * @param config 连接配置
     */
    public WebDavFileStorage(StorageConfig config) {
        this.config = config;
        String raw = config.require("endpoint");
        if (!raw.startsWith("http://") && !raw.startsWith("https://")) {
            raw = "https://" + raw;
        }
        this.endpoint = raw.endsWith("/") ? raw.substring(0, raw.length() - 1) : raw;
        this.basePath = StoragePaths.normalize(config.getOr("basePath", ""));
        String endpointPath;
        try {
            endpointPath = new java.net.URL(this.endpoint).getPath();
        } catch (java.net.MalformedURLException e) {
            throw StorageException.invalidConfig("endpoint 非法: " + this.endpoint);
        }
        String endpointPrefix = (endpointPath == null || endpointPath.isEmpty() || "/".equals(endpointPath))
                ? "" : StoragePaths.normalize(endpointPath);
        this.serverBasePath = StoragePaths.join(endpointPrefix, this.basePath);
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
        String xml;
        try {
            xml = Xmls.readAll(response.body());
        } finally {
            // 响应超限抛异常时也要关闭，避免连接泄漏
            response.close();
        }
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
        String xml;
        try {
            xml = Xmls.readAll(response.body());
        } finally {
            // 响应超限抛异常时也要关闭，避免连接泄漏
            response.close();
        }
        return parseMultistatus(xml, normalized, false);
    }

    @Override
    public void mkdir(String dir) {
        String normalized = StoragePaths.normalize(dir);
        if (normalized.isEmpty() || exists(normalized)) {
            return;
        }
        // WebDAV 要求父集合存在：逐级补齐（405 = 已存在，幂等）
        List<String> missing = new ArrayList<String>();
        String parent = normalized;
        while (!parent.isEmpty() && !exists(parent)) {
            missing.add(parent);
            parent = StoragePaths.parent(parent);
        }
        for (int i = missing.size() - 1; i >= 0; i--) {
            HttpCore.Response response = request("MKCOL", missing.get(i), null, null, null, -1L, null, null);
            if (response.status() == 405) {
                response.close();
                continue;
            }
            ensureSuccess(response, missing.get(i));
            response.close();
        }
    }

    @Override
    public void upload(String path, InputStream in, long size) {
        String normalized = requireFile(path);
        mkdir(StoragePaths.parent(normalized));
        HttpCore.Response response = request("PUT", normalized, null,
                MimeTypes.of(normalized), null, size, in, null);
        String location;
        try {
            ensureSuccess(response, normalized);
            location = response.header("Location");
        } finally {
            response.close();
        }
        String landed = landedPath(location);
        if (landed != null && !landed.equals(normalized)) {
            // 服务器（如 copyparty 未开 daw）遇同名不覆盖而是改名另存：把新文件挪回原路径
            replaceWithRenamed(landed, normalized);
        }
    }

    /**
     * 解析 PUT 响应 Location 对应的存储相对路径；无法解析或不在 basePath 下时返回 null。
     */
    String landedPath(String location) {
        if (location == null || location.trim().isEmpty()) {
            return null;
        }
        String decoded = decodeHref(location.trim());
        String relative = toRelative(decoded, serverBasePath);
        return relative == null ? null : StoragePaths.normalize(relative);
    }

    private void replaceWithRenamed(String landed, String target) {
        HttpCore.Response move = request("MOVE", landed, null, null, null, -1L, null, target);
        int status = move.status();
        move.close();
        if (status < 400 && !exists(landed)) {
            return;
        }
        // MOVE 覆盖被拒（多为缺少删除权限）：删除原文件后再挪回；仍失败则清理副本并报错
        HttpCore.Response delete = request("DELETE", target, null, null, null, -1L, null, null);
        int deleted = delete.status();
        delete.close();
        if (deleted < 400 || deleted == 404) {
            HttpCore.Response retry = request("MOVE", landed, null, null, null, -1L, null, target);
            int retried = retry.status();
            retry.close();
            if (retried < 400) {
                return;
            }
        }
        HttpCore.Response cleanup = request("DELETE", landed, null, null, null, -1L, null, null);
        cleanup.close();
        throw new StorageException(StorageErrorCode.CONFLICT,
                "WebDAV 服务器拒绝覆盖同名文件（另存为 " + landed + "），请开启覆盖/删除权限", target);
    }

    @Override
    public void download(String path, OutputStream out) {
        String normalized = requireFile(path);
        HttpCore.Response response = request("GET", normalized, null, null, null, -1L, null, null);
        streamBody(response, normalized, out);
    }

    @Override
    public boolean supportsRange() {
        return true;
    }

    @Override
    public void download(String path, long offset, OutputStream out) {
        if (offset <= 0) {
            download(path, out);
            return;
        }
        String normalized = requireFile(path);
        HttpCore.Request rangeRequest = new HttpCore.Request("GET", urlOf(normalized));
        if (!anonymous) {
            rangeRequest.header("Authorization", "Basic " + credentials);
        }
        rangeRequest.header("Range", "bytes=" + offset + "-");
        HttpCore.Response response = HttpCore.execute(HttpCore.configured(rangeRequest, config));
        if (response.status() == 416) {
            response.close();
            throw StorageException.conflict("续传偏移超出对象大小: " + normalized, normalized);
        }
        streamBody(response, normalized, out);
    }

    /** 把响应体写入输出流（404 映射 notFound，其余 <400 视为成功，含 206）。 */
    private void streamBody(HttpCore.Response response, String normalized, OutputStream out) {
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
        if ("PUT".equals(method)) {
            // RFC 4918：PUT 覆盖同名资源；copyparty 默认改名另存，需 replace 头（并有删除权限）才覆盖
            request.header("Overwrite", "T");
            request.header("replace", "1");
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
        return HttpCore.execute(HttpCore.configured(request, config));
    }

    private HttpCore.Response propfind(String path, int depth) {
        HttpCore.Request request = new HttpCore.Request("PROPFIND", urlOf(path));
        if (!anonymous) {
            request.header("Authorization", "Basic " + credentials);
        }
        request.header("Depth", String.valueOf(depth));
        request.header("Content-Type", "application/xml");
        request.body(PROPFIND_BODY.getBytes(StandardCharsets.UTF_8));
        return HttpCore.execute(HttpCore.configured(request, config));
    }

    private List<StorageEntry> parseMultistatus(String xml, String dir, boolean includeSelf) {
        Element root = Xmls.parse(xml);
        List<Element> responses = Xmls.deepAll(root, "response");
        List<StorageEntry> entries = new ArrayList<StorageEntry>();
        String prefix = StoragePaths.normalize(dir);
        // href 是服务器侧完整路径（含 endpoint 自带路径与 basePath），换算成相对 prefix 的相对路径
        String serverPrefix = StoragePaths.join(serverBasePath, prefix);
        for (Element response : responses) {
            String href = Xmls.text(response, "href");
            if (href == null || href.isEmpty()) {
                continue;
            }
            String decoded = decodeHref(href);
            String relative = toRelative(decoded, serverPrefix);
            if (relative == null) {
                continue;
            }
            boolean collection = !Xmls.deepAll(response, "collection").isEmpty()
                    || relative.endsWith("/");
            if (relative.endsWith("/")) {
                relative = relative.substring(0, relative.length() - 1);
            }
            if (relative.isEmpty()) {
                // 自身条目：stat（includeSelf）时作为返回值，list 时跳过
                if (includeSelf) {
                    entries.add(buildEntry(response, prefix, collection));
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
        // DAV 属性嵌套在 propstat/prop 之下，统一按本地名深度查找
        String lastModified = Xmls.deepText(response, "getlastmodified");
        if (collection) {
            return StorageEntry.dir(path, parseHttpDate(lastModified));
        }
        long size = StorageEntry.SIZE_UNKNOWN;
        String length = Xmls.deepText(response, "getcontentlength");
        if (length != null) {
            try {
                size = Long.parseLong(length.trim());
            } catch (NumberFormatException ignore) {
                // 部分服务器对目录返回空串，保持未知
            }
        }
        return StorageEntry.file(path, size, parseHttpDate(lastModified),
                Xmls.deepText(response, "getetag"));
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
