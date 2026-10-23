package com.alianga.jkit.storage.engine;

import com.alianga.jkit.storage.FileStorage;
import com.alianga.jkit.storage.StorageConfig;
import com.alianga.jkit.storage.StorageEntry;
import com.alianga.jkit.storage.StorageErrorCode;
import com.alianga.jkit.storage.StorageException;
import com.alianga.jkit.storage.StoragePaths;
import com.alianga.jkit.storage.UploadMetadata;
import com.alianga.jkit.storage.internal.HttpCore;
import com.alianga.jkit.storage.internal.MimeTypes;
import com.alianga.jkit.storage.internal.SimpleJson;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Cloudreve V4 引擎：账号密码直连（默认）或 OAuth2 授权码，Bearer 调 {@code /api/v4} 文件 API。
 *
 * <p><b>认证</b>（三选一，优先级 code &gt; refreshToken &gt; 账号密码）：</p>
 * <ul>
 *   <li><b>账号密码</b>（默认，零浏览器步骤）：程序自动走完整 OAuth2 授权链——
 *       {@code POST /session/token}（JSON：email / password）登录拿会话令牌，
 *       {@code POST /session/oauth/consent}（Bearer + 相对回调地址）自动「同意」授权申请，
 *       拿到一次性授权码后调 {@code POST /session/oauth/token} 换 access_token +
 *       refresh_token。用户只需要填用户名与密码。</li>
 *   <li><b>刷新令牌</b>（refreshToken）：已有令牌时填它免重新授权，过期自动刷新。</li>
 *   <li><b>授权码</b>（code）：浏览器打开 {@code /session/authorize?client_id=…} 完成授权后
 *       回调地址会带上 {@code ?code=…}，填进来直接换令牌。</li>
 * </ul>
 *
 * <p>access_token 有效期短（默认 15 分钟），过期自动用 refresh_token 调
 * {@code POST /api/v4/session/token/refresh} 续期；之后所有请求带
 * {@code Authorization: Bearer <access_token>}。</p>
 *
 * <p><b>文件 API</b>（envelope {@code {code, data, msg, error}}，code=0 成功）：
 * 路径统一为 {@code cloudreve://my/<path>} URI；列表 {@code GET /file?uri&page_size}，
 * 建目录 {@code POST /file/create}（type=folder），改名 {@code POST /file/rename}，
 * 移动 / 复制 {@code POST /file/move}，删除 {@code DELETE /file}（body 为 uris 数组），
 * 上传 {@code PUT /file/upload} 创建会话 + {@code POST /file/upload/{sessionId}/{index}}
 * 逐片上传（chunk_size=0 或单分片时一片传完），下载 {@code POST /file/url} 拿带签名的
 * 临时链接后跟随下载，容量 {@code GET /user/capacity}。</p>
 *
 * <p>配置键：siteUrl（站点地址，如 https://pan.example.com）、user / password（账号密码，
 * 默认认证方式）、clientId / clientSecret（OAuth2 应用凭据，内置桌面应用有默认值）、
 * code（一次性授权码）、refreshToken（已有令牌）、basePath（存储根前缀）。</p>
 *
 * @author 郑明亮
 */
public class CloudreveFileStorage implements FileStorage {
    /** API 前缀（Cloudreve V4 固定）。 */
    private static final String API_PREFIX = "/api/v4";
    /** 令牌提前刷新余量（毫秒）。 */
    private static final long EXPIRY_SLACK_MS = 60L * 1000L;
    /** 列表分页大小。 */
    private static final int LIST_PAGE_SIZE = 500;
    /** cloudreve URI 的 my 文件系统前缀。 */
    private static final String MY_URI = "cloudreve://my/";
    /** 内置桌面应用的 OAuth2 凭据（Cloudreve 全部署一致，开源常量；自建应用可覆盖）。 */
    private static final String BUILTIN_CLIENT_ID = "393a1839-f52e-498e-9972-e77cc2241eee";
    private static final String BUILTIN_CLIENT_SECRET = "8GaQIu3lOSdqYoDHi9cR8IZ4pvuMH8ya";
    /** 内置桌面应用的回调地址（相对路径，consent 校验只认注册值）。 */
    private static final String BUILTIN_REDIRECT_URI = "/callback/desktop";
    /** 授权 scope：内置桌面应用注册的子集（Files.Write 隐含 Files.Read）。 */
    private static final String OAUTH_SCOPES = "openid profile email offline_access Files.Write";

    private final StorageConfig config;
    private final String siteUrl;
    private final String clientId;
    private final String clientSecret;
    private final String user;
    private final String password;
    private final String basePath;

    /** Bearer 令牌与过期时间（过期自动刷新）。 */
    private volatile String accessToken;
    private volatile String refreshToken;
    private volatile long accessExpiresAt;
    private final ReentrantLock tokenLock = new ReentrantLock();

    /**
     * 由配置创建（配置键见类注释）。
     *
     * @param config 连接配置
     */
    public CloudreveFileStorage(StorageConfig config) {
        this.config = config;
        this.siteUrl = normalizeSite(config.require("siteUrl"));
        this.clientId = config.getOr("oauthClientId", config.getOr("clientId", BUILTIN_CLIENT_ID));
        this.clientSecret = config.getOr("oauthClientSecret",
                config.getOr("clientSecret", BUILTIN_CLIENT_SECRET));
        this.user = config.getOr("user", "");
        this.password = config.get("password");
        this.basePath = StoragePaths.normalize(config.getOr("basePath", ""));
        String code = config.getOr("oauthCode", config.getOr("code", ""));
        String refresh = config.getOr("oauthRefreshToken", config.getOr("refreshToken", ""));
        if (code != null && !code.trim().isEmpty()) {
            // 显式授权码：浏览器授权后填进来的（优先级最高）
            exchangeToken(code.trim());
        } else if (refresh != null && !refresh.trim().isEmpty()) {
            this.refreshToken = refresh.trim();
            refreshAccessToken();
        } else if (!user.isEmpty() && password != null && !password.isEmpty()) {
            // 账号密码直连：程序自动登录 + 申请授权码（零浏览器步骤）
            loginAndConsent();
        } else {
            throw StorageException.invalidConfig(
                    "Cloudreve 缺少认证凭据：请填写账号密码（user / password），"
                            + "或已有令牌时填 refreshToken，或浏览器授权后填 code");
        }
    }

    @Override
    public String platform() {
        return "cloudreve";
    }

    /**
     * 首次授权换回的刷新令牌（上层保存后下次连接免重新授权；值属于密钥，不打日志）。
     *
     * @return 刷新令牌，未取得返回 null
     */
    public String refreshToken() {
        return refreshToken;
    }

    // ================================================================ FileStorage

    @Override
    public boolean exists(String path) {
        String normalized = StoragePaths.normalize(path);
        if (normalized.isEmpty()) {
            return true;
        }
        try {
            stat(normalized);
            return true;
        } catch (StorageException e) {
            if (e.code() == StorageErrorCode.NOT_FOUND) {
                return false;
            }
            throw e;
        }
    }

    @Override
    public StorageEntry stat(String path) {
        String normalized = StoragePaths.normalize(path);
        if (normalized.isEmpty()) {
            return StorageEntry.dir("", 0L);
        }
        try {
            Map<String, Object> file = fileInfo(normalized);
            return toEntry(file, normalized);
        } catch (StorageException e) {
            if (e.code() != StorageErrorCode.NOT_FOUND) {
                throw e;
            }
        }
        // file/info 偶发未命中（rename 刚返回时 entity 缓存仍是旧 ID）：回退父目录列表探测
        List<StorageEntry> siblings = list(StoragePaths.parent(normalized));
        String name = StoragePaths.name(normalized);
        for (StorageEntry entry : siblings) {
            if (entry.name().equals(name)) {
                return entry;
            }
        }
        throw StorageException.notFound(normalized);
    }

    @Override
    public List<StorageEntry> list(String dir) {
        String normalized = StoragePaths.normalize(dir);
        String uri = fileUri(normalized);
        List<StorageEntry> entries = new ArrayList<>();
        String pageToken = "";
        while (true) {
            StringBuilder query = new StringBuilder();
            query.append("uri=").append(urlEncode(uri))
                    .append("&page_size=").append(LIST_PAGE_SIZE);
            if (!pageToken.isEmpty()) {
                query.append("&next_page_token=").append(urlEncode(pageToken));
            }
            Map<String, Object> data = apiData("GET", "/file?" + query, null, normalized);
            List<Map<String, Object>> files = asMapList(data == null ? null : data.get("files"));
            if (files != null) {
                for (Map<String, Object> file : files) {
                    entries.add(toEntry(file, null));
                }
            }
            pageToken = paginationToken(data);
            if (pageToken.isEmpty()) {
                break;
            }
        }
        sortEntries(entries);
        return entries;
    }

    @Override
    public void mkdir(String dir) {
        String normalized = StoragePaths.normalize(dir);
        if (normalized.isEmpty()) {
            return;
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("uri", fileUri(normalized));
        body.put("type", "folder");
        body.put("err_on_conflict", false);
        apiData("POST", "/file/create", SimpleJson.toJson(body), normalized);
    }

    @Override
    public void upload(String path, InputStream in, long size) {
        upload(path, in, size, null);
    }

    @Override
    public void upload(String path, InputStream in, long size, UploadMetadata meta) {
        String normalized = requireFile(path);
        byte[] data;
        try {
            data = readAll(in);
        } catch (IOException e) {
            throw StorageException.io("读取上传流失败: " + e.getMessage(), normalized, e);
        }
        // FileStorage 契约：覆盖上传。官方 upload 会话对已存在文件报 40004（ObjectExist），
        // 且中断的上传会留下带锁的占位文件——先删目标再走会话（目标不存在时 delete 幂等，
        // 只多一次轻量请求）
        try {
            delete(normalized);
        } catch (StorageException ignored) {
            // 删除失败（锁占用等）不预判：交给会话创建给出明确错误
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("uri", fileUri(normalized));
        body.put("size", (long) data.length);
        String contentType = meta != null && meta.contentType() != null
                ? meta.contentType() : MimeTypes.of(normalized);
        if (contentType != null && !contentType.isEmpty()) {
            body.put("mime_type", contentType);
        }
        if (meta != null && meta.hasLastModified()) {
            body.put("last_modified", meta.lastModified());
        }
        Map<String, Object> session = apiData("PUT", "/file/upload", SimpleJson.toJson(body), normalized);
        if (session == null) {
            throw StorageException.serverError("Cloudreve 上传会话创建未返回数据", normalized);
        }
        String sessionId = stringOf(session.get("session_id"));
        if (sessionId.isEmpty()) {
            throw StorageException.serverError("Cloudreve 上传会话缺少 session_id", normalized);
        }
        Number chunkSize = session.get("chunk_size") instanceof Number
                ? (Number) session.get("chunk_size") : null;
        long chunk = chunkSize == null || chunkSize.longValue() <= 0
                ? Math.max(data.length, 1L) : chunkSize.longValue();
        int index = 0;
        for (long offset = 0; offset < data.length || index == 0; offset += chunk) {
            int from = (int) Math.min(offset, data.length);
            int to = (int) Math.min(offset + chunk, data.length);
            uploadChunk(normalized, sessionId, index, data, from, to);
            if (to >= data.length) {
                break;
            }
            index++;
        }
    }

    /** 上传单个分片（{@code POST /file/upload/{sessionId}/{index}}，body 为分片字节）。 */
    private void uploadChunk(String path, String sessionId, int index,
                             byte[] data, int from, int to) {
        byte[] slice = new byte[to - from];
        System.arraycopy(data, from, slice, 0, slice.length);
        apiData("POST", "/file/upload/" + urlEncode(sessionId) + "/" + index,
                null, path, slice, "application/octet-stream");
    }

    @Override
    public void download(String path, OutputStream out) {
        download(path, -1L, out);
    }

    @Override
    public boolean supportsRange() {
        // 临时链接指向底层存储（本地 / S3 等，多数支持 Range），但引擎内不依赖 Range
        return false;
    }

    @Override
    public void download(String path, long offset, OutputStream out) {
        String normalized = requireFile(path);
        if (offset > 0) {
            throw StorageException.unsupported("Cloudreve 引擎不支持断点续传: " + normalized);
        }
        String url = entityUrl(normalized);
        HttpCore.Response response = HttpCore.execute(HttpCore.configured(
                new HttpCore.Request("GET", url), config));
        try {
            if (response.status() >= 400) {
                throw StorageException.io(
                        "Cloudreve 下载失败（HTTP " + response.status() + "）", normalized, null);
            }
            byte[] chunk = new byte[8192];
            int read;
            try {
                while ((read = response.body().read(chunk)) >= 0) {
                    out.write(chunk, 0, read);
                }
            } catch (IOException e) {
                throw StorageException.io("Cloudreve 下载失败: " + e.getMessage(), normalized, e);
            }
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
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("uris", java.util.Collections.singletonList(fileUri(normalized)));
        body.put("skip_soft_delete", false);
        apiData("DELETE", "/file", SimpleJson.toJson(body), normalized);
    }

    @Override
    public void copy(String src, String dst) {
        moveOrCopy(src, dst, true);
    }

    @Override
    public void rename(String src, String dst) {
        String from = requireFile(src);
        String to = requireFile(dst);
        // FileStorage 契约：目标已存在时覆盖。官方 rename 遇到同名报 40004（无覆盖参数），
        // 先删目标再改名（目标不存在时 delete 幂等，无额外请求代价）
        if (!to.equals(from) && exists(to)) {
            delete(to);
        }
        String srcParent = StoragePaths.parent(from);
        String dstParent = StoragePaths.parent(to);
        String dstName = StoragePaths.name(to);
        if (srcParent.equals(dstParent)) {
            // 同目录改名：原生 rename 接口
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("uri", fileUri(from));
            body.put("new_name", dstName);
            apiData("POST", "/file/rename", SimpleJson.toJson(body), from);
            return;
        }
        moveOrCopy(from, to, false);
    }

    /** 移动 / 复制：{@code POST /file/move}（uris + dst + copy 标记）。 */
    private void moveOrCopy(String src, String dst, boolean copy) {
        String from = requireFile(src);
        String to = requireFile(dst);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("uris", java.util.Collections.singletonList(fileUri(from)));
        body.put("dst", fileUri(StoragePaths.parent(to)));
        body.put("copy", copy);
        apiData("POST", "/file/move", SimpleJson.toJson(body), from);
        // move / copy 只换目录，文件名保持源名；目标名不同时再调 rename
        String srcName = StoragePaths.name(from);
        String dstName = StoragePaths.name(to);
        if (!srcName.equals(dstName)) {
            String movedUri = StoragePaths.join(StoragePaths.parent(to), srcName);
            if (exists(to)) {
                delete(to);
            }
            Map<String, Object> body2 = new LinkedHashMap<>();
            body2.put("uri", fileUri(movedUri));
            body2.put("new_name", dstName);
            apiData("POST", "/file/rename", SimpleJson.toJson(body2), from);
        }
    }

    @Override
    public String presign(String path, java.time.Duration ttl) {
        return entityUrl(requireFile(path));
    }

    // ================================================================ 链接与容量

    /**
     * 对象的带签名临时链接（{@code POST /file/url} 单个 uri + redirect=false）。
     *
     * @param path 对象路径
     * @return 临时下载链接
     */
    public String entityUrl(String path) {
        String normalized = requireFile(path);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("uris", java.util.Collections.singletonList(fileUri(normalized)));
        body.put("download", true);
        body.put("redirect", false);
        Map<String, Object> data = apiData("POST", "/file/url", SimpleJson.toJson(body), normalized);
        List<Map<String, Object>> urls = data == null ? null : asMapList(data.get("urls"));
        String url = urls != null && !urls.isEmpty() ? stringOf(urls.get(0).get("url")) : "";
        if (url.isEmpty()) {
            throw StorageException.serverError("Cloudreve 下载链接签发未返回 url", normalized);
        }
        return url;
    }

    /**
     * 当前账号的存储容量（{@code GET /user/capacity}）。
     *
     * @return [total, used] 字节
     */
    public long[] capacity() {
        Map<String, Object> data = apiData("GET", "/user/capacity", null, "-");
        long total = data != null && data.get("total") instanceof Number
                ? ((Number) data.get("total")).longValue() : -1L;
        long used = data != null && data.get("used") instanceof Number
                ? ((Number) data.get("used")).longValue() : -1L;
        return new long[]{total, used};
    }

    // ================================================================ 令牌

    /** 确保 Bearer 令牌可用（过期自动刷新）。 */
    private void ensureToken() {
        if (accessToken != null
                && System.currentTimeMillis() + EXPIRY_SLACK_MS < accessExpiresAt) {
            return;
        }
        refreshAccessToken();
    }

    /** 用 refresh_token 换新令牌对（{@code POST /session/token/refresh}）。 */
    private void refreshAccessToken() {
        tokenLock.lock();
        try {
            if (accessToken != null && !accessToken.isEmpty()
                    && System.currentTimeMillis() + EXPIRY_SLACK_MS < accessExpiresAt) {
                return;
            }
            if (refreshToken == null || refreshToken.isEmpty()) {
                throw StorageException.authFailed("Cloudreve 刷新令牌缺失，请重新授权");
            }
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("refresh_token", refreshToken);
            HttpCore.Response response = HttpCore.execute(HttpCore.configured(
                    new HttpCore.Request("POST", siteUrl + API_PREFIX + "/session/token/refresh")
                            .header("Content-Type", "application/json")
                            .body(SimpleJson.toJson(body).getBytes(StandardCharsets.UTF_8)), config));
            String text;
            try {
                text = response.bodyText();
            } finally {
                response.close();
            }
            Map<String, Object> json = SimpleJson.parseObject(text);
            Number code = json == null ? null : asNumber(json.get("code"));
            if (response.status() >= 400 || (code != null && code.intValue() != 0)) {
                throw StorageException.authFailed("Cloudreve 令牌刷新失败（可能已过期，请重新授权）: "
                        + summarize(text));
            }
            Map<String, Object> data = json == null ? null : asMap(json.get("data"));
            applyToken(data);
        } finally {
            tokenLock.unlock();
        }
    }

    /**
     * 账号密码直连：自动完成完整 OAuth2 授权链（零浏览器步骤）。
     * {@code POST /session/token} 登录 → {@code POST /session/oauth/consent} 自动同意
     * → 授权码 → {@code POST /session/oauth/token} 换令牌对。
     */
    private void loginAndConsent() {
        tokenLock.lock();
        try {
            String access = passwordLogin();
            String code = consentForCode(access);
            exchangeToken(code);
        } finally {
            tokenLock.unlock();
        }
    }

    /** 账号密码登录（{@code POST /session/token}，JSON），返回会话 access token。 */
    private String passwordLogin() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("email", user);
        body.put("password", password);
        HttpCore.Response response = HttpCore.execute(HttpCore.configured(
                new HttpCore.Request("POST", siteUrl + API_PREFIX + "/session/token")
                        .header("Content-Type", "application/json")
                        .body(SimpleJson.toJson(body).getBytes(StandardCharsets.UTF_8)), config));
        String text;
        try {
            text = response.bodyText();
        } finally {
            response.close();
        }
        Map<String, Object> json = SimpleJson.parseObject(text);
        Number code = json == null ? null : asNumber(json.get("code"));
        if (response.status() >= 400 || code == null || code.intValue() != 0) {
            // 密码错误（40080）等：不给「请重新授权」这种误导提示，直说账号密码不对
            throw StorageException.authFailed("Cloudreve 账号密码登录失败（请检查用户名与密码）: "
                    + summarize(text));
        }
        Map<String, Object> data = asMap(json.get("data"));
        Map<String, Object> token = data == null ? null : asMap(data.get("token"));
        String access = token == null ? "" : stringOf(token.get("access_token"));
        if (access.isEmpty()) {
            throw StorageException.authFailed("Cloudreve 登录未返回会话令牌");
        }
        return access;
    }

    /** 用会话令牌自动「同意」授权申请（{@code POST /session/oauth/consent}），返回授权码。 */
    private String consentForCode(String sessionAccess) {
        String code = consentOnce(sessionAccess, OAUTH_SCOPES);
        if (code != null) {
            return code;
        }
        // 应用只勾了 Read 权限（自建应用常见）：把 Files.Write 降级为 Files.Read 重试一次
        return consentOnce(sessionAccess,
                OAUTH_SCOPES.replace("Files.Write", "Files.Read"));
    }

    /** 一次 consent 调用；envelope code=0 时返回授权码，否则返回 null。 */
    private String consentOnce(String sessionAccess, String scope) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("client_id", clientId);
        body.put("response_type", "code");
        // 回调地址必须是应用注册的值：内置桌面应用为相对路径 /callback/desktop（实测
        // 绝对 URL 会 40001）；自建应用注册完整 URL 时填 oauthRedirectUri
        body.put("redirect_uri", redirectUriOf());
        body.put("state", "jkit-auth");
        body.put("scope", scope);
        HttpCore.Response response = HttpCore.execute(HttpCore.configured(
                new HttpCore.Request("POST", siteUrl + API_PREFIX + "/session/oauth/consent")
                        .header("Content-Type", "application/json")
                        .header("Authorization", "Bearer " + sessionAccess)
                        .body(SimpleJson.toJson(body).getBytes(StandardCharsets.UTF_8)), config));
        String text;
        try {
            text = response.bodyText();
        } finally {
            response.close();
        }
        Map<String, Object> json = SimpleJson.parseObject(text);
        Number code = json == null ? null : asNumber(json.get("code"));
        if (response.status() >= 400 || code == null || code.intValue() != 0) {
            String flat = summarize(text);
            if (flat.contains("App not found") || flat.contains("oauth_client not found")) {
                throw StorageException.authFailed(
                        "Cloudreve 授权申请失败：站点上不存在 OAuth2 应用 "
                                + clientId + "。请在站点管理 → OAuth2 应用里新建应用"
                                + "（回调地址填 " + BUILTIN_REDIRECT_URI + "），"
                                + "把 Client ID / Secret 填进连接配置的高级选项；"
                                + "或直接用账号密码 + 自建应用的凭据");
            }
            if (scope.equals(OAUTH_SCOPES)
                    && (flat.contains("Invalid scope") || flat.contains("scope"))) {
                // 交给降级重试
                return null;
            }
            throw StorageException.authFailed("Cloudreve 授权申请失败（consent）: " + flat);
        }
        Map<String, Object> data = asMap(json.get("data"));
        String authorizationCode = data == null ? "" : stringOf(data.get("code"));
        if (authorizationCode.isEmpty()) {
            throw StorageException.authFailed("Cloudreve 授权申请未返回授权码");
        }
        return authorizationCode;
    }

    /**
     * 授权申请用的回调地址：用户显式配置的 {@code oauthRedirectUri} 优先（须与注册值一致）；
     * 未配置时按应用类型给默认值——内置桌面应用注册的是<b>相对路径</b> {@code /callback/desktop}
     * （实测绝对 URL 会 40001），自建应用常注册完整 URL（{@code siteUrl + /callback/desktop}）。
     */
    private String redirectUriOf() {
        String configured = config.getOr("oauthRedirectUri", "");
        if (!configured.isEmpty()) {
            return configured;
        }
        if (BUILTIN_CLIENT_ID.equals(clientId)) {
            return BUILTIN_REDIRECT_URI;
        }
        return siteUrl + BUILTIN_REDIRECT_URI;
    }

    /** 授权码换令牌（{@code POST /session/oauth/token}，form 提交）。 */
    private void exchangeToken(String code) {
        tokenLock.lock();
        try {
            String form = "grant_type=authorization_code"
                    + "&client_id=" + urlEncode(clientId)
                    + "&client_secret=" + urlEncode(clientSecret)
                    + "&code=" + urlEncode(code);
            HttpCore.Response response = HttpCore.execute(HttpCore.configured(
                    new HttpCore.Request("POST", siteUrl + API_PREFIX + "/session/oauth/token")
                            .header("Content-Type", "application/x-www-form-urlencoded")
                            .body(form.getBytes(StandardCharsets.UTF_8)), config));
            String text;
            try {
                text = response.bodyText();
            } finally {
                response.close();
            }
            Map<String, Object> json = SimpleJson.parseObject(text);
            // 授权码换令牌直接返回 OAuth 标准响应（无 envelope），失败为 400 + {error, error_description}
            String error = json == null ? "" : stringOf(json.get("error"));
            String access = json == null ? "" : stringOf(json.get("access_token"));
            if (response.status() >= 400 || access.isEmpty()) {
                String detail = json == null ? "" : stringOf(json.get("error_description"));
                throw StorageException.authFailed("Cloudreve 授权码换取令牌失败: "
                        + (detail.isEmpty() ? summarize(text) : error + "（" + detail + "）"));
            }
            applyToken(json);
        } finally {
            tokenLock.unlock();
        }
    }

    /** 从令牌响应里取出 access / refresh 与过期时间。 */
    private void applyToken(Map<String, Object> data) {
        String access = data == null ? "" : stringOf(data.get("access_token"));
        if (access.isEmpty()) {
            throw StorageException.authFailed("Cloudreve 令牌响应缺少 access_token");
        }
        Number expiresIn = data.get("expires_in") instanceof Number
                ? (Number) data.get("expires_in") : null;
        this.accessToken = access;
        this.accessExpiresAt = expiresIn == null
                ? System.currentTimeMillis() + 10L * 60L * 1000L
                : System.currentTimeMillis() + expiresIn.longValue() * 1000L;
        String refresh = stringOf(data.get("refresh_token"));
        if (!refresh.isEmpty()) {
            this.refreshToken = refresh;
        }
    }

    // ================================================================ API 主干

    /**
     * 带鉴权的 API 调用（自动刷新过期令牌；401 时刷新一次重试）。
     * 返回 envelope 的 data 字段。
     */
    private Map<String, Object> apiData(String method, String pathWithQuery, String jsonBody,
                                        String path) {
        return apiData(method, pathWithQuery, jsonBody, path, null, null);
    }

    private Map<String, Object> apiData(String method, String pathWithQuery, String jsonBody,
                                        String path, byte[] rawBody, String rawContentType) {
        for (int attempt = 0; attempt < 2; attempt++) {
            ensureToken();
            HttpCore.Request request = new HttpCore.Request(method,
                    siteUrl + API_PREFIX + pathWithQuery)
                    .header("Authorization", "Bearer " + accessToken);
            if (rawBody != null) {
                request.header("Content-Type", rawContentType == null
                        ? "application/octet-stream" : rawContentType)
                        .body(rawBody);
            } else if (jsonBody != null) {
                request.header("Content-Type", "application/json")
                        .body(jsonBody.getBytes(StandardCharsets.UTF_8));
            }
            HttpCore.Response response = HttpCore.execute(HttpCore.configured(request, config));
            String text;
            try {
                text = response.bodyText();
            } finally {
                response.close();
            }
            Map<String, Object> json = SimpleJson.parseObject(text);
            Number code = json == null ? null : asNumber(json.get("code"));
            if (response.status() == 401
                    || (code != null && code.intValue() == StorageCodeCheckLogin)) {
                accessToken = null;
                continue;
            }
            if (response.status() >= 400 || (code != null && code.intValue() != 0)) {
                throw apiException(response.status(), json, text, path);
            }
            return json == null ? null : asMap(json.get("data"));
        }
        throw StorageException.authFailed("Cloudreve 会话不可用（令牌刷新后仍 401）");
    }

    /** Cloudreve envelope 的「未登录」码。 */
    private static final int StorageCodeCheckLogin = 401;

    /** API 错误映射（404 → NOT_FOUND，401 → AUTH，409 → CONFLICT，其余按状态码）。 */
    private StorageException apiException(int status, Map<String, Object> json, String text,
                                          String path) {
        String msg = json == null ? "" : stringOf(json.get("msg"));
        String error = json == null ? "" : stringOf(json.get("error"));
        String detail = msg.isEmpty() ? summarize(text)
                : (error.isEmpty() ? msg : msg + "（" + error + "）");
        Number code = json == null ? null : asNumber(json.get("code"));
        long codeValue = code == null ? status : code.longValue();
        if (status == 404 || codeValue == 404 || codeValue == 40016) {
            return StorageException.notFound(path);
        }
        if (codeValue == 40004) {
            return StorageException.alreadyExists(path);
        }
        if (status == 409 || codeValue == 409) {
            return StorageException.conflict(detail, path);
        }
        if (status == 401 || codeValue == 401 || codeValue == 40020) {
            return StorageException.authFailed(detail);
        }
        if (status == 403 || codeValue == 403) {
            return StorageException.authFailed(detail);
        }
        return new StorageException(status >= 500 || codeValue >= 500
                        ? StorageErrorCode.SERVER_ERROR : StorageErrorCode.IO_ERROR,
                detail, path, null);
    }

    // ================================================================ 解析

    /** 逻辑路径 → cloudreve URI（叠 basePath 前缀）。 */
    private String fileUri(String path) {
        String normalized = StoragePaths.normalize(path);
        String full = basePath.isEmpty() ? normalized
                : (normalized.isEmpty() ? basePath : basePath + "/" + normalized);
        return MY_URI + full;
    }

    /** cloudreve URI（cloudreve://my/a/b）→ 逻辑路径。 */
    private String pathOfUri(String uri) {
        String value = uri == null ? "" : uri;
        if (value.startsWith(MY_URI)) {
            value = value.substring(MY_URI.length());
        }
        if (!basePath.isEmpty() && value.startsWith(basePath + "/")) {
            value = value.substring(basePath.length() + 1);
        } else if (!basePath.isEmpty() && value.equals(basePath)) {
            value = "";
        }
        return StoragePaths.normalize(value);
    }

    /** 单文件元数据：{@code GET /file/info?uri}（目录也返回）。 */
    private Map<String, Object> fileInfo(String path) {
        String query = "uri=" + urlEncode(fileUri(path)) + "&extended=false";
        Map<String, Object> data = apiData("GET", "/file/info?" + query, null, path);
        if (data == null || data.isEmpty()) {
            throw StorageException.notFound(path);
        }
        return data;
    }

    /** FileResponse → 统一条目（type: 0=文件 1=目录）。 */
    private StorageEntry toEntry(Map<String, Object> file, String fallbackPath) {
        int type = file.get("type") instanceof Number ? ((Number) file.get("type")).intValue() : 0;
        String uri = stringOf(file.get("path"));
        String display = fallbackPath != null ? fallbackPath
                : (uri.isEmpty() ? fallbackPath : pathOfUri(uri));
        if (display == null) {
            display = stringOf(file.get("name"));
        }
        if (type == 1) {
            Number updated = updatedMillis(file);
            return updated == null
                    ? StorageEntry.dir(display)
                    : StorageEntry.dir(display, updated.longValue());
        }
        long size = file.get("size") instanceof Number ? ((Number) file.get("size")).longValue() : 0L;
        Number updated = updatedMillis(file);
        String id = stringOf(file.get("id"));
        return StorageEntry.file(display, size,
                updated == null ? StorageEntry.MTIME_UNKNOWN : updated.longValue(),
                id.isEmpty() ? null : id);
    }

    /** updated_at（RFC3339）→ epoch 毫秒；解析失败返回 null。 */
    private static Number updatedMillis(Map<String, Object> file) {
        String updated = stringOf(file.get("updated_at"));
        if (updated.isEmpty() || updated.startsWith("0001-01-01")) {
            return null;
        }
        try {
            return java.time.OffsetDateTime.parse(updated).toInstant().toEpochMilli();
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** 分页下一页标记（next_token，非游标分页时为空）。 */
    private static String paginationToken(Map<String, Object> data) {
        if (data == null) {
            return "";
        }
        Map<String, Object> pagination = asMap(data.get("pagination"));
        if (pagination == null) {
            return "";
        }
        if (pagination.get("is_cursor") instanceof Boolean && !(Boolean) pagination.get("is_cursor")) {
            return "";
        }
        return stringOf(pagination.get("next_token"));
    }

    /** 目录在前、文件在后，各自按名称排序（与界面其它引擎浏览顺序一致）。 */
    private static void sortEntries(List<StorageEntry> entries) {
        entries.sort(Comparator
                .comparing((StorageEntry e) -> !e.isDirectory())
                .thenComparing(e -> e.name().toLowerCase(Locale.ROOT)));
    }

    // ================================================================ 工具

    private static String normalizeSite(String raw) {
        String value = raw == null ? "" : raw.trim();
        while (value.endsWith("/")) {
            value = value.substring(0, value.length() - 1);
        }
        if (!value.startsWith("http://") && !value.startsWith("https://")) {
            throw StorageException.invalidConfig(
                    "Cloudreve 站点地址必须以 http:// 或 https:// 开头: " + value);
        }
        return value;
    }

    private static String requireFile(String path) {
        String normalized = StoragePaths.normalize(path);
        if (normalized.isEmpty()) {
            throw StorageException.invalidConfig("路径不能为存储根目录");
        }
        return normalized;
    }

    private static byte[] readAll(InputStream in) throws IOException {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream(8192);
        byte[] chunk = new byte[8192];
        int read;
        while ((read = in.read(chunk)) >= 0) {
            out.write(chunk, 0, read);
        }
        return out.toByteArray();
    }

    private static String summarize(String text) {
        if (text == null) {
            return "";
        }
        String flat = text.replaceAll("\\s+", " ").trim();
        return flat.length() > 200 ? flat.substring(0, 200) + "…" : flat;
    }

    private static String urlEncode(String value) {
        try {
            return URLEncoder.encode(value, "UTF-8");
        } catch (UnsupportedEncodingException e) {
            return value;
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object value) {
        return value instanceof Map ? (Map<String, Object>) value : null;
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> asMapList(Object value) {
        if (!(value instanceof List)) {
            return java.util.Collections.emptyList();
        }
        List<Map<String, Object>> result = new ArrayList<>();
        for (Object item : (List<Object>) value) {
            if (item instanceof Map) {
                result.add((Map<String, Object>) item);
            }
        }
        return result;
    }

    private static Number asNumber(Object value) {
        return value instanceof Number ? (Number) value : null;
    }

    private static String stringOf(Object value) {
        return value == null ? "" : String.valueOf(value);
    }
}
