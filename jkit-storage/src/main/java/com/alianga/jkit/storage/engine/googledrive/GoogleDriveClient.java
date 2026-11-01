package com.alianga.jkit.storage.engine.googledrive;

import com.alianga.jkit.storage.StorageEntry;
import com.alianga.jkit.storage.StorageErrorCode;
import com.alianga.jkit.storage.StorageException;
import com.alianga.jkit.storage.StoragePaths;
import com.alianga.jkit.storage.UploadMetadata;
import com.alianga.jkit.storage.internal.HttpCore;
import com.alianga.jkit.storage.internal.MimeTypes;
import com.alianga.jkit.storage.internal.SimpleJson;
import com.alianga.jkit.storage.internal.SpillBuffer;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.SequenceInputStream;
import java.io.UnsupportedEncodingException;
import java.net.Proxy;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TimeZone;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Google Drive REST API v3 客户端：实现令牌生命周期管理、路径-ID 级联映射与缓存、
 * 文件元数据查询、分页列表、目录创建、分块流式上传、断点续传下载与文件管理。
 *
 * <p>纯 JDK 实现，零第三方库依赖。</p>
 *
 * @author 郑明亮
 */
public class GoogleDriveClient {
    private final String clientId;
    private final String clientSecret;
    private final String redirectUri;
    private final String driveId;
    private final String apiBase;
    private final String uploadBase;
    private final String tokenUrl;
    private final Proxy proxy;

    private volatile String accessToken;
    private volatile String refreshToken;
    private volatile long expiresAt;
    private final ReentrantLock tokenLock = new ReentrantLock();

    /** 路径 → Google Drive 文件/目录 ID 缓存。 */
    private final Map<String, String> pathToIdCache = new ConcurrentHashMap<String, String>();

    public GoogleDriveClient(String clientId, String clientSecret, String redirectUri,
                             String accessToken, String refreshToken, long expiresAt,
                             String driveId,
                             String apiBase, String uploadBase, String tokenUrl, Proxy proxy) {
        this.clientId = clientId == null ? "" : clientId.trim();
        this.clientSecret = clientSecret == null ? "" : clientSecret.trim();
        this.redirectUri = redirectUri == null || redirectUri.trim().isEmpty()
                ? GoogleDriveConfig.DEFAULT_REDIRECT_URI : redirectUri.trim();
        this.accessToken = accessToken == null ? "" : accessToken.trim();
        this.refreshToken = refreshToken == null ? "" : refreshToken.trim();
        this.expiresAt = expiresAt;
        this.driveId = driveId == null ? "" : driveId.trim();
        this.apiBase = apiBase == null || apiBase.trim().isEmpty()
                ? GoogleDriveConfig.DEFAULT_API_BASE : trimTrailingSlash(apiBase.trim());
        this.uploadBase = uploadBase == null || uploadBase.trim().isEmpty()
                ? GoogleDriveConfig.DEFAULT_UPLOAD_BASE : trimTrailingSlash(uploadBase.trim());
        this.tokenUrl = tokenUrl == null || tokenUrl.trim().isEmpty()
                ? GoogleDriveConfig.DEFAULT_TOKEN_URL : tokenUrl.trim();
        this.proxy = proxy;
    }

    private static String trimTrailingSlash(String str) {
        return str.endsWith("/") ? str.substring(0, str.length() - 1) : str;
    }

    public String clientId() {
        return clientId;
    }

    public String clientSecret() {
        return clientSecret;
    }

    public String accessToken() {
        return accessToken;
    }

    public String refreshToken() {
        return refreshToken;
    }

    public long expiresAt() {
        return expiresAt;
    }

    public String driveId() {
        return driveId;
    }

