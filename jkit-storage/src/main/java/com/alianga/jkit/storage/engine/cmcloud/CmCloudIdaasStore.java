package com.alianga.jkit.storage.engine.cmcloud;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.Arrays;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/**
 * IDaaS 本地凭据存储（纯 Java）：与 SDK file 后端同格式的安全 bundle 读写 +
 * uuid/accessToken、clawUid、业务票据缓存三处凭据。
 *
 * <p>文件格式与 SDK 一致：{@code base64(JSON)} 单行；内容为加密信封
 * （{@link CmCloudIdaasCrypto#sealBundle}，v=2 PBKDF2+AES-GCM）。读失败一律按
 * 「无凭据」处理（返回 null / 空对象），触发重新授权而不是报错——指纹漂移、
 * 文件损坏都属于预期内的失效路径。</p>
 *
 * @author 郑明亮
 */
public final class CmCloudIdaasStore {
    private CmCloudIdaasStore() {
    }

    // ---------------------------------------------------------------- bundle IO

    /** 读安全 bundle；文件不存在 / 损坏 / 解密失败返回 null。 */
    public static Map<String, Object> readBundle(Path file) {
        if (file == null || !Files.isRegularFile(file)) {
            return null;
        }
        try {
            String raw = new String(Files.readAllBytes(file), StandardCharsets.UTF_8).trim();
            if (raw.isEmpty()) {
                return null;
            }
            String json = new String(Base64.getDecoder().decode(raw), StandardCharsets.UTF_8);
            Map<String, Object> data = CmCloudIdaasJson.parseMap(json);
            if (data == null) {
                return null;
            }
            if (Integer.valueOf(CmCloudIdaasCrypto.ENVELOPE_VERSION).equals(asInt(data.get("v")))
                    && CmCloudIdaasCrypto.ENVELOPE_KDF.equals(data.get("kdf"))) {
                // 信封：解密出真实 bundle
                return CmCloudIdaasCrypto.openBundle(data);
            }
            // 明文 bundle（加密关闭模式的历史文件）
            return data;
        } catch (java.io.IOException | RuntimeException e) {
            return null;
        }
    }

    /** 写安全 bundle（信封加密 + base64 单行；目录 0700 / 文件 0600）。 */
    public static void writeBundle(Path file, Map<String, Object> bundle) {
        try {
            Map<String, Object> envelope = CmCloudIdaasCrypto.sealBundle(bundle);
            String payload = Base64.getEncoder()
                    .encodeToString(CmCloudIdaasJson.toJson(envelope)
                            .getBytes(StandardCharsets.UTF_8));
            writePrivate(file, payload.getBytes(StandardCharsets.UTF_8));
        } catch (RuntimeException e) {
            if (e instanceof IllegalStateException) {
                throw (IllegalStateException) e;
            }
            throw new IllegalStateException("本地凭据写入失败：" + e.getMessage(), e);
        }
    }

    /** 直接覆盖写 + POSIX 0600/0700 收紧（Windows 由用户目录 ACL 兜底）。 */
    private static void writePrivate(Path file, byte[] bytes) throws RuntimeException {
        try {
            Path dir = file.getParent();
            if (dir != null) {
                Files.createDirectories(dir);
                tighten(dir, true);
            }
            Files.write(file, bytes);
            tighten(file, false);
        } catch (Exception e) {
            throw new IllegalStateException("凭据文件写入失败：" + file, e);
        }
    }

    private static void tighten(Path path, boolean directory) {
        try {
            Set<PosixFilePermission> perms = Files.getPosixFilePermissions(path);
            if (directory) {
                perms.retainAll(Arrays.asList(
                        PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE,
                        PosixFilePermission.OWNER_EXECUTE));
            } else {
                perms.retainAll(Arrays.asList(
                        PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE));
            }
            Files.setPosixFilePermissions(path, perms);
        } catch (UnsupportedOperationException ignored) {
            // 非 POSIX 文件系统：交给用户目录 ACL 兜底
        } catch (java.io.IOException ignored) {
            // 权限收紧失败不阻断主流程
        }
    }

    private static Integer asInt(Object value) {
        if (value instanceof Number) {
            return ((Number) value).intValue();
        }
        return null;
    }

    // ---------------------------------------------------------------- uuid / accessToken

