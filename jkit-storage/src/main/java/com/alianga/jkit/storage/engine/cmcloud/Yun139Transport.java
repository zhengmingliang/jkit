package com.alianga.jkit.storage.engine.cmcloud;

import com.alianga.jkit.json.JSON;
import com.alianga.jkit.storage.StorageErrorCode;
import com.alianga.jkit.storage.StorageException;
import com.alianga.jkit.storage.internal.HttpCore;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 139 云盘「新个人云」Web API 传输层：复用 jkit-storage 内部 {@link HttpCore}
 * （JDK {@code HttpURLConnection}，零第三方依赖），签名头按网页版同款拼装。
 *
 * <p>两类通道：</p>
 * <ul>
 *   <li><b>业务接口</b>：POST JSON 到个人云域名（配置指定或路由策略解析），带
 *   {@code Authorization: Basic} 与 {@code mcloud-sign: ts,rand,sign}，响应统一信封
 *   {@code success/code/message/data}；</li>
 *   <li><b>预签名地址</b>（分片上传 PUT / 下载 GET）：地址自带签名，<b>不带任何鉴权头</b>；
 *   上传 PUT 另带 {@code Origin} / {@code Referer}（AList 同款，部分网关校验）。</li>
 * </ul>
 *
 * <p>复用 {@link CmCloudTransport} 的信封判定、字段提取与下载流包装（同包同口径）。</p>
 *
 * @author 郑明亮
 */
final class Yun139Transport implements AutoCloseable {
    private static final int CONNECT_TIMEOUT_MS = 30_000;
    private static final int REQUEST_TIMEOUT_MS = 60_000;
    private static final int UPLOAD_TIMEOUT_MS = 600_000;
    /** 签名时间戳格式（本地时间）。 */
    private static final DateTimeFormatter TS_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final char[] RAND_CHARS =
            "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789".toCharArray();

    private final Yun139Config config;
    private final String routePolicyUrl;
    /** 个人云 API 域名（懒解析缓存）。 */
    private volatile String host;

    Yun139Transport(Yun139Config config) {
        this(config, Yun139Profile.ROUTE_POLICY_URL);
    }

    /** 测试可注入路由策略地址（默认官方地址）。 */
    Yun139Transport(Yun139Config config, String routePolicyUrl) {
        this.config = config;
        this.routePolicyUrl = routePolicyUrl;
    }

    /** 个人云 API 域名：配置优先，否则按账号经路由策略解析（modName=personal）。 */
    String host() {
        String resolved = host;
        if (resolved != null) {
            return resolved;
        }
        synchronized (this) {
            if (host == null) {
                host = config.baseUrl().isEmpty() ? resolveRoute() : config.baseUrl();
            }
            return host;
        }
    }

    /** 路由策略：{@code data.routePolicyList} 里取 {@code modName=personal} 的 httpsUrl。 */
    private String resolveRoute() {
        Map<String, Object> userInfo = new LinkedHashMap<>();
        userInfo.put("userType", 1);
        userInfo.put("accountType", 1);
        userInfo.put("accountName", config.account());
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("userInfo", userInfo);
        body.put("modAddrType", 1);
        Map<String, Object> data = signedPost(routePolicyUrl, body, "路由策略");
        if (!(data.get("routePolicyList") instanceof List)) {
            throw StorageException.io("路由策略响应缺少 routePolicyList，无法解析个人云 API 域名",
                    "路由策略", null);
        }
        List<?> policies = (List<?>) data.get("routePolicyList");
        for (Object entry : policies) {
            if (!(entry instanceof Map)) {
                continue;
            }
            Map<?, ?> policy = (Map<?, ?>) entry;
            Object httpsUrl = policy.get("httpsUrl");
            if ("personal".equals(String.valueOf(policy.get("modName")))
                    && httpsUrl != null && !Strs.isBlank(String.valueOf(httpsUrl))) {
                String url = String.valueOf(httpsUrl).trim();
                while (url.endsWith("/")) {
                    url = url.substring(0, url.length() - 1);
                }
                return url;
            }
        }
        throw StorageException.io("路由策略未返回个人云（personal）域名，请检查账号或改在"
                + "「个人云 API 地址」直接填写", "路由策略", null);
    }

