package com.alianga.jkit.http;

/**
 * 一次 HTTP 调用的句柄，替代原 OkHttp {@code Call}。
 *
 * @author 郑明亮
 */
public class HttpCall {
    private final HttpRequest request;
    private volatile boolean canceled;

    /**
     * @param request 本次调用对应的请求
     */
    public HttpCall(HttpRequest request) {
        this.request = request;
    }

    /**
     * @return 请求对象
     */
    public HttpRequest request() {
        return request;
    }

    /**
     * 取消调用（尽力而为，已发出的请求不一定能中断）。
     */
    public void cancel() {
        canceled = true;
    }

    /**
     * @return 是否已取消
     */
    public boolean isCanceled() {
        return canceled;
    }
}
