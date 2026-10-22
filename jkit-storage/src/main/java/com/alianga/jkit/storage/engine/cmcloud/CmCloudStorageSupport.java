package com.alianga.jkit.storage.engine.cmcloud;

import com.alianga.jkit.storage.FileStorages;
import com.alianga.jkit.storage.StorageConfig;
import com.alianga.jkit.storage.StorageField;
import com.alianga.jkit.storage.StoragePlatform;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 中国移动云盘平台声明与注册：把 {@link CmCloudFileStorage} 以 {@code cmcloud}
 * 平台接入 {@code FileStorages}，连接管理界面的平台卡片、表单字段、测试连接随即
 * 自动可用，无需界面层特判。
 *
 * <p>连接配置的核心是 IDaaS 授权票据五项（{@code creditToken} / {@code skillId} /
 * {@code clientId} / {@code strategyId} / {@code clawUid}）。<b>推荐路径是扫码授权</b>：
 * 上层界面提供「扫码授权」向导，由引擎内置的纯 Java IDaaS 授权栈
 * （{@link CmCloudIdaasClient}）驱动——浏览器扫码 / 登录、
 * 设备指纹、票据签发与本地缓存全部自带，不依赖任何外部技能包或 Python；授权完成后
 * 五项票据自动回填表单，用户全程不需要手填身份认证信息。五项字段保留手填入口，
 * 仅作备用。{@code creditToken} 是短期票据，过期后引擎按鉴权失败给出
 * 「重新扫码授权」指引，不尝试自行刷新。</p>
 *
 * <p>注册幂等，可在界面初始化与测试启动时重复调用。</p>
 *
 * @author 郑明亮
 */
public final class CmCloudStorageSupport {
    /** 平台 id（同时作为引擎 id 与连接配置里的 platform 值）。 */
    public static final String PLATFORM_ID = CmCloudConfig.PLATFORM_ID;

    /** 平台分类：与阿里云 OSS / 多吉云同属「公有云」（界面分组用）。 */
    private static final String CATEGORY = "公有云";

    /** 平台显示名。 */
    private static final String DISPLAY_NAME = "中国移动云盘";

    private static volatile boolean registered;

    private CmCloudStorageSupport() {
    }

    /**
     * @param platformId 平台 id
     * @return true 表示是本模块注册的中国移动云盘平台
     */
    public static boolean isCmCloud(String platformId) {
        return PLATFORM_ID.equals(platformId);
    }

    /** 注册中国移动云盘平台与引擎（幂等）。 */
    public static synchronized void register() {
        if (registered) {
            return;
        }
        Map<String, String> defaults = new LinkedHashMap<>();
        defaults.put(CmCloudConfig.BASE_URL, CmCloudConfig.DEFAULT_BASE_URL);
        defaults.put(CmCloudConfig.BASE_PATH, "/");
        defaults.put(CmCloudConfig.HASH_ALGORITHM, CmCloudConfig.HASH_SHA256);
        defaults.put(CmCloudConfig.DOWNLOAD_EXPIRE_SEC, "3600");

        StoragePlatform platform = new StoragePlatform(
                PLATFORM_ID, DISPLAY_NAME, PLATFORM_ID, CATEGORY,
                fields(), StorageConfig.of(defaults));
        FileStorages.register(platform);
        FileStorages.registerEngine(PLATFORM_ID, CmCloudFileStorage::new);
        registered = true;
    }

    private static List<StorageField> fields() {
        // 五项票据全部由「扫码授权」自动获取；登记常量内置（skillId 等已预填），手填仅备用
        String scanHint = "点「扫码授权」自动获取并缓存，无需手填；此字段仅作备用";
        return Arrays.asList(
                StorageField.secret(CmCloudConfig.CREDIT_TOKEN, "业务票据 creditToken", false,
                        scanHint + "。creditToken 是短期票据（有有效期），过期后重新扫码即可"),
                StorageField.optional(CmCloudConfig.SKILL_ID, "skillId",
                        CmCloudIdaasProfile.SKILL_ID, scanHint + "（已预填内置登记值）"),
                StorageField.optional(CmCloudConfig.CLIENT_ID, "clientId",
                        CmCloudIdaasProfile.CLIENT_ID, scanHint + "（已预填内置登记值）"),
                StorageField.optional(CmCloudConfig.STRATEGY_ID, "strategyId",
                        CmCloudIdaasProfile.STRATEGY_ID, scanHint + "（已预填内置登记值）"),
                StorageField.optional(CmCloudConfig.CLAW_UID, "clawUid", "", scanHint),
                StorageField.optional(CmCloudConfig.BASE_URL, "网关地址",
                        CmCloudConfig.DEFAULT_BASE_URL,
                        "个人云 V3 网关入口（含部署前缀）；一般保持默认"),
                StorageField.optional(CmCloudConfig.BASE_PATH, "起始目录", "/",
                        "个人云内目录，如 /AI空间/工作归档；留空或 / 为个人云根。"
                                + "注意：写操作（上传 / 建目录 / 复制）受平台授权范围约束，"
                                + "只能落在智能体专属目录及其子目录内"),
                StorageField.optional(CmCloudConfig.HASH_ALGORITHM, "上传摘要算法",
                        CmCloudConfig.HASH_SHA256,
                        "sha256（默认）/ sha1；个别接入底座只认 sha1，上传校验失败时改试 sha1"),
                StorageField.optional(CmCloudConfig.DOWNLOAD_EXPIRE_SEC, "下载链接有效期（秒）",
                        "3600", "1~86400 秒；分享链接与下载均使用该有效期"));
    }
}
