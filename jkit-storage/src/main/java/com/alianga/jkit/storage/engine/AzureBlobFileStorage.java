package com.alianga.jkit.storage.engine;

import com.alianga.jkit.storage.FileStorage;
import com.alianga.jkit.storage.StorageConfig;
import com.alianga.jkit.storage.StorageEntry;
import com.alianga.jkit.storage.StorageErrorCode;
import com.alianga.jkit.storage.StorageException;
import com.alianga.jkit.storage.StoragePaths;
import com.alianga.jkit.storage.internal.AwsSigners;
import com.alianga.jkit.storage.internal.HttpCore;
import com.alianga.jkit.storage.internal.MimeTypes;
import com.alianga.jkit.storage.internal.Urls;
import com.alianga.jkit.storage.internal.Xmls;
import org.w3c.dom.Element;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Azure Blob Storage 原生引擎：SharedKey 签名 + 纯 HttpCore 实现，不依赖 Azure SDK。
 *
 * <p>认证：每个请求带 {@code x-ms-date}（RFC 1123 GMT）与 {@code x-ms-version} 头，
 * 签名 {@code Authorization: SharedKey account:signature}，StringToSign 采用
 * 2015-02-21 版格式（VERB、Content-Encoding/Language/Length/MD5/Type、Date 与
 * 5 个条件头各占一行，再拼 CanonicalizedHeaders 与 CanonicalizedResource）。</p>
 *
 * <p>目录语义：目录用零长占位对象（key 以 {@code /} 结尾）实现，mkdir 幂等、
 * 已存在视为成功；没有占位对象的隐式目录通过 list 前缀探测。rename =
 * 服务端 copy + delete；目录 copy/rename 递归处理。</p>
 *
 * <p>配置键：endpoint（必填，如 https://account.blob.core.windows.net）、bucket（必填，
 * 即 container）、accessKey（必填，存储账户名）、secretKey（必填，Base64 账户密钥）、
 * basePath（默认空串，对象 key 统一前缀）。</p>
 *
 * @author 郑明亮
 */
public class AzureBlobFileStorage implements FileStorage {
    /** 请求使用的存储服务版本。 */
    private static final String MS_VERSION = "2021-08-06";
    /** 分页拉取全量列表时的最大页数（防御性上限）。 */
    private static final int MAX_LIST_PAGES = 500;
    /** 每页请求的最大条数（Azure 单页上限 5000）。 */
    private static final String PAGE_SIZE = "5000";

    private final StorageConfig config;
    private final String endpoint;
    private final String container;
    private final String account;
    private final byte[] accountKey;
    private final String basePath;

    /**
     * 由配置创建（配置键：endpoint / bucket / accessKey / secretKey / basePath）。
     *
     * @param config 连接配置
     */
    public AzureBlobFileStorage(StorageConfig config) {
        this.config = config;
        String raw = config.require("endpoint");
        if (!raw.startsWith("http://") && !raw.startsWith("https://")) {
            raw = "https://" + raw;
        }
        this.endpoint = raw.endsWith("/") ? raw.substring(0, raw.length() - 1) : raw;
        this.container = config.require("bucket");
        this.account = config.require("accessKey");
        try {
            this.accountKey = Base64.getDecoder().decode(config.require("secretKey"));
        } catch (IllegalArgumentException e) {
            throw StorageException.invalidConfig("secretKey 不是合法的 Base64 密钥: " + e.getMessage());
        }
        this.basePath = StoragePaths.normalize(config.getOr("basePath", ""));
    }

    @Override
    public String platform() {
        return "azure-blob";
    }

    @Override
    public boolean exists(String path) {
        String normalized = StoragePaths.normalize(path);
        if (normalized.isEmpty()) {
            return true;
        }
        String key = objectKey(normalized);
        if (request("HEAD", key, null, null, null, -1L, null, null).status() < 400) {
            return true;
        }
        if (request("HEAD", key + "/", null, null, null, -1L, null, null).status() < 400) {
            return true;
        }
        return !list(normalized).isEmpty();
    }

