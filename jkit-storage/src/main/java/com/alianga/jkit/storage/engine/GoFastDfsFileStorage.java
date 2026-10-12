package com.alianga.jkit.storage.engine;

import com.alianga.jkit.json.JSON;
import com.alianga.jkit.storage.FileStorage;
import com.alianga.jkit.storage.StorageConfig;
import com.alianga.jkit.storage.StorageEntry;
import com.alianga.jkit.storage.StorageErrorCode;
import com.alianga.jkit.storage.StorageException;
import com.alianga.jkit.storage.StoragePaths;
import com.alianga.jkit.storage.internal.HttpCore;
import com.alianga.jkit.storage.internal.Urls;
import com.alianga.jkit.storage.internal.Xmls;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.SequenceInputStream;
import java.nio.charset.StandardCharsets;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * go-fastdfs 引擎：基于其 HTTP API（multipart 上传 + REST 风格读写），零第三方依赖。
 *
 * <p>目录语义：go-fastdfs 没有「目录对象」，目录是隐式的——mkdir 直接成功；
 * exists / stat 在 HEAD 404 / 405 时回退到父目录 list 探测；delete 对 405（目录）
 * 与 404 一律视为幂等成功。</p>
 *
 * <p>上传：POST {endpoint}/{group}/upload，multipart/form-data 手工拼装（边界、头、尾），
 * 文件字段名 {@code file}、文本字段 {@code path}（目标目录）与 {@code output=json}；
 * 已知长度时用 SequenceInputStream 三段拼接按定长发送，未知长度交给 HttpCore 落盘缓冲。
 * 响应 JSON 中的 {@code md5} 会被缓存为 etag，供同实例后续 stat 使用。</p>
 *
 * <p>配置键：endpoint（必填，如 http://127.0.0.1:8080）、group（默认 group1）、
 * basePath（默认空串）、token（可选，非空时带 {@code Authorization: Bearer}）。</p>
 *
 * @author 郑明亮
 */
public class GoFastDfsFileStorage implements FileStorage {
    private final StorageConfig config;
    private final String endpoint;
    private final String group;
    private final String basePath;
    private final String token;
    /** 上传响应中的 md5 → etag 缓存（本实例内有效）。 */
    private final Map<String, String> etagCache = new ConcurrentHashMap<String, String>();

    /**
     * 由配置创建（配置键：endpoint / group / basePath / token）。
     *
     * @param config 连接配置
     */
    public GoFastDfsFileStorage(StorageConfig config) {
        this.config = config;
        String raw = config.require("endpoint");
        this.endpoint = raw.endsWith("/") ? raw.substring(0, raw.length() - 1) : raw;
        this.group = config.getOr("group", "group1");
        this.basePath = StoragePaths.normalize(config.getOr("basePath", ""));
        String tokenValue = config.getOr("token", "");
        this.token = tokenValue.isEmpty() ? null : tokenValue;
    }

    @Override
    public String platform() {
        return "go-fastdfs";
    }

    @Override
    public boolean exists(String path) {
        String normalized = StoragePaths.normalize(path);
        if (normalized.isEmpty()) {
            return true;
        }
        HttpCore.Response response = request("HEAD", objectKey(normalized));
        int status = response.status();
        response.close();
        if (status < 400) {
            return true;
        }
        if (status != 404 && status != 405) {
            throw toException(status, "", normalized);
        }
        // 目录是隐式的：HEAD 404 / 405 时回退父目录 list 查名字
        try {
            for (StorageEntry entry : list(StoragePaths.parent(normalized))) {
                if (entry.path().equals(normalized)) {
                    return true;
                }
            }
        } catch (StorageException e) {
            if (e.code() == StorageErrorCode.NOT_FOUND) {
                return false;
            }
            throw e;
        }
        return false;
    }