    /**
     * 业务接口 POST：签名头 + 统一信封校验，返回 {@code data} 对象。
     *
     * @param api  接口路径（如 {@code /file/list}；路由策略传完整 URL）
     * @param body 业务请求体
     * @param path 出错时的逻辑路径（异常文案用）
     */
    Map<String, Object> post(String api, Map<String, Object> body, String path) {
        String url = api.startsWith("http") ? api : host() + api;
        return signedPost(url, body, path);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> signedPost(String url, Map<String, Object> body, String path) {
        String json = JSON.toJsonString(body);
        String ts = LocalDateTime.now().format(TS_FORMAT);
        String rand = randomString(16);
        String sign = Yun139Sign.calc(json, ts, rand);
        HttpCore.Request request = signedRequest(url, ts, rand, sign)
                .body(json.getBytes(StandardCharsets.UTF_8))
                .timeouts(null, CONNECT_TIMEOUT_MS, REQUEST_TIMEOUT_MS);
        HttpCore.Response response = HttpCore.execute(HttpCore.configured(request, config.raw()));
        int status = response.status();
        String text = response.bodyText();
        response.close();
        if (status < 200 || status >= 300) {
            throw Yun139Errors.http(status, text, path);
        }
        Object parsed;
        try {
            parsed = JSON.parse(text);
        } catch (RuntimeException e) {
            throw StorageException.io("139 云盘响应无法解析：" + summarize(text), path, e);
        }
        if (!(parsed instanceof Map)) {
            throw StorageException.io("139 云盘返回了非对象响应：" + summarize(text), path, null);
        }
        Map<String, Object> envelope = (Map<String, Object>) parsed;
        Object success = envelope.get("success");
        if (success != null && !"true".equalsIgnoreCase(String.valueOf(success).trim())) {
            throw Yun139Errors.business(CmCloudTransport.str(envelope, "code"),
                    CmCloudTransport.str(envelope, "message"), path);
        }
        Object code = envelope.get("code");
        if (code != null) {
            String codeText = String.valueOf(code).trim();
            if (!"0".equals(codeText) && !"0000".equalsIgnoreCase(codeText)) {
                throw Yun139Errors.business(codeText, CmCloudTransport.str(envelope, "message"), path);
            }
        }
        Object data = envelope.get("data");
        if (data instanceof Map) {
            return (Map<String, Object>) data;
        }
        return new LinkedHashMap<>();
    }

    /** 签名请求头（网页版同款全套）。 */
    private HttpCore.Request signedRequest(String url, String ts, String rand, String sign) {
        return new HttpCore.Request("POST", url)
                .header("Accept", "application/json, text/plain, */*")
                .header("Content-Type", "application/json;charset=UTF-8")
                .header("mcloud-channel", Yun139Profile.MCLOUD_CHANNEL)
                .header("mcloud-client", Yun139Profile.MCLOUD_CLIENT)
                .header("mcloud-version", Yun139Profile.MCLOUD_VERSION)
                .header("Origin", Yun139Profile.ORIGIN)
                .header("Referer", Yun139Profile.REFERER)
                .header("x-DeviceInfo", Yun139Profile.DEVICE_INFO)
                .header("x-huawei-channelSrc", Yun139Profile.MCLOUD_CHANNEL_SRC)
                .header("x-inner-ntwk", "2")
                .header("x-m4c-caller", "PC")
                .header("x-m4c-src", "10002")
                .header("x-yun-api-version", "v1")
                .header("x-yun-app-channel", Yun139Profile.MCLOUD_CHANNEL_SRC)
                .header("x-yun-channel-source", Yun139Profile.MCLOUD_CHANNEL_SRC)
                .header("x-yun-client-info", Yun139Profile.CLIENT_INFO)
                .header("x-yun-module-type", "100")
                .header("Inner-Hcy-Router-Https", "1")
                .header("x-yun-svc-type", "1")
                .header("x-SvcType", "1")
                .header("Authorization", "Basic " + config.authorization())
                .header("mcloud-sign", ts + "," + rand + "," + sign);
    }

    /**
     * 分片 PUT 到预签名上传地址（无鉴权头；octet-stream + 显式 Content-Length +
     * {@code Origin} / {@code Referer}）。
     *
     * @param uploadUrl 预签名地址（来自 file/create 或 file/getUploadUrl）
     * @param data      分片数据（字节数须与提交的 partSize 严格一致）
     * @param path      出错时的逻辑路径
     */
    void putPart(String uploadUrl, byte[] data, String path) {
        if (Strs.isBlank(uploadUrl)) {
            throw StorageException.io("平台未返回分片上传地址，无法继续上传", path, null);
        }
        if (data == null || data.length == 0) {
            throw StorageException.io("上传分片为空，无法发送", path, null);
        }
        HttpCore.Request request = new HttpCore.Request("PUT", uploadUrl.trim())
                .header("Content-Type", Yun139Profile.CONTENT_TYPE_OCTET)
                .header("Origin", Yun139Profile.ORIGIN)
                .header("Referer", Yun139Profile.REFERER)
                .body(data)
                .timeouts(null, CONNECT_TIMEOUT_MS, UPLOAD_TIMEOUT_MS);
        HttpCore.Response response = HttpCore.execute(request);
        int status = response.status();
        if (status < 200 || status >= 300) {
            byte[] errorBody = response.bodyBytes();
            throw new StorageException(StorageErrorCode.IO_ERROR,
                    "分片数据被对象存储拒绝（HTTP " + status + "）"
                            + CmCloudTransport.objectStorageDetail(errorBody), path);
        }
        response.close();
    }

    /**
     * 打开预签名下载地址的输入流（支持 Range 断点续传；不带任何鉴权头）。
     *
     * @param url    预签名下载地址
     * @param offset 起始偏移；0 表示全量
     * @param path   出错时的逻辑路径
     */
    InputStream openDownload(String url, long offset, String path) {
        HttpCore.Request request = new HttpCore.Request("GET", url.trim())
                .timeouts(null, CONNECT_TIMEOUT_MS, UPLOAD_TIMEOUT_MS);
        if (offset > 0) {
            request.header("Range", "bytes=" + offset + "-");
        }
        HttpCore.Response response = HttpCore.execute(request);
        int status = response.status();
        if (status < 200 || status >= 300) {
            // 先读完错误响应体再抛，顺手关连接
            response.bodyBytes();
            throw Yun139Errors.http(status, "", path);
        }
        return new CmCloudTransport.DownloadStream(response);
    }

    /** 16 位随机串（Mcloud-Sign 用，字母 + 数字）。 */
    private static String randomString(int length) {
        StringBuilder out = new StringBuilder(length);
        for (int i = 0; i < length; i++) {
            out.append(RAND_CHARS[RANDOM.nextInt(RAND_CHARS.length)]);
        }
        return out.toString();
    }

    private static String summarize(String body) {
        if (body == null) {
            return "";
        }
        String text = body.replaceAll("\\s+", " ").trim();
        return text.length() > 200 ? text.substring(0, 200) + "…" : text;
    }

    @Override
    public void close() {
        // HttpCore 每次请求独立开连接，无需持有资源
    }

    /** 供 Sign 测试取时间戳格式。 */
    static DateTimeFormatter tsFormat() {
        return TS_FORMAT;
    }
}
