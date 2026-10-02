package com.alianga.jkit.mock.server;

import java.util.Map;

/**
 * 响应模板渲染：先把 {@code {{xxx}}} 占位符替换为请求上下文的取值，再按需交给
 * Mock.js 模板引擎生成动态数据。占位符不存在的取值替换为空串（保留原文更利于排查，
 * 但会把「没配参数」误当成字面量输出，两害取其轻选择空串）。
 *
 * <p>支持的占位符：</p>
 * <ul>
 *   <li>{@code {{param.id}}} —— 路径模板 {@code {id}} 捕获的参数</li>
 *   <li>{@code {{query.uid}}} —— query 第一个值</li>
 *   <li>{@code {{header.X-Token}}} —— 请求头第一个值（忽略大小写）</li>
 *   <li>{@code {{body.user.name}}} —— 请求体 JSON 点分路径（key 省略即整段 body）</li>
 *   <li>{@code {{method}}} / {@code {{path}}} —— 方法与解码后的路径</li>
 *   <li>{@code {{event}}} —— EVENT 推送策略下触发推送的客户端消息原文（SSE/WS）</li>
 * </ul>
 *
 * <p>Mock.js 模板（{@code @cname}、{@code 'list|1-3'} 等）只在
 * {@link MockRoute#dynamicTemplate} 开启时由 {@link MockJs} 展开，本类只做占位符替换。</p>
 *
 * @author 郑明亮
 * @since 2.0.4
 */
public final class MockTemplateRenderer {
    private MockTemplateRenderer() {
    }

    /**
     * 替换模板中的 {@code {{xxx}}} 占位符；未知占位符替换为空串。
     *
     * @param template 模板文本
     * @param ctx      请求上下文；为 null 时占位符一律替换为空串
     * @return 渲染后的文本
     */
    public static String render(String template, MockRequestContext ctx) {
        return render(template, ctx, null);
    }

    /**
     * 同 {@link #render(String, MockRequestContext)}，另支持一组自定义占位符
     * （如请求参数回显 JSON 挂在 {@code {{request}}}）；自定义表优先于内置占位符。
     *
     * @param template 模板文本
     * @param ctx      请求上下文
     * @param extra    自定义占位符表（可空）
     * @return 渲染后的文本
     */
    public static String render(String template, MockRequestContext ctx,
                                java.util.Map<String, String> extra) {
        if (template == null || template.isEmpty() || template.indexOf('{') < 0) {
            return template == null ? "" : template;
        }
        StringBuilder out = new StringBuilder(template.length());
        int i = 0;
        int n = template.length();
        while (i < n) {
            char c = template.charAt(i);
            if (c == '{' && i + 1 < n && template.charAt(i + 1) == '{') {
                int end = template.indexOf("}}", i + 2);
                if (end >= 0) {
                    String expr = template.substring(i + 2, end).trim();
                    out.append(resolve(expr, ctx, extra));
                    i = end + 2;
                    continue;
                }
            }
            out.append(c);
            i++;
        }
        return out.toString();
    }

    private static String resolve(String expr, MockRequestContext ctx,
                                  java.util.Map<String, String> extra) {
        if (expr.isEmpty()) {
            return "";
        }
        if (extra != null && extra.containsKey(expr)) {
            return extra.get(expr) == null ? "" : extra.get(expr);
        }
        if ("method".equalsIgnoreCase(expr)) {
            return ctx == null ? "" : ctx.method();
        }
        if ("path".equalsIgnoreCase(expr)) {
            return ctx == null ? "" : ctx.path();
        }
        if ("event".equalsIgnoreCase(expr)) {
            // EVENT 推送策略下触发推送的客户端消息原文；非事件响应为空串
            return ctx == null || ctx.eventText() == null ? "" : ctx.eventText();
        }
        String prefix = null;
        String key = expr;
        int dot = expr.indexOf('.');
        if (dot > 0) {
            prefix = expr.substring(0, dot);
            key = expr.substring(dot + 1);
        }
        if (prefix == null) {
            // 无前缀：当作路径参数名，如 {{id}}
            return ctx == null ? "" : valueOrEmpty(ctx.pathParams().get(key));
        }
        if (ctx == null) {
            return "";
        }
        switch (prefix) {
            case "param":
                return valueOrEmpty(ctx.pathParams().get(key));
            case "query":
                return valueOrEmpty(ctx.queryFirst(key));
            case "header":
                return valueOrEmpty(ctx.headerFirst(key));
            case "body":
                return valueOrEmpty(ctx.bodyPathOrRaw(key));
            case "var":
                return valueOrEmpty(ctx.variable(key));
            default:
                return "";
        }
    }

    private static String valueOrEmpty(String value) {
        return value == null ? "" : value;
    }

    /** 渲染路由的附加响应头（值支持占位符），返回保序副本。 */
    static Map<String, String> renderHeaders(Map<String, String> headers, MockRequestContext ctx) {
        java.util.LinkedHashMap<String, String> out = new java.util.LinkedHashMap<>();
        for (Map.Entry<String, String> e : headers.entrySet()) {
            out.put(e.getKey(), render(e.getValue(), ctx));
        }
        return out;
    }
}
