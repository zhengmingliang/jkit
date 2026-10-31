package com.alianga.jkit.storage.svn;

import com.alianga.jkit.storage.StorageErrorCode;
import com.alianga.jkit.storage.StorageException;
import org.tmatesoft.svn.core.SVNErrorCode;
import org.tmatesoft.svn.core.SVNErrorMessage;
import org.tmatesoft.svn.core.SVNException;

import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.util.Locale;

/**
 * SVNKit 异常到统一 {@link StorageException} 的翻译：连接失败、鉴权失败、目标不存在、
 * 已存在、冲突等场景分别落到 {@link StorageErrorCode}，界面侧再按错误码给出提示与重试策略。
 *
 * @author 郑明亮
 */
final class SvnErrors {
    private SvnErrors() {
    }

    /**
     * 翻译 SVN 操作异常。
     *
     * @param path 逻辑路径（可空）
     * @param e    SVNKit 抛出的异常
     * @return 统一存储异常
     */
    static StorageException map(String path, SVNException e) {
        StorageErrorCode code = classify(e);
        String message = "SVN 操作失败：" + summarize(e);
        return new StorageException(code, message, path == null ? "" : path, e);
    }

    /**
     * 连接建立阶段的异常（测试连接 / 打开实例）：措辞面向用户，不区分具体操作。
     *
     * @param e SVNKit 抛出的异常
     * @return 统一存储异常
     */
    static StorageException connect(SVNException e) {
        StorageErrorCode code = classify(e);
        return new StorageException(code, "无法连接 SVN 服务器：" + summarize(e), "");
    }

    private static StorageErrorCode classify(SVNException e) {
        SVNErrorMessage message = e.getErrorMessage();
        if (message == null) {
            return StorageErrorCode.IO_ERROR;
        }
        if (message.hasChildWithErrorCode(SVNErrorCode.CANCELLED)) {
            return StorageErrorCode.CANCELLED;
        }
        if (isAuth(message)) {
            return StorageErrorCode.AUTH_FAILED;
        }
        if (isNotFound(message)) {
            return StorageErrorCode.NOT_FOUND;
        }
        if (isAlreadyExists(message)) {
            return StorageErrorCode.ALREADY_EXISTS;
        }

        String text = message.getFullMessage() == null ? "" : message.getFullMessage().toLowerCase(Locale.ROOT);
        if (contains(text, "timed out", "timeout", "read timed out") || hasCause(e, SocketTimeoutException.class)) {
            return StorageErrorCode.TIMEOUT;
        }
        // 注意别用裸 "eof"：错误消息常带路径，任何含 eof 子串的文件名（如 beforeof）
        // 都会把普通 IO 错误误分类成 CONNECT_FAILED，进而触发重试语义
        if (contains(text, "connection refused", "connection reset", "unknown host",
                "network is unreachable", "no route to host", "broken pipe",
                "unexpected end of file", "unexpected eof", "premature eof",
                "connection closed", "unable to connect") || hasCause(e, ConnectException.class)
                || hasCause(e, UnknownHostException.class)) {
            return StorageErrorCode.CONNECT_FAILED;
        }
        // 仅 svn:// 传输层 IO（RA_SVN_IO_ERROR）算连不上；通用 IO_ERROR 保留为 IO_ERROR
        if (message.hasChildWithErrorCode(SVNErrorCode.RA_SVN_IO_ERROR)) {
            return StorageErrorCode.CONNECT_FAILED;
        }
        if (message.hasChildWithErrorCode(SVNErrorCode.RA_ILLEGAL_URL)
                || message.hasChildWithErrorCode(SVNErrorCode.UNSUPPORTED_FEATURE)) {
            return StorageErrorCode.UNSUPPORTED;
        }
        if (message.hasChildWithErrorCode(SVNErrorCode.FS_CONFLICT)
                || message.hasChildWithErrorCode(SVNErrorCode.FS_TXN_OUT_OF_DATE)) {
            // E160028 事务过时：提交基线落后于 HEAD（并发改动 / 删除缺失的深层路径）
            return StorageErrorCode.CONFLICT;
        }
        return StorageErrorCode.IO_ERROR;
    }

    private static boolean isAuth(SVNErrorMessage message) {
        if (message.hasChildWithErrorCode(SVNErrorCode.RA_NOT_AUTHORIZED)
                || message.hasChildWithErrorCode(SVNErrorCode.AUTHZ_ROOT_UNREADABLE)) {
            return true;
        }
        // 整个错误链上任一错误码属于认证 / 授权类别
        for (SVNErrorMessage current = message; current != null; current = current.getChildErrorMessage()) {
            SVNErrorCode code = current.getErrorCode();
            if (code != null && (code.getCategory() == SVNErrorCode.AUTHN_CATEGORY
                    || code.getCategory() == SVNErrorCode.AUTHZ_CATEGORY)) {
                return true;
            }
        }
        String text = message.getFullMessage() == null ? "" : message.getFullMessage().toLowerCase(Locale.ROOT);
        return contains(text, "authorization failed", "authentication failed",
                " 401 ", "401 unauthorized", " 403", "403 forbidden", "no more credentials",
                "permission denied", "not authorized");
    }

    private static boolean isNotFound(SVNErrorMessage message) {
        if (message.hasChildWithErrorCode(SVNErrorCode.FS_NOT_FOUND)
                || message.hasChildWithErrorCode(SVNErrorCode.ENTRY_NOT_FOUND)
                || message.hasChildWithErrorCode(SVNErrorCode.RA_DAV_PATH_NOT_FOUND)) {
            return true;
        }
        String text = message.getFullMessage() == null ? "" : message.getFullMessage().toLowerCase(Locale.ROOT);
        // E160013 = FS_NOT_FOUND，E170000 = 路径不存在；DAV 的 404 文本
        return contains(text, "404 not found", "e160013", "doesn't exist", "does not exist",
                "path not found", "file not found", "no such");
    }

    private static boolean isAlreadyExists(SVNErrorMessage message) {
        if (message.hasChildWithErrorCode(SVNErrorCode.FS_ALREADY_EXISTS)
                || message.hasChildWithErrorCode(SVNErrorCode.ENTRY_EXISTS)
                || message.hasChildWithErrorCode(SVNErrorCode.RA_DAV_ALREADY_EXISTS)) {
            return true;
        }
        String text = message.getFullMessage() == null ? "" : message.getFullMessage().toLowerCase(Locale.ROOT);
        return contains(text, "already exists", "405 method not allowed", "e160020");
    }

    private static boolean contains(String text, String... markers) {
        for (String marker : markers) {
            if (text.contains(marker)) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasCause(Throwable throwable, Class<?> type) {
        for (Throwable current = throwable; current != null; current = current.getCause()) {
            if (type.isInstance(current)) {
                return true;
            }
        }
        return false;
    }

    private static String summarize(SVNException e) {
        SVNErrorMessage message = e.getErrorMessage();
        if (message == null || message.getFullMessage() == null) {
            return e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
        }
        String full = message.getFullMessage().trim();
        SVNErrorMessage root = message.getRootErrorMessage();
        if (root != null && root.getMessage() != null && full.length() > 240) {
            // 长错误链只保留根因，界面提示避免整屏 svn: E##### 包装文本
            return root.getMessage().trim();
        }
        return full.length() > 240 ? full.substring(0, 240) + "…" : full;
    }
}
