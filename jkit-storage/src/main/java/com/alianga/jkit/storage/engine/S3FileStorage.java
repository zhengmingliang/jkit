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

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * S3 协议引擎：一套实现覆盖 Amazon S3 与所有 S3 兼容云（MinIO、Cloudflare R2、
 * 华为 OBS / 腾讯 COS / 七牛 Kodo / 百度 BOS / 又拍 USS / 火山 TOS / IBM COS 等 20+）。
 *
 * <p>能力：SigV4 与 SigV2 双签名（{@code authStyle=v4|v2|ksyun}）、虚拟主机与路径
 * 两种寻址（{@code pathStyle}）、ListObjects V1/V2、目录占位对象、服务端复制（rename =
 * copy + delete）。单次上传用 PUT（≤5GB）；更大文件请分片后按路径上传。</p>
 *
 * @author 郑明亮
 */
public class S3FileStorage implements FileStorage {
    /** 分页拉取全量列表时的最大页数（每页 1000 条，防御性上限）。 */
    private static final int MAX_LIST_PAGES = 500;

    private final StorageConfig config;
    private final String endpoint;
    private final String bucket;
    private final String accessKey;
    private final String secretKey;
    private final String region;
    private final boolean pathStyle;
    private final String basePath;
    private final boolean listV2;
    private final String authPrefix;
    /** 单片阈值：超过该大小走 Multipart（默认 64MB）。 */
    private final long multipartThreshold;
    /** 分片大小（默认 8MB，S3 规范最小 5MB）。 */
    private final long partSize;

    /**
     * 由配置创建（配置键：endpoint / bucket / accessKey / secretKey / region /
     * pathStyle / basePath / listVersion / authStyle / md5Secret）。
     *
     * @param config 连接配置
     */
    public S3FileStorage(StorageConfig config) {
        this.config = config;
        String raw = config.require("endpoint");
        if (!raw.startsWith("http://") && !raw.startsWith("https://")) {
            raw = "https://" + raw;
        }
        this.endpoint = raw.endsWith("/") ? raw.substring(0, raw.length() - 1) : raw;
        this.bucket = config.require("bucket");
        this.accessKey = config.require("accessKey");
        String secret = config.require("secretKey");
        this.secretKey = config.boolOr("md5Secret", false)
                ? AwsSigners.md5Hex(secret.getBytes(java.nio.charset.StandardCharsets.UTF_8)) : secret;
        this.region = config.getOr("region", "us-east-1");
        this.pathStyle = config.boolOr("pathStyle", false);
        this.basePath = StoragePaths.normalize(config.getOr("basePath", ""));
        String listVersion = config.getOr("listVersion", "1");
        this.listV2 = "2".equals(listVersion);
        String authStyle = config.getOr("authStyle", "v4");
        this.authPrefix = "ksyun".equalsIgnoreCase(authStyle) ? "KSYUN"
                : "v2".equalsIgnoreCase(authStyle) ? "AWS" : null;
        this.partSize = Math.max(5L * 1024L * 1024L, config.longOr("partSize", 8L * 1024L * 1024L));
        this.multipartThreshold = Math.max(this.partSize,
                config.longOr("multipartThreshold", 64L * 1024L * 1024L));
    }

    @Override
    public String platform() {
        return "s3";
    }

    @Override
    public boolean exists(String path) {
        String normalized = StoragePaths.normalize(path);
        if (normalized.isEmpty()) {
            return true;
        }
        String key = objectKey(normalized);
        if (request("HEAD", key, null, null, null, -1L, null).status() < 400) {
            return true;
        }
        if (request("HEAD", key + "/", null, null, null, -1L, null).status() < 400) {
            return true;
        }
        return !listRaw(normalized + "/", 1, null).entries.isEmpty();
    }

    @Override
    public StorageEntry stat(String path) {
        String normalized = StoragePaths.normalize(path);
        if (normalized.isEmpty()) {
            return StorageEntry.dir("", 0L);
        }
        String key = objectKey(normalized);
        HttpCore.Response response = request("HEAD", key, null, null, null, -1L, null);
        if (response.status() < 400) {
            return StorageEntry.file(normalized, lengthOf(response),
                    metaMtimeOr(response, lastModifiedOf(response)), response.header("ETag"));
        }
        response = request("HEAD", key + "/", null, null, null, -1L, null);
        if (response.status() < 400) {
            return StorageEntry.dir(normalized, lastModifiedOf(response));
        }
        throw StorageException.notFound(normalized);
    }

