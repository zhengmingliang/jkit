package com.alianga.jkit.http;

import java.io.IOException;

/**
 * 异步 HTTP 回调，替代原 OkHttp {@code Callback}/{@code HttpCallBack}。
 *
 * @param <T> 成功时的业务结果类型
 * @author 郑明亮
 */
public interface HttpCallBack<T> {
    /**
     * 请求失败。
     *
     * @param call 调用句柄
     * @param e 失败原因
     */
    void onFailure(HttpCall call, IOException e);

    /**
     * 下载/读取进度。
     *
     * @param process 本次读取的字节数
     * @param total 总字节数，未知时为 {@code -1}
     */
    void onProcess(long process, long total);

    /**
     * 请求成功。
     *
     * @param call 调用句柄
     * @param response 响应
     * @param result 业务结果（如下载得到的本地路径）
     * @throws IOException 回调处理失败
     */
    void onResponse(HttpCall call, HttpResponse response, T result) throws IOException;
}
