package com.alianga.jkit.storage.git;

import com.alianga.jkit.storage.StorageErrorCode;
import com.alianga.jkit.storage.StorageException;

/**
 * HTTP 状态码 / 平台错误消息 → 统一 {@link StorageException}。
 *
 * <p>「明确报错、不假装成功」是本类的全部职责：429 单独归到 RATE_LIMITED
 * （界面可提示稍后重试），401 / 403 归 AUTH_FAILED 并补一句「令牌权限不足」的
 * 具体指引，413 明确说文件太大而不是笼统报 IO 错误。平台在响应体里给的
 * {@code message} 会被带出来，但只取前 200 字符——长 HTML 错误页不进弹窗。</p>
 *
 * @author 郑明亮
 */
final class GitErrors {
    /** 平台错误消息的最大保留长度。 */
    private static final int MAX_MESSAGE = 200;

    private GitErrors() {
    }

    /**
     * 按状态码翻译。
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
                        "Git 鉴权失败（401）：令牌无效或已过期，请检查令牌与所需权限" + suffix(detail), path);
                // 403 既可能是权限不足，也可能是限流（GitHub 的次级限流就报 403）：
                // 错误码跟着判定走，界面才能对限流给「稍后重试」而不是「令牌没权限」
            case 403:
                return new StorageException(
                        isRateLimit(detail) ? StorageErrorCode.RATE_LIMITED : StorageErrorCode.AUTH_FAILED,
                        forbiddenText(detail), path);
            case 404:
                return StorageException.notFound(path);
            case 409:
                return new StorageException(StorageErrorCode.CONFLICT,
                        isListingTooLarge(detail)
                                ? "目录过大（409）：GitHub Contents API 单目录最多返回 1000 项，"
                                  + "请先进入子目录再浏览" + suffix(detail)
                                : "Git 操作冲突（409）：目标内容与服务端不一致，可能已被他人修改，请刷新后重试"
                                  + suffix(detail), path);
            case 413:
                return new StorageException(StorageErrorCode.CONFLICT,
                        "文件过大（413）：Git 托管平台对单次提交有体积上限（GitHub Contents API 约 100MB，"
                                + "Gitee / Gitea 通常更低），请改用 Git 客户端推送", path);
            case 422:
                return new StorageException(StorageErrorCode.CONFLICT,
                        "请求被拒绝（422）：路径非法或内容校验失败" + suffix(detail), path);
            case 429:
                return new StorageException(StorageErrorCode.RATE_LIMITED,
                        "请求过于频繁（429）：平台已限流，请稍后重试" + suffix(detail), path);
            default:
                    break;
        }
        if (status >= 500) {
            return new StorageException(StorageErrorCode.SERVER_ERROR,
                    "Git 服务端错误（" + status + "）" + suffix(detail), path);
        }
        return new StorageException(StorageErrorCode.IO_ERROR,
                "Git 请求失败（HTTP " + status + "）" + suffix(detail), path);
    }

    /** 403 的文案要区分「限流」与「权限不足」：GitHub 会把次级限流也报成 403。 */
    private static String forbiddenText(String detail) {
        if (detail != null && isRateLimit(detail)) {
            return "Git 请求被限流（403）：已达平台速率上限，请稍后重试";
        }
        return "Git 权限不足（403）：令牌缺少写权限（GitHub 需 repo 或 contents:write，"
                + "Gitee 需 projects 全勾，Gitea / Gogs 需仓库写权限）" + suffix(detail);
    }

    /** 从响应体里取 {@code message} 字段（不解析完整结构，避免依赖平台 JSON 形态）。 */
    private static String platformMessage(String body) {
        if (body == null || body.trim().isEmpty()) {
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

    private static String suffix(String detail) {
        return detail == null || detail.isEmpty() ? "" : "：" + detail;
    }

    /**
     * 平台消息是否为限流：各家措辞不同（rate limit / rate_limit / 请求过于频繁 /
     * 已达上限），统一按关键词命中判定，命中即归 RATE_LIMITED（可退避重试）。
     */
    private static boolean isRateLimit(String detail) {
        if (detail == null) {
            return false;
        }
        String text = detail.toLowerCase(java.util.Locale.ROOT)
                .replace('_', ' ').replace('-', ' ');
        return text.contains("rate limit") || text.contains("ratelimit")
                || text.contains("too many requests")
                || text.contains("请求过于频繁") || text.contains("已达上限");
    }

    /**
     * 409 是否为「目录过大」：GitHub Contents API 单目录上限 1000 项，超限返回
     * 409 + "returns listings up to 1000 items"——不是真的内容冲突，刷新多少次
     * 都没用，必须单独给文案指路。
     *
     * @param detail 平台错误消息摘要
     * @return true 表示目录超上限
     */
    private static boolean isListingTooLarge(String detail) {
        if (detail == null) {
            return false;
        }
        String text = detail.toLowerCase(java.util.Locale.ROOT);
        return text.contains("1000 items") || text.contains("too large")
                || text.contains("up to 1000");
    }
}
