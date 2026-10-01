package com.alianga.jkit.mock.server;

import java.time.Instant;

/**
 * 一次入站请求的日志条目。query / 请求体 / 响应体在写入时已按长度截断，避免撑爆内存；
 * 服务停止打断或客户端提前断开时同样会留下记录（以当时已确定的状态码记入）。
 *
 * @author 郑明亮
 * @since 2.0.4
 */
public final class MockRequestLog {
    public final Instant timestamp;
    public final String method;
    public final String path;
    /** 可能已截断的 query（不含前导 ?）。 */
    public final String query;
    public final boolean matched;
    public final int status;
    public final long durationMs;
    /** 可能已截断的请求体摘要；无体时为空串。 */
    public final String requestBody;
    /** 可能已截断的响应体摘要；无体时为空串。 */
    public final String responseBody;

    public MockRequestLog(Instant timestamp, String method, String path, String query,
                          boolean matched, int status, long durationMs) {
        this(timestamp, method, path, query, matched, status, durationMs, "", "");
    }

    public MockRequestLog(Instant timestamp, String method, String path, String query,
                          boolean matched, int status, long durationMs,
                          String requestBody, String responseBody) {
        this.timestamp = timestamp == null ? Instant.now() : timestamp;
        this.method = method == null ? "" : method;
        this.path = path == null ? "" : path;
        this.query = query == null ? "" : query;
        this.matched = matched;
        this.status = status;
        this.durationMs = durationMs;
        this.requestBody = requestBody == null ? "" : requestBody;
        this.responseBody = responseBody == null ? "" : responseBody;
    }

    @Override
    public String toString() {
        String q = query.isEmpty() ? "" : "?" + query;
        return String.format("%s %s%s -> %d (%dms)%s",
                method, path, q, status, durationMs, matched ? "" : " [miss]");
    }
}
