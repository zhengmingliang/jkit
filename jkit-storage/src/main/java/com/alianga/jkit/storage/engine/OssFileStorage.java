package com.alianga.jkit.storage.engine;

import com.alianga.jkit.storage.FileStorage;
import com.alianga.jkit.storage.StorageConfig;
import com.alianga.jkit.storage.StorageEntry;
import com.alianga.jkit.storage.StorageErrorCode;
import com.alianga.jkit.storage.StorageException;
import com.alianga.jkit.storage.StoragePaths;
import com.alianga.jkit.storage.UploadMetadata;
import com.alianga.jkit.storage.internal.AwsSigners;
import com.alianga.jkit.storage.internal.HttpCore;
import com.alianga.jkit.storage.internal.MimeTypes;
import com.alianga.jkit.storage.internal.Urls;
import com.alianga.jkit.storage.internal.Xmls;
import org.w3c.dom.Element;

import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * 阿里云 OSS 原生协议引擎（非 S3 兼容层）：官方 v1 签名
 * {@code Authorization: OSS AccessKeyId:Signature}，虚拟主机寻址
 * {@code bucket.endpoint/key}，ListObjects 翻页、目录占位对象、服务端复制
 * （x-oss-copy-source，参与签名）。零第三方依赖。
 *
 * @author 郑明亮
 */
public class OssFileStorage implements FileStorage {
    private static final int MAX_LIST_PAGES = 500;

    private final StorageConfig config;
    private final String endpoint;
    private final String bucket;
    private final String accessKey;
    private final String secretKey;
    private final String basePath;
    private final boolean pathStyle;
    /** 单片阈值：超过该大小走 Multipart（默认 64MB）。 */
    private final long multipartThreshold;
    /** 分片大小（默认 8MB，OSS 规范最小 100KB）。 */
    private final long partSize;

    /**
     * 由配置创建（配置键：endpoint / bucket / accessKey / secretKey / basePath）。
     *
     * @param config 连接配置
     */
    public OssFileStorage(StorageConfig config) {
        this.config = config;
        String raw = config.require("endpoint");
        if (!raw.startsWith("http://") && !raw.startsWith("https://")) {
            raw = "https://" + raw;
        }
        this.endpoint = raw.endsWith("/") ? raw.substring(0, raw.length() - 1) : raw;
        this.bucket = config.require("bucket");
        this.accessKey = config.require("accessKey");
        this.secretKey = config.require("secretKey");
        this.basePath = StoragePaths.normalize(config.getOr("basePath", ""));
        this.pathStyle = config.boolOr("pathStyle", false);
        this.partSize = Math.max(100L * 1024L, config.longOr("partSize", 8L * 1024L * 1024L));
        this.multipartThreshold = Math.max(this.partSize,
                config.longOr("multipartThreshold", 64L * 1024L * 1024L));
    }

    @Override
    public String platform() {
        return "oss";
    }

    @Override
    public boolean exists(String path) {
        String normalized = StoragePaths.normalize(path);
        if (normalized.isEmpty()) {
            return true;
        }
        if (head(normalized) < 400) {
            return true;
        }
        if (head(normalized + "/") < 400) {
            return true;
        }
        return !listRaw(normalized + "/", 1).isEmpty();
    }

    @Override
    public StorageEntry stat(String path) {
        String normalized = StoragePaths.normalize(path);
        if (normalized.isEmpty()) {
            return StorageEntry.dir("", 0L);
        }
        HttpCore.Response response = request("HEAD", normalized, null, null, null, -1L, null);
        if (response.status() < 400) {
            StorageEntry entry = StorageEntry.file(normalized, lengthOf(response),
                    metaMtimeOr(response, lastModifiedOf(response)), response.header("ETag"));
            response.close();
            return entry;
        }
        response.close();
        response = request("HEAD", normalized + "/", null, null, null, -1L, null);
        if (response.status() < 400) {
            StorageEntry entry = StorageEntry.dir(normalized, lastModifiedOf(response));
            response.close();
            return entry;
        }
        response.close();
        throw StorageException.notFound(normalized);
    }

    @Override
    public List<StorageEntry> list(String dir) {
        return listRaw(dir, Integer.MAX_VALUE);
    }

    @Override
    public void mkdir(String dir) {
        String normalized = StoragePaths.normalize(dir);
        if (normalized.isEmpty() || exists(normalized)) {
            return;
        }
        HttpCore.Response response = request("PUT", normalized + "/", null, null,
                new byte[0], -1L, null);
        try {
            ensureSuccess(response, normalized);
        } finally {
            response.close();
        }
    }

