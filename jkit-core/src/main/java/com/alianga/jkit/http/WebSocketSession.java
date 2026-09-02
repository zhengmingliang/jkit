package com.alianga.jkit.http;

import java.io.IOException;
import java.nio.ByteBuffer;

/**
 * 一条 WebSocket 连接。JDK 11+ 可用。
 *
 * @author 郑明亮
 */
public interface WebSocketSession extends AutoCloseable {
    /**
     * 发送文本。
     *
     * @param text 文本
     * @throws IOException 发送失败
     */
    void sendText(String text) throws IOException;

    /**
     * 发送二进制。
     *
     * @param data 数据
     * @throws IOException 发送失败
     */
    void sendBinary(byte[] data) throws IOException;

    /**
     * 发送 ping。
     *
     * @param data 负载，可为 {@code null}
     * @throws IOException 发送失败
     */
    void sendPing(ByteBuffer data) throws IOException;

    /**
     * @return 是否仍然打开
     */
    boolean isOpen();

    /**
     * 以 1000 正常关闭。
     *
     * @throws IOException 关闭失败
     */
    @Override
    void close() throws IOException;

    /**
     * @param statusCode 关闭码
     * @param reason 原因
     * @throws IOException 关闭失败
     */
    void close(int statusCode, String reason) throws IOException;
}
