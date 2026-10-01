package com.alianga.jkit.mock.server;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 一条 Mock 路由定义：HTTP 方法、路径模板、状态码、延迟、Content-Type、响应体，
 * 以及 2.0.4 起的响应头、响应方案（多响应 + 激活规则）、Mock.js 模板动态渲染与启用开关。
 *
 * <p>路径支持 {@code {name}} 单段参数（如 {@code /api/users/{id}}），匹配到的参数值可在
 * 响应模板中以 {@code {{param.name}}} 引用；方法支持 {@code *} 表示任意方法。
 * {@code echo} 为 true 时把请求体原样回写；{@code dynamicSchema} 为 true 时按 JSON Schema
 * 每次请求生成；{@code dynamicTemplate} 为 true 时把 body 当 Mock.js 模板每次请求渲染。</p>
 *
 * <p>本类不可变，可跨线程安全共享；用 {@link #builder()} 组装，或继续用历史便捷构造器。
 * {@link MockHttpServer#setRoutes} 热更新即整表替换快照。</p>
 *
 * @author 郑明亮
 * @since 2.0.4
 */
public final class MockRoute {
    /** HTTP 方法，或 {@code *} 表示任意方法。 */
    public final String method;
    /** 精确路径如 {@code /foo}，或带 {@code {name}} 段的模板。 */
    public final String path;
    public final int statusCode;
    public final long latencyMs;
    public final String contentType;
    public final String body;
    /** 可选 JSON Schema；非空时可一键生成或动态生成响应体。 */
    public final String responseSchema;
    /** 为 true 且 schema 非空时，服务每次请求用 {@link com.alianga.jkit.mock.MockSchema} 重新生成。 */
    public final boolean dynamicSchema;
    /** 为 true 时把请求体原样回写，并沿用请求的 Content-Type。 */
    public final boolean echo;
    /** 为 false 时本路由不参与匹配（热更新立即生效）。 */
    public final boolean enabled;
    /** 延迟区间上限（毫秒）；大于 {@link #latencyMs} 时每次请求在区间内随机，0 表示固定延迟。 */
    public final long latencyMaxMs;
    /** 为 true 时本路由按 CRUD 资源模式服务（{@code GET/POST /base}、{@code GET/PUT/PATCH/DELETE /base/{id}}）。 */
    public final boolean crud;
    /** 为 true 时 body 按 Mock.js 模板（{@code @cname}、{@code 'list|1-3'} 等）每次请求渲染。 */
    public final boolean dynamicTemplate;
    /** 附加响应头（保序）；值支持 {@code {{query.x}}} 等占位符。 */
    public final Map<String, String> headers;
    /** 响应方案；非空时按「规则全中者优，无中者取第一条」挑选，主字段不再参与。 */
    public final List<MockResponse> responses;

    public MockRoute(String method, String path, int statusCode, long latencyMs,
                     String contentType, String body) {
        this(method, path, statusCode, latencyMs, contentType, body, "", false, false,
                true, false, Collections.<String, String>emptyMap(),
                Collections.<MockResponse>emptyList(), 0L, false);
    }

    public MockRoute(String method, String path, int statusCode, long latencyMs,
                     String contentType, String body, String responseSchema, boolean dynamicSchema) {
        this(method, path, statusCode, latencyMs, contentType, body, responseSchema, dynamicSchema,
                false, true, false, Collections.<String, String>emptyMap(),
                Collections.<MockResponse>emptyList(), 0L, false);
    }

    /** 历史 eight-arg + echo 的便捷重载。 */
    public MockRoute(String method, String path, int statusCode, long latencyMs,
                     String contentType, String body, String responseSchema, boolean dynamicSchema,
                     boolean echo) {
        this(method, path, statusCode, latencyMs, contentType, body, responseSchema, dynamicSchema,
                echo, true, false, Collections.<String, String>emptyMap(),
                Collections.<MockResponse>emptyList(), 0L, false);
    }

    /**
     * 全参构造；一般用 {@link #builder()} 更易读。
     */
    public MockRoute(String method, String path, int statusCode, long latencyMs,
                     String contentType, String body, String responseSchema, boolean dynamicSchema,
                     boolean echo, boolean enabled, boolean dynamicTemplate,
                     Map<String, String> headers, List<MockResponse> responses,
                     long latencyMaxMs, boolean crud) {
        this.method = method == null || method.trim().isEmpty() ? "*" : method.trim();
        this.path = path == null || path.trim().isEmpty() ? "/" : path.trim();
        this.statusCode = statusCode <= 0 ? 200 : statusCode;
        this.latencyMs = Math.max(0L, latencyMs);
        this.contentType = contentType == null || contentType.trim().isEmpty()
                ? "application/json; charset=utf-8"
                : contentType.trim();
        this.body = body == null ? "" : body;
        this.responseSchema = responseSchema == null ? "" : responseSchema;
        this.dynamicSchema = dynamicSchema;
        this.echo = echo;
        this.enabled = enabled;
        this.dynamicTemplate = dynamicTemplate;
        this.latencyMaxMs = Math.max(0L, latencyMaxMs);
        this.crud = crud;
        Map<String, String> headerCopy = new LinkedHashMap<>();
        if (headers != null) {
            for (Map.Entry<String, String> e : headers.entrySet()) {
                if (e.getKey() != null && !e.getKey().trim().isEmpty()) {
                    headerCopy.put(e.getKey().trim(), e.getValue() == null ? "" : e.getValue());
                }
            }
        }
        this.headers = Collections.unmodifiableMap(headerCopy);
        List<MockResponse> responseCopy = new ArrayList<>();
        if (responses != null) {
            responseCopy.addAll(responses);
        }
        this.responses = Collections.unmodifiableList(responseCopy);
    }

    public static Builder builder() {
        return new Builder();
    }

    /** {@link MockRoute} 的 builder（JDK 8 兼容，字段均可链式设置）。 */
    public static final class Builder {
        private String method = "GET";
        private String path = "/";
        private int statusCode = 200;
        private long latencyMs;
        private String contentType = "application/json; charset=utf-8";
        private String body = "";
        private String responseSchema = "";
        private boolean dynamicSchema;
        private boolean echo;
        private boolean enabled = true;
        private boolean dynamicTemplate;
        private Map<String, String> headers = new LinkedHashMap<>();
        private List<MockResponse> responses = new ArrayList<>();
        private long latencyMaxMs;
        private boolean crud;

        public Builder method(String value) {
            this.method = value;
            return this;
        }

        public Builder path(String value) {
            this.path = value;
            return this;
        }

        public Builder statusCode(int value) {
            this.statusCode = value;
            return this;
        }

        public Builder latencyMs(long value) {
            this.latencyMs = value;
            return this;
        }

        public Builder contentType(String value) {
            this.contentType = value;
            return this;
        }

        public Builder body(String value) {
            this.body = value;
            return this;
        }

        public Builder responseSchema(String value) {
            this.responseSchema = value;
            return this;
        }

        public Builder dynamicSchema(boolean value) {
            this.dynamicSchema = value;
            return this;
        }

        public Builder echo(boolean value) {
            this.echo = value;
            return this;
        }

        public Builder enabled(boolean value) {
            this.enabled = value;
            return this;
        }

        public Builder dynamicTemplate(boolean value) {
            this.dynamicTemplate = value;
            return this;
        }

        public Builder latencyMaxMs(long value) {
            this.latencyMaxMs = value;
            return this;
        }

        public Builder crud(boolean value) {
            this.crud = value;
            return this;
        }

        public Builder header(String name, String value) {
            this.headers.put(name, value);
            return this;
        }

        public Builder headers(Map<String, String> values) {
            if (values != null) {
                this.headers.putAll(values);
            }
            return this;
        }

        public Builder response(MockResponse response) {
            this.responses.add(response);
            return this;
        }

        public Builder responses(List<MockResponse> values) {
            if (values != null) {
                this.responses.addAll(values);
            }
            return this;
        }

        public MockRoute build() {
            return new MockRoute(method, path, statusCode, latencyMs, contentType, body,
                    responseSchema, dynamicSchema, echo, enabled, dynamicTemplate,
                    headers, responses, latencyMaxMs, crud);
        }
    }

    /**
     * 内置演示路由，方便首次使用即可立刻测通。
     *
     * @return 三条演示路由：健康检查、问候与回显
     */
    public static List<MockRoute> demoRoutes() {
        return Collections.unmodifiableList(Arrays.asList(
                new MockRoute("GET", "/health", 200, 0,
                        "application/json; charset=utf-8", "{\"ok\":true}"),
                new MockRoute("GET", "/api/hello", 200, 0,
                        "application/json; charset=utf-8", "{\"message\":\"hello from api-mock\"}"),
                new MockRoute("POST", "/api/echo", 200, 0,
                        "application/json; charset=utf-8", "", "", false, true)));
    }
}
