package com.alianga.jkit.storage.engine;

import com.alianga.jkit.storage.FileStorage;
import com.alianga.jkit.storage.StorageConfig;
import com.alianga.jkit.storage.StorageEntry;
import com.alianga.jkit.storage.StorageErrorCode;
import com.alianga.jkit.storage.StorageException;
import com.alianga.jkit.storage.StoragePaths;
import com.alianga.jkit.storage.UploadMetadata;
import com.alianga.jkit.storage.internal.DogeSigners;
import com.alianga.jkit.storage.internal.HttpCore;
import com.alianga.jkit.storage.internal.MimeTypes;
import com.alianga.jkit.storage.internal.SimpleJson;
import com.alianga.jkit.storage.internal.Urls;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 多吉云引擎：付费桶（标准型，S3 兼容协议）与基础桶（基础型，控制台会话 API）双通道实现。
 *
 * <p><b>付费桶</b>（官方原生支持，无额外适配）：控制台密钥对经管理 API
 * {@code /auth/tmp_token.json} 换取腾讯云 STS 临时凭据（约 2 小时，自动刷新），
 * 桶本体走标准 SigV4 + 会话令牌 + 虚拟主机寻址（复用 {@link S3FileStorage}）。</p>
 *
 * <p><b>基础桶</b>（官方无公开 API，本引擎独立调研适配）：官方明确「基础型不支持
 * API/SDK，只能控制台或临时链接/加速域名访问」。实际承载是控制台 Web 会话——
 * {@code POST /user/login.json}（密码登录，{@code noguess=md5(password+"~dogecloud~password")}）
 * 换取会话 cookie，{@code GET /v2/console/v1/session.json} 拿 CSRF，
 * 之后调 {@code /v2/console/v1/oss/*}（list / sign / upload/put / file/delete / file/info，
 * 写操作需 {@code X-Doge-Csrf} + 唯一 {@code X-Doge-Request-Id} 头）。2026-10-21 实测
 * meitu 基础桶全链路（上传/列表/临时链接/下载/删除）通过。</p>
 *
 * <p><b>直链与鉴权</b>：{@link #directLinkOf(String)} 返回自定义域名直链（需已绑定加速域名）；
 * {@link #urlAuthEnabled()} 读取绑定域名的 URL 鉴权开关（CDN 配置 {@code config.authKey}）。
 * 未开启 URL 鉴权时直链可直接访问；开启时直链会 403，须改用 {@link #temporaryLink(String)}
 * （官方 {@code file/sign} 签发的 10 分钟临时链接）。</p>
 *
 * <p>配置键（付费桶）：accessKey、secretKey（控制台密钥对）、bucket（业务桶名）、
 * region（留空自动从桶端点推导）、basePath、listVersion。配置键（基础桶）：
 * bucketType=free、bucket（业务桶名）、user、password（控制台账号密码）、
 * domain（自定义域名，未绑定时直链不可用）。</p>
 *
 * @author 郑明亮
 */
public class DogeFileStorage implements FileStorage {
    /** 管理与控制台 API 基址。 */
    /** 生产 API 基址（可用 apiEndpoint 配置覆盖，测试用）。 */
    private static final String API_BASE = "https://api.dogecloud.com";
    /** 控制台 v2 API 前缀（基础桶）。 */
    private static final String V2_OSS = "/v2/console/v1/oss/";
    /** 临时凭据提前刷新余量（毫秒）。 */
    private static final long EXPIRY_SLACK_MS = 5L * 60L * 1000L;
    /** 基础桶列表分页大小。 */
    private static final int BASIC_LIST_PAGE = 500;

    private final StorageConfig config;
    private final boolean basic;
    private final String bucket;
    private final String accessKey;
    private final String secretKey;
    private final String user;
    private final String password;
    private final String domain;
    private final String basePath;
    private final String listVersion;
    /** 临时链接时效（秒）：仅对 SigV4 预签名通道生效；基础桶 file/sign 官方固定约 10 分钟不可配。 */
    private final long temporaryLinkTtlSeconds;
    /** API 基址（测试可覆盖；生产固定 https://api.dogecloud.com）。 */
    private final String apiBase;
    /** 付费桶 S3 寻址风格（测试假服务器用；生产恒为虚拟主机）。 */
    private final boolean pathStyle;

    /** 付费桶：底层 S3 实例（临时凭据刷新时整体替换）。 */
    private volatile S3FileStorage delegate;
    private volatile long expiresAt;
    private final ReentrantLock refreshLock = new ReentrantLock();

    /** 基础桶：会话 cookie 与 CSRF（过期自动重新登录）。 */
    private volatile String sessionCookie;
    private volatile String csrf;
    private final ReentrantLock sessionLock = new ReentrantLock();
    /** 域名鉴权缓存：最近一次探测到的（桶 → 是否开启 URL 鉴权）。 */
    private volatile Boolean urlAuthEnabledCache;

    /**
     * 由配置创建（配置键见类注释）。
     *
     * @param config 连接配置
     */
    public DogeFileStorage(StorageConfig config) {
        this.config = config;
        this.basic = "free".equalsIgnoreCase(config.getOr("bucketType", ""));
        this.bucket = config.require("bucket");
        this.accessKey = config.getOr("accessKey", "");
        this.secretKey = config.getOr("secretKey", "");
        this.user = config.getOr("user", "");
        this.password = config.getOr("password", "");
        this.domain = config.getOr("domain", "");
        this.basePath = StoragePaths.normalize(config.getOr("basePath", ""));
        this.listVersion = config.getOr("listVersion", "2");
        this.apiBase = config.getOr("apiEndpoint", "https://api.dogecloud.com");
        this.pathStyle = config.boolOr("pathStyle", false);
        // 临时链接时效：60s ~ 7d（SigV4 X-Amz-Expires 上限）；基础桶 file/sign 固定 10 分钟，该配置不生效
        this.temporaryLinkTtlSeconds = Math.min(7L * 24 * 3600,
                Math.max(60, config.longOr("temporaryLinkTtl", 600)));
        if (basic) {
            if (user.isEmpty() || password.isEmpty()) {
                throw StorageException.invalidConfig(
                        "多吉云基础桶需要控制台账号（user / password）：基础型不支持 API 密钥");
            }
        } else if (accessKey.isEmpty() || secretKey.isEmpty()) {
            throw StorageException.invalidConfig("多吉云付费桶缺少密钥（accessKey / secretKey）");
        }
    }

    @Override
    public String platform() {
        return "doge";
    }

    // ================================================================ FileStorage 委托

    @Override
    public boolean exists(String path) {
        String normalized = StoragePaths.normalize(path);
        if (normalized.isEmpty()) {
            return true;
        }
        if (basic) {
            return basicList(normalized, 1).size() > 0;
        }
        return delegate().exists(normalized);
    }

    @Override
    public StorageEntry stat(String path) {
        String normalized = StoragePaths.normalize(path);
        if (normalized.isEmpty()) {
            return StorageEntry.dir("", 0L);
        }
        if (basic) {
            return basicStat(normalized);
        }
        return delegate().stat(normalized);
    }

    @Override
    public List<StorageEntry> list(String dir) {
        if (basic) {
            return basicList(dir, Integer.MAX_VALUE);
        }
        return delegate().list(dir);
    }

    @Override
    public void mkdir(String dir) {
        String normalized = StoragePaths.normalize(dir);
        if (normalized.isEmpty() || exists(normalized)) {
            return;
        }
        if (basic) {
            // 基础桶目录即前缀：upload/put 一个 0 字节的「目录对象」（application/x-directory）
            basicPut(normalized + "/", new byte[0], "application/x-directory");
            return;
        }
        delegate().mkdir(normalized);
    }

    @Override
    public void upload(String path, InputStream in, long size) {
        upload(path, in, size, null);
    }

    @Override
    public void upload(String path, InputStream in, long size, UploadMetadata meta) {
        String normalized = requireFile(path);
        String contentType = meta != null && meta.contentType() != null
                ? meta.contentType() : MimeTypes.of(normalized);
        if (basic) {
            byte[] data;
            try {
                data = readAll(in);
            } catch (IOException e) {
                throw StorageException.io("读取上传流失败: " + e.getMessage(), normalized, e);
            }
            basicPut(normalized, data, contentType);
            return;
        }
        delegate().upload(normalized, in, size, meta);
    }

    @Override
    public void download(String path, OutputStream out) {
        download(path, -1L, out);
    }

    @Override
    public boolean supportsRange() {
        if (basic) {
            // 临时链接指向 COS（支持 Range），但引擎内不依赖 Range，直接声明不支持
            return false;
        }
        return delegate().supportsRange();
    }

    @Override
    public void download(String path, long offset, OutputStream out) {
        String normalized = requireFile(path);
        if (basic) {
            if (offset > 0) {
                throw StorageException.unsupported("基础桶不支持断点续传: " + normalized);
            }
            basicDownload(normalized, out);
            return;
        }
        delegate().download(normalized, offset, out);
    }

    @Override
    public void delete(String path) {
        String normalized = StoragePaths.normalize(path);
        if (normalized.isEmpty()) {
            throw StorageException.invalidConfig("拒绝删除存储根目录");
        }
        if (basic) {
            basicDelete(normalized);
            return;
        }
        delegate().delete(normalized);
    }

    @Override
    public void copy(String src, String dst) {
        if (basic) {
            throw StorageException.unsupported("基础桶不支持服务端复制: " + bucket);
        }
        delegate().copy(src, dst);
    }

    @Override
    public void rename(String src, String dst) {
        if (basic) {
            throw StorageException.unsupported("基础桶不支持重命名: " + bucket);
        }
        delegate().rename(src, dst);
    }

    @Override
    public String presign(String path, java.time.Duration ttl) {
        String normalized = requireFile(path);
        if (basic) {
            return temporaryLink(normalized);
        }
        return delegate().presign(normalized, ttl);
    }

    @Override
    public void close() {
        S3FileStorage current = delegate;
        if (current != null) {
            current.close();
        }
    }

    // ================================================================ 链接能力（直链 / 临时链接 / URL 鉴权）

    /**
     * 对象的自定义域名直链（复制链接用）：绑定域名未开启 URL 鉴权时可直接访问；
     * 开启了 URL 鉴权（或未绑定域名）时该链接不可用，改用 {@link #temporaryLink(String)}。
     *
     * <p>域名优先级：用户显式配置的 {@code domain} &gt; 桶绑定的已启用加速域名（自动探测）。
     * 探测不到任何可用域名时抛 {@code UNSUPPORTED}。</p>
     *
     * @param path 对象路径
     * @return 直链 URL
     */
    public String directLinkOf(String path) {
        String normalized = requireFile(path);
        String host = linkDomain();
        if (host == null || host.isEmpty()) {
            throw StorageException.unsupported(
                    "桶 " + bucket + " 未绑定可用的自定义域名，无法生成直链；"
                            + "请在连接配置里填写「自定义域名」，或补充控制台账号（user / password）以自动探测");
        }
        String scheme = host.startsWith("https://") || host.startsWith("http://")
                ? host.substring(0, host.indexOf("://")) : "https";
        String name = host.replaceFirst("^https?://", "").replaceFirst("/$", "");
        return scheme + "://" + name + "/" + Urls.encodePath(objectKey(normalized));
    }

    /**
     * 官方 {@code file/sign} 签发的临时访问链接。基础桶走官方接口（固定约 10 分钟，
     * 官方不支持自定义时效）；付费桶走 SigV4 预签名，时效由 {@code temporaryLinkTtl}
     * 配置控制（60 秒 ~ 7 天，默认 10 分钟）。
     *
     * @param path 对象路径
     * @return 临时链接
     */
    public String temporaryLink(String path) {
        String normalized = requireFile(path);
        if (basic) {
            return basicSign(normalized);
        }
        return delegate().presign(normalized, java.time.Duration.ofSeconds(temporaryLinkTtlSeconds));
    }

    /**
     * 临时链接的实际有效秒数（界面提示用）：基础桶固定约 600 秒（官方不可配）；
     * 付费桶为配置值（SigV4 presign 支持）。
     *
     * @return 有效秒数
     */
    public long temporaryLinkTtlSeconds() {
        return basic ? 600L : temporaryLinkTtlSeconds;
    }

    /**
     * 临时链接时效是否可由用户配置：基础桶 file/sign 官方固定不可配（false）；
     * 付费桶 SigV4 presign 可配（true）。
     *
     * @return true 表示时效可配置
     */
    public boolean temporaryLinkTtlConfigurable() {
        return !basic;
    }

    /**
     * 当前桶是否具备「自定义域名直链」能力（复制链接按钮显隐用）：
     * 显式配置了 domain，或（配置了控制台账号时）自动探测到绑定的加速域名。
     *
     * @return true 表示直链可用
     */
    public boolean directLinkAvailable() {
        return linkDomain() != null && !linkDomain().isEmpty();
    }

    /**
     * 当前桶的直链域名是否开启了 URL 鉴权（CDN {@code config.authKey.switch}）。
     * 结果按域名缓存；域名读取失败按「未开启」处理（直链兜底策略）。
     *
     * @return true 表示已开启（直链会 403，复制链接应改用临时链接）
     */
    public boolean urlAuthEnabled() {
        String host = linkDomain();
        if (host == null || host.isEmpty()) {
            return false;
        }
        Boolean cached = urlAuthEnabledCache;
        if (cached != null) {
            return cached;
        }
        boolean enabled = detectUrlAuth(host);
        urlAuthEnabledCache = enabled;
        return enabled;
    }

    /** 清空 URL 鉴权检测结果缓存（域名配置变更后主动刷新用）。 */
    public void clearUrlAuthCache() {
        urlAuthEnabledCache = null;
    }

    /** 直链域名：显式配置优先，其次探测桶绑定的已启用加速域名。 */
    private String linkDomain() {
        if (!domain.isEmpty()) {
            return domain;
        }
        // 自动探测域名需要控制台会话；仅凭 API 密钥（未配账号密码）时跳过探测
        if (user.isEmpty() || password.isEmpty()) {
            return null;
        }
        try {
            DomainInfo info = fetchDefaultDomain();
            return info == null ? null : info.name;
        } catch (RuntimeException e) {
            // 探测失败（登录失效 / 网络异常）不阻断直链生成
            return null;
        }
    }

    /** 通过 CDN v1 接口读取域名配置，判断 URL 鉴权开关。 */
    private boolean detectUrlAuth(String host) {
        String name = host.replaceFirst("^https?://", "").replaceFirst("/$", "");
        try {
            ensureSession();
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("id", "");
            // v1 域名信息接口按域名查询（控制台「域名与网站」用）
            Map<String, String> headers = new LinkedHashMap<>();
            headers.put("Authorization", "COOKIE");
            headers.put("Cookie", sessionCookie);
            headers.put("Content-Type", "application/x-www-form-urlencoded");
            byte[] payload = ("id=" + URLEncoder.encode(name, "UTF-8")).getBytes(StandardCharsets.UTF_8);
            HttpCore.Response response = HttpCore.execute(HttpCore.configured(
                    new HttpCore.Request("POST", apiBase + "/cdn/domain/info.json")
                            .header("Content-Type", "application/x-www-form-urlencoded")
                            .header("Authorization", "COOKIE")
                            .header("Cookie", sessionCookie)
                            .body(payload), config));
            String text;
            try {
                text = response.bodyText();
            } finally {
                response.close();
            }
            if (response.status() >= 400) {
                return false;
            }
            Map<String, Object> json = SimpleJson.parseObject(text);
            Map<String, Object> data = json == null ? null : asMap(json.get("data"));
            Map<String, Object> cfg = data == null ? null : asMap(data.get("config"));
            if (cfg == null) {
                return false;
            }
            Map<String, Object> authKey = asMap(cfg.get("authKey"));
            Object sw = authKey == null ? null : authKey.get("switch");
            return sw != null && (Integer.toString(1).equals(String.valueOf(sw))
                    || "true".equalsIgnoreCase(String.valueOf(sw)));
        } catch (RuntimeException | UnsupportedEncodingException e) {
            return false;
        }
    }

    // ================================================================ 付费桶通道

    private S3FileStorage delegate() {
        S3FileStorage current = delegate;
        if (current == null || System.currentTimeMillis() + EXPIRY_SLACK_MS >= expiresAt) {
            refresh(false);
        }
        return delegate;
    }

    private void refresh(boolean force) {
        refreshLock.lock();
        try {
            if (!force && delegate != null
                    && System.currentTimeMillis() + EXPIRY_SLACK_MS < expiresAt) {
                return;
            }
            TempToken token = requestTempToken();
            expiresAt = token.expiredAt;
            delegate = newS3Storage(token);
        } finally {
            refreshLock.unlock();
        }
    }

    private S3FileStorage newS3Storage(TempToken token) {
        String endpoint = token.endpoint;
        String s3Bucket = token.s3Bucket;
        StorageConfig s3Config = StorageConfig.empty();
        s3Config.set("endpoint", endpoint);
        s3Config.set("bucket", s3Bucket);
        s3Config.set("accessKey", token.accessKeyId);
        s3Config.set("secretKey", token.secretAccessKey);
        s3Config.set("sessionToken", token.sessionToken);
        s3Config.set("region", resolveRegion(endpoint));
        s3Config.set("pathStyle", String.valueOf(pathStyle));
        if (!basePath.isEmpty()) {
            s3Config.set("basePath", basePath);
        }
        s3Config.set("listVersion", listVersion);
        String proxyHost = config.getOr("proxyHost", "");
        String proxyPort = config.get("proxyPort");
        if (!proxyHost.isEmpty() && proxyPort != null) {
            s3Config.set("proxyHost", proxyHost);
            s3Config.set("proxyPort", proxyPort);
        }
        return new S3FileStorage(s3Config);
    }

    /** SigV4 区域：优先显式配置，其次从桶端点第二段推导（cos.ap-beijing.myqcloud.com → ap-beijing）。 */
    private String resolveRegion(String endpoint) {
        String explicit = config.getOr("region", "");
        if (!explicit.isEmpty()) {
            return explicit;
        }
        String host = endpoint.replaceFirst("^https?://", "");
        String[] parts = host.split("\\.");
        return parts.length >= 3 ? parts[1] : "ap-beijing";
    }

    /** TOKEN 签名调 {@code /auth/tmp_token.json} 换临时凭据 + 桶清单。 */
    private TempToken requestTempToken() {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("channel", "OSS_FULL");
        payload.put("scopes", java.util.Collections.singletonList("*"));
        byte[] body = SimpleJson.toJson(payload).getBytes(StandardCharsets.UTF_8);
        String apiPath = "/auth/tmp_token.json";
        HttpCore.Response response = HttpCore.execute(HttpCore.configured(
                new HttpCore.Request("POST", apiBase + apiPath)
                        .header("Content-Type", "application/json")
                        .header("Authorization",
                                "TOKEN " + accessKey + ":" + DogeSigners.signature(secretKey, apiPath, body))
                        .body(body), config));
        String text;
        try {
            text = response.bodyText();
        } finally {
            response.close();
        }
        Map<String, Object> root = SimpleJson.parseObject(text);
        Number code = root == null ? null : asNumber(root.get("code"));
        if (code == null || code.intValue() != 200) {
            throw StorageException.authFailed("多吉云临时凭据获取失败: " + summarize(text));
        }
        Map<String, Object> data = asMap(root.get("data"));
        Map<String, Object> credentials = data == null ? null : asMap(data.get("Credentials"));
        Number expiredAt = data == null ? null : asNumber(data.get("ExpiredAt"));
        List<Map<String, Object>> buckets = data == null ? null : asMapList(data.get("Buckets"));
        if (credentials == null || expiredAt == null) {
            throw StorageException.serverError("多吉云临时凭据响应缺少 Credentials / ExpiredAt", "");
        }
        return new TempToken(credentials, buckets, expiredAt.longValue() * 1000L);
    }

    /** 临时凭据 + 桶清单（s3Bucket / s3Endpoint / name 三重匹配）。 */
    private final class TempToken {
        final String accessKeyId;
        final String secretAccessKey;
        final String sessionToken;
        final long expiredAt;
        final String endpoint;
        final String s3Bucket;

        TempToken(Map<String, Object> credentials, List<Map<String, Object>> buckets, long expiredAt) {
            this.accessKeyId = stringOf(credentials.get("accessKeyId"));
            this.secretAccessKey = stringOf(credentials.get("secretAccessKey"));
            this.sessionToken = stringOf(credentials.get("sessionToken"));
            this.expiredAt = expiredAt;
            String ep = null;
            String s3 = null;
            for (Map<String, Object> item : buckets) {
                String name = stringOf(item.get("name"));
                String s3Bucket = stringOf(item.get("s3Bucket"));
                if (bucket.equals(s3Bucket) || bucket.equals(name)) {
                    ep = stringOf(item.get("s3Endpoint"));
                    s3 = s3Bucket;
                    break;
                }
            }
            if (s3 == null) {
                StringBuilder names = new StringBuilder();
                for (Map<String, Object> item : buckets) {
                    if (names.length() > 0) {
                        names.append(", ");
                    }
                    names.append(stringOf(item.get("name")));
                }
                throw StorageException.invalidConfig(
                        "多吉云账号下不存在桶 " + bucket + "（可用: " + names + "）");
            }
            this.endpoint = ep;
            this.s3Bucket = s3;
        }
    }

    // ================================================================ 基础桶通道（控制台会话 API）

    /** 基础桶对象 key（basePath 前缀）。 */
    private String objectKey(String path) {
        String normalized = StoragePaths.normalize(path);
        if (basePath.isEmpty()) {
            return normalized;
        }
        return basePath + (normalized.isEmpty() ? "" : "/" + normalized);
    }

    /** 确保控制台会话可用（cookie + CSRF），失效则重新登录。 */
    private void ensureSession() {
        sessionLock.lock();
        try {
            if (sessionCookie != null && csrf != null && !csrf.isEmpty()) {
                return;
            }
            basicLogin();
        } finally {
            sessionLock.unlock();
        }
    }

    /** {@code POST /user/login.json}（ltype=password + noguess md5）。 */
    private void basicLogin() {
        String noguess = md5Hex(password + "~dogecloud~password");
        StringBuilder form = new StringBuilder();
        form.append("ltype=password")
                .append("&phone=").append(urlEncode(user))
                .append("&noguess=").append(noguess)
                .append("&remember=1");
        HttpCore.Response response = HttpCore.execute(HttpCore.configured(
                new HttpCore.Request("POST", apiBase + "/user/login.json")
                        .header("Content-Type", "application/x-www-form-urlencoded")
                        .body(form.toString().getBytes(StandardCharsets.UTF_8)), config));
        String text;
        try {
            text = response.bodyText();
        } finally {
            response.close();
        }
        Map<String, Object> json = SimpleJson.parseObject(text);
        Number code = json == null ? null : asNumber(json.get("code"));
        if (code == null || code.intValue() != 200) {
            throw StorageException.authFailed("多吉云控制台登录失败: " + summarize(text));
        }
        // 登录 Set-Cookie（token=...）在响应头里
        String cookie = cookieOf(response, "token");
        if (cookie == null || cookie.isEmpty()) {
            throw StorageException.authFailed("多吉云控制台登录未返回会话 token");
        }
        sessionCookie = cookie;
        fetchSession();
    }

    /** {@code GET /v2/console/v1/session.json} 拿 CSRF。 */
    private void fetchSession() {
        HttpCore.Response response = HttpCore.execute(HttpCore.configured(
                new HttpCore.Request("GET", apiBase + "/v2/console/v1/session.json")
                        .header("Cookie", sessionCookie), config));
        String text;
        try {
            text = response.bodyText();
        } finally {
            response.close();
        }
        Map<String, Object> json = SimpleJson.parseObject(text);
        Number code = json == null ? null : asNumber(json.get("code"));
        Map<String, Object> data = json == null ? null : asMap(json.get("data"));
        String token = data == null ? null : stringOf(data.get("csrf"));
        if (code == null || code.intValue() != 200 || token == null || token.isEmpty()) {
            throw StorageException.authFailed("多吉云控制台会话获取失败: " + summarize(text));
        }
        csrf = token;
    }

    /** 基础桶 v2 GET（自动带 cookie；401 时重新登录一次）。 */
    private Map<String, Object> basicGet(String path, String query) {
        Object data = basicRequest("GET", path, query, null, null);
        return data instanceof Map ? asMap(data) : null;
    }

    /** 基础桶 v2 POST（带 CSRF 与唯一 Request-Id），data 为对象。 */
    private Map<String, Object> basicPost(String path, String query, String contentType, byte[] body) {
        Object data = basicRequest("POST", path, query, contentType, body);
        return data instanceof Map ? asMap(data) : null;
    }

    /**
     * 基础桶 v2 请求主实现（GET / POST 统一）：
     * 自动带 cookie；POST 补 CSRF 与唯一 Request-Id；401 时重新登录重试一次。
     * 返回 envelope 的 data 字段（可为对象 / 数组 / 标量）。
     */
    private Object basicRequest(String method, String path, String query,
                                String contentType, byte[] body) {
        for (int attempt = 0; attempt < 2; attempt++) {
            ensureSession();
            String url = apiBase + V2_OSS + path + (query == null || query.isEmpty() ? "" : "?" + query);
            HttpCore.Request request = new HttpCore.Request(method, url)
                    .header("Cookie", sessionCookie);
            if ("POST".equals(method)) {
                String requestId = "req_" + UUID.randomUUID().toString().replace("-", "").substring(0, 20);
                request.header("Content-Type", contentType == null ? "application/json" : contentType)
                        .header("X-Doge-Csrf", csrf)
                        .header("X-Doge-Request-Id", requestId)
                        .body(body == null ? new byte[0] : body);
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
            if (code != null && code.intValue() == 200) {
                return json.get("data");
            }
            if (response.status() == 401 || (code != null && code.intValue() == 401)) {
                sessionCookie = null;
                csrf = null;
                continue;
            }
            throw basicException(response.status(), text);
        }
        throw StorageException.authFailed("多吉云控制台会话不可用");
    }

    /** 基础桶错误映射（404 → NOT_FOUND，403/401 → AUTH，409 → CONFLICT，其余按状态码）。 */
    private StorageException basicException(int status, String text) {
        Map<String, Object> json = SimpleJson.parseObject(text);
        String error = json == null ? "" : stringOf(json.get("error"));
        String msg = json == null ? "" : stringOf(json.get("msg"));
        String detail = msg.isEmpty() ? summarize(text) : msg + "（" + error + "）";
        if (status == 404 || "ERROR_OSS_OBJECT_NOT_FOUND".equals(error)) {
            return StorageException.notFound(detail);
        }
        if (status == 409) {
            return new StorageException(StorageErrorCode.CONFLICT, detail, "", null);
        }
        if (status == 403 || status == 401) {
            return StorageException.authFailed(detail);
        }
        return new StorageException(status >= 500 ? StorageErrorCode.SERVER_ERROR : StorageErrorCode.IO_ERROR,
                detail, "", null);
    }

    /**
     * 基础桶 stat：优先 {@code file/info.json}（精确大小与时间，批量 20 个的接口单查一个），
     * 未命中回退前缀列表探测（目录）。
     */
    private StorageEntry basicStat(String path) {
        String key = objectKey(path);
        String body = SimpleJson.toJson(java.util.Collections.singletonList(key));
        try {
            Object data = basicRequest("POST", "file/info.json",
                    "bucket=" + urlEncode(bucket), "application/json", body.getBytes(StandardCharsets.UTF_8));
            List<Map<String, Object>> rows = asMapList(data);
            if (rows != null && !rows.isEmpty()) {
                Map<String, Object> row = rows.get(0);
                if ("missing".equals(stringOf(row.get("state")))) {
                    throw StorageException.notFound(path);
                }
                long size = row.get("fsize") instanceof Number ? ((Number) row.get("fsize")).longValue() : 0L;
                return StorageEntry.file(path, size, StorageEntry.MTIME_UNKNOWN,
                        stringOf(row.get("etag")));
            }
        } catch (StorageException e) {
            if (e.code() != StorageErrorCode.NOT_FOUND) {
                throw e;
            }
        }
        // 目录探测：前缀列表有子项即目录
        List<StorageEntry> children = basicList(path, 1);
        if (!children.isEmpty()) {
            return StorageEntry.dir(path);
        }
        throw StorageException.notFound(path);
    }

    /**
     * 基础桶列表：{@code file/list.json?bucket&prefix&limit}。官方无 delimiter 参数，
     * 返回该前缀下全部后代条目（key 含完整路径），按 S3 语义折叠出「一层目录」。
     */
    private List<StorageEntry> basicList(String dir, int limit) {
        String prefix = StoragePaths.normalize(dir);
        String fullPrefix = objectKey(prefix);
        if (!fullPrefix.isEmpty() && !fullPrefix.endsWith("/")) {
            fullPrefix = fullPrefix + "/";
        }
        String query = "bucket=" + urlEncode(bucket)
                + "&limit=" + BASIC_LIST_PAGE
                + "&view=folder"
                + "&prefix=" + urlEncode(fullPrefix);
        List<StorageEntry> entries = new ArrayList<>();
        List<String> keys = new ArrayList<>();
        collectBasicList(query, keys, limit);
        for (String key : keys) {
            String relative = relativeOf(key, fullPrefix);
            if (relative.isEmpty()) {
                continue;
            }
            int slash = relative.indexOf('/');
            if (slash >= 0) {
                String dirName = relative.substring(0, slash);
                String dirPath = StoragePaths.join(prefix, dirName);
                if (!containsDir(entries, dirPath)) {
                    entries.add(StorageEntry.dir(dirPath));
                }
            } else {
                String filePath = StoragePaths.join(prefix, relative);
                // 单条元数据延迟到 stat（list 不为每条发 file/info，避免放大）
                entries.add(StorageEntry.file(filePath, StorageEntry.SIZE_UNKNOWN,
                        StorageEntry.MTIME_UNKNOWN, null));
            }
            if (entries.size() >= limit) {
                break;
            }
        }
        return entries;
    }

    /** 拉一页基础桶列表（后续翻页用 continue 标记；此处单页 500 足够常规目录）。 */
    private void collectBasicList(String query, List<String> keys, int limit) {
        Map<String, Object> data = basicGet("file/list.json", query);
        List<Map<String, Object>> files = data == null ? null : asMapList(data.get("files"));
        if (files == null) {
            return;
        }
        for (Map<String, Object> f : files) {
            keys.add(stringOf(f.get("key")));
            if (keys.size() >= limit) {
                return;
            }
        }
        // 官方 continue 翻页标记
        String next = data == null ? "" : stringOf(data.get("continue"));
        if (!next.isEmpty() && keys.size() < limit) {
            collectBasicList(query.replaceAll("(^|&)continue=[^&]*", "$1") + "&continue=" + urlEncode(next),
                    keys, limit);
        }
    }

    /** 基础桶上传：{@code upload/put.json?spaceId&key&force}，body 为对象内容。 */
    private void basicPut(String path, byte[] data, String contentType) {
        String key = objectKey(path);
        String spaceId = requireSpaceId();
        String query = "spaceId=" + urlEncode(spaceId)
                + "&key=" + urlEncode(key)
                + "&force=1";
        Map<String, Object> result = basicPost("upload/put.json", query, contentType, data);
        String state = result == null ? "" : stringOf(result.get("state"));
        if (!state.isEmpty() && !"succeeded".equals(state)) {
            throw StorageException.io("基础桶上传未确认成功（state=" + state + "）", path, null);
        }
    }

    /** 基础桶下载：签发临时链接后跟随下载（链接指向 COS，无凭据头）。 */
    private void basicDownload(String path, OutputStream out) {
        String url = basicSign(path);
        HttpCore.Response response = HttpCore.execute(HttpCore.configured(
                new HttpCore.Request("GET", url), config));
        try {
            if (response.status() >= 400) {
                throw StorageException.io("基础桶下载失败（HTTP " + response.status() + "）", path, null);
            }
            byte[] chunk = new byte[8192];
            int read;
            try {
                while ((read = response.body().read(chunk)) >= 0) {
                    out.write(chunk, 0, read);
                }
            } catch (IOException e) {
                throw StorageException.io("基础桶下载失败: " + e.getMessage(), path, e);
            }
        } finally {
            response.close();
        }
    }

    /** 基础桶删除：{@code file/delete.json?bucket}，body 为 key 数组。 */
    private void basicDelete(String path) {
        String key = objectKey(path);
        String body = SimpleJson.toJson(java.util.Collections.singletonList(key));
        Map<String, Object> result = basicPost("file/delete.json",
                "bucket=" + urlEncode(bucket), "application/json", body.getBytes(StandardCharsets.UTF_8));
        // state=processing 时操作已受理（最终一致），视为成功
    }

    /** 基础桶临时链接：{@code file/sign.json?bucket&key(b64url)}。 */
    private String basicSign(String path) {
        String key = objectKey(path);
        String query = "bucket=" + urlEncode(bucket)
                + "&key=" + urlEncode(b64Url(key.getBytes(StandardCharsets.UTF_8)));
        Map<String, Object> data = basicGet("file/sign.json", query);
        String url = data == null ? "" : stringOf(data.get("url"));
        if (url.isEmpty()) {
            throw StorageException.serverError("基础桶临时链接签发未返回 url", path);
        }
        return url;
    }

    /** 桶绑定的默认域名（{@code bucket/default-domain.json?name}）。 */
    private DomainInfo fetchDefaultDomain() {
        Map<String, Object> data = basicGet("bucket/default-domain.json",
                "name=" + urlEncode(bucket));
        if (data == null) {
            return null;
        }
        List<Map<String, Object>> domains = asMapList(data.get("domains"));
        String configuredId = stringOf(data.get("configuredDomainId"));
        boolean configuredAvailable = Boolean.parseBoolean(stringOf(data.get("configuredDomainAvailable")));
        String defaultId = stringOf(data.get("defaultDomainId"));
        Map<String, Object> primary = null;
        String primaryId = configuredAvailable && !configuredId.isEmpty() ? configuredId : defaultId;
        for (Map<String, Object> item : domains) {
            if (!"true".equalsIgnoreCase(stringOf(item.get("enabled")))
                    && !"1".equals(stringOf(item.get("enabled")))) {
                continue;
            }
            String id = stringOf(item.get("id"));
            if ((primary == null && !id.isEmpty()) || id.equals(primaryId)) {
                primary = item;
                if (id.equals(primaryId)) {
                    break;
                }
            }
        }
        if (primary == null) {
            return null;
        }
        DomainInfo info = new DomainInfo();
        info.id = stringOf(primary.get("id"));
        info.name = stringOf(primary.get("name"));
        info.tlsConfigured = Boolean.parseBoolean(stringOf(primary.get("tlsConfigured")));
        info.urlAuthEnabled = detectUrlAuth(info.name);
        return info;
    }

    /** 桶的 spaceId（基础桶 upload 需要；用 spaces/list 按名反查，结果缓存）。 */
    private volatile String cachedSpaceId;

    private String requireSpaceId() {
        String cached = cachedSpaceId;
        if (cached != null && !cached.isEmpty()) {
            return cached;
        }
        Map<String, Object> list = basicGet("spaces/list.json",
                "search=" + urlEncode(bucket) + "&pageSize=20&sort=name&direction=asc");
        List<Map<String, Object>> spaces = list == null ? null : asMapList(list.get("items"));
        if ((spaces == null || spaces.isEmpty())) {
            spaces = list == null ? null : asMapList(list.get("spaces"));
        }
        String found = "";
        if (spaces != null) {
            for (Map<String, Object> s : spaces) {
                if (bucket.equals(stringOf(s.get("name")))) {
                    found = stringOf(s.get("id"));
                    if (found.isEmpty()) {
                        found = stringOf(s.get("spaceId"));
                    }
                    break;
                }
            }
        }
        if (found.isEmpty()) {
            throw StorageException.notFound("多吉云基础桶 " + bucket + "（spaces/list 未返回该桶）");
        }
        cachedSpaceId = found;
        return found;
    }

    /** 域名信息。 */
    private static final class DomainInfo {
        String id;
        String name;
        boolean tlsConfigured;
        boolean urlAuthEnabled;
    }

    // ================================================================ 工具

    private static String relativeOf(String key, String fullPrefix) {
        if (!fullPrefix.isEmpty() && key.startsWith(fullPrefix)) {
            return key.substring(fullPrefix.length());
        }
        return key;
    }

    private static boolean containsDir(List<StorageEntry> entries, String path) {
        for (StorageEntry entry : entries) {
            if (entry.path().equals(path)) {
                return true;
            }
        }
        return false;
    }

    private static String requireFile(String path) {
        String normalized = StoragePaths.normalize(path);
        if (normalized.isEmpty()) {
            throw StorageException.invalidConfig("路径不能为存储根目录");
        }
        return normalized;
    }

    private static String summarize(String text) {
        if (text == null) {
            return "";
        }
        String flat = text.replaceAll("\\s+", " ").trim();
        return flat.length() > 200 ? flat.substring(0, 200) + "…" : flat;
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

    private static String urlEncode(String value) {
        try {
            return URLEncoder.encode(value, "UTF-8");
        } catch (UnsupportedEncodingException e) {
            return value;
        }
    }

    private static String b64Url(byte[] data) {
        return java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(data);
    }

    private static String md5Hex(String text) {
        try {
            byte[] digest = MessageDigest.getInstance("MD5")
                    .digest(text.getBytes(StandardCharsets.UTF_8));
            StringBuilder out = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                out.append(Character.forDigit((b >> 4) & 0xF, 16));
                out.append(Character.forDigit(b & 0xF, 16));
            }
            return out.toString();
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException("缺少 MD5", e);
        }
    }

    private static String cookieOf(HttpCore.Response response, String name) {
        for (Map.Entry<String, List<String>> entry : response.headers().entrySet()) {
            if (entry.getKey() == null || !"set-cookie".equalsIgnoreCase(entry.getKey())) {
                continue;
            }
            for (String value : entry.getValue()) {
                if (value != null && value.startsWith(name + "=")) {
                    String pair = value.split(";", 2)[0];
                    return pair;
                }
            }
        }
        return null;
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
