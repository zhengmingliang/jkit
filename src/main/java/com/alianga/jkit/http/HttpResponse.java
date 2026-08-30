package com.alianga.jkit.http;

import java.io.Closeable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * HTTP 响应，替代原 OkHttp {@code Response}。
 *
 * @author 郑明亮
 */
public class HttpResponse implements Closeable {
    private final int code;
    private final String message;
    private final String requestUrl;
    private final Map<String, List<String>> headers;
    private final HttpResponseBody body;
    private volatile long elapsedMs = -1L;
    private volatile int attempts = 1;
    private volatile String endpointBaseUrl;

    /**
     * @param code 状态码
     * @param message 状态短语
     * @param requestUrl 请求 URL
     * @param headers 响应头（可含 {@code null} 键的状态行）
     * @param body 响应体
     */
    public HttpResponse(int code, String message, String requestUrl,
                        Map<String, List<String>> headers, HttpResponseBody body) {
        this.code = code;
        this.message = message;
        this.requestUrl = requestUrl;
        this.headers = normalizeHeaders(headers);
        this.body = body;
    }

    private static Map<String, List<String>> normalizeHeaders(Map<String, List<String>> raw) {
        Map<String, List<String>> map = new LinkedHashMap<String, List<String>>();
        if (raw == null) {
            return map;
        }
        for (Map.Entry<String, List<String>> entry : raw.entrySet()) {
            if (entry.getKey() == null) {
                continue;
            }
            List<String> values = entry.getValue() == null
                    ? Collections.<String>emptyList()
                    : new ArrayList<String>(entry.getValue());
            map.put(entry.getKey(), values);
        }
        return map;
    }

    /**
     * @return HTTP 状态码
     */
    public int code() {
        return code;
    }

    /**
     * 本次请求的端到端耗时（毫秒），含重试、重定向跟随与故障转移的全部时间。
     *
     * @return 耗时毫秒；未被计时（例如直接由引擎构造）时为 {@code -1}
     */
    public long elapsedMs() {
        return elapsedMs;
    }

    /**
     * 设置端到端耗时，由发送链路填充。
     *
     * @param elapsedMs 耗时毫秒
     * @return this
     */
    public HttpResponse elapsedMs(long elapsedMs) {
        this.elapsedMs = elapsedMs;
        return this;
    }

    /**
     * 实际发出的网络请求次数（首次 + 重试 + 故障转移），最小为 1。
     *
     * @return 尝试次数
     */
    public int attempts() {
        return attempts;
    }

    /**
     * 设置实际尝试次数，由发送链路填充。
     *
     * @param attempts 尝试次数
     * @return this
     */
    public HttpResponse attempts(int attempts) {
        this.attempts = Math.max(1, attempts);
        return this;
    }

    /**
     * 本次请求最终命中的负载均衡端点基址。
     *
     * @return 端点基址；未启用负载均衡时为 {@code null}
     */
    public String endpointBaseUrl() {
        return endpointBaseUrl;
    }

    /**
     * 设置命中的端点基址，由发送链路填充。
     *
     * @param endpointBaseUrl 端点基址
     * @return this
     */
    public HttpResponse endpointBaseUrl(String endpointBaseUrl) {
        this.endpointBaseUrl = endpointBaseUrl;
        return this;
    }

    /**
     * @return 状态短语，可能为 {@code null}
     */
    public String message() {
        return message;
    }

    /**
     * @return 状态码是否在 200–299
     */
    public boolean isSuccessful() {
        return code >= 200 && code < 300;
    }

    /**
     * 要求响应为 2xx，否则抛出包含状态码和 URL 的异常。
     *
     * @return this
     * @throws HttpStatusException 非 2xx 响应
     */
    public HttpResponse requireSuccessful() throws HttpStatusException {
        if (!isSuccessful()) {
            throw new HttpStatusException(this);
        }
        return this;
    }

    /**
     * @return 请求 URL
     */
    public String requestUrl() {
        return requestUrl;
    }

    /**
     * @return 响应体，可能为 {@code null}
     */
    public HttpResponseBody body() {
        return body;
    }

    /**
     * 按名称取第一个头（大小写不敏感）。
     *
     * @param name 头名称
     * @return 头值，不存在时为 {@code null}
     */
    public String header(String name) {
        List<String> values = headers(name);
        if (values == null || values.isEmpty()) {
            return null;
        }
        return values.get(0);
    }

    /**
     * 按名称取全部头值（大小写不敏感）。
     *
     * @param name 头名称
     * @return 值列表，不存在时为空列表
     */
    public List<String> headers(String name) {
        if (name == null) {
            return Collections.emptyList();
        }
        for (Map.Entry<String, List<String>> entry : headers.entrySet()) {
            if (name.equalsIgnoreCase(entry.getKey())) {
                return entry.getValue() == null
                        ? Collections.<String>emptyList()
                        : entry.getValue();
            }
        }
        return Collections.emptyList();
    }

    /**
     * @return 全部响应头（不含状态行）
     */
    public Map<String, List<String>> headers() {
        return headers;
    }

    /**
     * 以小写名为键的头映射，便于与 {@link com.alianga.jkit.FileUtils#getFileNameFromHttp(Map)} 一起使用。
     *
     * @return 新的头 Map
     */
    public Map<String, List<String>> headersLowerCase() {
        Map<String, List<String>> map = new LinkedHashMap<String, List<String>>();
        for (Map.Entry<String, List<String>> entry : headers.entrySet()) {
            map.put(entry.getKey().toLowerCase(Locale.ROOT), entry.getValue());
        }
        return map;
    }

    @Override
    public void close() {
        if (body != null) {
            body.close();
        }
    }
}
