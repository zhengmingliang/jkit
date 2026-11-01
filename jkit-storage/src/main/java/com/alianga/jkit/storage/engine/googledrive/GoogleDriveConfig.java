package com.alianga.jkit.storage.engine.googledrive;

import com.alianga.jkit.storage.StorageConfig;
import com.alianga.jkit.storage.StorageException;

/**
 * Google Drive 存储配置常量与校验。
 *
 * @author 郑明亮
 */
public final class GoogleDriveConfig {
    /** 平台 ID 与引擎 ID。 */
    public static final String PLATFORM_ID = "google-drive";

    /** OAuth2 Client ID。 */
    public static final String CLIENT_ID = "clientId";
    /** OAuth2 Client Secret。 */
    public static final String CLIENT_SECRET = "clientSecret";
    /** OAuth2 重定向回调地址。 */
    public static final String REDIRECT_URI = "redirectUri";
    /** 访问令牌 (Access Token)。 */
    public static final String ACCESS_TOKEN = "accessToken";
    /** 刷新令牌 (Refresh Token)。 */
    public static final String REFRESH_TOKEN = "refreshToken";
    /** 访问令牌过期时间戳（毫秒）。 */
    public static final String EXPIRES_AT = "expiresAt";
    /** 团队盘 / 共享驱动器 ID（为空表示个人云端硬盘）。 */
    public static final String DRIVE_ID = "driveId";
    /** 起始目录 / 基路径。 */
    public static final String BASE_PATH = "basePath";

    /** 默认重定向回调地址。 */
    public static final String DEFAULT_REDIRECT_URI = "http://localhost:8080/gd/callback";
    /** Google OAuth2 授权端点。 */
    public static final String DEFAULT_AUTH_URL = "https://accounts.google.com/o/oauth2/v2/auth";
    /** Google OAuth2 令牌端点。 */
    public static final String DEFAULT_TOKEN_URL = "https://oauth2.googleapis.com/token";
    /** Google Drive API v3 基础地址。 */
    public static final String DEFAULT_API_BASE = "https://www.googleapis.com/drive/v3";
    /** Google Drive 上传 API 基础地址。 */
    public static final String DEFAULT_UPLOAD_BASE = "https://www.googleapis.com/upload/drive/v3";
    /** 默认权限作用域（完全访问云端硬盘文件及元数据）。 */
    public static final String DEFAULT_SCOPE = "https://www.googleapis.com/auth/drive";

    /** 文件夹 MIME 类型。 */
    public static final String FOLDER_MIME_TYPE = "application/vnd.google-apps.folder";
    /** 快捷方式 MIME 类型。 */
    public static final String SHORTCUT_MIME_TYPE = "application/vnd.google-apps.shortcut";

    private GoogleDriveConfig() {
    }

    /**
     * 校验配置完整性。必须满足：
     * 1. 存在 accessToken；或者
     * 2. 存在 refreshToken 且提供 clientId 与 clientSecret（可自动刷新）。
     *
     * @param config 连接配置
     * @throws StorageException 配置不合法时抛出 INVALID_CONFIG
     */
    public static void validate(StorageConfig config) {
        if (config == null) {
            throw StorageException.invalidConfig("Google Drive 配置不能为空");
        }
        String accessToken = config.getOr(ACCESS_TOKEN, "").trim();
        String refreshToken = config.getOr(REFRESH_TOKEN, "").trim();
        String clientId = config.getOr(CLIENT_ID, "").trim();
        String clientSecret = config.getOr(CLIENT_SECRET, "").trim();

        if (accessToken.isEmpty() && refreshToken.isEmpty()) {
            throw StorageException.invalidConfig(
                    "Google Drive 需要访问令牌 (accessToken) 或刷新令牌 (refreshToken)，"
                            + "请先通过「获取令牌」完成 OAuth 授权");
        }
        if (!refreshToken.isEmpty() && (clientId.isEmpty() || clientSecret.isEmpty())) {
            throw StorageException.invalidConfig(
                    "配置了刷新令牌 (refreshToken) 时，必须同时配置 Client ID 与 Client Secret，"
                            + "以便令牌到期时自动刷新");
        }
    }
}
