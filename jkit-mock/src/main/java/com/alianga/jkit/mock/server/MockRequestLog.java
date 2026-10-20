package com.alianga.jkit.mock.server;

import java.time.Instant;
import java.util.Collections;
import java.util.Map;

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
    /** 为 true 表示该请求未命中路由、被代理转发到真实后端。 */
    public final boolean proxied;
    /** 协议标记：HTTP（缺省）/ SSE / WS，供日志区分长连接。 */
    public final String protocol;
    /** 请求头快照（每名取首值、不可变）；WS 消息帧等无头场景为空表。 */
    public final Map<String, String> requestHeaders;
    /** 实际写出的响应头快照（每名取首值、不可变）；连接中断时以已设置的头为准。 */
    public final Map<String, String> responseHeaders;
    /**
     * 本次触发并生效的网络层故障标识（{@link MockFault#toString()}，如 {@code SLOW_BODY(256B/200ms)}）；
     * 未触发故障时为空串。概率未命中不记，方便定位「为什么这条请求没故障」。
     */
    public final String fault;

    public MockRequestLog(Instant timestamp, String method, String path, String query,
                          boolean matched, int status, long durationMs) {
        this(timestamp, method, path, query, matched, status, durationMs, "", "", false);
    }

    public MockRequestLog(Instant timestamp, String method, String path, String query,
                          boolean matched, int status, long durationMs,
                          String requestBody, String responseBody) {
        this(timestamp, method, path, query, matched, status, durationMs,
                requestBody, responseBody, false);
    }

    public MockRequestLog(Instant timestamp, String method, String path, String query,
                          boolean matched, int status, long durationMs,
                          String requestBody, String responseBody, boolean proxied) {
        this(timestamp, method, path, query, matched, status, durationMs,
                requestBody, responseBody, proxied, "HTTP");
    }

    /**
     * @param protocol HTTP / SSE / WS
     */
    public MockRequestLog(Instant timestamp, String method, String path, String query,
                          boolean matched, int status, long durationMs,
                          String requestBody, String responseBody, boolean proxied,
                          String protocol) {
        this(timestamp, method, path, query, matched, status, durationMs,
                requestBody, responseBody, proxied, protocol, null, null);
    }

    /**
     * @param protocol        HTTP / SSE / WS
     * @param requestHeaders  请求头快照；null 按空表记录
     * @param responseHeaders 响应头快照；null 按空表记录
     * @since 2.0.5
     */
    public MockRequestLog(Instant timestamp, String method, String path, String query,
                          boolean matched, int status, long durationMs,
                          String requestBody, String responseBody, boolean proxied,
                          String protocol, Map<String, String> requestHeaders,
                          Map<String, String> responseHeaders) {
        this(timestamp, method, path, query, matched, status, durationMs,
                requestBody, responseBody, proxied, protocol, requestHeaders, responseHeaders, "");
    }

    /**
     * @param fault 本次触发的故障标识（{@link MockFault#toString()}）；空串表示未触发，可传 null
     * @since 2.0.5
     */
    public MockRequestLog(Instant timestamp, String method, String path, String query,
                          boolean matched, int status, long durationMs,
                          String requestBody, String responseBody, boolean proxied,
                          String protocol, Map<String, String> requestHeaders,
                          Map<String, String> responseHeaders, String fault) {
        this.timestamp = timestamp == null ? Instant.now() : timestamp;
        this.method = method == null ? "" : method;
        this.path = path == null ? "" : path;
        this.query = query == null ? "" : query;
        this.matched = matched;
        this.status = status;
        this.durationMs = durationMs;
        this.requestBody = requestBody == null ? "" : requestBody;
        this.responseBody = responseBody == null ? "" : responseBody;
        this.proxied = proxied;
        this.protocol = protocol == null || protocol.trim().isEmpty() ? "HTTP" : protocol;
        this.requestHeaders = safeHeaders(requestHeaders);
        this.responseHeaders = safeHeaders(responseHeaders);
        this.fault = fault == null ? "" : fault;
    }

    private static Map<String, String> safeHeaders(Map<String, String> headers) {
        if (headers == null || headers.isEmpty()) {
            return Collections.emptyMap();
        }
        return Collections.unmodifiableMap(headers);
    }

    @Override
    public String toString() {
        String q = query.isEmpty() ? "" : "?" + query;
        return String.format("%s %s%s -> %d (%dms)%s%s%s",
                method, path, q, status, durationMs,
                matched ? "" : " [miss]", proxied ? " [proxy]" : "",
                fault.isEmpty() ? "" : " [fault: " + fault + "]");
    }
}
