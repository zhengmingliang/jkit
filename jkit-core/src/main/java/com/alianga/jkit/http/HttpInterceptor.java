package com.alianga.jkit.http;

import java.io.IOException;

/**
 * 请求拦截器：可以在发送前改写请求、在收到响应后加工响应，也可以直接短路返回。
 * <p>
 * 拦截器包裹的是<b>整个</b>发送过程（含重试、故障转移、重定向跟随），因此
 * {@link Chain#proceed(HttpRequest)} 只会被调用一次，而内部可能实际发出多次网络请求。
 * 这样计时、链路追踪、签名、鉴权刷新都只需要写一遍。
 * <p>
 * 注册顺序即执行顺序，先注册的在最外层：
 * <pre>{@code
 * HttpUtils.config().addInterceptor(chain -> {
 *     HttpRequest request = chain.request().header("X-Trace-Id", traceId());
 *     long start = System.nanoTime();
 *     HttpResponse response = chain.proceed(request);
 *     metrics.record(request.getMethod(), response.code(),
 *             (System.nanoTime() - start) / 1_000_000);
 *     return response;
 * });
 * }</pre>
 * <p>
 * 注意：拦截器内不要读取（消费）响应体，否则后续调用方拿不到内容。需要读正文时
 * 请用 {@link HttpResponse#code()} 与响应头判断，或自行缓冲后重建响应。
 *
 * @author 郑明亮
 */
public interface HttpInterceptor {
    /**
     * 拦截一次请求。
     *
     * @param chain 调用链
     * @return 响应，不能为 {@code null}
     * @throws IOException 网络错误或拦截器主动抛出
     */
    HttpResponse intercept(Chain chain) throws IOException;

    /**
     * 拦截器调用链。
     */
    interface Chain {
        /**
         * @return 当前待发送的请求
         */
        HttpRequest request();

        /**
         * 继续向下执行（最终真正发出网络请求）。
         *
         * @param request 要发送的请求，可以是改写后的对象
         * @return 响应
         * @throws IOException 网络错误
         */
        HttpResponse proceed(HttpRequest request) throws IOException;
    }
}
