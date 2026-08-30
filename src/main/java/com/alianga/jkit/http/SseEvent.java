package com.alianga.jkit.http;

/**
 * 一条 Server-Sent Event。
 *
 * @author 郑明亮
 */
public final class SseEvent {
    private final String id;
    private final String event;
    private final String data;
    private final long retryMs;

    /**
     * @param id 事件 id，可为空
     * @param event 事件名，缺省为 {@code message}
     * @param data 数据（多行 data 已用换行拼接）
     * @param retryMs 服务端建议的重连间隔，未出现时为 {@code -1}
     */
    public SseEvent(String id, String event, String data, long retryMs) {
        this.id = id;
        this.event = event;
        this.data = data;
        this.retryMs = retryMs;
    }

    /**
     * @return id
     */
    public String getId() {
        return id;
    }

    /**
     * @return 事件名
     */
    public String getEvent() {
        return event;
    }

    /**
     * @return 数据
     */
    public String getData() {
        return data;
    }

    /**
     * @return 重连间隔毫秒，未指定为 {@code -1}
     */
    public long getRetryMs() {
        return retryMs;
    }

    @Override
    public String toString() {
        return "SseEvent{event=" + event + ", id=" + id + ", data=" + data + '}';
    }
}