    /**
     * 确保拥有有效的访问令牌。若令牌即将过期且存在 refresh_token，则自动刷新。
     */
    public String ensureAccessToken() {
        if (!accessToken.isEmpty() && System.currentTimeMillis() < expiresAt - 60_000L) {
            return accessToken;
        }
        tokenLock.lock();
        try {
            if (!accessToken.isEmpty() && System.currentTimeMillis() < expiresAt - 60_000L) {
                return accessToken;
            }
            if (!refreshToken.isEmpty() && !clientId.isEmpty() && !clientSecret.isEmpty()) {
                refreshAccessToken();
                return accessToken;
            }
            if (!accessToken.isEmpty()) {
                // 没有 refreshToken 但有 accessToken，尝试继续使用
                return accessToken;
            }
            throw StorageException.authFailed(
                    "Google Drive 访问令牌为空或已过期，且缺少刷新令牌 (refreshToken)，请重新进行授权");
        } finally {
            tokenLock.unlock();
        }
    }

    /** 强制刷新 access_token。 */
    public void refreshAccessToken() {
        tokenLock.lock();
        try {
            if (refreshToken.isEmpty() || clientId.isEmpty() || clientSecret.isEmpty()) {
                throw StorageException.authFailed(
                        "无法自动刷新 Google Drive 令牌: 缺少 clientId、clientSecret 或 refreshToken");
            }
            GoogleDriveOAuth.TokenResult result = GoogleDriveOAuth.requestToken(
                    tokenUrl, clientId, clientSecret,
                    "grant_type=refresh_token"
                            + "&client_id=" + urlEncode(clientId)
                            + "&client_secret=" + urlEncode(clientSecret)
                            + "&refresh_token=" + urlEncode(refreshToken),
                    refreshToken, proxy);
            this.accessToken = result.accessToken();
            if (result.refreshToken() != null && !result.refreshToken().isEmpty()) {
                this.refreshToken = result.refreshToken();
            }
            this.expiresAt = result.expiresAtMillis();
        } finally {
            tokenLock.unlock();
        }
    }

    /**
     * 执行 HTTP 请求：自动注入 Bearer 令牌；持有刷新凭据且遇 401 时自动刷新，
     * 并按工厂重建请求重试一次（重建保证请求头与请求体不丢）。
     *
     * <p>请求体是一次性流的场景（multipart 上传）不可重建：{@code retryOn401}
     * 传 false，401 时刷新令牌后抛认证异常，引导用户重试整个操作。</p>
     *
     * @param factory    请求工厂（重试时重新构造完整请求）
     * @param retryOn401 true 表示 401 后自动重试
     * @return 响应（调用方负责关闭）
     */
    public HttpCore.Response executeAuthed(java.util.function.Supplier<HttpCore.Request> factory,
                                           boolean retryOn401) {
        HttpCore.Response response = HttpCore.execute(prepare(factory.get()));
        if (response.status() == 401 && canRefresh()) {
            closeQuietly(response);
            refreshAccessToken();
            if (retryOn401) {
                return HttpCore.execute(prepare(factory.get()));
            }
            throw StorageException.authFailed(
                    "Google Drive 访问令牌已过期，已自动刷新；本次上传的数据流无法重放，请重试该操作");
        }
        return response;
    }

    private HttpCore.Request prepare(HttpCore.Request request) {
        request.header("Authorization", "Bearer " + ensureAccessToken());
        request.timeouts(proxy, 15_000, 60_000);
        return request;
    }

    private boolean canRefresh() {
        return !refreshToken.isEmpty() && !clientId.isEmpty() && !clientSecret.isEmpty();
    }

    private static void closeQuietly(HttpCore.Response response) {
        try {
            response.close();
        } catch (Exception ignore) {
            // 忽略关闭失败
        }
    }

