package com.alianga.jkit.storage.engine.googledrive;

import com.alianga.jkit.storage.FileStorages;
import com.alianga.jkit.storage.StorageConfig;
import com.alianga.jkit.storage.StorageField;
import com.alianga.jkit.storage.StoragePlatform;
import com.alianga.jkit.storage.cap.StorageCapabilities;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Google Drive 存储平台声明与注册：将 {@link GoogleDriveFileStorage} 以
 * {@code google-drive} 平台接入 {@link FileStorages}。
 *
 * <p>连接表单包含 OAuth2 凭据、令牌回填字段、共享盘配置与删除策略。
 * 上层界面可基于内置向导引导用户完成 Google Cloud API 申请与一键 OAuth 授权。</p>
 *
 * @author 郑明亮
 */
public final class GoogleDriveStorageSupport {
    /** 平台 ID。 */
    public static final String PLATFORM_ID = GoogleDriveConfig.PLATFORM_ID;
    /** 平台分类（公有云）。 */
    public static final String CATEGORY = "公有云";
    /** 平台显示名称。 */
    public static final String DISPLAY_NAME = "Google Drive";

    private static volatile boolean registered;

    private GoogleDriveStorageSupport() {
    }

    /**
     * @param platformId 平台 ID
     * @return true 表示为 Google Drive 平台
     */
    public static boolean isGoogleDrive(String platformId) {
        return PLATFORM_ID.equals(platformId);
    }

    /** 注册 Google Drive 平台、引擎与能力（幂等）。 */
    public static synchronized void register() {
        if (registered) {
            return;
        }
        Map<String, String> defaults = new LinkedHashMap<String, String>();
        defaults.put(GoogleDriveConfig.REDIRECT_URI, GoogleDriveConfig.DEFAULT_REDIRECT_URI);
        defaults.put(GoogleDriveConfig.BASE_PATH, "/");

        StoragePlatform platform = new StoragePlatform(
                PLATFORM_ID, DISPLAY_NAME, PLATFORM_ID, CATEGORY,
                fields(), StorageConfig.of(defaults));
        FileStorages.register(platform);
        FileStorages.registerEngine(PLATFORM_ID, GoogleDriveFileStorage::new);
        FileStorages.registerCapabilities(PLATFORM_ID,
                StorageCapabilities.standard()
                        .withSupportsRange(true)
                        .withNativeRecycleBin());
        registered = true;
    }

    private static List<StorageField> fields() {
        return Arrays.asList(
                StorageField.required(GoogleDriveConfig.CLIENT_ID, "Client ID",
                        "从 Google Cloud Console 凭据页面获取的 OAuth 2.0 客户端 ID"),
                StorageField.secret(GoogleDriveConfig.CLIENT_SECRET, "Client Secret", false,
                        "从 Google Cloud Console 凭据页面获取的客户端密钥"),
                StorageField.secret(GoogleDriveConfig.ACCESS_TOKEN, "访问令牌 (Access Token)", false,
                        "点上方「获取令牌 / 配置向导」自动填入；手动填需有效令牌"),
                StorageField.secret(GoogleDriveConfig.REFRESH_TOKEN, "刷新令牌 (Refresh Token)", false,
                        "点上方「获取令牌 / 配置向导」自动填入；访问令牌过期时用它自动续期"),
                StorageField.optional(GoogleDriveConfig.DRIVE_ID, "团队共享盘 ID", "",
                        "可选；访问团队盘或共享云端硬盘时填入（向导里可一键读取），"
                                + "留空默认使用个人主云端硬盘"),
                StorageField.optional(GoogleDriveConfig.BASE_PATH, "起始目录", "/",
                        "云端硬盘内作为存储根的目录，如 / 或 /工作文件；留空或 / 为根目录"),
                StorageField.optional(GoogleDriveConfig.REDIRECT_URI, "重定向回调地址",
                        GoogleDriveConfig.DEFAULT_REDIRECT_URI,
                        "必须与 Google Cloud Console 中配置的重定向 URI 完全一致"));
    }
}
