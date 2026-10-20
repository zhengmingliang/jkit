package com.alianga.jkit.storage.engine;

import com.alianga.jkit.json.JSON;
import com.alianga.jkit.storage.FileStorage;
import com.alianga.jkit.storage.StorageConfig;
import com.alianga.jkit.storage.StorageEntry;
import com.alianga.jkit.storage.StorageException;
import com.alianga.jkit.storage.StoragePaths;
import com.alianga.jkit.storage.UploadMetadata;
import com.alianga.jkit.storage.internal.DogeSigners;
import com.alianga.jkit.storage.internal.HttpCore;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 多吉云引擎：管理 API（{@code api.dogecloud.com}）+ S3 兼容协议的组合实现，零第三方依赖。
 *
 * <p>多吉云不发放长期 S3 密钥：控制台密钥管理里拿到的是「密钥对」，用它对管理 API 做
 * {@code TOKEN} 签名（{@link DogeSigners}）调 {@code /auth/tmp_token.json}
 * （{@code channel=OSS_FULL}、{@code scopes=["*"]}）换取 STS 临时凭据
 * （accessKeyId / secretAccessKey / sessionToken，有效期约 2 小时）与桶清单
 * （业务桶名 → s3Bucket / s3Endpoint）。桶本体是 S3 兼容协议
 * （SigV4 + 会话令牌 + 虚拟主机寻址，见 {@link S3FileStorage}）。</p>
 *
 * <p>本引擎负责编排：按 {@code ExpiredAt} 缓存临时凭据并在过期前刷新（提前 5 分钟），
 * 每次刷新后重建底层 S3 实例；所有 {@link FileStorage} 方法委托给当前实例。
 * 线程安全：凭据刷新由锁串行化，双检查避免并发请求重复刷新。</p>
 *
 * <p>付费桶与免费桶（基础型）协议完全一致——免费桶同样走 tmp_token + S3，
 * 无需单独适配（2026-10-20 实测免费桶 s-bj-12472-fee-standard-1258813047 全部 9 项
 * 基础操作通过）。</p>
 *
 * <p>配置键：accessKey（必填）、secretKey（必填）、bucket（必填，业务桶名如
 * {@code fee-standard} 或 s3Bucket 全名均可）、region（SigV4 区域，默认从桶端点推导）、
 * basePath、listVersion、apiEndpoint（管理 API，默认 https://api.dogecloud.com）。</p>
 *
 * @author 郑明亮
 */
public class DogeFileStorage implements FileStorage {
    /** 默认管理 API 端点。 */
    private static final String DEFAULT_API_ENDPOINT = "https://api.dogecloud.com";
    /** 凭据提前刷新余量（毫秒）：到期前 5 分钟即视为过期。 */
    private static final long EXPIRY_SLACK_MS = 5L * 60L * 1000L;
    /** 地区码 → SigV4 区域（PicList 同款映射：端点网关可能不回 s3Endpoint 时兜底）。 */
    private static final Map<String, String> REGION_BY_CODE = regionMap();

    private final StorageConfig config;
    private final String accessKey;
    private final String secretKey;
    private final String bucket;
    private final String apiEndpoint;
    private final String basePath;
    private final String listVersion;

    private final ReentrantLock refreshLock = new ReentrantLock();
    /** 当前底层 S3 实例（临时凭据刷新时整体替换）。 */
    private volatile S3FileStorage delegate;
    /** 当前临时凭据的过期时间（epoch 毫秒）。 */
    private volatile long expiresAt;

    /**
     * 由配置创建（配置键见类注释）。
     *
     * @param config 连接配置
     */
    public DogeFileStorage(StorageConfig config) {
        this.config = config;
        this.accessKey = config.require("accessKey");
        this.secretKey = config.require("secretKey");
        this.bucket = config.require("bucket");
        String api = config.getOr("apiEndpoint", DEFAULT_API_ENDPOINT);
        this.apiEndpoint = api.endsWith("/") ? api.substring(0, api.length() - 1) : api;
        this.basePath = StoragePaths.normalize(config.getOr("basePath", ""));
        this.listVersion = config.getOr("listVersion", "");
    }

    @Override
    public String platform() {
        return "doge";
    }

    @Override
    public boolean exists(String path) {
        return delegate().exists(path);
    }

    @Override
    public StorageEntry stat(String path) {
        return delegate().stat(path);
    }

    @Override
    public List<StorageEntry> list(String dir) {
        return delegate().list(dir);
    }

    @Override
    public void mkdir(String dir) {
        delegate().mkdir(dir);
    }

    @Override
    public void upload(String path, InputStream in, long size) {
        delegate().upload(path, in, size);
    }

    @Override
    public void upload(String path, InputStream in, long size, UploadMetadata meta) {
        delegate().upload(path, in, size, meta);
    }

