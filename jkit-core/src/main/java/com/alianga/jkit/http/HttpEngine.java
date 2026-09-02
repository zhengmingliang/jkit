package com.alianga.jkit.http;

import java.io.IOException;

/**
 * HTTP 传输引擎。JDK 8 使用 {@link UrlConnectionHttpEngine}，
 * JDK 11+ 优先使用 {@code java.net.http.HttpClient} 实现。
 *
 * @author 郑明亮
 */
public interface HttpEngine {
    /**
     * @return 引擎标识，如 {@link HttpEngines#URL_CONNECTION}
     */
    String name();

    /**
     * 同步执行请求。
     *
     * @param request 请求
     * @return 响应
     * @throws IOException 网络或协议错误
     */
    HttpResponse execute(HttpRequest request) throws IOException;
}
