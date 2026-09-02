package com.alianga.jkit.http;

import java.io.IOException;

/**
 * 表示 HTTP 响应状态码不是 2xx。
 */
public class HttpStatusException extends IOException {
    /**
     * 响应状态码，响应为 {@code null} 时为 -1
     */
    private final int statusCode;
    /**
     * 发起请求的 URL，响应为 {@code null} 时为 {@code null}
     */
    private final String requestUrl;
    /**
     * 原始响应对象，可能为 {@code null}
     */
    private final transient HttpResponse response;
    /**
     * 已读取的错误正文片段，可能为 {@code null}
     */
    private final String bodySnippet;

    /**
     * @param response 原始响应
     */
    public HttpStatusException(HttpResponse response) {
        this(response, null);
    }

    /**
     * 用于响应已被读取并关闭的场景：正文片段直接进异常信息，调用方不必再持有响应。
     *
     * @param response 原始响应，可为 {@code null}
     * @param bodySnippet 错误正文片段，可为 {@code null}
     */
    public HttpStatusException(HttpResponse response, String bodySnippet) {
        super("HTTP request failed with status "
                + (response == null ? -1 : response.code())
                + " for " + (response == null ? "<unknown>" : response.requestUrl())
                + (bodySnippet == null || bodySnippet.isEmpty() ? "" : ", body: " + snippet(bodySnippet)));
        this.response = response;
        this.statusCode = response == null ? -1 : response.code();
        this.requestUrl = response == null ? null : response.requestUrl();
        this.bodySnippet = bodySnippet;
    }

    private static String snippet(String body) {
        String value = body.replace('\n', ' ').replace('\r', ' ').trim();
        return value.length() <= 512 ? value : value.substring(0, 512) + "...";
    }

    /**
     * @return 状态码
     */
    public int getStatusCode() {
        return statusCode;
    }

    /**
     * @return 请求 URL
     */
    public String getRequestUrl() {
        return requestUrl;
    }

    /**
     * @return 错误正文（由抛出方读取并保留），未保留时为 {@code null}
     */
    public String getBodySnippet() {
        return bodySnippet;
    }

    /**
     * @return 原始响应；调用方负责关闭。经 {@code throwOnHttpError} 抛出时响应已关闭，
     *         正文请用 {@link #getBodySnippet()}
     */
    public HttpResponse getResponse() {
        return response;
    }
}
