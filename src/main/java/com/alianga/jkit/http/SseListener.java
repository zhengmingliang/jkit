package com.alianga.jkit.http;

import java.io.IOException;

/**
 * SSE 回调。除 {@link #onEvent(SseEvent)} 外均可选用。
 *
 * @author 郑明亮
 */
public interface SseListener {
    /**
     * 连接已建立（响应头可读）。
     *
     * @param response 响应
     */
    default void onOpen(HttpResponse response) {
    }

    /**
     * 收到一条事件。
     *
     * @param event 事件
     */
    void onEvent(SseEvent event);

    /**
     * 收到注释行（以 {@code :} 开头）。
     *
     * @param comment 注释内容
     */
    default void onComment(String comment) {
    }

    /**
     * 读流出错。
     *
     * @param e 错误
     */
    default void onError(IOException e) {
    }

    /**
     * 自动重连前回调（仅 {@link com.alianga.jkit.HttpUtils#sseReconnect} 系列触发）。
     *
     * @param attempt 第几次重连（从 1 开始）
     * @param delayMs 本次重连前等待的毫秒数
     * @param cause 触发重连的原因（流结束或读流异常）
     */
    default void onReconnect(int attempt, long delayMs, Throwable cause) {
    }

    /**
     * 流结束或被取消。
     */
    default void onClosed() {
    }
}