    @Override
    public void upload(String path, InputStream in, long size) {
        upload(path, in, size, null);
    }

    @Override
    public void upload(String path, InputStream in, long size, UploadMetadata meta) {
        String normalized = requireFile(path);
        String contentType = MimeTypes.of(normalized);
        if (meta != null && meta.contentType() != null) {
            contentType = meta.contentType();
        }
        boolean knownBig = size >= 0 && size > multipartThreshold;
        if (size >= 0 && !knownBig) {
            Map<String, String> extra = new LinkedHashMap<String, String>();
            if (meta != null && meta.hasLastModified()) {
                extra.put("x-oss-meta-mtime", String.valueOf(meta.lastModified()));
            }
            HttpCore.Response response = request("PUT", normalized, null, contentType,
                    null, size, in, extra);
            try {
                ensureSuccess(response, normalized);
            } finally {
                response.close();
            }
            return;
        }
        if (size < 0) {
            byte[] first = readChunk(in, partSize);
            if (first.length < partSize) {
                HttpCore.Response response = request("PUT", normalized, null, contentType,
                        first, -1L, null);
                try {
                    ensureSuccess(response, normalized);
                } finally {
                    response.close();
                }
                return;
            }
            multipart(normalized, contentType, first, in, null);
            return;
        }
        multipart(normalized, contentType, null, in, size);
    }

    private static byte[] readChunk(InputStream in, long limit) {
        try {
            java.io.ByteArrayOutputStream out =
                    new java.io.ByteArrayOutputStream((int) Math.min(limit, Integer.MAX_VALUE));
            byte[] chunk = new byte[8192];
            long total = 0L;
            int read;
            while (total < limit && (read = in.read(chunk, 0,
                    (int) Math.min(chunk.length, limit - total))) >= 0) {
                out.write(chunk, 0, read);
                total += read;
            }
            return out.toByteArray();
        } catch (java.io.IOException e) {
            throw StorageException.io("读取上传流失败: " + e.getMessage(), "-", e);
        }
    }

    /** Multipart 上传：firstPart 非空表示已读出的首片，否则按已知 totalSize 分片。 */
    private void multipart(String path, String contentType, byte[] firstPart,
                           InputStream in, Long totalSize) {
        String key = objectKey(path);
        String uploadId = initiateMultipart(path, key);
        List<String> etags = new ArrayList<String>();
        try {
            int partNumber = 1;
            if (firstPart != null) {
                etags.add(uploadPart(path, key, uploadId, partNumber++,
                        new java.io.ByteArrayInputStream(firstPart), firstPart.length));
            }
            long uploaded = firstPart == null ? 0L : firstPart.length;
            while (totalSize == null || uploaded < totalSize) {
                long limit = partSize;
                if (totalSize != null) {
                    limit = Math.min(partSize, totalSize - uploaded);
                }
                byte[] part = readChunk(in, limit);
                if (part.length == 0) {
                    break;
                }
                etags.add(uploadPart(path, key, uploadId, partNumber++,
                        new java.io.ByteArrayInputStream(part), part.length));
                uploaded += part.length;
                if (totalSize == null && part.length < partSize) {
                    break;
                }
            }
            completeMultipart(path, key, uploadId, etags);
        } catch (RuntimeException e) {
            abortMultipart(key, uploadId);
            throw e;
        }
    }

    private String initiateMultipart(String normalized, String key) {
        Map<String, String> query = new TreeMap<String, String>();
        query.put("uploads", "");
        HttpCore.Response response = request("POST", key, query, null, new byte[0], -1L, null);
        try {
            ensureSuccess(response, normalized);
            Element root = Xmls.parse(Xmls.readAll(response.body()));
            String uploadId = Xmls.textOr(root, "UploadId", null);
            if (uploadId == null || uploadId.isEmpty()) {
                throw StorageException.serverError("Multipart 初始化未返回 UploadId", normalized);
            }
            return uploadId;
        } finally {
            response.close();
        }
    }