    @Override
    public StorageEntry stat(String path) {
        String normalized = StoragePaths.normalize(path);
        if (normalized.isEmpty()) {
            return StorageEntry.dir("", 0L);
        }
        String key = objectKey(normalized);
        HttpCore.Response response = request("HEAD", key, null, null, null, -1L, null, null);
        if (response.status() < 400) {
            StorageEntry entry = StorageEntry.file(normalized, lengthOf(response),
                    lastModifiedOf(response), response.header("ETag"));
            response.close();
            return entry;
        }
        response.close();
        response = request("HEAD", key + "/", null, null, null, -1L, null, null);
        if (response.status() < 400) {
            StorageEntry entry = StorageEntry.dir(normalized, lastModifiedOf(response));
            response.close();
            return entry;
        }
        response.close();
        // 隐式目录（无占位对象）：通过列表前缀探测，保证与 exists 语义一致
        try {
            if (!list(normalized).isEmpty()) {
                return StorageEntry.dir(normalized);
            }
        } catch (StorageException e) {
            // 列表失败时保持 NOT_FOUND 语义
        }
        throw StorageException.notFound(normalized);
    }

    @Override
    public List<StorageEntry> list(String dir) {
        String prefix = StoragePaths.normalize(dir);
        String fullPrefix = objectKey(prefix);
        if (!fullPrefix.isEmpty()) {
            fullPrefix = fullPrefix + "/";
        }
        List<StorageEntry> entries = new ArrayList<StorageEntry>();
        String marker = "";
        int pages = 0;
        while (pages++ < MAX_LIST_PAGES) {
            TreeMap<String, String> query = new TreeMap<String, String>();
            query.put("comp", "list");
            query.put("delimiter", "/");
            query.put("marker", marker);
            query.put("maxresults", PAGE_SIZE);
            query.put("prefix", fullPrefix);
            query.put("restype", "container");
            HttpCore.Response response = request("GET", "", query, null, null, -1L, null, null);
            ensureSuccess(response, prefix);
            String xml = Xmls.readAll(response.body());
            response.close();
            Element root = Xmls.parse(xml);
            collectPage(root, prefix, fullPrefix, entries);
            String next = Xmls.textOr(root, "NextMarker", null);
            if (next == null || next.isEmpty()) {
                break;
            }
            marker = next;
        }
        return entries;
    }

    private static void collectPage(Element root, String prefix, String fullPrefix,
                                    List<StorageEntry> entries) {
        Element blobs = Xmls.first(root, "Blobs");
        if (blobs == null) {
            return;
        }
        for (Element blob : Xmls.children(blobs, "Blob")) {
            String name = Xmls.textOr(blob, "Name", "");
            String relative = relativeOf(name, fullPrefix);
            if (name.equals(fullPrefix) || relative.isEmpty()) {
                continue;
            }
            String childPath = StoragePaths.join(prefix, relative);
            if (name.endsWith("/")) {
                entries.add(StorageEntry.dir(childPath));
                continue;
            }
            Element properties = Xmls.first(blob, "Properties");
            String etag = properties == null ? null
                    : Xmls.textOr(properties, "Etag", Xmls.textOr(properties, "ETag", null));
            long size = properties == null ? StorageEntry.SIZE_UNKNOWN
                    : parseLong(Xmls.textOr(properties, "Content-Length", null));
            long mtime = properties == null ? StorageEntry.MTIME_UNKNOWN
                    : parseHttpDate(Xmls.textOr(properties, "Last-Modified", null));
            entries.add(StorageEntry.file(childPath, size, mtime, etag));
        }
        for (Element common : Xmls.children(blobs, "BlobPrefix")) {
            String relative = relativeOf(Xmls.textOr(common, "Name", ""), fullPrefix);
            if (relative.endsWith("/")) {
                relative = relative.substring(0, relative.length() - 1);
            }
            if (!relative.isEmpty()) {
                entries.add(StorageEntry.dir(StoragePaths.join(prefix, relative)));
            }
        }
    }

    @Override
    public void mkdir(String dir) {
        String normalized = StoragePaths.normalize(dir);
        if (normalized.isEmpty()) {
            return;
        }
        if (exists(normalized)) {
            return;
        }
        HttpCore.Response response = request("PUT", objectKey(normalized) + "/", null, null,
                new byte[0], -1L, null, blobTypeHeaders());
        if (response.status() < 400) {
            response.close();
            return;
        }
        String body = Xmls.readAll(response.body());
        response.close();
        if (hasErrorCode(body, "BlobAlreadyExists")) {
            return;
        }
        throw toException(response.status(), body, normalized);
    }