    /**
     * 发送 JSON 请求并解析响应对象。遇到 HTTP >= 400 统一抛出规范的 StorageException。
     */
    public Map<String, Object> executeJson(String method, String url, Object bodyObj, String pathContext) {
        final byte[] bodyBytes = bodyObj == null ? null
                : SimpleJson.toJson(bodyObj).getBytes(StandardCharsets.UTF_8);
        java.util.function.Supplier<HttpCore.Request> factory = () -> {
            HttpCore.Request req = new HttpCore.Request(method, url)
                    .header("Accept", "application/json");
            if (bodyBytes != null) {
                req.header("Content-Type", "application/json; charset=UTF-8");
                req.body(bodyBytes);
            }
            return req;
        };

        try (HttpCore.Response response = executeAuthed(factory, true)) {
            String text = response.bodyText();
            int status = response.status();
            Map<String, Object> json = SimpleJson.parseObject(text);

            if (status >= 400) {
                handleApiError(status, text, json, pathContext);
            }
            return json == null ? new LinkedHashMap<String, Object>() : json;
        }
    }

    private void handleApiError(int status, String rawBody, Map<String, Object> json, String pathContext) {
        String msg = extractErrorMessage(json, rawBody);
        if (status == 404) {
            throw new StorageException(StorageErrorCode.NOT_FOUND, "Google Drive 对象不存在: " + msg, pathContext);
        }
        if (status == 401) {
            throw StorageException.authFailed("Google Drive 认证失败或令牌失效 (401): " + msg);
        }
        if (status == 403) {
            if (msg.contains("usageLimits") || msg.contains("userRateLimitExceeded")
                    || msg.contains("rateLimitExceeded")) {
                throw new StorageException(StorageErrorCode.RATE_LIMITED, "Google Drive API 调用频率超限: " + msg, pathContext);
            }
            throw new StorageException(StorageErrorCode.AUTH_FAILED, "Google Drive 访问权限不足 (403): " + msg, pathContext);
        }
        if (status == 409 || status == 412) {
            throw StorageException.conflict("Google Drive 资源状态冲突 (" + status + "): " + msg, pathContext);
        }
        throw new StorageException(StorageErrorCode.IO_ERROR,
                "Google Drive API 调用失败 (HTTP " + status + "): " + msg, pathContext);
    }

    @SuppressWarnings("unchecked")
    private static String extractErrorMessage(Map<String, Object> json, String rawBody) {
        if (json != null && json.get("error") instanceof Map) {
            Map<String, Object> err = (Map<String, Object>) json.get("error");
            Object message = err.get("message");
            if (message != null) {
                return String.valueOf(message);
            }
        }
        return rawBody == null || rawBody.trim().isEmpty() ? "空响应" : rawBody.trim();
    }

    /** 根目录 ID。 */
    public String rootId() {
        return !driveId.isEmpty() ? driveId : "root";
    }

    /**
     * 根据逻辑路径解析 Google Drive 文件/文件夹 ID。
     *
     * @param path 存储相对路径
     * @return 文件/文件夹 ID；若路径中的中间节点不存在则返回 null
     */
    public String resolveId(String path) {
        String normalized = StoragePaths.normalize(path);
        if (normalized.isEmpty()) {
            return rootId();
        }
        String cached = pathToIdCache.get(normalized);
        if (cached != null) {
            return cached;
        }

        String[] segments = normalized.split("/");
        String parentId = rootId();
        StringBuilder acc = new StringBuilder();

        for (int i = 0; i < segments.length; i++) {
            String seg = segments[i];
            if (acc.length() > 0) {
                acc.append('/');
            }
            acc.append(seg);
            String currentPath = acc.toString();

            String subId = pathToIdCache.get(currentPath);
            if (subId == null) {
                subId = queryChildId(parentId, seg, currentPath);
                if (subId == null) {
                    return null;
                }
                pathToIdCache.put(currentPath, subId);
            }
            parentId = subId;
        }
        return parentId;
    }