    @Override
    public StorageEntry stat(String path) {
        String normalized = StoragePaths.normalize(path);
        if (normalized.isEmpty()) {
            return StorageEntry.dir("", 0L);
        }
        HttpCore.Response response = request("HEAD", objectKey(normalized));
        int status = response.status();
        if (status < 400) {
            StorageEntry entry = StorageEntry.file(normalized, lengthOf(response),
                    lastModifiedOf(response), etagCache.get(normalized));
            response.close();
            return entry;
        }
        response.close();
        if (status != 404 && status != 405) {
            throw toException(status, "", normalized);
        }
        try {
            for (StorageEntry entry : list(StoragePaths.parent(normalized))) {
                if (entry.path().equals(normalized)) {
                    return entry;
                }
            }
        } catch (StorageException e) {
            if (e.code() == StorageErrorCode.NOT_FOUND) {
                throw StorageException.notFound(normalized);
            }
            throw e;
        }
        throw StorageException.notFound(normalized);
    }

    @Override
    public List<StorageEntry> list(String dir) {
        String normalized = StoragePaths.normalize(dir);
        HttpCore.Request request = new HttpCore.Request("GET",
                endpoint + "/" + group + "/list?dir=" + Urls.encodeQuery(objectKey(normalized)));
        authorize(request);
        HttpCore.Response response = HttpCore.execute(request);
        ensureSuccess(response, normalized);
        String body = Xmls.readAll(response.body());
        response.close();
        List<StorageEntry> entries = new ArrayList<StorageEntry>();
        Collection<?> rows;
        try {
            rows = JSON.parseCollection(body, ArrayList.class);
        } catch (RuntimeException e) {
            throw StorageException.io("列表响应解析失败: " + e.getMessage(), normalized, e);
        }
        if (rows == null) {
            return entries;
        }
        for (Object item : rows) {
            if (!(item instanceof Map)) {
                continue;
            }
            StorageEntry entry = toEntry(normalized, (Map<?, ?>) item);
            if (entry != null) {
                entries.add(entry);
            }
        }
        return entries;
    }

    private StorageEntry toEntry(String normalized, Map<?, ?> row) {
        Object nameValue = row.get("name");
        if (nameValue == null) {
            return null;
        }
        String name = String.valueOf(nameValue);
        boolean directory = name.endsWith("/") || Boolean.TRUE.equals(row.get("isDir"));
        if (name.endsWith("/")) {
            name = name.substring(0, name.length() - 1);
        }
        if (name.isEmpty()) {
            return null;
        }
        if (directory) {
            return StorageEntry.dir(StoragePaths.join(normalized, name));
        }
        return StorageEntry.file(StoragePaths.join(normalized, name), parseSize(row.get("size")),
                parseMtime(row.get("mtime")), row.get("md5") == null ? null : String.valueOf(row.get("md5")));
    }

    @Override
    public void mkdir(String dir) {
        // go-fastdfs 目录是隐式的，无需创建，直接视为成功
    }

