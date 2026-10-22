package com.alianga.jkit.storage.engine.cmcloud;

import com.alianga.jkit.storage.StorageConfig;
import com.alianga.jkit.storage.StorageErrorCode;
import com.alianga.jkit.storage.StorageException;
import com.alianga.jkit.storage.StoragePaths;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * 中国移动云盘连接配置：IDaaS 业务鉴权五项票据 + 网关地址 + 起始目录 + 上传摘要算法。
 *
 * <p>移动云盘的个人云接口（{@code /richlifeApp/idaasV3/personalSaas/*}）不做用户名密码
 * 鉴权，而是要求每个业务请求携带五项 IDaaS 授权票据请求头：{@code creditToken}（短期
 * 业务票据）、{@code skillId}、{@code clientId}、{@code strategyId}、{@code clawUid}
 * （设备身份）。整套授权由引擎内置的纯 Java IDaaS 栈完成（见
 * {@link CmCloudIdaasClient}）：上层界面提供「扫码授权」向导，浏览器完成登录后
 * 五项票据自动回填并缓存，全程不需要手填。</p>
 *
 * <p>凭据解析顺序：连接配置显式值 → 扫码授权落盘的本地票据缓存（{@code creditToken} /
 * {@code clawUid}）或内置登记常量（{@code skillId} / {@code clientId} /
 * {@code strategyId}）→ 都没有才报「请扫码授权」。{@code creditToken} 是短期票据，
 * 过期后平台返回鉴权失败，引擎给出「重新扫码授权」的可读指引，不尝试自行刷新。</p>
 *
 * <p>逻辑路径与其他存储引擎一致：{@code ""}（或 {@code /}）为根（即配置的起始目录），
 * 段间以 {@code /} 分隔；根对应的云端 {@code parentFileId} 是平台约定的 {@code "/"}，
 * 起始目录之下通过逐层列目录解析为真实文件 ID。</p>
 *
 * @author 郑明亮
 */
final class CmCloudConfig {
    static final String PLATFORM_ID = "cmcloud";

    /** 平台默认网关入口（V3，含部署前缀；一般不需要改）。 */
    static final String DEFAULT_BASE_URL = "https://openapi.yun.139.com/open-ai-space";

    /** 业务接口公共前缀（相对网关地址）。 */
    static final String PERSONAL_SAAS_PREFIX = "/richlifeApp/idaasV3/personalSaas";

    static final String BASE_URL = "baseUrl";
    static final String CREDIT_TOKEN = "creditToken";
    static final String SKILL_ID = "skillId";
    static final String CLIENT_ID = "clientId";
    static final String STRATEGY_ID = "strategyId";
    static final String CLAW_UID = "clawUid";
    static final String BASE_PATH = "basePath";
    static final String HASH_ALGORITHM = "hashAlgorithm";
    static final String DOWNLOAD_EXPIRE_SEC = "downloadExpireSec";

    static final String HASH_SHA256 = "sha256";
    static final String HASH_SHA1 = "sha1";

    /** 个人云根的 parentFileId 约定值（接口契约：{@code "/"} 表示当前授权用户的个人云根）。 */
    static final String PERSONAL_ROOT_ID = "/";

    /** 原始引擎配置（把代理 / 超时等通用网络设置透传给 {@code HttpCore}）。 */
    private final StorageConfig raw;
    private final String baseUrl;
    private final String creditToken;
    private final String skillId;
    private final String clientId;
    private final String strategyId;
    private final String clawUid;
    /** 起始目录的云端路径段（不含前导斜杠；空表示个人云根）。 */
    private final List<String> rootSegments;
    private final String hashAlgorithm;
    private final int downloadExpireSec;

    private CmCloudConfig(StorageConfig raw, String baseUrl, String creditToken, String skillId,
                          String clientId, String strategyId, String clawUid,
                          List<String> rootSegments, String hashAlgorithm, int downloadExpireSec) {
        this.raw = raw;
        this.baseUrl = baseUrl;
        this.creditToken = creditToken;
        this.skillId = skillId;
        this.clientId = clientId;
        this.strategyId = strategyId;
        this.clawUid = clawUid;
        this.rootSegments = rootSegments;
        this.hashAlgorithm = hashAlgorithm;
        this.downloadExpireSec = downloadExpireSec;
    }

    /**
     * 解析并校验连接配置。
     *
     * @param config 引擎配置（已由 {@code FileStorages.of} 叠加平台默认值）
     * @return 合法配置
     * @throws StorageException INVALID_CONFIG：网关地址非法 / 缺票据 / 摘要算法未知 / 有效期越界
     */
    static CmCloudConfig from(StorageConfig config) {
        String baseUrl = trimToEmpty(config.getOr(BASE_URL, DEFAULT_BASE_URL));
        if (baseUrl.isEmpty()) {
            baseUrl = DEFAULT_BASE_URL;
        }
        if (!baseUrl.startsWith("https://")) {
            throw new StorageException(StorageErrorCode.INVALID_CONFIG,
                    "移动云盘网关地址必须是 https:// 开头，当前为：" + baseUrl);
        }
        if (baseUrl.endsWith("/")) {
            baseUrl = baseUrl.substring(0, baseUrl.length() - 1);
        }
        // creditToken：配置优先，其次扫码授权留下的本地票据缓存；都没有才报错并给扫码指引
        String creditToken = trimToEmpty(config.get(CREDIT_TOKEN));
        if (creditToken.isEmpty()) {
            creditToken = trimToEmpty(cachedTicket()[0]);
        }
        if (creditToken.isEmpty()) {
            throw StorageException.invalidConfig(
                    "移动云盘连接缺少业务票据（creditToken）：请在连接表单点「扫码授权」"
                            + "完成移动云盘授权（票据会自动填入并缓存）");
        }
        // skillId / clientId / strategyId：登记常量内置，配置留空自动回落，不需要用户填
        String skillId = orFallback(config.get(SKILL_ID), CmCloudIdaasProfile.SKILL_ID);
        String clientId = orFallback(config.get(CLIENT_ID), CmCloudIdaasProfile.CLIENT_ID);
        String strategyId = orFallback(config.get(STRATEGY_ID), CmCloudIdaasProfile.STRATEGY_ID);
        // clawUid（设备身份）：配置 → 票据缓存 → 本地设备身份文件，全 miss 才报错
        String clawUid = trimToEmpty(config.get(CLAW_UID));
        if (clawUid.isEmpty()) {
            clawUid = trimToEmpty(cachedTicket()[1]);
        }
        if (clawUid.isEmpty()) {
            clawUid = trimToEmpty(CmCloudIdaasStore.loadClawUid());
        }
        if (clawUid.isEmpty()) {
            throw StorageException.invalidConfig(
                    "移动云盘连接缺少设备身份（clawUid）：请点「扫码授权」完成授权后重试");
        }
        String hash = trimToEmpty(config.getOr(HASH_ALGORITHM, HASH_SHA256))
                .toLowerCase(Locale.ROOT);
        if (!HASH_SHA256.equals(hash) && !HASH_SHA1.equals(hash)) {
            throw StorageException.invalidConfig(
                    "上传摘要算法仅支持 sha256 / sha1，当前为：" + hash);
        }
        int expireSec;
        String expireText = trimToEmpty(config.get(DOWNLOAD_EXPIRE_SEC));
        if (expireText.isEmpty()) {
            expireSec = 3600;
        } else {
            try {
                expireSec = Integer.parseInt(expireText);
            } catch (NumberFormatException e) {
                throw StorageException.invalidConfig("下载链接有效期必须是数字（秒），当前为：" + expireText);
            }
            if (expireSec < 1 || expireSec > 86400) {
                throw StorageException.invalidConfig("下载链接有效期须在 1~86400 秒之间，当前为：" + expireSec);
            }
        }
        List<String> root = new ArrayList<>();
        for (String segment : trimToEmpty(config.getOr(BASE_PATH, "")).split("/", -1)) {
            if (!Strs.isBlank(segment)) {
                root.add(segment.trim());
            }
        }
        return new CmCloudConfig(config, baseUrl, creditToken, skillId, clientId, strategyId,
                clawUid, Collections.unmodifiableList(root), hash, expireSec);
    }

    /** @return 原始引擎配置（代理 / 超时等通用网络设置） */
    StorageConfig raw() {
        return raw;
    }

    String baseUrl() {
        return baseUrl;
    }

    String creditToken() {
        return creditToken;
    }

    String skillId() {
        return skillId;
    }

    String clientId() {
        return clientId;
    }

    String strategyId() {
        return strategyId;
    }

    String clawUid() {
        return clawUid;
    }

    String hashAlgorithm() {
        return hashAlgorithm;
    }

    int downloadExpireSec() {
        return downloadExpireSec;
    }

    /**
     * 业务接口完整 URL（网关 + personalSaas 前缀 + 接口路径）。
     *
     * @param api 接口路径，如 {@code /file/list}
     * @return 完整 URL
     */
    String apiUrl(String api) {
        return baseUrl + PERSONAL_SAAS_PREFIX + api;
    }

    /**
     * 逻辑路径转云端展示路径（起始目录 + 逻辑路径；仅用于文案与「复制路径」，不参与请求）。
     *
     * @param logicalPath 逻辑路径
     * @return 云端绝对路径
     */
    String cloudPath(String logicalPath) {
        String relative = StoragePaths.normalize(logicalPath);
        StringBuilder out = new StringBuilder();
        for (String segment : rootSegments) {
            out.append('/').append(segment);
        }
        if (!relative.isEmpty()) {
            for (String segment : relative.split("/", -1)) {
                out.append('/').append(segment);
            }
        }
        return out.length() == 0 ? "/" : out.toString();
    }

    /** @return 起始目录的路径段（不可变；空列表表示个人云根） */
    List<String> rootSegments() {
        return rootSegments;
    }

    /** 不含密钥的诊断文本（票据只露出前 6 位，足够对账又不可冒用）。 */
    @Override
    public String toString() {
        String token = creditToken.length() <= 6 ? "***" : creditToken.substring(0, 6) + "***";
        return "cmcloud[" + baseUrl + " skill=" + skillId + " token=" + token + "]";
    }

    /** @return 本地票据缓存 {@code [creditToken, clawUid, expireTime]}（读不到给 null 元素） */
    private static String[] cachedTicket() {
        try {
            return CmCloudIdaasStore.loadTicketCache();
        } catch (RuntimeException e) {
            // 凭据读不出来（指纹漂移等）：按无缓存处理，走重新授权
            return new String[]{null, null, null};
        }
    }

    /** 配置值非空用配置值，否则回落到内置登记常量。 */
    private static String orFallback(String value, String fallback) {
        String trimmed = trimToEmpty(value);
        return trimmed.isEmpty() ? fallback : trimmed;
    }

    private static String trimToEmpty(String value) {
        return value == null ? "" : value.trim();
    }
}