    /**
     * 查询指定父目录下名为 childName 的未放入回收站子项 ID。
     */
    @SuppressWarnings("unchecked")
    private String queryChildId(String parentId, String childName, String pathContext) {
        String escapedName = childName.replace("\\", "\\\\").replace("'", "\\'");
        String q = "'" + parentId + "' in parents and name = '" + escapedName + "' and trashed = false";

        StringBuilder url = new StringBuilder(apiBase).append("/files");
        url.append("?q=").append(urlEncode(q));
        url.append("&fields=").append(urlEncode("files(id,mimeType,shortcutDetails)"));
        url.append("&pageSize=10");
        appendDriveScopeParams(url);

        Map<String, Object> json = executeJson("GET", url.toString(), null, pathContext);
        Object filesObj = json.get("files");
        if (!(filesObj instanceof List)) {
            return null;
        }
        List<?> files = (List<?>) filesObj;
        if (files.isEmpty()) {
            return null;
        }
        Object first = files.get(0);
        if (!(first instanceof Map)) {
            return null;
        }
        Map<String, Object> fileMap = (Map<String, Object>) first;
        if (fileMap.get("shortcutDetails") instanceof Map) {
            Map<String, Object> shortcut = (Map<String, Object>) fileMap.get("shortcutDetails");
            Object targetId = shortcut.get("targetId");
            if (targetId != null) {
                return String.valueOf(targetId);
            }
        }
        return fileMap.get("id") != null ? String.valueOf(fileMap.get("id")) : null;
    }

    private void appendDriveScopeParams(StringBuilder url) {
        url.append("&supportsAllDrives=true&includeItemsFromAllDrives=true");
        if (!driveId.isEmpty()) {
            url.append("&corpora=drive&driveId=").append(urlEncode(driveId));
        } else {
            url.append("&corpora=user");
        }
    }

    /**
     * 查询单个对象的元数据。
     */
    public StorageEntry stat(String path) {
        String normalized = StoragePaths.normalize(path);
        if (normalized.isEmpty()) {
            return StorageEntry.dir("", System.currentTimeMillis());
        }
        String id = resolveId(normalized);
        if (id == null) {
            throw new StorageException(StorageErrorCode.NOT_FOUND, "对象不存在: " + normalized, normalized);
        }

        StringBuilder url = new StringBuilder(apiBase).append("/files/").append(urlEncode(id));
        url.append("?fields=").append(urlEncode("id,name,mimeType,shortcutDetails,size,modifiedTime,trashed"));
        url.append("&supportsAllDrives=true");

        Map<String, Object> json = executeJson("GET", url.toString(), null, normalized);
        return parseStorageEntry(json, normalized);
    }

    @SuppressWarnings("unchecked")
    private StorageEntry parseStorageEntry(Map<String, Object> json, String path) {
        String mimeType = json.get("mimeType") != null ? String.valueOf(json.get("mimeType")) : "";
        if (GoogleDriveConfig.SHORTCUT_MIME_TYPE.equals(mimeType) && json.get("shortcutDetails") instanceof Map) {
            Map<String, Object> shortcut = (Map<String, Object>) json.get("shortcutDetails");
            if (shortcut.get("targetMimeType") != null) {
                mimeType = String.valueOf(shortcut.get("targetMimeType"));
            }
        }
        boolean isDir = GoogleDriveConfig.FOLDER_MIME_TYPE.equals(mimeType);

        long mtime = parseIsoTime(json.get("modifiedTime"));
        if (isDir) {
            return StorageEntry.dir(path, mtime);
        }

        long size = -1L;
        Object s = json.get("size");
        if (s instanceof Number) {
            size = ((Number) s).longValue();
        } else if (s != null) {
            try {
                size = Long.parseLong(String.valueOf(s));
            } catch (NumberFormatException ignore) {
                size = -1L;
            }
        }
        return StorageEntry.file(path, size, mtime, null);
    }

