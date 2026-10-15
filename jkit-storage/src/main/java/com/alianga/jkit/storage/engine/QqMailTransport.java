package com.alianga.jkit.storage.engine;

import java.io.Closeable;
import java.io.InputStream;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 单跳 HTTP 交换（不跟随重定向、不管 Cookie）；重定向与 Cookie 由 {@link QqMailWebClient} 处理。
 * 生产实现基于 HttpCore，单测注入 Mock。
 *
 * @author 郑明亮
 */
public interface QqMailTransport {
    /**
     * 执行一次请求。
     *
     * @param method  方法
     * @param url     完整 URL（可能含凭据，实现不得记录）
     * @param headers 请求头
     * @param body    请求体（GET 为 null）
     * @return 响应（调用方 close）
     */
    Response exchange(String method, String url, Map<String, String> headers, byte[] body);

    /** 单跳响应。 */
    final class Response implements Closeable {
        final int status;
        final Map<String, List<String>> headers;
        final InputStream body;
        private final Closeable onClose;

        /**
         * @param status  状态码
         * @param headers 响应头（名大小写不敏感查找）
         * @param body    响应体
         * @param onClose 关闭回调（可 null）
         */
        public Response(int status, Map<String, List<String>> headers, InputStream body, Closeable onClose) {
            this.status = status;
            this.headers = headers == null ? Collections.<String, List<String>>emptyMap()
                    : new LinkedHashMap<String, List<String>>(headers);
            this.body = body;
            this.onClose = onClose;
        }

        /**
         * @param name 头名
         * @return 全部取值（不存在为空列表）
         */
        public List<String> all(String name) {
            for (Map.Entry<String, List<String>> e : headers.entrySet()) {
                if (e.getKey() != null && e.getKey().equalsIgnoreCase(name) && e.getValue() != null) {
                    return e.getValue();
                }
            }
            return Collections.emptyList();
        }

        /**
         * @param name 头名
         * @return 第一个取值或 null
         */
        public String first(String name) {
            List<String> v = all(name);
            return v.isEmpty() ? null : v.get(0);
        }

        @Override
        public void close() {
            try {
                if (body != null) {
                    body.close();
                }
                if (onClose != null) {
                    onClose.close();
                }
            } catch (java.io.IOException ignored) {
                // ignore
            }
        }
    }
}
