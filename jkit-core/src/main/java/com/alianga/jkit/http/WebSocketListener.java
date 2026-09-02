package com.alianga.jkit.http;

/**
 * WebSocket 回调。需要 JDK 11+ 的 {@code java.net.http.WebSocket}。
 *
 * @author 郑明亮
 */
public interface WebSocketListener {
    /**
     * 握手完成。
     */
    default void onOpen() {
    }

    /**
     * 文本帧。
     *
     * @param text 文本
     * @param last 是否为该消息最后一帧
     */
    default void onText(String text, boolean last) {
    }

    /**
     * 二进制帧。
     *
     * @param data 数据
     * @param last 是否为该消息最后一帧
     */
    default void onBinary(byte[] data, boolean last) {
    }

    /**
     * Ping。
     *
     * @param data 负载
     */
    default void onPing(byte[] data) {
    }

    /**
     * Pong。
     *
     * @param data 负载
     */
    default void onPong(byte[] data) {
    }

    /**
     * 连接关闭。
     *
     * @param statusCode 状态码
     * @param reason 原因
     */
    default void onClose(int statusCode, String reason) {
    }

    /**
     * 错误。
     *
     * @param error 异常
     */
    default void onError(Throwable error) {
    }
}