    @Override
    public void upload(String path, InputStream in, long size) {
        String normalized = requireFile(path);
        if (size >= 0) {
            uploadStream(normalized, in, size);
            return;
        }
        // 未知长度：先缓冲到临时文件取得精确长度，再定长上传（签名依赖 Content-Length）
        java.io.File temp;
        try {
            temp = java.io.File.createTempFile("jkit-storage-azure-", ".tmp");
        } catch (IOException e) {
            throw StorageException.io("创建临时文件失败: " + e.getMessage(), normalized, e);
        }
        try {
            long buffered;
            try (OutputStream fileOut = Files.newOutputStream(temp.toPath())) {
                buffered = pumpCounting(in, fileOut);
            }
            try (InputStream fileIn = Files.newInputStream(temp.toPath())) {
                uploadStream(normalized, fileIn, buffered);
            }
        } catch (IOException e) {
            throw StorageException.io("上传失败: " + normalized + "（" + e.getMessage() + "）", normalized, e);
        } finally {
            try {
                Files.deleteIfExists(temp.toPath());
            } catch (IOException ignore) {
                // 临时文件清理失败不影响结果
            }
        }
    }

    private void uploadStream(String normalized, InputStream in, long size) {
        HttpCore.Response response = request("PUT", objectKey(normalized), null,
                MimeTypes.of(normalized), null, size, in, blobTypeHeaders());
        ensureSuccess(response, normalized);
        response.close();
    }

    @Override
    public void download(String path, OutputStream out) {
        downloadInternal(path, out, -1L);
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
        downloadInternal(path, out, offset);
    }