    @Override
    public void upload(String path, InputStream in, long size) {
        String normalized = requireFile(path);
        String boundary = "jkit-storage-fastdfs-" + Long.toHexString(System.currentTimeMillis())
                + Long.toHexString(System.nanoTime());
        byte[] head = multipartHead(boundary, objectKey(StoragePaths.parent(normalized)),
                StoragePaths.name(normalized));
        byte[] tail = ("--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8);
        // 已知 size：头 + 数据 + 尾三段拼接后按定长发送；未知 size 交给 HttpCore 落盘缓冲
        InputStream body = new SequenceInputStream(new ByteArrayInputStream(head),
                new SequenceInputStream(in, new ByteArrayInputStream(tail)));
        HttpCore.Request request = new HttpCore.Request("POST", endpoint + "/" + group + "/upload");
        request.header("Content-Type", "multipart/form-data; boundary=" + boundary);
        authorize(request);
        request.body(body, size >= 0 ? head.length + size + tail.length : -1L);
        HttpCore.Response response = HttpCore.execute(request);
        String bodyText = Xmls.readAll(response.body());
        response.close();
        if (response.status() >= 400) {
            throw toException(response.status(), bodyText, normalized);
        }
        recordUpload(normalized, bodyText);
    }

    private byte[] multipartHead(String boundary, String pathValue, String filename) {
        StringBuilder builder = new StringBuilder();
        builder.append("--").append(boundary).append("\r\n");
        builder.append("Content-Disposition: form-data; name=\"path\"\r\n\r\n");
        builder.append(pathValue).append("\r\n");
        builder.append("--").append(boundary).append("\r\n");
        builder.append("Content-Disposition: form-data; name=\"output\"\r\n\r\n");
        builder.append("json\r\n");
        builder.append("--").append(boundary).append("\r\n");
        builder.append("Content-Disposition: form-data; name=\"file\"; filename=\"")
                .append(filename).append("\"\r\n");
        builder.append("Content-Type: application/octet-stream\r\n\r\n");
        return builder.toString().getBytes(StandardCharsets.UTF_8);
    }

    /** 解析上传响应 JSON：从 url 截取 /group/ 之后的部分作为对象 key（去 basePath 前缀），md5 记为 etag。 */
    private void recordUpload(String normalized, String bodyText) {
        Map<?, ?> json;
        try {
            json = JSON.parseObject(bodyText);
        } catch (RuntimeException e) {
            throw StorageException.io("上传响应解析失败: " + e.getMessage(), normalized, e);
        }
        if (json == null) {
            return;
        }
        Object md5 = json.get("md5");
        if (md5 == null) {
            return;
        }
        String md5Text = String.valueOf(md5);
        etagCache.put(normalized, md5Text);
        String objectKey = objectKeyFromUrl(json.get("url") == null ? "" : String.valueOf(json.get("url")));
        if (!objectKey.isEmpty() && !objectKey.equals(normalized)) {
            etagCache.put(objectKey, md5Text);
        }
    }

    private String objectKeyFromUrl(String url) {
        int at = url.lastIndexOf("/" + group + "/");
        if (at < 0) {
            return "";
        }
        String key = StoragePaths.normalize(url.substring(at + group.length() + 2));
        if (!basePath.isEmpty() && key.startsWith(basePath + "/")) {
            key = key.substring(basePath.length() + 1);
        } else if (key.equals(basePath)) {
            key = "";
        }
        return key;
    }

    @Override
    public void download(String path, OutputStream out) {
        String normalized = requireFile(path);
        HttpCore.Response response = request("GET", objectKey(normalized));
        ensureSuccess(response, normalized);
        streamTo(response, normalized, out);
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
        HttpCore.Request rangeRequest = new HttpCore.Request("GET",
                endpoint + "/" + group + "/" + Urls.encodePath(objectKey(normalized)));
        authorize(rangeRequest);
        rangeRequest.header("Range", "bytes=" + offset + "-");
        HttpCore.Response response = HttpCore.execute(HttpCore.configured(rangeRequest, config));
        if (response.status() == 416) {
            response.close();
            throw StorageException.conflict("续传偏移超出对象大小: " + normalized, normalized);
        }
        ensureSuccess(response, normalized);
        streamTo(response, normalized, out);
    }

    /** 响应体 → 输出流。 */
    private void streamTo(HttpCore.Response response, String normalized, OutputStream out) {
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
        HttpCore.Response response = request("DELETE", objectKey(normalized));
        int status = response.status();
        String body = Xmls.readAll(response.body());
        response.close();
        // 200/204 正常删除；404 幂等；405 目录（go-fastdfs 目录隐式，无实体可删）同样视为成功
        if (status < 300 || status == 404 || status == 405) {
            return;
        }
        throw toException(status, body, normalized);
    }

    @Override
    public void copy(String src, String dst) {
        String source = requireFile(src);
        String target = requireFile(dst);
        if (stat(source).isDirectory()) {
            copyDir(source, target);
            return;
        }
        // go-fastdfs 无服务端复制 API，走 readBytes + upload（大文件受内存限制）
        upload(target, readBytes(source));
    }

    @Override
    public void rename(String src, String dst) {
        String source = requireFile(src);
        String target = requireFile(dst);
        if (stat(source).isDirectory()) {
            copyDir(source, target);
            deleteDir(source);
            return;
        }
        copy(source, target);
        delete(source);
    }

    private void copyDir(String source, String target) {
        mkdir(target);
        for (StorageEntry entry : list(source)) {
            if (entry.isDirectory()) {
                copyDir(entry.path(), StoragePaths.join(target, entry.name()));
            } else {
                copy(entry.path(), StoragePaths.join(target, entry.name()));
            }
        }
    }

    // ------------------------------------------------------------------ 请求与工具

    private HttpCore.Response request(String method, String objectPath) {
        HttpCore.Request request =
                new HttpCore.Request(method, endpoint + "/" + group + "/" + Urls.encodePath(objectPath));
        authorize(request);
        return HttpCore.execute(HttpCore.configured(request, config));
    }

    private void authorize(HttpCore.Request request) {
        if (token != null) {
            request.header("Authorization", "Bearer " + token);
        }
    }

    private String objectKey(String path) {
        String normalized = StoragePaths.normalize(path);
        if (basePath.isEmpty()) {
            return normalized;
        }
        return basePath + (normalized.isEmpty() ? "" : "/" + normalized);
    }

    private void ensureSuccess(HttpCore.Response response, String path) {
        if (response.status() < 400) {
            return;
        }
        String body = Xmls.readAll(response.body());
        response.close();
        throw toException(response.status(), body, path);
    }

    private StorageException toException(int status, String body, String path) {
        StorageErrorCode code = status == 401 || status == 403 ? StorageErrorCode.AUTH_FAILED
                : status == 404 ? StorageErrorCode.NOT_FOUND
                : status == 429 ? StorageErrorCode.RATE_LIMITED
                : status >= 500 ? StorageErrorCode.SERVER_ERROR
                : StorageErrorCode.IO_ERROR;
        String detail = "HTTP " + status;
        String trimmed = body == null ? "" : body.trim();
        if (!trimmed.isEmpty()) {
            detail = detail + "：" + trimmed.substring(0, Math.min(trimmed.length(), 200));
        }
        return new StorageException(code, detail, path);
    }

    private String requireFile(String path) {
        String normalized = StoragePaths.normalize(path);
        if (normalized.isEmpty()) {
            throw StorageException.invalidConfig("路径不能为存储根目录");
        }
        return normalized;
    }

    private static long lengthOf(HttpCore.Response response) {
        String length = response.header("Content-Length");
        if (length == null) {
            return StorageEntry.SIZE_UNKNOWN;
        }
        try {
            long value = Long.parseLong(length.trim());
            return value >= 0 ? value : StorageEntry.SIZE_UNKNOWN;
        } catch (NumberFormatException e) {
            return StorageEntry.SIZE_UNKNOWN;
        }
    }

    private static long lastModifiedOf(HttpCore.Response response) {
        String date = response.header("Last-Modified");
        if (date == null) {
            return StorageEntry.MTIME_UNKNOWN;
        }
        try {
            SimpleDateFormat format =
                    new SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss 'GMT'", java.util.Locale.US);
            format.setTimeZone(java.util.TimeZone.getTimeZone("GMT"));
            return format.parse(date).getTime();
        } catch (ParseException e) {
            return StorageEntry.MTIME_UNKNOWN;
        }
    }

    private static long parseSize(Object value) {
        if (value instanceof Number) {
            return ((Number) value).longValue();
        }
        if (value == null) {
            return StorageEntry.SIZE_UNKNOWN;
        }
        try {
            return Long.parseLong(String.valueOf(value).trim());
        } catch (NumberFormatException e) {
            return StorageEntry.SIZE_UNKNOWN;
        }
    }

    /** mtime 兼容数字秒与字符串两种格式，无法识别时返回未知。 */
    private static long parseMtime(Object value) {
        if (value instanceof Number) {
            return ((Number) value).longValue() * 1000L;
        }
        if (value == null) {
            return StorageEntry.MTIME_UNKNOWN;
        }
        String text = String.valueOf(value).trim();
        if (text.isEmpty()) {
            return StorageEntry.MTIME_UNKNOWN;
        }
        boolean digits = true;
        for (int i = 0; i < text.length(); i++) {
            if (!Character.isDigit(text.charAt(i))) {
                digits = false;
                break;
            }
        }
        if (digits) {
            return Long.parseLong(text) * 1000L;
        }
        try {
            return new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").parse(text).getTime();
        } catch (ParseException ignored) {
            // 非该格式的字符串日期，继续尝试 ISO-8601
        }
        try {
            return java.time.Instant.parse(text).toEpochMilli();
        } catch (java.time.format.DateTimeParseException ignored) {
            // 无法识别的时间格式，返回未知
        }
        return StorageEntry.MTIME_UNKNOWN;
    }
}
