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
    /** 可选显示名（纯元信息，引擎不参与匹配与响应）。 */
    public final String name;
    /** 可选描述（纯元信息，引擎不参与匹配与响应）。 */
    public final String description;
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
    /**
     * 附加响应头（保序，值支持占位符；审计 P1-2：同名可多枚，如 Set-Cookie）。
     */
    public final Map<String, List<String>> headers;
    /** 响应方案；非空时按「规则全中者优，无中者取第一条」挑选，主字段不再参与。 */
    public final List<MockResponse> responses;
    /** 标签（逗号分隔的自由文本，纯元信息：搜索 / 分组用，引擎不参与匹配）。 */
    public final String tags;
    /** 协议：{@code HTTP}（缺省，历史行为）、{@code SSE}（同端口长连接推送）、{@code WS}（伴随 WebSocket 端口）。 */
    public final String protocol;
    /** 「返回请求参数」模式配置；{@code enabled=false} 时不影响历史行为。 */
    public final MockEchoConfig echoRequest;
    /** SSE / WebSocket 推送策略；HTTP 路由忽略，为 null 时按「连接后推一次」。 */
    public final MockPushPolicy pushPolicy;
    /** 多响应方案挑选策略；null 按 {@link MockPlanStrategy#FIRST}（历史行为）。 */
    public final MockPlanStrategy responseStrategy;
    /** 网络层故障注入配置；{@link MockFault.Mode#NONE}（缺省）时与历史行为一致。 */
    public final MockFault fault;
    /**
     * 为 true 时响应体与响应头值按「响应模板变量」渲染（{{request.*}} / {{faker.*}} 等扩展变量、
     * 未解析保留原文并遇 {@code X-Mock-Warning}、字面量 {{ 转义）；false 时与历史行为完全一致
     * （历史路由的响应体里可能本来就写着 {{xxx}} 字面量）。
     */
    public final boolean templated;
    /** 序贯命中的计数器（{@link MockPlanStrategy#SEQUENCE} 轮播用）；随路由对象生命周期，热更新即重置。 */
    public final java.util.concurrent.atomic.AtomicLong planHitCounter = new java.util.concurrent.atomic.AtomicLong();

    /** 序贯计数当前值（只读场景用；一般无需关心）。 */
    public long planHitCount() {
        return planHitCounter.get();
    }

    public MockRoute(String method, String path, int statusCode, long latencyMs,
                     String contentType, String body) {
        this(method, path, statusCode, latencyMs, contentType, body, "", false, false,
                true, false, Collections.<String, String>emptyMap(),
                Collections.<MockResponse>emptyList(), 0L, false, "", "");
    }

    public MockRoute(String method, String path, int statusCode, long latencyMs,
                     String contentType, String body, String responseSchema, boolean dynamicSchema) {
        this(method, path, statusCode, latencyMs, contentType, body, responseSchema, dynamicSchema,
                false, true, false, Collections.<String, String>emptyMap(),
                Collections.<MockResponse>emptyList(), 0L, false, "", "");
    }

    /** 历史 eight-arg + echo 的便捷重载。 */
    public MockRoute(String method, String path, int statusCode, long latencyMs,
                     String contentType, String body, String responseSchema, boolean dynamicSchema,
                     boolean echo) {
        this(method, path, statusCode, latencyMs, contentType, body, responseSchema, dynamicSchema,
                echo, true, false, Collections.<String, String>emptyMap(),
                Collections.<MockResponse>emptyList(), 0L, false, "", "");
    }

    /**
     * 全参构造；一般用 {@link #builder()} 更易读。
     */
    public MockRoute(String method, String path, int statusCode, long latencyMs,
                     String contentType, String body, String responseSchema, boolean dynamicSchema,
                     boolean echo, boolean enabled, boolean dynamicTemplate,
                     Map<String, String> headers, List<MockResponse> responses,
                     long latencyMaxMs, boolean crud, String name, String description) {
        this(method, path, statusCode, latencyMs, contentType, body, responseSchema, dynamicSchema,
                echo, enabled, dynamicTemplate, headers, responses, latencyMaxMs, crud,
                name, description, "HTTP", MockEchoConfig.disabled(), null, "");
    }

    /**
     * 全参构造（2.0.5 起含协议 / 请求回显 / 推送策略）；一般用 {@link #builder()} 更易读。
     *
     * @param protocol    HTTP / SSE / WS；空白按 HTTP
     * @param echoRequest 请求参数回显配置；null 按关闭
     * @param pushPolicy  长连接推送策略；null 时 SSE/WS 按「连接后推一次」
     */
    public MockRoute(String method, String path, int statusCode, long latencyMs,
                     String contentType, String body, String responseSchema, boolean dynamicSchema,
                     boolean echo, boolean enabled, boolean dynamicTemplate,
                     Map<String, String> headers, List<MockResponse> responses,
                     long latencyMaxMs, boolean crud, String name, String description,
                     String protocol, MockEchoConfig echoRequest, MockPushPolicy pushPolicy) {
        this(method, path, statusCode, latencyMs, contentType, body, responseSchema, dynamicSchema,
                echo, enabled, dynamicTemplate, headers, responses, latencyMaxMs, crud,
                name, description, protocol, echoRequest, pushPolicy, "");
    }

    /**
     * 全参构造（2.0.4 后增量字段：协议 / 请求回显 / 推送策略 / 标签）。
     *
     * @param tags 逗号分隔标签，纯元信息
     */
    public MockRoute(String method, String path, int statusCode, long latencyMs,
                     String contentType, String body, String responseSchema, boolean dynamicSchema,
                     boolean echo, boolean enabled, boolean dynamicTemplate,
                     Map<String, String> headers, List<MockResponse> responses,
                     long latencyMaxMs, boolean crud, String name, String description,
                     String protocol, MockEchoConfig echoRequest, MockPushPolicy pushPolicy,
                     String tags) {
        this(method, path, statusCode, latencyMs, contentType, body, responseSchema, dynamicSchema,
                echo, enabled, dynamicTemplate, toMulti(headers), responses, latencyMaxMs, crud,
                name, description, protocol, echoRequest, pushPolicy, tags, null,
                MockFault.none(), false);
    }

    /**
     * 全参构造（2.0.4 后增量：协议 / 请求回显 / 推送策略 / 标签 / 方案挑选策略）。
     *
     * @param responseStrategy 多方案挑选策略；null 按 FIRST（历史行为）
     */
    public MockRoute(String method, String path, int statusCode, long latencyMs,
                     String contentType, String body, String responseSchema, boolean dynamicSchema,
                     boolean echo, boolean enabled, boolean dynamicTemplate,
                     Map<String, String> headers, List<MockResponse> responses,
                     long latencyMaxMs, boolean crud, String name, String description,
                     String protocol, MockEchoConfig echoRequest, MockPushPolicy pushPolicy,
                     String tags, MockPlanStrategy responseStrategy) {
        this(method, path, statusCode, latencyMs, contentType, body, responseSchema, dynamicSchema,
                echo, enabled, dynamicTemplate, toMulti(headers), responses, latencyMaxMs, crud,
                name, description, protocol, echoRequest, pushPolicy, tags, responseStrategy,
                MockFault.none(), false);
    }

    /**
     * 多值响应头全参构造（审计 P1-2）：builder 用；同名列内多枚全部保留。
     *
     * @param multiHeaders 同名可多枚的响应头；键按输入顺序、每枚一个值。
     *                     注意与历史 Map<String, String> 全参构造擦除后同签名，运行时实际
     *                     值类型由 {@link #toMulti(Map)} 兼容（旧字节码传 String 值也能读）。
     */
    public MockRoute(String method, String path, int statusCode, long latencyMs,
                     String contentType, String body, String responseSchema, boolean dynamicSchema,
                     boolean echo, boolean enabled, boolean dynamicTemplate,
                     Map<String, List<String>> multiHeaders, List<MockResponse> responses,
                     long latencyMaxMs, boolean crud, String name, String description,
                     String protocol, MockEchoConfig echoRequest, MockPushPolicy pushPolicy,
                     String tags, MockPlanStrategy responseStrategy, boolean builderMarker) {
        this(method, path, statusCode, latencyMs, contentType, body, responseSchema, dynamicSchema,
                echo, enabled, dynamicTemplate, multiHeaders, responses, latencyMaxMs, crud,
                name, description, protocol, echoRequest, pushPolicy, tags, responseStrategy,
                MockFault.none(), false);
    }

    /** 多值响应头真身构造（私有）：同名列内多枚全部保留。 */
    private MockRoute(String method, String path, int statusCode, long latencyMs,
                      String contentType, String body, String responseSchema, boolean dynamicSchema,
                      boolean echo, boolean enabled, boolean dynamicTemplate,
                      Map<String, List<String>> multiHeaders, List<MockResponse> responses,
                      long latencyMaxMs, boolean crud, String name, String description,
                      String protocol, MockEchoConfig echoRequest, MockPushPolicy pushPolicy,
                      String tags, MockPlanStrategy responseStrategy,
                      MockFault fault, boolean templated) {
        this.method = method == null || method.trim().isEmpty() ? "*" : method.trim();
        this.path = path == null || path.trim().isEmpty() ? "/" : path.trim();
        this.statusCode = statusCode <= 0 ? 200 : statusCode;
        // 注：100–599 之外的字段值不做构造层 clamp——serve 阶段已有护栏按 500 兑底并写日志
        // （见 MockHttpServerTest＃invalidStatusCodeServedAs500AndLogged），比静默变 200 更显眼
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
        this.name = name == null ? "" : name.trim();
        this.description = description == null ? "" : description.trim();
        this.tags = tags == null ? "" : tags.trim();
        Map<String, List<String>> headerCopy = new LinkedHashMap<>();
        if (multiHeaders != null) {
            for (Map.Entry<String, List<String>> e : multiHeaders.entrySet()) {
                if (e.getKey() != null && !e.getKey().trim().isEmpty()
                        && e.getValue() != null && !e.getValue().isEmpty()) {
                    headerCopy.put(e.getKey().trim(),
                            Collections.unmodifiableList(new ArrayList<>(e.getValue())));
                }
            }
        }
        this.headers = Collections.unmodifiableMap(headerCopy);
        List<MockResponse> responseCopy = new ArrayList<>();
        if (responses != null) {
            responseCopy.addAll(responses);
        }
        this.responses = Collections.unmodifiableList(responseCopy);
        String proto = protocol == null || protocol.trim().isEmpty()
                ? "HTTP" : protocol.trim().toUpperCase(java.util.Locale.ROOT);
        this.protocol = "SSE".equals(proto) || "WS".equals(proto) ? proto : "HTTP";
        this.echoRequest = echoRequest == null ? MockEchoConfig.disabled() : echoRequest;
        this.pushPolicy = pushPolicy;
        this.responseStrategy = responseStrategy == null ? MockPlanStrategy.FIRST : responseStrategy;
        this.fault = fault == null ? MockFault.none() : fault;
        this.templated = templated;
    }

    /** 是否为 SSE 路由。 */
    public boolean isSse() {
        return "SSE".equals(protocol);
    }

    /** 是否为 WebSocket 路由。 */
    public boolean isWebSocket() {
        return "WS".equals(protocol);
    }

    public static Builder builder() {
        return new Builder();
    }

    /**
     * 响应头表兼容归一：值为 List 时逐枚收集，为 String（历史单值、擦除后的旧字节码也会走到这里）
     * 时当一枚；键不动。放在漏斗构造的入口，使多值/单值两种历史调用都归一到 List 语义。
     */
    private static Map<String, List<String>> toMulti(Map<String, ?> headers) {
        Map<String, List<String>> out = new LinkedHashMap<>();
        if (headers == null) {
            return out;
        }
        for (Map.Entry<String, ?> e : headers.entrySet()) {
            List<String> values = new ArrayList<>();
            Object v = e.getValue();
            if (v instanceof List) {
                for (Object o : (List<?>) v) {
                    values.add(o == null ? "" : String.valueOf(o));
                }
            } else if (v != null) {
                values.add(String.valueOf(v));
            }
            out.put(e.getKey(), values);
        }
        return out;
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
        private Map<String, List<String>> headers = new LinkedHashMap<>();
        private List<MockResponse> responses = new ArrayList<>();
        private long latencyMaxMs;
        private boolean crud;
        private String name = "";
        private String description = "";
        private String protocol = "HTTP";
        private MockEchoConfig echoRequest = MockEchoConfig.disabled();
        private MockPushPolicy pushPolicy;
        private String tags = "";
        private MockPlanStrategy responseStrategy;
        private MockFault fault = MockFault.none();
        private boolean templated;

        /**
         * 多响应方案挑选策略；null 按 {@link MockPlanStrategy#FIRST}。
         */
        public Builder responseStrategy(MockPlanStrategy value) {
            this.responseStrategy = value;
            return this;
        }

        /**
         * 网络层故障注入配置；null 按 {@link MockFault#none()}（不注入，历史行为）。
         *
         * @param value 故障配置（builder 内对非法参数抛 IllegalArgumentException，不静默回落）
         */
        public Builder fault(MockFault value) {
            this.fault = value == null ? MockFault.none() : value;
            return this;
        }

        /**
         * 响应模板变量开关（{{request.*}} / {{faker.*}} 等）；默认 false（历史行为）。
         */
        public Builder templated(boolean value) {
            this.templated = value;
            return this;
        }

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

        public Builder name(String value) {
            this.name = value == null ? "" : value;
            return this;
        }

        public Builder description(String value) {
            this.description = value == null ? "" : value;
            return this;
        }

        /**
         * @param value HTTP / SSE / WS；空白或无法识别按 HTTP
         */
        public Builder protocol(String value) {
            this.protocol = value == null ? "HTTP" : value;
            return this;
        }

        public Builder echoRequest(MockEchoConfig value) {
            this.echoRequest = value == null ? MockEchoConfig.disabled() : value;
            return this;
        }

        public Builder pushPolicy(MockPushPolicy value) {
            this.pushPolicy = value;
            return this;
        }

        /**
         * @param value 逗号分隔的标签文本
         */
        public Builder tags(String value) {
            this.tags = value == null ? "" : value;
            return this;
        }

        /**
         * 追加一枚响应头；同名多次调用即多枚（审计 P1-2，Set-Cookie 场景）。
         */
        public Builder header(String name, String value) {
            if (name != null && !name.trim().isEmpty()) {
                this.headers.computeIfAbsent(name.trim(), k -> new ArrayList<>()).add(value == null ? "" : value);
            }
            return this;
        }

        /** 兼容单值 API：同名被覆盖为单枚多行内集合（即每枚一条）。 */
        public Builder headers(Map<String, String> values) {
            if (values != null) {
                for (Map.Entry<String, String> e : values.entrySet()) {
                    header(e.getKey(), e.getValue());
                }
            }
            return this;
        }

        /**
         * 多值响应头（审计 P1-2）：同名多枚合一；调用方的列表自身安全管理。
         */
        public Builder headersMulti(Map<String, List<String>> values) {
            if (values != null) {
                for (Map.Entry<String, List<String>> e : values.entrySet()) {
                    if (e.getValue() == null || e.getValue().isEmpty()) {
                        continue;
                    }
                    for (String v : e.getValue()) {
                        header(e.getKey(), v);
                    }
                }
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
            // 直接调私有真身构造：fault / templated 只在 builder 链可设，历史便捷构造保持缺省
            return new MockRoute(method, path, statusCode, latencyMs, contentType, body,
                    responseSchema, dynamicSchema, echo, enabled, dynamicTemplate,
                    headers, responses, latencyMaxMs, crud, name, description,
                    protocol, echoRequest, pushPolicy, tags, responseStrategy,
                    fault, templated);
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