    /**
     * 列出指定目录下的直接子项。
     */
    @SuppressWarnings("unchecked")
    public List<StorageEntry> list(String dir) {
        String normalized = StoragePaths.normalize(dir);
        String folderId = resolveId(normalized);
        if (folderId == null) {
            throw new StorageException(StorageErrorCode.NOT_FOUND, "目录不存在: " + normalized, normalized);
        }

        List<StorageEntry> result = new ArrayList<StorageEntry>();
        String pageToken = "";

        do {
            StringBuilder url = new StringBuilder(apiBase).append("/files");
            String q = "'" + folderId + "' in parents and trashed = false";
            url.append("?q=").append(urlEncode(q));
            url.append("&fields=").append(urlEncode(
                    "files(id,name,mimeType,shortcutDetails,size,modifiedTime),nextPageToken"));
            url.append("&pageSize=1000");
            appendDriveScopeParams(url);
            if (!pageToken.isEmpty()) {
                url.append("&pageToken=").append(urlEncode(pageToken));
            }

            Map<String, Object> json = executeJson("GET", url.toString(), null, normalized);
            Object filesObj = json.get("files");
            if (filesObj instanceof List) {
                for (Object item : (List<?>) filesObj) {
                    if (item instanceof Map) {
                        Map<String, Object> fileMap = (Map<String, Object>) item;
                        String itemName = fileMap.get("name") != null ? String.valueOf(fileMap.get("name")) : "";
                        String childPath = StoragePaths.join(normalized, itemName);
                        String childId = fileMap.get("id") != null ? String.valueOf(fileMap.get("id")) : null;
                        if (childId != null) {
                            pathToIdCache.put(childPath, childId);
                        }
                        result.add(parseStorageEntry(fileMap, childPath));
                    }
                }
            }

            Object nextTokenObj = json.get("nextPageToken");
            pageToken = nextTokenObj != null ? String.valueOf(nextTokenObj).trim() : "";
        } while (!pageToken.isEmpty());

        return result;
    }

    /**
     * 递归创建目录（若已存在则忽略，幂等）。
     */
    public String mkdirs(String dir) {
        String normalized = StoragePaths.normalize(dir);
        if (normalized.isEmpty()) {
            return rootId();
        }
        String existing = resolveId(normalized);
        if (existing != null) {
            return existing;
        }

        String[] segments = normalized.split("/");
        String parentId = rootId();
        StringBuilder acc = new StringBuilder();

        for (String seg : segments) {
            if (acc.length() > 0) {
                acc.append('/');
            }
            acc.append(seg);
            String currentPath = acc.toString();

            String subId = pathToIdCache.get(currentPath);
            if (subId == null) {
                subId = queryChildId(parentId, seg, currentPath);
                if (subId == null) {
                    // 创建子文件夹
                    subId = createFolder(seg, parentId, currentPath);
                }
                pathToIdCache.put(currentPath, subId);
            }
            parentId = subId;
        }
        return parentId;
    }

    private String createFolder(String folderName, String parentId, String pathContext) {
        StringBuilder url = new StringBuilder(apiBase).append("/files?supportsAllDrives=true");
        Map<String, Object> body = new LinkedHashMap<String, Object>();
        body.put("name", folderName);
        body.put("mimeType", GoogleDriveConfig.FOLDER_MIME_TYPE);
        body.put("parents", Collections.singletonList(parentId));

        Map<String, Object> resp = executeJson("POST", url.toString(), body, pathContext);
        Object id = resp.get("id");
        if (id == null) {
            throw new StorageException(StorageErrorCode.IO_ERROR, "创建 Google Drive 目录失败: 无 ID 返回", pathContext);
        }
        return String.valueOf(id);
    }

