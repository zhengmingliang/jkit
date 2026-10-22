package com.alianga.jkit.storage.engine.cmcloud;

import com.alianga.jkit.storage.StorageErrorCode;
import com.alianga.jkit.storage.StorageException;

import java.util.Locale;

/**
 * 139 云盘（新个人云 Web API）错误 → 统一 {@link StorageException}。
 *
 * <p>两层翻译：HTTP 层（401/403 → 鉴权失败并给「重新抓取 Authorization」指引，429 → 限流，
 * 5xx → 服务端错误）；业务信封层按 message 关键词归类（票据 / 已存在 / 限流），兜底归
 * 服务端错误并保留原始 code 与 message。</p>
 *
 * @author 郑明亮
 */
final class Yun139Errors {
    private static final int MAX_MESSAGE = 200;

    private Yun139Errors() {
    }

    /** 业务信封失败（HTTP 200 但 success=false 或 code 非 0）。 */
    static StorageException business(String code, String message, String path) {
        String detail = clip(message);
        String prefix = code == null || Strs.isBlank(code)
                ? "139 云盘业务失败" : "139 云盘业务失败（code=" + code + "）";
        if (isAuthMessage(detail)) {
            return new StorageException(StorageErrorCode.AUTH_FAILED,
                    prefix + "：网页版 Authorization 无效或已过期。重新登录 yun.139.com，"
                            + "在开发者工具网络面板找 hcy/file/list 请求，复制请求头"
                            + " Authorization 中 Basic 后面的内容并更新连接配置"
                            + suffix(detail), path);
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

    /** HTTP 状态码翻译（网关 / 对象存储层错误，未到业务信封）。 */
    static StorageException http(int status, String body, String path) {
        String detail = platformMessage(body);
        switch (status) {
            case 401:
                return new StorageException(StorageErrorCode.AUTH_FAILED,
                        "139 云盘鉴权失败（401）：网页版 Authorization 无效或已过期，"
                                + "请重新登录 yun.139.com 抓取并更新连接配置" + suffix(detail), path);
            case 403:
                return new StorageException(StorageErrorCode.AUTH_FAILED,
                        "139 云盘权限不足（403）：登录态未被授权访问该资源" + suffix(detail), path);
            case 404:
                return StorageException.notFound(path);
            case 429:
                return new StorageException(StorageErrorCode.RATE_LIMITED,
                        "请求过于频繁（429）：平台已限流，请稍后重试" + suffix(detail), path);
            default:
                if (status >= 500) {
                    return new StorageException(StorageErrorCode.SERVER_ERROR,
                            "139 云盘服务端错误（" + status + "）" + suffix(detail), path);
                }
                return new StorageException(StorageErrorCode.IO_ERROR,
                        "139 云盘请求失败（HTTP " + status + "）" + suffix(detail), path);
        }
    }

    private static boolean isAuthMessage(String detail) {
        if (detail == null || detail.isEmpty()) {
            return false;
        }
        String text = detail.toLowerCase(Locale.ROOT);
        return text.contains("authorization") || text.contains("token")
                || text.contains("未授权") || text.contains("鉴权") || text.contains("登录")
                || text.contains("unauthorized") || text.contains("forbidden");
    }

    private static boolean isExistsMessage(String detail) {
        if (detail == null || detail.isEmpty()) {
            return false;
        }
        return detail.contains("已存在") || detail.contains("同名")
                || detail.contains("already exist") || detail.contains("duplicate");
    }

    private static boolean isRateLimitMessage(String detail) {
        if (detail == null || detail.isEmpty()) {
            return false;
        }
        String text = detail.toLowerCase(Locale.ROOT).replace('_', ' ').replace('-', ' ');
        return text.contains("rate limit") || text.contains("too many requests")
                || text.contains("频繁") || text.contains("限流");
    }

    /** 从响应体提取 {@code message} 字段（平铺 JSON / 简单 XML；HTML 错误页不带出来）。 */
    private static String platformMessage(String body) {
        if (body == null || Strs.isBlank(body)) {
            return null;
        }
        String text = body.trim();
        if (text.startsWith("{")) {
            return clip(xmlOrJsonString(text, "message"));
        }
        if (text.startsWith("<")) {
            return clip(xmlOrJsonString(text, "Message"));
        }
        return null;
    }

    /** 从 JSON 或 XML 文本里粗取一个字符串字段值（错误信息场景够用，不做完整解析）。 */
    private static String xmlOrJsonString(String text, String field) {
        String marker = "\"" + field + "\"";
        int at = text.indexOf(marker);
        String quote = "\"";
        if (at < 0) {
            marker = "<" + field + ">";
            at = text.indexOf(marker);
            if (at < 0) {
                return null;
            }
            int start = at + marker.length();
            int end = text.indexOf("</" + field + ">", start);
            return end < 0 ? null : text.substring(start, end).trim();
        }
        int colon = text.indexOf(':', at + marker.length());
        if (colon < 0) {
            return null;
        }
        int quoteStart = text.indexOf(quote, colon);
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
        return value.isEmpty() ? null : value;
    }

    private static String clip(String message) {
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
