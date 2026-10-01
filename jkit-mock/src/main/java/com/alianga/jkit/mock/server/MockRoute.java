package com.alianga.jkit.mock.server;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * 一条 Mock 路由定义：HTTP 方法、路径模板、状态码、延迟、Content-Type 与响应体。
 *
 * <p>路径支持 {@code {name}} 单段参数（如 {@code /api/users/{id}}）；方法支持
 * {@code *} 表示任意方法。{@code echo} 为 true 时把请求体原样回写（用于联调回显）；
 * {@code responseSchema} 非空时可由调用方按 JSON Schema 生成响应体，
 * {@code dynamicSchema} 为 true 时由 {@link MockHttpServer} 每次请求重新生成。</p>
 *
 * <p>本类不可变，可跨线程安全共享；{@link MockHttpServer#setRoutes} 热更新即整表替换快照。</p>
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

    public MockRoute(String method, String path, int statusCode, long latencyMs,
                     String contentType, String body) {
        this(method, path, statusCode, latencyMs, contentType, body, "", false, false);
    }

    public MockRoute(String method, String path, int statusCode, long latencyMs,
                     String contentType, String body, String responseSchema, boolean dynamicSchema) {
        this(method, path, statusCode, latencyMs, contentType, body, responseSchema, dynamicSchema, false);
    }

    /**
     * 全参构造。
     *
     * @param method         HTTP 方法或 {@code *}，空白回落 {@code *}
     * @param path           路径模板，空白回落 {@code /}
     * @param statusCode     状态码，{@code <= 0} 回落 200；超范围值由服务按 500 兜底
     * @param latencyMs      人为延迟毫秒，负值取 0
     * @param contentType    响应 Content-Type，空白回落 JSON
     * @param body           响应体文本
     * @param responseSchema JSON Schema 文本，可为空串
     * @param dynamicSchema  是否每次请求按 Schema 重新生成响应体
     * @param echo           是否回显请求体（忽略 body）
     */
    public MockRoute(String method, String path, int statusCode, long latencyMs,
                     String contentType, String body, String responseSchema, boolean dynamicSchema,
                     boolean echo) {
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