    /**
     * 上传流数据到指定路径。支持覆盖已有同名文件。
     */
    public void upload(String path, InputStream in, long size, UploadMetadata meta) {
        String normalized = StoragePaths.normalize(path);
        if (normalized.isEmpty()) {
            throw StorageException.invalidConfig("上传目标路径不能为空");
        }
        String parentPath = StoragePaths.parent(normalized);
        String fileName = StoragePaths.name(normalized);
        String parentId = mkdirs(parentPath);

        String existingId = resolveId(normalized);
        String mimeType = meta != null && meta.contentType() != null
                ? meta.contentType() : MimeTypes.of(fileName);

        String boundary = "===JKitGoogleDriveBoundary" + System.nanoTime() + "===";
        byte[] metaJsonBytes;
        Map<String, Object> metaMap = new LinkedHashMap<String, Object>();
        metaMap.put("name", fileName);
        if (existingId == null) {
            metaMap.put("parents", Collections.singletonList(parentId));
        }
        metaJsonBytes = SimpleJson.toJson(metaMap).getBytes(StandardCharsets.UTF_8);

        byte[] part1 = ("--" + boundary + "\r\n"
                + "Content-Type: application/json; charset=UTF-8\r\n\r\n").getBytes(StandardCharsets.UTF_8);
        byte[] part2 = ("\r\n--" + boundary + "\r\n"
                + "Content-Type: " + mimeType + "\r\n\r\n").getBytes(StandardCharsets.UTF_8);
        byte[] part4 = ("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8);

        String uploadUrl;
        String method;
        if (existingId != null) {
            method = "PATCH";
            uploadUrl = uploadBase + "/files/" + urlEncode(existingId)
                    + "?uploadType=multipart&supportsAllDrives=true";
        } else {
            method = "POST";
            uploadUrl = uploadBase + "/files?uploadType=multipart&supportsAllDrives=true";
        }

        // 组装整体请求体与计算长度；上传体是一次性流，401 后无法重放：
        // retryOn401=false，令牌照常刷新，本次操作报错引导用户重试
        if (size >= 0) {
            final long totalLength = part1.length + metaJsonBytes.length + part2.length + size + part4.length;
            java.util.function.Supplier<HttpCore.Request> factory = () -> {
                InputStream bodyStream = new SequenceInputStream(
                        new ByteArrayInputStream(concat(part1, metaJsonBytes, part2)),
                        new SequenceInputStream(in, new ByteArrayInputStream(part4)));
                return uploadRequest(method, uploadUrl, boundary, bodyStream, totalLength);
            };
            applyUploadResponse(factory, false, normalized);
        } else {
            // 大小未知：通过有界缓冲先测定实际字节数
            try (SpillBuffer buffer = new SpillBuffer(2L * 1024L * 1024L, normalized)) {
                byte[] chunk = new byte[8192];
                int read;
                while ((read = in.read(chunk)) >= 0) {
                    if (read > 0) {
                        buffer.write(chunk, 0, read);
                    }
                }
                final long totalLength = part1.length + metaJsonBytes.length + part2.length
                        + buffer.size() + part4.length;
                java.util.function.Supplier<HttpCore.Request> factory = () -> {
                    InputStream bodyStream;
                    try {
                        bodyStream = new SequenceInputStream(
                                new ByteArrayInputStream(concat(part1, metaJsonBytes, part2)),
                                new SequenceInputStream(buffer.openInput(), new ByteArrayInputStream(part4)));
                    } catch (IOException e) {
                        throw new StorageException(StorageErrorCode.IO_ERROR,
                                "上传时重放缓冲数据失败: " + e.getMessage(), normalized, e);
                    }
                    return uploadRequest(method, uploadUrl, boundary, bodyStream, totalLength);
                };
                applyUploadResponse(factory, false, normalized);
            } catch (IOException e) {
                throw new StorageException(StorageErrorCode.IO_ERROR, "上传时缓冲数据失败: " + e.getMessage(), normalized, e);
            }
        }
    }

    private HttpCore.Request uploadRequest(String method, String uploadUrl, String boundary,
                                           InputStream bodyStream, long totalLength) {
        return new HttpCore.Request(method, uploadUrl)
                .header("Content-Type", "multipart/related; boundary=" + boundary)
                .header("Accept", "application/json")
                .body(bodyStream, totalLength);
    }

    private void applyUploadResponse(java.util.function.Supplier<HttpCore.Request> factory,
                                     boolean retryOn401, String normalized) {
        try (HttpCore.Response response = executeAuthed(factory, retryOn401)) {
            int status = response.status();
            String bodyText = response.bodyText();
            Map<String, Object> json = SimpleJson.parseObject(bodyText);
            if (status >= 400) {
                handleApiError(status, bodyText, json, normalized);
            }
            if (json != null && json.get("id") != null) {
                pathToIdCache.put(normalized, String.valueOf(json.get("id")));
            }
        }
    }

    private static byte[] concat(byte[]... parts) {
        int total = 0;
        for (byte[] p : parts) {
            total += p.length;
        }
        byte[] res = new byte[total];
        int off = 0;
        for (byte[] p : parts) {
            System.arraycopy(p, 0, res, off, p.length);
            off += p.length;
        }
        return res;
    }

    /**
     * 下载对象到输出流。支持 Range 偏移读取。
     */
    public void download(String path, long offset, OutputStream out) {
        String normalized = StoragePaths.normalize(path);
        String fileId = resolveId(normalized);
        if (fileId == null) {
            throw new StorageException(StorageErrorCode.NOT_FOUND, "对象不存在: " + normalized, normalized);
        }

        StringBuilder url = new StringBuilder(apiBase).append("/files/").append(urlEncode(fileId));
        url.append("?alt=media&supportsAllDrives=true");

        final String downloadUrl = url.toString();
        try (HttpCore.Response response = executeAuthed(() -> {
            HttpCore.Request req = new HttpCore.Request("GET", downloadUrl);
            if (offset > 0) {
                req.header("Range", "bytes=" + offset + "-");
            }
            return req;
        }, true)) {
            int status = response.status();
            if (status >= 400) {
                String bodyText = response.bodyText();
                Map<String, Object> json = SimpleJson.parseObject(bodyText);
                handleApiError(status, bodyText, json, normalized);
            }

            try (InputStream in = response.body()) {
                byte[] buffer = new byte[16384];
                int read;
                while ((read = in.read(buffer)) >= 0) {
                    if (read > 0) {
                        out.write(buffer, 0, read);
                    }
                }
                out.flush();
            } catch (IOException e) {
                throw new StorageException(StorageErrorCode.IO_ERROR,
                        "读取 Google Drive 下载流失败: " + e.getMessage(), normalized, e);
            }
        }
    }

    /**
     * 删除文件或目录：移入 Google Drive 原生回收站（网页端可恢复）。
     *
     * <p>引擎能力声明了 {@code hasNativeRecycleBin()}，界面据此跳过软删除路径，
     * 所以这里必须保持「删除即可恢复」的语义，不提供彻底删除开关。</p>
     */
    public void delete(String path) {
        String normalized = StoragePaths.normalize(path);
        if (normalized.isEmpty()) {
            throw StorageException.invalidConfig("delete 拒绝删除 Google Drive 根目录");
        }
        String fileId = resolveId(normalized);
        if (fileId == null) {
            return; // 幂等：不存在视为成功
        }

        StringBuilder url = new StringBuilder(apiBase).append("/files/").append(urlEncode(fileId));
        url.append("?supportsAllDrives=true");
        Map<String, Object> body = new LinkedHashMap<String, Object>();
        body.put("trashed", true);
        executeJson("PATCH", url.toString(), body, normalized);
        invalidateCache(normalized);
    }

    /**
     * 复制文件到目标路径。
     */
    public void copy(String src, String dst) {
        String normSrc = StoragePaths.normalize(src);
        String normDst = StoragePaths.normalize(dst);
        String srcId = resolveId(normSrc);
        if (srcId == null) {
            throw new StorageException(StorageErrorCode.NOT_FOUND, "源文件不存在: " + normSrc, normSrc);
        }

        String dstParent = StoragePaths.parent(normDst);
        String dstName = StoragePaths.name(normDst);
        String dstParentId = mkdirs(dstParent);

        // 如果目标文件已存在，先删除已有文件保证覆盖语义
        String existingDstId = resolveId(normDst);
        if (existingDstId != null) {
            delete(normDst);
        }

        StringBuilder url = new StringBuilder(apiBase).append("/files/")
                .append(urlEncode(srcId)).append("/copy?supportsAllDrives=true");
        Map<String, Object> body = new LinkedHashMap<String, Object>();
        body.put("name", dstName);
        body.put("parents", Collections.singletonList(dstParentId));

        Map<String, Object> resp = executeJson("POST", url.toString(), body, normDst);
        Object newId = resp.get("id");
        if (newId != null) {
            pathToIdCache.put(normDst, String.valueOf(newId));
        }
    }

    /**
     * 重命名或移动对象。
     */
    public void rename(String src, String dst) {
        String normSrc = StoragePaths.normalize(src);
        String normDst = StoragePaths.normalize(dst);
        String srcId = resolveId(normSrc);
        if (srcId == null) {
            throw new StorageException(StorageErrorCode.NOT_FOUND, "源对象不存在: " + normSrc, normSrc);
        }

        String srcParent = StoragePaths.parent(normSrc);
        String dstParent = StoragePaths.parent(normDst);
        String dstName = StoragePaths.name(normDst);

        StringBuilder url = new StringBuilder(apiBase).append("/files/").append(urlEncode(srcId));
        url.append("?supportsAllDrives=true");

        if (!srcParent.equals(dstParent)) {
            String dstParentId = mkdirs(dstParent);
            String srcParentId = resolveId(srcParent);
            url.append("&addParents=").append(urlEncode(dstParentId));
            if (srcParentId != null) {
                url.append("&removeParents=").append(urlEncode(srcParentId));
            }
        }

        Map<String, Object> body = new LinkedHashMap<String, Object>();
        body.put("name", dstName);
        executeJson("PATCH", url.toString(), body, normDst);

        invalidateCache(normSrc);
        pathToIdCache.put(normDst, srcId);
    }

    /**
     * 获取用户有权访问的团队共享盘列表。
     */
    @SuppressWarnings("unchecked")
    public List<SharedDrive> listSharedDrives() {
        List<SharedDrive> drives = new ArrayList<SharedDrive>();
        StringBuilder url = new StringBuilder(apiBase).append("/drives?pageSize=100");

        Map<String, Object> json = executeJson("GET", url.toString(), null, "shared-drives");
        Object drivesObj = json.get("drives");
        if (drivesObj instanceof List) {
            for (Object item : (List<?>) drivesObj) {
                if (item instanceof Map) {
                    Map<String, Object> m = (Map<String, Object>) item;
                    String id = m.get("id") != null ? String.valueOf(m.get("id")) : "";
                    String name = m.get("name") != null ? String.valueOf(m.get("name")) : "";
                    if (!id.isEmpty()) {
                        drives.add(new SharedDrive(id, name));
                    }
                }
            }
        }
        return drives;
    }

    /** 团队共享盘条目。 */
    public static final class SharedDrive {
        private final String id;
        private final String name;

        public SharedDrive(String id, String name) {
            this.id = id;
            this.name = name;
        }

        public String id() {
            return id;
        }

        public String name() {
            return name;
        }
    }

    public void invalidateCache(String path) {
        String norm = StoragePaths.normalize(path);
        pathToIdCache.remove(norm);
        String prefix = norm.isEmpty() ? "" : norm + "/";
        for (String key : new ArrayList<String>(pathToIdCache.keySet())) {
            if (key.startsWith(prefix)) {
                pathToIdCache.remove(key);
            }
        }
    }

    private static String urlEncode(String val) {
        try {
            return URLEncoder.encode(val == null ? "" : val, StandardCharsets.UTF_8.name());
        } catch (UnsupportedEncodingException e) {
            return val == null ? "" : val;
        }
    }

    private static long parseIsoTime(Object obj) {
        if (obj == null) {
            return System.currentTimeMillis();
        }
        String str = String.valueOf(obj).trim();
        if (str.isEmpty()) {
            return System.currentTimeMillis();
        }
        SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.ROOT);
        sdf.setTimeZone(TimeZone.getTimeZone("UTC"));
        try {
            Date d = sdf.parse(str);
            return d.getTime();
        } catch (ParseException e) {
            SimpleDateFormat fallback = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.ROOT);
            fallback.setTimeZone(TimeZone.getTimeZone("UTC"));
            try {
                Date d = fallback.parse(str);
                return d.getTime();
            } catch (ParseException ignore) {
                return System.currentTimeMillis();
            }
        }
    }
}
