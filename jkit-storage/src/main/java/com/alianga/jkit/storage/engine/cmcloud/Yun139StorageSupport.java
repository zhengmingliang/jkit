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
 * 139 云盘（全量）平台声明与注册：把 {@link Yun139FileStorage} 以 {@code yun139}
 * 平台接入 {@code FileStorages}，连接管理界面的平台卡片、表单字段、测试连接随即自动可用。
 *
 * <p>与 {@link CmCloudStorageSupport}（{@code cmcloud}，IDaaS V3 + 扫码授权，写操作受
 * 智能体专属目录约束）是两条并存的接入路线：本平台走网页版同款接口，Authorization 认证，
 * <b>全目录可读可写</b>，支持移动 / 重命名（IDaaS 路线未开放的能力）。用户按需选择：
 * 要全量操作选 {@code yun139}；要走官方扫码授权且只在专属目录内写选 {@code cmcloud}。</p>
 *
 * <p>注册幂等，可在界面初始化与测试启动时重复调用。</p>
 *
 * @author 郑明亮
 */
public final class Yun139StorageSupport {
    /** 平台 id（同时作为引擎 id 与连接配置里的 platform 值）。 */
    public static final String PLATFORM_ID = Yun139Profile.PLATFORM_ID;

    private static volatile boolean registered;

    private Yun139StorageSupport() {
    }

    /**
     * @param platformId 平台 id
     * @return true 表示是本模块注册的 139 全量平台
     */
    public static boolean isYun139(String platformId) {
        return PLATFORM_ID.equals(platformId);
    }

    /** 注册 139 云盘（全量）平台与引擎（幂等）。 */
    public static synchronized void register() {
        if (registered) {
            return;
        }
        Map<String, String> defaults = new LinkedHashMap<>();
        defaults.put(Yun139Profile.PART_SIZE_MB, "20");
        defaults.put(Yun139Profile.BASE_PATH, "/");

        StoragePlatform platform = new StoragePlatform(
                PLATFORM_ID, Yun139Profile.DISPLAY_NAME, PLATFORM_ID, Yun139Profile.CATEGORY,
                fields(), StorageConfig.of(defaults));
        FileStorages.register(platform);
        FileStorages.registerEngine(PLATFORM_ID, Yun139FileStorage::new);
        registered = true;
    }

    private static List<StorageField> fields() {
        return Arrays.asList(
                StorageField.secret(Yun139Profile.AUTHORIZATION, "Authorization", true,
                        "登录 yun.139.com 网页版，开发者工具 → 网络面板找 hcy/file/list 请求，"
                                + "复制请求头 Authorization 中 Basic 后面的内容（整段粘贴也可以，"
                                + "自动去掉 Basic 前缀）。网页版同款接口：全目录可读可写，"
                                + "Authorization 过期后重新抓取更新即可"),
                StorageField.required(Yun139Profile.ACCOUNT, "登录账号",
                        "手机号或 139 邮箱账号；用于按路由策略解析个人云 API 域名，"
                                + "补取分片上传地址也要带上它"),
                StorageField.optional(Yun139Profile.BASE_URL, "个人云 API 地址", "",
                        "形如 https://personal-kd-njs.yun.139.com/hcy；留空则按账号经"
                                + "路由策略自动解析"),
                StorageField.optional(Yun139Profile.PART_SIZE_MB, "上传分片大小（MB）", "20",
                        "1~5120 MB；默认 20（与网页版一致）。EOS 单对象上限 5GB，"
                                + "分片不要超过该值"),
                StorageField.optional(Yun139Profile.BASE_PATH, "起始目录", "",
                        "云盘内目录，如 /我的文件夹/备份；留空或 / 为云盘根。"
                                + "全量访问：所有目录层级的文件操作不受专属目录限制"));
    }
}