    @Override
    public void download(String path, OutputStream out) {
        delegate().download(path, out);
    }

    @Override
    public boolean supportsRange() {
        return delegate().supportsRange();
    }

    @Override
    public void download(String path, long offset, OutputStream out) {
        delegate().download(path, offset, out);
    }

    @Override
    public String presign(String path, java.time.Duration ttl) {
        return delegate().presign(path, ttl);
    }

    @Override
    public void delete(String path) {
        delegate().delete(path);
    }

    @Override
    public void copy(String src, String dst) {
        delegate().copy(src, dst);
    }

    @Override
    public void rename(String src, String dst) {
        delegate().rename(src, dst);
    }

    /**
     * 取当前底层实例；临时凭据过期（含临近过期）时刷新。
     *
     * <p>鉴权失败（令牌被服务端提前吊销等）时由调用方捕获后强制刷新一次。</p>
     *
     * @return 底层 S3 实例
     */
    private S3FileStorage delegate() {
        S3FileStorage current = delegate;
        if (current == null || System.currentTimeMillis() + EXPIRY_SLACK_MS >= expiresAt) {
            refresh(false);
        }
        return delegate;
    }

    /**
     * 刷新临时凭据并重建底层实例。
     *
     * @param force true 跳过双检查强制刷新（鉴权失败恢复用）
     */
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
        Bucket target = token.bucketOf(bucket);
        StorageConfig s3Config = StorageConfig.empty();
        s3Config.set("endpoint", target.endpoint);
        s3Config.set("bucket", target.s3Bucket);
        s3Config.set("accessKey", token.credentials.get("accessKeyId"));
        s3Config.set("secretKey", token.credentials.get("secretAccessKey"));
        s3Config.set("sessionToken", token.credentials.get("sessionToken"));
        s3Config.set("region", resolveRegion(target));
        // 寻址风格：默认虚拟主机（多吉云桶要求）；测试 / 特殊网关可配 true 切换路径风格
        s3Config.set("pathStyle", config.getOr("pathStyle", "false"));
        if (!basePath.isEmpty()) {
            s3Config.set("basePath", basePath);
        }
        if (!listVersion.isEmpty()) {
            s3Config.set("listVersion", listVersion);
        }
        String proxyHost = config.getOr("proxyHost", "");
        String proxyPort = config.get("proxyPort");
        if (!proxyHost.isEmpty() && proxyPort != null) {
            s3Config.set("proxyHost", proxyHost);
            s3Config.set("proxyPort", proxyPort);
        }
        return new S3FileStorage(s3Config);
    }

    /**
     * SigV4 区域：优先用户显式配置，其次从桶端点域名推导
     * （{@code cos.ap-beijing.myqcloud.com → ap-beijing}）。
     */
    private String resolveRegion(Bucket target) {
        String explicit = config.getOr("region", "");
        if (!explicit.isEmpty()) {
            return explicit;
        }
        // s3Endpoint 形如 https://cos.ap-beijing.myqcloud.com，第二段是地区
        String host = target.endpoint.replaceFirst("^https?://", "");
        String[] parts = host.split("\\.");
        if (parts.length >= 3) {
            return parts[1];
        }
        return "ap-beijing";
    }

    /**
     * 调 {@code /auth/tmp_token.json} 换临时凭据。
     *
     * @return 临时凭据 + 桶清单 + 过期时间
     */
    private TempToken requestTempToken() {
        Map<String, Object> payload = new java.util.LinkedHashMap<String, Object>();
        payload.put("channel", "OSS_FULL");
        payload.put("scopes", java.util.Collections.singletonList("*"));
        byte[] body = JSON.toJsonString(payload).getBytes(StandardCharsets.UTF_8);
        HttpCore.Request request = new HttpCore.Request("POST", apiEndpoint + "/auth/tmp_token.json");
        request.header("Content-Type", "application/json");
        request.header("Authorization",
                DogeSigners.tokenAuth(accessKey, secretKey, "/auth/tmp_token.json", body));
        request.body(body);
        HttpCore.Response response = HttpCore.execute(HttpCore.configured(request, config));
        String text;
        try {
            text = response.bodyText();
        } finally {
            response.close();
        }
        int status = response.status();
        if (status >= 400) {
            throw new StorageException(com.alianga.jkit.storage.StorageErrorCode.AUTH_FAILED,
                    "多吉云临时凭据获取失败（HTTP " + status + "）: " + summarize(text), "", null);
        }
        Map<String, Object> root;
        try {
            root = JSON.parseObject(text);
        } catch (RuntimeException e) {
            throw new StorageException(com.alianga.jkit.storage.StorageErrorCode.SERVER_ERROR,
                    "多吉云临时凭据响应不是 JSON: " + summarize(text), "", null);
        }
        Number code = asNumber(root.get("code"));
        if (code == null || code.intValue() != 200) {
            throw new StorageException(com.alianga.jkit.storage.StorageErrorCode.AUTH_FAILED,
                    "多吉云临时凭据获取失败: " + summarize(String.valueOf(root.get("msg"))), "", null);
        }
        Map<String, Object> data = asMap(root.get("data"));
        if (data == null) {
            throw new StorageException(com.alianga.jkit.storage.StorageErrorCode.SERVER_ERROR,
                    "多吉云临时凭据响应缺少 data: " + summarize(text), "", null);
        }
        Map<String, String> credentials = stringMap(asMap(data.get("Credentials")));
        Number expiredAt = asNumber(data.get("ExpiredAt"));
        if (credentials.isEmpty() || expiredAt == null) {
            throw new StorageException(com.alianga.jkit.storage.StorageErrorCode.SERVER_ERROR,
                    "多吉云临时凭据响应缺少 Credentials / ExpiredAt", "", null);
        }
        List<Map<String, Object>> buckets = asMapList(data.get("Buckets"));
        return new TempToken(credentials, buckets, expiredAt.longValue() * 1000L);
    }

    private static Map<String, String> regionMap() {
        Map<String, String> map = new java.util.LinkedHashMap<String, String>();
        map.put("ap-shanghai", "0");
        map.put("ap-beijing", "1");
        map.put("ap-guangzhou", "2");
        map.put("ap-chengdu", "3");
        return map;
    }

    private static String summarize(String text) {
        if (text == null) {
            return "";
        }
        String flat = text.replaceAll("\\s+", " ").trim();
        return flat.length() > 200 ? flat.substring(0, 200) + "…" : flat;
    }

    private static Number asNumber(Object value) {
        return value instanceof Number ? (Number) value : null;
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
        List<Map<String, Object>> result = new java.util.ArrayList<Map<String, Object>>();
        for (Object item : (List<Object>) value) {
            if (item instanceof Map) {
                result.add((Map<String, Object>) item);
            }
        }
        return result;
    }

    private static Map<String, String> stringMap(Map<String, Object> map) {
        Map<String, String> result = new java.util.LinkedHashMap<String, String>();
        if (map != null) {
            for (Map.Entry<String, Object> entry : map.entrySet()) {
                if (entry.getValue() != null) {
                    result.put(entry.getKey(), String.valueOf(entry.getValue()));
                }
            }
        }
        return result;
    }

    /** 临时凭据响应里的桶描述：业务名 / s3Bucket / s3Endpoint。 */
    private static final class Bucket {
        final String name;
        final String s3Bucket;
        final String endpoint;

        Bucket(String name, String s3Bucket, String endpoint) {
            this.name = name;
            this.s3Bucket = s3Bucket;
            this.endpoint = endpoint;
        }
    }

    /** tmp_token 响应：临时凭据 + 桶清单 + 过期时间（epoch 毫秒）。 */
    private final class TempToken {
        final Map<String, String> credentials;
        final List<Map<String, Object>> buckets;
        final long expiredAt;

        TempToken(Map<String, String> credentials, List<Map<String, Object>> buckets,
                  long expiredAt) {
            this.credentials = credentials;
            this.buckets = buckets;
            this.expiredAt = expiredAt;
        }

        /**
         * 找配置 bucket 对应的桶条目：s3Bucket 全名优先，其次业务名；
         * 用户填的也可能是 s3Bucket 全名。
         */
        Bucket bucketOf(String configured) {
            for (Map<String, Object> item : buckets) {
                Map<String, String> bucket = stringMap(item);
                if (configured.equals(bucket.get("s3Bucket"))) {
                    return new Bucket(bucket.get("name"), bucket.get("s3Bucket"), bucket.get("s3Endpoint"));
                }
            }
            for (Map<String, Object> item : buckets) {
                Map<String, String> bucket = stringMap(item);
                if (configured.equals(bucket.get("name"))) {
                    return new Bucket(bucket.get("name"), bucket.get("s3Bucket"), bucket.get("s3Endpoint"));
                }
            }
            throw new StorageException(com.alianga.jkit.storage.StorageErrorCode.INVALID_CONFIG,
                    "多吉云账号下不存在桶 " + configured + "（可用: " + names() + "）", "", null);
        }

        private String names() {
            StringBuilder out = new StringBuilder();
            for (Map<String, Object> item : buckets) {
                Map<String, String> bucket = stringMap(item);
                if (out.length() > 0) {
                    out.append(", ");
                }
                out.append(bucket.get("name"));
            }
            return out.length() == 0 ? "(无)" : out.toString();
        }
    }
}
