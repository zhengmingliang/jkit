package com.alianga.jkit.http;

import java.net.HttpCookie;
import java.net.URI;
import java.util.List;

/**
 * Cookie 仓库，替代原 OkHttp {@code CookieJar}。
 * <p>
 * 默认实现见 {@link CookieJarImpl}（基于 JDK {@link java.net.CookieManager}）。
 *
 * @author 郑明亮
 */
public interface HttpCookieJar {
    /**
     * 加载即将发往指定 URI 的 Cookie。
     *
     * @param uri 请求 URI
     * @return Cookie 列表，不应返回 {@code null}
     */
    List<HttpCookie> loadForRequest(URI uri);

    /**
     * 将响应中的 Cookie 保存到仓库。
     *
     * @param uri 请求 URI
     * @param cookies 解析后的 Cookie 列表
     */
    void saveFromResponse(URI uri, List<HttpCookie> cookies);
}