    /** @return {@code [uuid, accessToken]}（读不到给 null 元素） */
    public static String[] loadAuthInfo() {
        Map<String, Object> bundle = readBundle(CmCloudIdaasProfile.authInfoFile());
        if (bundle == null) {
            return new String[]{null, null};
        }
        return new String[]{
                text(bundle.get("uuid")),
                text(bundle.get("accessToken")),
        };
    }

    /** 保存 uuid / accessToken（null 表示不修改该字段；clearUuid 时移除 uuid）。 */
    public static void saveAuthInfo(String accessToken, String authUuid, boolean clearUuid) {
        Path file = CmCloudIdaasProfile.authInfoFile();
        Map<String, Object> bundle = readBundle(file);
        if (bundle == null) {
            bundle = new HashMap<>();
        }
        if (accessToken != null) {
            bundle.put("accessToken", accessToken);
        }
        if (authUuid != null) {
            bundle.put("uuid", authUuid);
        }
        if (clearUuid) {
            bundle.remove("uuid");
        }
        writeBundle(file, bundle);
    }

    /** 只清掉待授权会话 uuid（保留 accessToken）；用于作废无法展示入口的遗留会话。 */
    public static void clearAuthUuid() {
        Path file = CmCloudIdaasProfile.authInfoFile();
        Map<String, Object> bundle = readBundle(file);
        if (bundle == null) {
            return;
        }
        bundle.remove("uuid");
        writeBundle(file, bundle);
    }

    // ---------------------------------------------------------------- clawUid

    /** @return 持久化的设备身份（clawUid.json），没有给 null */
    public static String loadClawUid() {
        Map<String, Object> bundle = readBundle(CmCloudIdaasProfile.clawUidFile());
        return bundle == null ? null : text(bundle.get("clawUid"));
    }

    public static void saveClawUid(String clawUid) {
        if (clawUid == null || Strs.isBlank(clawUid)) {
            return;
        }
        Map<String, Object> bundle = new HashMap<>();
        bundle.put("clawUid", clawUid);
        writeBundle(CmCloudIdaasProfile.clawUidFile(), bundle);
    }

    /**
     * 兜底设备身份（与 cmcloud CLI 共享的 fallback 文件）：存在就用它的 deviceId，
     * 否则生成新 UUID 写入——同一台机器上工具与 CLI 共用一套设备身份。
     *
     * @return 设备身份；生成/写入失败时仍返回内存值
     */
    public static String resolveFallbackClawUid() {
        Path fallback = CmCloudIdaasProfile.fallbackClawUidFile();
        Map<String, Object> existing = readBundle(fallback);
        if (existing != null) {
            String deviceId = text(existing.get("deviceId"));
            if (deviceId != null) {
                return deviceId;
            }
        }
        String generated = java.util.UUID.randomUUID().toString();
        Map<String, Object> bundle = new HashMap<>();
        bundle.put("deviceId", generated);
        try {
            writeBundle(fallback, bundle);
        } catch (RuntimeException ignored) {
            // 写不进去就用内存值（可用性优先）
        }
        return generated;
    }

    // ---------------------------------------------------------------- 业务票据缓存

    /**
     * 读缓存的业务票据（扫码成功或上次取票后落盘）。
     *
     * @return {@code [creditToken, clawUid, expireTime]}；没有给 null 元素
     */
    public static String[] loadTicketCache() {
        Map<String, Object> bundle = readBundle(CmCloudIdaasProfile.ticketCacheFile());
        if (bundle == null) {
            return new String[]{null, null, null};
        }
        return new String[]{
                text(bundle.get("creditToken")),
                text(bundle.get("clawUid")),
                text(bundle.get("expireTime")),
        };
    }

    public static void saveTicketCache(String creditToken, String clawUid, String expireTime) {
        if (creditToken == null || Strs.isBlank(creditToken)) {
            return;
        }
        Map<String, Object> bundle = new HashMap<>();
        bundle.put("creditToken", creditToken);
        bundle.put("clawUid", clawUid == null ? "" : clawUid);
        bundle.put("expireTime", expireTime == null ? "" : expireTime);
        writeBundle(CmCloudIdaasProfile.ticketCacheFile(), bundle);
    }

    private static String text(Object value) {
        if (value == null) {
            return null;
        }
        String text = String.valueOf(value).trim();
        return text.isEmpty() ? null : text;
    }
}
