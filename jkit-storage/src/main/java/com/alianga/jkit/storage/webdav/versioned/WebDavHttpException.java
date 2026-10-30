package com.alianga.jkit.storage.webdav.versioned;

import java.io.IOException;

/**
 * WebDAV HTTP 失败，携带状态码供上层按状态映射本地化文案。
 *
 * <p>本异常的 {@link #getMessage()} 为英文技术摘要；界面文案由调用方按 {@link #status()}
 * 分类生成（例如 ReqDesk 的 {@code WebDavException.fromStatus}）。</p>
 *
 * @author 郑明亮
 */
public final class WebDavHttpException extends IOException {
    private final int status;
    private final String responseBody;

    /**
     * @param status  HTTP 状态码
     * @param message 技术摘要（英文）
     */
    public WebDavHttpException(int status, String message) {
        this(status, message, null);
    }

    /**
     * @param status       HTTP 状态码
     * @param message      技术摘要（英文）
     * @param responseBody 响应体摘要（可空）
     */
    public WebDavHttpException(int status, String message, String responseBody) {
        super(message);
        this.status = status;
        this.responseBody = responseBody;
    }

    /**
     * @param status       HTTP 状态码
     * @param message      技术摘要
     * @param responseBody 响应体摘要（可空）
     * @param cause        原因
     */
    public WebDavHttpException(int status, String message, String responseBody, Throwable cause) {
        super(message, cause);
        this.status = status;
        this.responseBody = responseBody;
    }

    /** @return HTTP 状态码 */
    public int status() {
        return status;
    }

    /** @return 响应体摘要；可能为 {@code null} */
    public String responseBody() {
        return responseBody;
    }
}