    /** 统一下载实现：offset > 0 时带 Range 头（期望 206 Partial Content）。 */
    private void downloadInternal(String path, OutputStream out, long offset) {
        String normalized = requireFile(path);
        HttpCore.Response response = request("GET", objectKey(normalized), null, null, null,
                -1L, null, offset > 0
                        ? java.util.Collections.singletonMap("Range", "bytes=" + offset + "-")
                        : null);
        if (response.status() == 404) {
            response.close();
            throw StorageException.notFound(normalized);
        }
        if (offset > 0 && response.status() == 416) {
            response.close();
            throw StorageException.conflict("续传偏移超出对象大小: " + normalized, normalized);
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
        String key = objectKey(normalized);
        HttpCore.Response response = request("HEAD", key + "/", null, null, null, -1L, null, null);
        boolean directory = response.status() < 400;
        response.close();
        if (directory && !list(normalized).isEmpty()) {
            throw StorageException.conflict("目录非空: " + normalized + "，请用 deleteDir", normalized);
        }
        response = request("DELETE", directory ? key + "/" : key, null, null, null, -1L, null, null);
        int status = response.status();
        if (status == 202 || status == 200 || status == 204 || status == 404) {
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
        if (isDirectory(source)) {
            copyDir(source, target);
            return;
        }
        copyObject(objectKey(source), objectKey(target));
    }

    @Override
    public void rename(String src, String dst) {
        String source = requireFile(src);
        String target = requireFile(dst);
        if (isDirectory(source)) {
            renameDir(source, target);
            return;
        }
        copyObject(objectKey(source), objectKey(target));
        deleteQuietly(objectKey(source));
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

    private void renameDir(String source, String target) {
        copyDir(source, target);
        deleteDir(source);
    }

    private void copyObject(String srcKey, String dstKey) {
        Map<String, String> extra = new TreeMap<String, String>();
        extra.put("x-ms-copy-source", endpoint + "/" + container + "/" + Urls.encodePath(srcKey));
        HttpCore.Response response = request("PUT", dstKey, null, MimeTypes.of(dstKey),
                new byte[0], -1L, null, extra);
        ensureSuccess(response, dstKey);
        response.close();
    }

    private void deleteQuietly(String key) {
        HttpCore.Response response = request("DELETE", key, null, null, null, -1L, null, null);
        response.close();
    }

    private boolean isDirectory(String normalized) {
        HttpCore.Response response = request("HEAD", objectKey(normalized) + "/", null, null,
                null, -1L, null, null);
        response.close();
        if (response.status() < 400) {
            return true;
        }
        return !list(normalized).isEmpty();
    }

    // ------------------------------------------------------------------ 请求与签名

    private static Map<String, String> blobTypeHeaders() {
        Map<String, String> extra = new TreeMap<String, String>();
        extra.put("x-ms-blob-type", "BlockBlob");
        return extra;
    }

    private String objectKey(String path) {
        String normalized = StoragePaths.normalize(path);
        if (basePath.isEmpty()) {
            return normalized;
        }
        return basePath + (normalized.isEmpty() ? "" : "/" + normalized);
    }

    private HttpCore.Response request(String method, String key, Map<String, String> query,
                                      String contentType, byte[] body, long streamLength,
                                      InputStream stream, Map<String, String> extraHeaders) {
        String url = endpoint + "/" + container
                + (key.isEmpty() ? "" : "/" + Urls.encodePath(key)) + buildQuery(query);
        Map<String, String> headers = new TreeMap<String, String>();
        headers.put("x-ms-date", AwsSigners.httpDateNow());
        headers.put("x-ms-version", MS_VERSION);
        if (contentType != null) {
            headers.put("Content-Type", contentType);
        }
        if (extraHeaders != null) {
            headers.putAll(extraHeaders);
        }
        headers.put("Authorization", "SharedKey " + account + ":"
                + sign(method, key, query, headers, body, streamLength, stream, contentType));
        HttpCore.Request request = new HttpCore.Request(method, url);
        for (Map.Entry<String, String> header : headers.entrySet()) {
            request.header(header.getKey(), header.getValue());
        }
        if (body != null) {
            request.body(body);
        } else if (stream != null) {
            request.body(stream, streamLength);
        }
        return HttpCore.execute(HttpCore.configured(request, config));
    }

    /**
     * 计算 SharedKey 签名：StringToSign 按 2015-02-21 版格式逐行拼装，
     * CanonicalizedHeaders 为全部 x-ms-* 头（小写排序），CanonicalizedResource 为
     * {@code /account/container/key} 加按名称排序的查询子资源。
     */
    private String sign(String method, String key, Map<String, String> query,
                        Map<String, String> headers, byte[] body, long streamLength,
                        InputStream stream, String contentType) {
        String contentLength = "";
        if (body != null && body.length > 0) {
            contentLength = String.valueOf(body.length);
        } else if (stream != null && streamLength > 0) {
            contentLength = String.valueOf(streamLength);
        }
        StringBuilder canonicalHeaders = new StringBuilder();
        for (Map.Entry<String, String> header : headers.entrySet()) {
            String name = header.getKey().toLowerCase();
            if (name.startsWith("x-ms-")) {
                canonicalHeaders.append(name).append(':').append(header.getValue()).append('\n');
            }
        }
        StringBuilder resource = new StringBuilder("/").append(account).append('/').append(container);
        if (!key.isEmpty()) {
            resource.append('/').append(key);
        }
        if (query != null) {
            for (Map.Entry<String, String> entry : query.entrySet()) {
                resource.append('\n').append(entry.getKey()).append(':').append(entry.getValue());
            }
        }
        String stringToSign = method + "\n"
                + "\n"
                + "\n"
                + contentLength + "\n"
                + "\n"
                + (contentType == null ? "" : contentType) + "\n"
                + "\n"
                + "\n"
                + "\n"
                + "\n"
                + "\n"
                + "\n"
                + canonicalHeaders
                + resource;
        return AwsSigners.base64(AwsSigners.hmacSha256(accountKey, stringToSign));
    }

    private static String buildQuery(Map<String, String> query) {
        if (query == null || query.isEmpty()) {
            return "";
        }
        StringBuilder builder = new StringBuilder();
        for (Map.Entry<String, String> entry : query.entrySet()) {
            builder.append(builder.length() == 0 ? '?' : '&')
                    .append(Urls.encodeQuery(entry.getKey()))
                    .append('=')
                    .append(Urls.encodeQuery(entry.getValue()));
        }
        return builder.toString();
    }

    // ------------------------------------------------------------------ 错误映射

    private void ensureSuccess(HttpCore.Response response, String path) {
        if (response.status() < 400) {
            return;
        }
        String body = Xmls.readAll(response.body());
        response.close();
        throw toException(response.status(), body, path);
    }

    private StorageException toException(int status, String body, String path) {
        StorageErrorCode byStatus = status >= 500 ? StorageErrorCode.SERVER_ERROR
                : status == 404 ? StorageErrorCode.NOT_FOUND
                : status == 401 || status == 403 ? StorageErrorCode.AUTH_FAILED
                : status == 409 ? StorageErrorCode.CONFLICT
                : status == 429 ? StorageErrorCode.RATE_LIMITED
                : StorageErrorCode.IO_ERROR;
        String message = "HTTP " + status;
        try {
            Element error = Xmls.parse(body);
            String code = Xmls.textOr(error, "Code", null);
            String detail = Xmls.textOr(error, "Message", null);
            if (detail != null) {
                message = detail + "（" + code + "）";
            }
            if (code != null) {
                StorageErrorCode mapped = mapCode(code);
                if (mapped != null) {
                    return new StorageException(mapped, message, path);
                }
            }
        } catch (RuntimeException ignore) {
            // 响应体不是 XML（代理 / 网关错误等），退回状态码语义
        }
        return new StorageException(byStatus, message, path);
    }

    private static boolean hasErrorCode(String body, String code) {
        try {
            return code.equals(Xmls.textOr(Xmls.parse(body), "Code", null));
        } catch (RuntimeException ignore) {
            // 响应体不是 XML 时按无该错误码处理
            return false;
        }
    }

    private static StorageErrorCode mapCode(String code) {
        switch (code) {
            case "BlobNotFound":
            case "ContainerNotFound":
                return StorageErrorCode.NOT_FOUND;
            case "AuthenticationFailed":
            case "AuthorizationFailure":
            case "InvalidAuthenticationInfo":
                return StorageErrorCode.AUTH_FAILED;
            case "BlobAlreadyExists":
            case "ContainerAlreadyExists":
                return StorageErrorCode.ALREADY_EXISTS;
            case "LeaseIdMissing":
            case "ContainerBeingDeleted":
                return StorageErrorCode.CONFLICT;
            case "ServerBusy":
                return StorageErrorCode.RATE_LIMITED;
            default:
                return null;
        }
    }

    // ------------------------------------------------------------------ 工具方法

    private String requireFile(String path) {
        String normalized = StoragePaths.normalize(path);
        if (normalized.isEmpty()) {
            throw StorageException.invalidConfig("路径不能为存储根目录");
        }
        return normalized;
    }

    private static String relativeOf(String key, String fullPrefix) {
        if (!fullPrefix.isEmpty() && key.startsWith(fullPrefix)) {
            return key.substring(fullPrefix.length());
        }
        return key;
    }

    private static long lengthOf(HttpCore.Response response) {
        String length = response.header("Content-Length");
        return length == null ? StorageEntry.SIZE_UNKNOWN : parseLong(length);
    }

    private static long lastModifiedOf(HttpCore.Response response) {
        return parseHttpDate(response.header("Last-Modified"));
    }

    private static long parseHttpDate(String date) {
        if (date == null) {
            return StorageEntry.MTIME_UNKNOWN;
        }
        try {
            java.text.SimpleDateFormat format =
                    new java.text.SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss 'GMT'", java.util.Locale.US);
            format.setTimeZone(java.util.TimeZone.getTimeZone("GMT"));
            return format.parse(date).getTime();
        } catch (java.text.ParseException e) {
            return StorageEntry.MTIME_UNKNOWN;
        }
    }

    private static long parseLong(String value) {
        if (value == null) {
            return StorageEntry.SIZE_UNKNOWN;
        }
        try {
            return Long.parseLong(value.trim());
        } catch (NumberFormatException e) {
            return StorageEntry.SIZE_UNKNOWN;
        }
    }

    private static long pumpCounting(InputStream in, OutputStream out) throws IOException {
        long total = 0L;
        byte[] chunk = new byte[8192];
        int read;
        while ((read = in.read(chunk)) >= 0) {
            out.write(chunk, 0, read);
            total += read;
        }
        return total;
    }
}