    private String uploadPart(String normalized, String key, String uploadId, int partNumber,
                              InputStream in, long length) {
        Map<String, String> query = new TreeMap<String, String>();
        query.put("partNumber", String.valueOf(partNumber));
        query.put("uploadId", uploadId);
        HttpCore.Response response = request("PUT", key, query, null, null, length, in);
        try {
            ensureSuccess(response, normalized);
            String etag = response.header("ETag");
            if (etag == null || etag.isEmpty()) {
                throw StorageException.serverError("分片 " + partNumber + " 未返回 ETag", normalized);
            }
            return etag;
        } finally {
            response.close();
        }
    }

    private void completeMultipart(String normalized, String key, String uploadId,
                                   List<String> etags) {
        StringBuilder xml = new StringBuilder("<CompleteMultipartUpload>");
        for (int i = 0; i < etags.size(); i++) {
            xml.append("<Part><PartNumber>").append(i + 1).append("</PartNumber><ETag>")
                    .append(etags.get(i)).append("</ETag></Part>");
        }
        xml.append("</CompleteMultipartUpload>");
        Map<String, String> query = new TreeMap<String, String>();
        query.put("uploadId", uploadId);
        HttpCore.Response response = request("POST", key, query, "application/xml",
                xml.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8), -1L, null);
        try {
            ensureSuccess(response, normalized);
        } finally {
            response.close();
        }
    }

    private void abortMultipart(String key, String uploadId) {
        Map<String, String> query = new TreeMap<String, String>();
        query.put("uploadId", uploadId);
        try {
            HttpCore.Response response = request("DELETE", key, query, null, null, -1L, null);
            response.close();
        } catch (RuntimeException ignore) {
            // 中止失败不影响原错误
        }
    }

    @Override
    public void download(String path, OutputStream out) {
        String normalized = requireFile(path);
        HttpCore.Response response = request("GET", normalized, null, null, null, -1L, null);
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
        } catch (java.io.IOException e) {
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
        if (!listRaw(normalized + "/", 2).isEmpty()) {
            throw StorageException.conflict("目录非空: " + normalized + "，请用 deleteDir", normalized);
        }
        deleteQuietly(objectKey(normalized));
        // 目录占位对象（key 以 / 结尾）一并清理，保证 deleteDir 后目录真正消失
        deleteQuietly(objectKey(normalized) + "/");
    }

    @Override
    public void copy(String src, String dst) {
        String source = requireFile(src);
        String target = requireFile(dst);
        if (exists(source + "/") && !exists(source)) {
            copyDir(source, target);
            return;
        }
        copyObject(objectKey(source), objectKey(target));
    }

    @Override
    public void rename(String src, String dst) {
        String source = requireFile(src);
        String target = requireFile(dst);
        if (exists(source + "/") && !exists(source)) {
            copyDir(source, target);
            deleteDir(source);
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

    // ------------------------------------------------------------------ 列表

    private List<StorageEntry> listRaw(String dir, int limit) {
        String prefix = StoragePaths.normalize(dir);
        String fullPrefix = objectKey(prefix);
        if (!fullPrefix.isEmpty() && !fullPrefix.endsWith("/")) {
            fullPrefix = fullPrefix + "/";
        }
        List<StorageEntry> entries = new java.util.ArrayList<StorageEntry>();
        String marker = null;
        int pages = 0;
        while (pages++ < MAX_LIST_PAGES) {
            Map<String, String> query = new TreeMap<String, String>();
            query.put("prefix", fullPrefix);
            query.put("delimiter", "/");
            query.put("max-keys", "1000");
            if (marker != null && !marker.isEmpty()) {
                query.put("marker", marker);
            }
            HttpCore.Response response = request("GET", "", query, null, null, -1L, null);
            ensureSuccess(response, prefix);
            String xml = Xmls.readAll(response.body());
            response.close();
            Element root = Xmls.parse(xml);
            boolean truncated = Boolean.parseBoolean(Xmls.textOr(root, "IsTruncated", "false"));
            for (Element content : Xmls.children(root, "Contents")) {
                String key = Xmls.textOr(content, "Key", "");
                if (key.equals(fullPrefix)) {
                    continue;
                }
                String relative = relativeOf(key, fullPrefix);
                if (relative.isEmpty()) {
                    continue;
                }
                if (key.endsWith("/")) {
                    entries.add(StorageEntry.dir(StoragePaths.join(prefix, relative)));
                } else {
                    entries.add(StorageEntry.file(StoragePaths.join(prefix, relative),
                            parseLong(Xmls.textOr(content, "Size", "0")),
                            parseIso8601(Xmls.textOr(content, "LastModified", null)),
                            Xmls.textOr(content, "ETag", null)));
                }
            }
            for (Element common : Xmls.children(root, "CommonPrefixes")) {
                String key = Xmls.textOr(common, "Prefix", "");
                String relative = relativeOf(key, fullPrefix);
                if (relative.endsWith("/")) {
                    relative = relative.substring(0, relative.length() - 1);
                }
                if (!relative.isEmpty()) {
                    entries.add(StorageEntry.dir(StoragePaths.join(prefix, relative)));
                }
            }
            if (entries.size() >= limit || !truncated) {
                break;
            }
            marker = Xmls.textOr(root, "NextMarker", null);
            if (marker == null || marker.isEmpty()) {
                List<Element> contents = Xmls.children(root, "Contents");
                marker = contents.isEmpty() ? null
                        : Xmls.textOr(contents.get(contents.size() - 1), "Key", null);
                if (marker == null) {
                    break;
                }
            }
        }
        return entries;
    }

    private static String relativeOf(String key, String fullPrefix) {
        if (!fullPrefix.isEmpty() && key.startsWith(fullPrefix)) {
            return key.substring(fullPrefix.length());
        }
        return key;
    }

    // ------------------------------------------------------------------ 请求

    private String objectKey(String path) {
        String normalized = StoragePaths.normalize(path);
        String key = basePath.isEmpty() ? normalized
                : (basePath + (normalized.isEmpty() ? "" : "/" + normalized));
        // 目录占位对象以 "/" 结尾（exists/mkdir 会显式传入带斜杠的路径），
        // normalize 会剥掉尾斜杠，这里按原始输入补回
        if (!key.isEmpty() && !key.endsWith("/") && path != null
                && path.replace('\\', '/').endsWith("/")) {
            key = key + "/";
        }
        return key;
    }

    private int head(String path) {
        HttpCore.Response response = request("HEAD", path, null, null, null, -1L, null);
        int status = response.status();
        response.close();
        return status;
    }

    private HttpCore.Response request(String method, String path, Map<String, String> query,
                                      String contentType, byte[] body, long streamLength,
                                      InputStream stream) {
        return request(method, path, query, contentType, body, streamLength, stream, null);
    }

    private HttpCore.Response request(String method, String path, Map<String, String> query,
                                      String contentType, byte[] body, long streamLength,
                                      InputStream stream, Map<String, String> extraHeaders) {
        String key = objectKey(path);
        String encodedKey = Urls.encodePath(key);
        String url = pathStyle
                ? endpoint + "/" + bucket + (encodedKey.isEmpty() ? "" : "/" + encodedKey)
                : endpoint.replace("://", "://" + bucket + ".")
                        + (encodedKey.isEmpty() ? "/" : "/" + encodedKey);
        url = buildUrl(url, query);
        String date = AwsSigners.httpDateNow();
        Map<String, String> ossHeaders = new LinkedHashMap<String, String>();
        if (extraHeaders != null) {
            ossHeaders.putAll(extraHeaders);
        }
        StringBuilder canonical = new StringBuilder();
        for (Map.Entry<String, String> entry : ossHeaders.entrySet()) {
            canonical.append(entry.getKey()).append(':').append(entry.getValue()).append('\n');
        }
        canonical.append('/').append(bucket);
        if (!key.isEmpty()) {
            canonical.append('/').append(key);
        }
        if (query != null && (query.containsKey("uploadId") || query.containsKey("partNumber"))) {
            canonical.append('?');
            boolean first = true;
            for (Map.Entry<String, String> entry : new TreeMap<String, String>(java.util.Collections
                    .unmodifiableMap(query)).entrySet()) {
                if (!entry.getKey().equals("partNumber") && !entry.getKey().equals("uploadId")) {
                    continue;
                }
                if (!first) {
                    canonical.append('&');
                }
                first = false;
                canonical.append(entry.getKey()).append('=').append(entry.getValue());
            }
        }
        String toSign = method + "\n\n" + (contentType == null ? "" : contentType) + "\n"
                + date + "\n" + canonical;
        String signature = AwsSigners.base64(
                AwsSigners.hmacSha1(secretKey.getBytes(java.nio.charset.StandardCharsets.UTF_8), toSign));
        HttpCore.Request request = new HttpCore.Request(method, url);
        request.header("Date", date);
        request.header("Authorization", "OSS " + accessKey + ":" + signature);
        if (contentType != null) {
            request.header("Content-Type", contentType);
        }
        for (Map.Entry<String, String> entry : ossHeaders.entrySet()) {
            request.header(entry.getKey(), entry.getValue());
        }
        if (body != null) {
            request.body(body);
        } else if (stream != null) {
            request.body(stream, streamLength);
        }
        return HttpCore.execute(HttpCore.configured(request, config));
    }

    private String buildUrl(String base, Map<String, String> query) {
        if (query == null || query.isEmpty()) {
            return base;
        }
        StringBuilder builder = new StringBuilder(base);
        boolean first = true;
        for (Map.Entry<String, String> entry : query.entrySet()) {
            builder.append(first ? '?' : '&');
            first = false;
            builder.append(Urls.encodeQuery(entry.getKey())).append('=')
                    .append(Urls.encodeQuery(entry.getValue()));
        }
        return builder.toString();
    }

    private void copyObject(String srcKey, String dstKey) {
        Map<String, String> extra = new LinkedHashMap<String, String>();
        extra.put("x-oss-copy-source", "/" + bucket + "/" + Urls.encodePath(srcKey));
        HttpCore.Response response = request("PUT", dstKey, null, MimeTypes.of(dstKey),
                new byte[0], -1L, null, extra);
        try {
            ensureSuccess(response, dstKey);
        } finally {
            response.close();
        }
    }

    /** 幂等删除：404 / 204 / 200 都算成功，其它状态抛出。 */
    private void deleteQuietly(String key) {
        HttpCore.Response response = request("DELETE", key, null, null, null, -1L, null);
        int status = response.status();
        if (status < 400) {
            response.close();
            return;
        }
        String body = Xmls.readAll(response.body());
        response.close();
        if (status == 404) {
            return;
        }
        throw toException(status, body, key);
    }

    private String requireFile(String path) {
        String normalized = StoragePaths.normalize(path);
        if (normalized.isEmpty()) {
            throw StorageException.invalidConfig("路径不能为存储根目录");
        }
        return normalized;
    }

    /** 同步保留的 mtime 存在 x-oss-meta-mtime，优先于服务端 Last-Modified。 */
    private static long metaMtimeOr(HttpCore.Response response, long fallback) {
        String meta = response.header("x-oss-meta-mtime");
        if (meta == null) {
            return fallback;
        }
        try {
            return Long.parseLong(meta.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static long lengthOf(HttpCore.Response response) {
        String length = response.header("Content-Length");
        if (length == null) {
            return StorageEntry.SIZE_UNKNOWN;
        }
        return parseLong(length);
    }

    private static long lastModifiedOf(HttpCore.Response response) {
        String date = response.header("Last-Modified");
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
        try {
            return Long.parseLong(value.trim());
        } catch (NumberFormatException e) {
            return 0L;
        }
    }

    private static long parseIso8601(String value) {
        if (value == null) {
            return StorageEntry.MTIME_UNKNOWN;
        }
        try {
            String normalized = value.trim().replaceAll("\\.\\d+", "");
            if (normalized.endsWith("Z")) {
                normalized = normalized.substring(0, normalized.length() - 1) + "+0000";
            }
            java.text.SimpleDateFormat format =
                    new java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssZ");
            return format.parse(normalized).getTime();
        } catch (java.text.ParseException e) {
            return StorageEntry.MTIME_UNKNOWN;
        }
    }

    private void ensureSuccess(HttpCore.Response response, String path) {
        if (response.status() < 400) {
            return;
        }
        int status = response.status();
        String body = Xmls.readAll(response.body());
        response.close();
        throw toException(status, body, path);
    }

    private StorageException toException(int status, String body, String path) {
        StorageErrorCode byStatus = status >= 500 ? StorageErrorCode.SERVER_ERROR
                : status == 404 ? StorageErrorCode.NOT_FOUND
                : status == 403 || status == 401 ? StorageErrorCode.AUTH_FAILED
                : status == 409 ? StorageErrorCode.CONFLICT
                : status == 429 ? StorageErrorCode.RATE_LIMITED
                : StorageErrorCode.IO_ERROR;
        try {
            Element error = Xmls.parse(body);
            String code = Xmls.textOr(error, "Code", null);
            String message = Xmls.textOr(error, "Message", null);
            if (message != null) {
                return new StorageException(byStatus, message + "（" + code + "）", path);
            }
        } catch (RuntimeException ignore) {
            // 非 XML 错误体，退回状态码语义
        }
        return new StorageException(byStatus, "OSS HTTP " + status, path);
    }
}
