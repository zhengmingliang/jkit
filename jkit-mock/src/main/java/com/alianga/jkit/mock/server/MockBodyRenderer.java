package com.alianga.jkit.mock.server;

import com.alianga.jkit.mock.MockJs;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 响应 / 推送帧内容渲染：先做 {@code {{...}}} 占位符替换，再按需交给 Mock.js
 * 模板引擎。HTTP 响应体与 SSE / WebSocket 的每帧内容共用同一套语义。
 *
 * <p>Mock.js 模板渲染失败时不抛异常（避免整个请求 500 / 断连），回落为占位符
 * 替换后的原文；若调用方传入了错误收集表，会追加一条原因，便于通过响应头
 * {@code X-Mock-Warning} 暴露给调用方。</p>
 *
 * @author 郑明亮
 * @since 2.0.4
 */
final class MockBodyRenderer {
    private MockBodyRenderer() {
    }

    /**
     * 渲染一条路由的一帧内容（HTTP 响应体 / SSE data / WS 消息）。
     * 开启请求参数回显时：body 留空直接返回回显 JSON；body 非空则把回显 JSON
     * 挂到占位符 {@code {{request}}} 与模板机制叠加。
     *
     * @param route 路由（读取 body / dynamicTemplate / echoRequest）
     * @param ctx   请求上下文
     * @return 渲染后的文本
     */
    static String routeFrame(MockRoute route, MockRequestContext ctx) {
        String raw = route.body == null ? "" : route.body;
        if (route.echoRequest.enabled) {
            String echoJson = MockRequestEcho.echoJson(ctx, route.echoRequest);
            if (raw.trim().isEmpty()) {
                return echoJson;
            }
            Map<String, String> extra = new LinkedHashMap<>();
            extra.put("request", echoJson);
            return render(raw, route.dynamicTemplate, ctx, extra);
        }
        return render(raw, route.dynamicTemplate, ctx, null);
    }

    static String render(String raw, boolean dynamicTemplate, MockRequestContext ctx) {
        return render(raw, dynamicTemplate, ctx, null, null);
    }

    static String render(String raw, boolean dynamicTemplate, MockRequestContext ctx,
                         Map<String, String> extra) {
        return render(raw, dynamicTemplate, ctx, extra, null);
    }

    /**
     * @param raw             原始模板
     * @param dynamicTemplate 是否再走 Mock.js 展开
     * @param ctx             请求上下文
     * @param extra           额外占位符（如 {@code request=回显 JSON}），可空
     * @param errors          渲染错误收集表（可空）；Mock.js 失败时追加原因
     * @return 渲染结果；raw 为 null 返回空串
     */
    static String render(String raw, boolean dynamicTemplate, MockRequestContext ctx,
                         Map<String, String> extra, List<String> errors) {
        String template = raw == null ? "" : raw;
        String rendered = MockTemplateRenderer.render(template, ctx, extra);
        if (!dynamicTemplate) {
            return rendered;
        }
        try {
            return MockJs.mockJson(rendered);
        } catch (RuntimeException ex) {
            // 模板非法回落占位符替换后的原文，避免整帧失败断连；原因交给调用方暴露
            if (errors != null) {
                errors.add(ex.getMessage() == null ? ex.getClass().getSimpleName()
                        : ex.getMessage());
            }
            return rendered;
        }
    }
}
