package com.alianga.jkit.storage.engine.cmcloud;

import com.alianga.jkit.storage.StorageErrorCode;
import com.alianga.jkit.storage.StorageException;

import java.util.Locale;

/**
 * 移动云盘错误 → 统一 {@link StorageException}。
 *
 * <p>两层翻译：HTTP 层按状态码（401 / 403 归鉴权失败，429 归限流，5xx 归服务端错误）；
 * 业务信封层按平台 {@code code} 与 {@code message} 关键词——平台没有公开的完整业务码表，
 * 只对确认过的码做精确映射（如 16110013 资源路径越权），其余按消息关键词归到
 * 「已存在 / 限流 / 鉴权」，兜底归服务端错误并保留原始 code 与 message，
 * 做到「明确报错、不假装成功、不猜成功」。</p>
 *
 * @author 郑明亮
 */
final class CmCloudErrors {
    /** 平台错误消息的最大保留长度。 */
    private static final int MAX_MESSAGE = 200;

    /** 资源路径未在授权范围内：写落点越出智能体专属目录（平台码 16110013）。 */
    private static final String CODE_SCOPE_DENIED = "16110013";

    private CmCloudErrors() {
    }

    /**
     * 业务信封失败（HTTP 200 但 success=false 或 code 非 0000）。
     *
     * @param code    平台业务码
     * @param message 平台消息
     * @param path    逻辑路径
     * @return 统一异常
     */
    static StorageException business(String code, String message, String path) {
        String detail = trim(message);
        String prefix = code == null || Strs.isBlank(code) ? "移动云盘业务失败" : "移动云盘业务失败（code=" + code + "）";
        if (CODE_SCOPE_DENIED.equals(code)) {
            return new StorageException(StorageErrorCode.AUTH_FAILED,
                    prefix + "：资源路径未在授权范围内。移动云盘的写操作只能落在智能体专属目录"
                            + "及其子目录内，请把目标改到专属目录后重试" + suffix(detail), path);
        }
        if (isAuthMessage(detail)) {
            return new StorageException(StorageErrorCode.AUTH_FAILED,
                    prefix + "：业务票据无效或已过期（creditToken 为短期票据），"
                            + "请重新完成移动云盘授权后更新连接配置" + suffix(detail), path);
        }
        if (isExistsMessage(detail)) {
            return new StorageException(StorageErrorCode.ALREADY_EXISTS,
                    prefix + "：同名对象已存在，请改名或改用已有目录" + suffix(detail), path);
        }
        if (isRateLimitMessage(detail)) {
            return new StorageException(StorageErrorCode.RATE_LIMITED,
                    prefix + "：平台已限流，请稍后重试" + suffix(detail), path);
        }
        return new StorageException(StorageErrorCode.SERVER_ERROR,
                prefix + (detail.isEmpty() ? "" : "：" + detail), path);
    }

    /**
     * 按 HTTP 状态码翻译（网关 / 对象存储层错误，未到业务信封）。
     *
     * @param status HTTP 状态码
     * @param body   响应体（用于提取平台消息）
     * @param path   逻辑路径
     * @return 统一异常
     */
    static StorageException http(int status, String body, String path) {
        String detail = platformMessage(body);
        switch (status) {
            case 401:
                return new StorageException(StorageErrorCode.AUTH_FAILED,
                        "移动云盘鉴权失败（401）：业务票据无效或已过期，请重新授权后更新连接配置"
                                + suffix(detail), path);
            case 403:
                return new StorageException(StorageErrorCode.AUTH_FAILED,
                        "移动云盘权限不足（403）：票据或设备身份未被授权访问该资源" + suffix(detail), path);
            case 404:
                return StorageException.notFound(path);
            case 429:
                return new StorageException(StorageErrorCode.RATE_LIMITED,
                        "请求过于频繁（429）：平台已限流，请稍后重试" + suffix(detail), path);
            default:
                if (status >= 500) {
                    return new StorageException(StorageErrorCode.SERVER_ERROR,
                            "移动云盘服务端错误（" + status + "）" + suffix(detail), path);
                }
                return new StorageException(StorageErrorCode.IO_ERROR,
                        "移动云盘请求失败（HTTP " + status + "）" + suffix(detail), path);
        }
    }

    /** 消息是否为鉴权类：票据失效 / 未授权 / 登录态丢失等。 */
    private static boolean isAuthMessage(String detail) {
        if (detail == null || detail.isEmpty()) {
            return false;
        }
        String text = detail.toLowerCase(Locale.ROOT);
        return text.contains("credittoken") || text.contains("token")
                || text.contains("未授权") || text.contains("鉴权") || text.contains("登录")
                || text.contains("unauthorized") || text.contains("forbidden");
    }

    /** 消息是否为「已存在」类：建目录 / 建文件撞名。 */
    private static boolean isExistsMessage(String detail) {
        if (detail == null || detail.isEmpty()) {
            return false;
        }
        return detail.contains("已存在") || detail.contains("同名")
                || detail.contains("already exist") || detail.contains("duplicate");
    }

    /** 消息是否为限流类。 */
    private static boolean isRateLimitMessage(String detail) {
        if (detail == null || detail.isEmpty()) {
            return false;
        }
        String text = detail.toLowerCase(Locale.ROOT).replace('_', ' ').replace('-', ' ');
        return text.contains("rate limit") || text.contains("too many requests")
                || text.contains("频繁") || text.contains("限流");
    }

    /** 从响应体里取 {@code message} 字段（不解析完整结构，避免依赖平台 JSON 形态）。 */
    private static String platformMessage(String body) {
        if (body == null || Strs.isBlank(body)) {
            return null;
        }
        String text = body.trim();
        if (!text.startsWith("{")) {
            // 网关 / 反代常返回 HTML 错误页，不整段带出来
            return null;
        }
        String marker = "\"message\"";
        int at = text.indexOf(marker);
        if (at < 0) {
            return null;
        }
        int colon = text.indexOf(':', at + marker.length());
        if (colon < 0) {
            return null;
        }
        int quoteStart = text.indexOf('"', colon);
        if (quoteStart < 0) {
            return null;
        }
        StringBuilder out = new StringBuilder();
        for (int i = quoteStart + 1; i < text.length(); i++) {
            char ch = text.charAt(i);
            if (ch == '\\' && i + 1 < text.length()) {
                out.append(text.charAt(++i));
                continue;
            }
            if (ch == '"') {
                break;
            }
            out.append(ch);
        }
        String value = out.toString().trim();
        if (value.isEmpty()) {
            return null;
        }
        return value.length() > MAX_MESSAGE ? value.substring(0, MAX_MESSAGE) + "…" : value;
    }

    private static String trim(String message) {
        if (message == null) {
            return "";
        }
        String text = message.trim();
        return text.length() > MAX_MESSAGE ? text.substring(0, MAX_MESSAGE) + "…" : text;
    }

    private static String suffix(String detail) {
        return detail == null || detail.isEmpty() ? "" : "：" + detail;
    }
}
