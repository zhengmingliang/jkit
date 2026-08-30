package com.alianga.jkit.http;

import com.alianga.jkit.jdk.JdkUtils;

/**
 * HTTP 引擎工厂。
 * <p>
 * 运行在 JDK 9+ 且存在 {@code java.net.http.HttpClient}（标准 API 自 JDK 11 起）时，
 * 加载 {@code META-INF/versions/11} 中的 {@code JdkHttpClientEngine}；
 * 否则使用 {@link UrlConnectionHttpEngine}。可通过系统属性 {@code jkit.http.engine}
 * 强制选择：{@code url} 或 {@code jdk}。
 *
 * @author 郑明亮
 */
public final class HttpEngines {
    /**
     * {@link java.net.HttpURLConnection} 实现标识
     */
    public static final String URL_CONNECTION = "http-url-connection";
    /**
     * {@code java.net.http.HttpClient} 实现标识
     */
    public static final String JDK_HTTP_CLIENT = "jdk-http-client";

    private HttpEngines() {
    }

    /**
     * 按当前 JDK 与系统属性创建引擎。
     *
     * @return 传输引擎
     */
    public static HttpEngine create() {
        String forced = System.getProperty("jkit.http.engine");
        if (forced != null) {
            if ("url".equalsIgnoreCase(forced) || URL_CONNECTION.equalsIgnoreCase(forced)) {
                return new UrlConnectionHttpEngine();
            }
            if ("jdk".equalsIgnoreCase(forced) || JDK_HTTP_CLIENT.equalsIgnoreCase(forced)) {
                HttpEngine jdk = tryJdkHttpClient();
                if (jdk != null) {
                    return jdk;
                }
            }
        }
        if (JdkUtils.JAVA_VERSION >= 9) {
            HttpEngine jdk = tryJdkHttpClient();
            if (jdk != null) {
                return jdk;
            }
        }
        return new UrlConnectionHttpEngine();
    }

    /**
     * @return HttpURLConnection 引擎
     */
    public static HttpEngine urlConnection() {
        return new UrlConnectionHttpEngine();
    }

    /**
     * 尝试加载 JDK HttpClient 引擎。类不存在或当前 JDK 无 {@code java.net.http} 时返回 {@code null}。
     *
     * @return JDK HttpClient 引擎，不可用时为 {@code null}
     */
    public static HttpEngine tryJdkHttpClient() {
        try {
            Class.forName("java.net.http.HttpClient");
            Class<?> clazz = Class.forName("com.alianga.jkit.http.JdkHttpClientEngine");
            return (HttpEngine) clazz.getDeclaredConstructor().newInstance();
        } catch (Throwable t) {
            return null;
        }
    }
}