    @Override
    public List<StorageEntry> list(String dir) {
        return listRaw(dir, Integer.MAX_VALUE, null).entries;
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
            putObject(normalized, contentType, in, size, meta);
            return;
        }
        if (size < 0) {
            // 未知长度：读出一个分片，单片则退回普通 PUT，多片走流式 Multipart
            byte[] first = readChunk(in, partSize);
            if (first.length < partSize) {
                putObjectBytes(normalized, contentType, first, meta);
                return;
            }
            multipart(normalized, contentType, first, in, null);
            return;
        }
        multipart(normalized, contentType, null, in, size);
    }

    private void putObject(String normalized, String contentType, InputStream in, long size,
                           UploadMetadata meta) {
        HttpCore.Response response = request("PUT", objectKey(normalized), null, contentType,
                null, size, null, in, metaHeaders(meta));
        try {
            ensureSuccess(response, normalized);
        } finally {
            response.close();
        }
    }

    private void putObjectBytes(String normalized, String contentType, byte[] data,
                                UploadMetadata meta) {
        HttpCore.Response response = request("PUT", objectKey(normalized), null, contentType,
                data, -1L, null, null, metaHeaders(meta));
        try {
            ensureSuccess(response, normalized);
        } finally {
            response.close();
        }
    }

    /** mtime 保留：S3 用户元数据 x-amz-meta-mtime。 */
    private Map<String, String> metaHeaders(UploadMetadata meta) {
        Map<String, String> extra = new LinkedHashMap<String, String>();
        if (meta != null && meta.hasLastModified()) {
            extra.put("x-amz-meta-mtime", String.valueOf(meta.lastModified()));
        }
        return extra;
    }

    /** 读取至多 limit 字节（阻塞到读满或 EOF）。 */
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
        } catch (IOException e) {
            throw StorageException.io("读取上传流失败: " + e.getMessage(), "-", e);
        }
    }

    /**
     * Multipart 上传：firstPart 非空表示已读出的首片（流式未知长度），
     * 否则按已知 totalSize 均匀分片。
     */
    private void multipart(String path, String contentType, byte[] firstPart,
                           InputStream in, Long totalSize) {
        String normalized = path;
        String key = objectKey(normalized);
        String uploadId = initiateMultipart(normalized, key);
        List<String> etags = new ArrayList<String>();
        try {
            int partNumber = 1;
            if (firstPart != null) {
                etags.add(uploadPart(normalized, key, uploadId, partNumber++,
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
                etags.add(uploadPart(normalized, key, uploadId, partNumber++,
                        new java.io.ByteArrayInputStream(part), part.length));
                uploaded += part.length;
                if (totalSize == null && part.length < partSize) {
                    break;
                }
            }
            completeMultipart(normalized, key, uploadId, etags);
        } catch (RuntimeException e) {
            abortMultipart(key, uploadId);
            throw e;
        }
    }

    private String initiateMultipart(String normalized, String key) {
        Map<String, String> query = new LinkedHashMap<String, String>();
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
        Map<String, String> query = new LinkedHashMap<String, String>();
        query.put("partNumber", String.valueOf(partNumber));
        query.put("uploadId", uploadId);
        HttpCore.Response response = request("PUT", key, query, null, null, length, null, in);
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
        Map<String, String> query = new LinkedHashMap<String, String>();
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
        Map<String, String> query = new LinkedHashMap<String, String>();
        query.put("uploadId", uploadId);
        try {
            closeQuietly(request("DELETE", key, query, null, null, -1L, null));
        } catch (RuntimeException ignore) {
            // 中止失败不影响原错误
        }
    }

    @Override
    public void download(String path, OutputStream out) {
        String normalized = requireFile(path);
        HttpCore.Response response = request("GET", objectKey(normalized), null, null, null, -1L, null);
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
        String prefix = objectKey(normalized) + "/";
        ListPage children = listRaw(prefix, 2, null);
        if (!children.entries.isEmpty()) {
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
        if (isDirectoryKey(objectKey(source) + "/")) {
            copyDir(source, target);
            return;
        }
        copyObject(objectKey(source), objectKey(target));
    }

    @Override
    public void rename(String src, String dst) {
        String source = requireFile(src);
        String target = requireFile(dst);
        if (isDirectoryKey(objectKey(source) + "/")) {
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
        HttpCore.Response response = request("PUT", dstKey, null, MimeTypes.of(dstKey),
                new byte[0], -1L, srcKey);
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

    private boolean isDirectoryKey(String dirKey) {
        HttpCore.Response response = request("HEAD", dirKey, null, null, null, -1L, null);
        response.close();
        if (response.status() < 400) {
            return true;
        }
        return !listRaw(dirKey, 1, null).entries.isEmpty();
    }

    // ------------------------------------------------------------------ 列表

    private ListPage listRaw(String dir, int limit, String startAfter) {
        String prefix = StoragePaths.normalize(dir);
        String fullPrefix = objectKey(prefix);
        if (!fullPrefix.isEmpty() && !fullPrefix.endsWith("/")) {
            fullPrefix = fullPrefix + "/";
        }
        List<StorageEntry> entries = new ArrayList<StorageEntry>();
        String token = startAfter;
        int pages = 0;
        while (pages++ < MAX_LIST_PAGES) {
            Map<String, String> query = new TreeMap<String, String>();
            query.put("prefix", fullPrefix);
            query.put("delimiter", "/");
            query.put("max-keys", "1000");
            if (listV2) {
                query.put("list-type", "2");
                if (token != null && !token.isEmpty()) {
                    query.put("continuation-token", token);
                }
            } else if (token != null && !token.isEmpty()) {
                query.put("marker", token);
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
                long size = parseLong(Xmls.textOr(content, "Size", "0"));
                if (key.endsWith("/")) {
                    entries.add(StorageEntry.dir(StoragePaths.join(prefix, relative)));
                } else {
                    entries.add(StorageEntry.file(StoragePaths.join(prefix, relative), size,
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
                if (!relative.isEmpty() && !containsDir(entries, prefix, relative)) {
                    entries.add(StorageEntry.dir(StoragePaths.join(prefix, relative)));
                }
            }
            if (entries.size() >= limit) {
                break;
            }
            if (!truncated) {
                break;
            }
            token = Xmls.textOr(root, "NextContinuationToken", Xmls.textOr(root, "NextMarker", null));
            if (token == null || token.isEmpty()) {
                String lastKey = lastKeyOf(root);
                if (lastKey == null) {
                    break;
                }
                token = lastKey;
            }
        }
        return new ListPage(entries, token);
    }

    private static boolean containsDir(List<StorageEntry> entries, String prefix, String relative) {
        String path = StoragePaths.join(prefix, relative);
        for (StorageEntry entry : entries) {
            if (entry.path().equals(path)) {
                return true;
            }
        }
        return false;
    }

    private static String lastKeyOf(Element root) {
        List<Element> contents = Xmls.children(root, "Contents");
        if (contents.isEmpty()) {
            return null;
        }
        return Xmls.textOr(contents.get(contents.size() - 1), "Key", null);
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
        if (basePath.isEmpty()) {
            return normalized;
        }
        return basePath + (normalized.isEmpty() ? "" : "/" + normalized);
    }

    private HttpCore.Response request(String method, String key, Map<String, String> query,
                                      String contentType, byte[] body, long streamLength,
                                      String copySource) {
        return request(method, key, query, contentType, body, streamLength, copySource, null);
    }

    private HttpCore.Response request(String method, String key, Map<String, String> query,
                                      String contentType, byte[] body, long streamLength,
                                      String copySource, InputStream stream) {
        return request(method, key, query, contentType, body, streamLength, copySource, stream, null);
    }

    private HttpCore.Response request(String method, String key, Map<String, String> query,
                                      String contentType, byte[] body, long streamLength,
                                      String copySource, InputStream stream,
                                      Map<String, String> extraXamz) {
        String encodedPath = Urls.encodePath(key);
        String urlPath = pathStyle ? "/" + bucket + (encodedPath.isEmpty() ? "" : "/" + encodedPath)
                : (encodedPath.isEmpty() ? "/" : "/" + encodedPath);
        String host = hostOf();
        String url = endpoint + urlPath + buildQuery(query);
        Map<String, String> headers = new LinkedHashMap<String, String>();
        if (contentType != null) {
            headers.put("Content-Type", contentType);
        }
        if (copySource != null) {
            headers.put("x-amz-copy-source", "/" + bucket + "/" + Urls.encodePath(copySource));
        }
        if (extraXamz != null) {
            for (Map.Entry<String, String> entry : extraXamz.entrySet()) {
                headers.put(entry.getKey(), entry.getValue());
            }
        }
        if (authPrefix == null) {
            String amzDate = AwsSigners.amzDateNow();
            String payloadHash = body != null
                    ? AwsSigners.sha256Hex(body) : AwsSigners.UNSIGNED_PAYLOAD;
            headers.put("x-amz-date", amzDate);
            headers.put("x-amz-content-sha256", payloadHash);
            // 规范 URI 必须与实际请求的路径逐字节一致（不能多出尾斜杠），
            // 否则真实 S3 服务端按收到的 URI 重算签名必然不匹配
            String authorization = AwsSigners.sigV4(method, host, urlPath, query,
                    headers, payloadHash, accessKey, secretKey, region, amzDate);
            headers.put("Authorization", authorization);
        } else {
            String date = AwsSigners.httpDateNow();
            headers.put("Date", date);
            Map<String, String> amzHeaders = new LinkedHashMap<String, String>();
            for (Map.Entry<String, String> entry : headers.entrySet()) {
                if (entry.getKey().toLowerCase().startsWith("x-amz-")) {
                    amzHeaders.put(entry.getKey(), entry.getValue());
                }
            }
            String keyPath = pathStyle ? bucket + "/" + key : key;
            Map<String, String> subResources = null;
            if (query != null && (query.containsKey("uploadId") || query.containsKey("partNumber"))) {
                subResources = new LinkedHashMap<String, String>();
                if (query.containsKey("partNumber")) {
                    subResources.put("partNumber", query.get("partNumber"));
                }
                if (query.containsKey("uploadId")) {
                    subResources.put("uploadId", query.get("uploadId"));
                }
            }
            String authorization = AwsSigners.sigV2(authPrefix, accessKey, secretKey, method,
                    contentType, date, amzHeaders, bucket, keyPath, subResources);
            headers.put("Authorization", authorization);
        }
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

    private String hostOf() {
        try {
            java.net.URL url = new java.net.URL(endpoint);
            String effectiveHost = pathStyle ? url.getHost() : bucket + "." + url.getHost();
            return url.getPort() > 0 ? effectiveHost + ":" + url.getPort() : effectiveHost;
        } catch (java.net.MalformedURLException e) {
            throw StorageException.invalidConfig("endpoint 非法: " + endpoint);
        }
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
                : status == 403 || status == 401 ? StorageErrorCode.AUTH_FAILED
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

    private static StorageErrorCode mapCode(String code) {
        switch (code) {
            case "NoSuchKey":
            case "NoSuchBucket":
            case "NotFound":
                return StorageErrorCode.NOT_FOUND;
            case "AccessDenied":
            case "InvalidAccessKeyId":
            case "SignatureDoesNotMatch":
            case "SecurityTokenInvalid":
            case "InvalidSecurityToken":
                return StorageErrorCode.AUTH_FAILED;
            case "BucketAlreadyExists":
            case "BucketAlreadyOwnedByYou":
                return StorageErrorCode.ALREADY_EXISTS;
            case "SlowDown":
            case "Throttling":
            case "RequestThrottled":
                return StorageErrorCode.RATE_LIMITED;
            case "PreconditionFailed":
            case "BucketNotEmpty":
                return StorageErrorCode.CONFLICT;
            case "InternalError":
            case "ServiceUnavailable":
                return StorageErrorCode.SERVER_ERROR;
            default:
                return null;
        }
    }

    private String requireFile(String path) {
        String normalized = StoragePaths.normalize(path);
        if (normalized.isEmpty()) {
            throw StorageException.invalidConfig("路径不能为存储根目录");
        }
        return normalized;
    }

    /** 同步保留的 mtime 存在 x-amz-meta-mtime，优先于服务端 Last-Modified。 */
    private static long metaMtimeOr(HttpCore.Response response, long fallback) {
        String meta = response.header("x-amz-meta-mtime");
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
        return length == null ? StorageEntry.SIZE_UNKNOWN : parseLong(length);
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
            // 2026-10-11T08:00:00.000Z / 2026-10-11T08:00:00Z → 去掉毫秒段后按秒解析
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

    private static void closeQuietly(HttpCore.Response response) {
        if (response != null) {
            response.close();
        }
    }

    /** 一页列表的原始结果。 */
    private static final class ListPage {
        private final List<StorageEntry> entries;
        private final String nextToken;

        private ListPage(List<StorageEntry> entries, String nextToken) {
            this.entries = entries;
            this.nextToken = nextToken;
        }
    }
}
