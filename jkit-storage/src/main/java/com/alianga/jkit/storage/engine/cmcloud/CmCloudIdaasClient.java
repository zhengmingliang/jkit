package com.alianga.jkit.storage.engine.cmcloud;

import com.alianga.jkit.storage.StorageException;
import com.alianga.jkit.storage.internal.HttpCore;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Base64;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * IDaaS 取票客户端（纯 Java）：完整移植 SDK 的 {@code getToken} 流程——
 *
 * <ol>
 *   <li>{@code configDelivery}：拉取路径配置（sign = base64(sha256(traceId+timestamp+"2.0"))）；
 *   <li>clawUid 三路收敛：调用方传入 → 持久化值 → 兜底文件（与 CLI 共享）→ 生成新 UUID；
 *   <li>读本地 {@code uuid / accessToken} 缓存；
 *   <li>{@code preAuth}：MD5 签名 + 数字信封加密（{@link CmCloudIdaasCrypto#sealRequest}）；</li>
 *   <li>按结果码落盘：103000 存 accessToken 清 uuid、持久化 clawUid、返回 creditToken；
 *       103001 存 uuid、返回 authPageUrl；130088 表示待授权会话未完成（继续轮询）。</li>
 * </ol>
 *
 * <p>HTTP 走 jkit-storage 内部 {@link HttpCore}，与其余引擎共用同一套网络栈（零第三方依赖）。</p>
 *
 * @author 郑明亮
 */
public final class CmCloudIdaasClient {
    /** 一次 getToken 的结果。 */
    public static final class TokenResult {
        private final String code;
        private final String desc;
        private final String creditToken;
        private final String expireTime;
        private final String accessToken;
        private final String uuid;
        private final String authPageUrl;

        private TokenResult(String code, String desc, String creditToken, String expireTime,
                            String accessToken, String uuid, String authPageUrl) {
            this.code = code;
            this.desc = desc;
            this.creditToken = creditToken;
            this.expireTime = expireTime;
            this.accessToken = accessToken;
            this.uuid = uuid;
            this.authPageUrl = authPageUrl;
        }

        public static TokenResult of(String code, String desc) {
            return new TokenResult(code, desc, null, null, null, null, null);
        }

        public String code() {
            return code;
        }

        public String desc() {
            return desc;
        }

        public String creditToken() {
            return creditToken;
        }

        public String expireTime() {
            return expireTime;
        }

        public String accessToken() {
            return accessToken;
        }

        public String uuid() {
            return uuid;
        }

        public String authPageUrl() {
            return authPageUrl;
        }
    }

    private static final int TIMEOUT_MS = 15_000;
    private static final DateTimeFormatter TIMESTAMP_FORMAT =
            DateTimeFormatter.ofPattern("yyyyMMddHHmmss");

    /**
     * 执行一次 getToken。
     *
     * @param uuidParam 授权流程标识；null 表示按本地缓存（首次授权后轮询场景由调用方决定）
     * @param clawUid   设备身份；null 表示按本地三路收敛
     * @return 结果（永不返回 null；网络失败等也有结果码）
     */
    public TokenResult getToken(String uuidParam, String clawUid) {
        String traceId = UUID.randomUUID().toString();
        try {
            // 1. 配置下发（每次都拉，不缓存；失败即失败）
            TokenResult configError = fetchConfig(traceId);
            if (configError != null) {
                return configError;
            }

            // 2. clawUid 三路收敛
            String device = !Strs.isBlank(clawUid) ? clawUid.trim() : resolveClawUid();
            if (device == null) {
                return TokenResult.of("200009", "无法确定设备身份（clawUid）");
            }

            // 3. uuid / accessToken：调用方指定 uuid 优先，否则读本地缓存
            String authUuid = uuidParam;
            String accessToken = null;
            if (Strs.isBlank(authUuid)) {
                String[] cached = CmCloudIdaasStore.loadAuthInfo();
                authUuid = cached[0];
                accessToken = cached[1];
            }

            // 4. 签名 + 明文 payload
            String timestamp = timestamp();
            String sign = md5(CmCloudIdaasProfile.CLIENT_ID + CmCloudIdaasProfile.INTERFACE_VERSION
                    + timestamp + traceId + device + CmCloudIdaasProfile.SKILL_ID
                    + CmCloudIdaasProfile.SKILL_NAME + CmCloudIdaasProfile.DEV_ID);
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("clawUid", device);
            payload.put("skillId", CmCloudIdaasProfile.SKILL_ID);
            payload.put("skillName", CmCloudIdaasProfile.SKILL_NAME);
            payload.put("devId", CmCloudIdaasProfile.DEV_ID);
            payload.put("strategyId", CmCloudIdaasProfile.STRATEGY_ID);
            payload.put("sign", sign);
            payload.put("templateId", CmCloudIdaasProfile.TEMPLATE_ID);
            payload.put("scopeIds", CmCloudIdaasProfile.SCOPE_IDS);
            if (!Strs.isBlank(authUuid)) {
                payload.put("uuid", authUuid);
            }
            if (!Strs.isBlank(accessToken)) {
                payload.put("accessToken", accessToken);
            }
            payload.putAll(deviceInfo());
            payload.put("agentPlatformPath", "");
            payload.put("agentPlatform", "");

            // 5. 数字信封 + preAuth
            Map<String, String> body = CmCloudIdaasCrypto.sealRequest(payload);
            HttpCore.Request request = new HttpCore.Request("POST",
                    CmCloudIdaasProfile.TOKEN_SERVICE_BASE_URL + CmCloudIdaasProfile.PRE_AUTH_PATH)
                    .header("clientId", CmCloudIdaasProfile.CLIENT_ID)
                    .header("timestamp", timestamp)
                    .header("interfaceVersion", CmCloudIdaasProfile.INTERFACE_VERSION)
                    .header("sdkVersion", CmCloudIdaasProfile.SDK_VERSION)
                    .header("traceId", traceId)
                    .header("Content-Type", "application/json")
                    .body(CmCloudIdaasJson.toJson(body).getBytes(StandardCharsets.UTF_8))
                    .timeouts(null, TIMEOUT_MS, TIMEOUT_MS);
            String text = send(request);
            Map<String, Object> result = CmCloudIdaasJson.parseMap(text);
            if (result == null) {
                return TokenResult.of("200007", "preAuth 响应解析失败");
            }
            return handleResult(result, device);
        } catch (StorageException e) {
            return TokenResult.of("200005", "网络异常：" + rootMessage(e));
        } catch (IllegalStateException e) {
            return TokenResult.of("200013", e.getMessage());
        } catch (RuntimeException e) {
            return TokenResult.of("200008", "取票异常：" + rootMessage(e));
        }
    }

    /** 按结果码处理 preAuth 响应并落盘凭据（与 SDK 第 8 步语义一致）。 */
    private TokenResult handleResult(Map<String, Object> result, String clawUid) {
        String code = text(result.get("resultCode"));
        String desc = text(result.get("desc"));
        Object rawData = result.get("data");
        Map<String, Object> data = rawData instanceof Map ? asObjectMap(rawData)
                : new LinkedHashMap<String, Object>();
        if (CmCloudIdaasProfile.CODE_SUCCESS.equals(code)) {
            String accessToken = text(data.get("accessToken"));
            String creditToken = text(data.get("creditToken"));
            String expireTime = text(data.get("expireTime"));
            if (accessToken != null) {
                // 服务端验证通过：存 accessToken、清 uuid（会话升级为已授权）
                CmCloudIdaasStore.saveAuthInfo(accessToken, null, true);
            }
            CmCloudIdaasStore.saveClawUid(clawUid);
            return new TokenResult(code, desc == null ? "成功" : desc,
                    creditToken, expireTime, accessToken, null, null);
        }
        if (CmCloudIdaasProfile.CODE_AUTH_REQUIRED.equals(code)) {
            String uuid = text(data.get("uuid"));
            String authPageUrl = text(data.get("authPageUrl"));
            if (uuid != null) {
                CmCloudIdaasStore.saveAuthInfo(null, uuid, false);
            }
            CmCloudIdaasStore.saveClawUid(clawUid);
            return new TokenResult(code, desc == null ? "需要用户授权" : desc,
                    null, null, null, uuid, authPageUrl);
        }
        return TokenResult.of(code == null ? "UNKNOWN" : code, desc == null ? "取票失败" : desc);
    }

    /** 配置下发；成功返回 null，失败返回错误结果。 */
    private TokenResult fetchConfig(String traceId) {
        try {
            String timestamp = timestamp();
            String version = "2.0";
            String sign = Base64.getEncoder().encodeToString(MessageDigest
                    .getInstance("SHA-256")
                    .digest((traceId + timestamp + version).getBytes(StandardCharsets.UTF_8)));
            Map<String, Object> body = new HashMap<>();
            body.put("sign", sign);
            HttpCore.Request request = new HttpCore.Request("POST",
                    CmCloudIdaasProfile.TOKEN_SERVICE_BASE_URL
                            + CmCloudIdaasProfile.CONFIG_DELIVERY_PATH)
                    .header("interfaceVersion", version)
                    .header("timestamp", timestamp)
                    .header("traceId", traceId)
                    .header("Content-Type", "application/json")
                    .body(CmCloudIdaasJson.toJson(body).getBytes(StandardCharsets.UTF_8))
                    .timeouts(null, TIMEOUT_MS, TIMEOUT_MS);
            String text = send(request);
            Map<String, Object> result = CmCloudIdaasJson.parseMap(text);
            String code = result == null ? null : text(result.get("resultCode"));
            if (!CmCloudIdaasProfile.CODE_SUCCESS.equals(code)) {
                String desc = result == null ? "配置下发响应解析失败" : text(result.get("desc"));
                return TokenResult.of(code == null ? "200012" : code,
                        desc == null ? "配置下发失败" : desc);
            }
            return null;
        } catch (StorageException e) {
            return TokenResult.of("200005", "配置下发网络异常：" + rootMessage(e));
        } catch (Exception e) {
            return TokenResult.of("200012", "配置下发失败：" + rootMessage(e));
        }
    }

    /** 发请求并取响应文本：非 2xx 直接抛 {@link StorageException}（调用方按网络异常翻译）。 */
    private static String send(HttpCore.Request request) {
        HttpCore.Response response = HttpCore.execute(request);
        int status = response.status();
        String text = response.bodyText();
        response.close();
        if (status < 200 || status >= 300) {
            throw StorageException.io("IDaaS 请求失败（HTTP " + status + "）", "-", null);
        }
        return text;
    }

    /** clawUid 三路收敛：持久化值 → 兜底文件（与 CLI 共享）→ 生成新 UUID。 */
    private static String resolveClawUid() {
        String persisted = CmCloudIdaasStore.loadClawUid();
        if (persisted != null) {
            return persisted;
        }
        return CmCloudIdaasStore.resolveFallbackClawUid();
    }

    /** 设备风控参数（对齐 SDK collect_device_info 的四个字段）。 */
    private static Map<String, Object> deviceInfo() {
        Map<String, Object> info = new HashMap<>();
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        info.put("osType", os.contains("win") ? "windows"
                : os.contains("mac") || os.contains("darwin") ? "macos" : "linux");
        info.put("osVersion", System.getProperty("os.version", ""));
        String host = "";
        try {
            host = java.net.InetAddress.getLocalHost().getHostName();
        } catch (java.net.UnknownHostException ignored) {
            // 主机名拿不到就传空
        }
        info.put("hostName", host.toUpperCase(Locale.ROOT));
        info.put("cpuId", "");
        return info;
    }

    /** 17 位时间戳：yyyyMMddHHmmss + 毫秒后 3 位。 */
    private static String timestamp() {
        long millis = System.currentTimeMillis();
        return LocalDateTime.now().format(TIMESTAMP_FORMAT)
                + String.format("%03d", millis % 1000);
    }

    private static String md5(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("MD5")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder out = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                out.append(Character.forDigit((b >> 4) & 0xF, 16));
                out.append(Character.forDigit(b & 0xF, 16));
            }
            return out.toString();
        } catch (Exception e) {
            throw new IllegalStateException("JVM 不支持 MD5", e);
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asObjectMap(Object raw) {
        return (Map<String, Object>) raw;
    }

    private static String text(Object value) {
        if (value == null) {
            return null;
        }
        String text = String.valueOf(value).trim();
        return text.isEmpty() ? null : text;
    }

    private static String rootMessage(Throwable e) {
        Throwable cause = e;
        while (cause.getCause() != null) {
            cause = cause.getCause();
        }
        return cause.getMessage() == null ? cause.getClass().getSimpleName() : cause.getMessage();
    }
}
