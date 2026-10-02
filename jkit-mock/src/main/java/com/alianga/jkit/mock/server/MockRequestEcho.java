package com.alianga.jkit.mock.server;

import com.alianga.jkit.json.JSON;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 请求参数回显组装器：按 {@link MockEchoConfig} 把一次请求的 path 变量 / query /
 * header / body 抽取成保序 JSON 对象。纯函数、零 IO，可在 HTTP、SSE、WebSocket
 * 等任意协议适配层复用，也便于单测直接断言。
 *
 * <p>全量返回结构：</p>
 * <pre>{@code
 * {"method":"POST","path":"/users/7",
 *  "pathParams":{"id":"7"},
 *  "query":{"debug":"1"},
 *  "headers":{"content-type":"application/json","x-token":"t-1"},
 *  "body":{"name":"张三"}}
 * }</pre>
 * <p>query / header 多值在仅有一个值时给字符串，多个值时给字符串数组；body 是 JSON
 * 时按结构返回，否则整体作为字符串返回。白名单模式下 {@code method} / {@code path}
 * 仍始终带上，取不到的字段路径静默跳过。</p>
 *
 * @author 郑明亮
 * @since 2.0.4
 */
public final class MockRequestEcho {
    private MockRequestEcho() {
    }

    /**
     * 组装回显 JSON 文本。
     *
     * @param ctx    请求上下文
     * @param config 回显配置；{@code enabled=false} 时仍按全量组装（调用方自行先判断）
     * @return JSON 文本
     */
    public static String echoJson(MockRequestContext ctx, MockEchoConfig config) {
        Object data = echoValue(ctx, config);
        return JSON.toJsonString(data);
    }

    /**
     * 组装回显对象（Map / List / 原始值），供需要二次包装的适配层使用。
     *
     * @param ctx    请求上下文
     * @param config 回显配置
     * @return 保序 Map
     */
    public static Map<String, Object> echoValue(MockRequestContext ctx, MockEchoConfig config) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("method", ctx.method());
        out.put("path", ctx.path());
        if (config == null || !config.enabled || config.includes.isEmpty()) {
            putIfNotEmpty(out, "pathParams", new LinkedHashMap<>(ctx.pathParams()));
            putIfNotEmpty(out, "query", flatten(ctx.queryMap()));
            putIfNotEmpty(out, "headers", flatten(ctx.headersMap()));
            Object body = bodyValue(ctx);
            if (body != null) {
                out.put("body", body);
            }
            return out;
        }
        for (String path : config.includes) {
            applyInclude(out, ctx, path);
        }
        return out;
    }

    /** 按单条字段路径（如 {@code query.uid} / {@code body.user.name}）把取值写进结果。 */
    @SuppressWarnings("unchecked")
    private static void applyInclude(Map<String, Object> out, MockRequestContext ctx, String path) {
        int dot = path.indexOf('.');
        String source = dot < 0 ? path : path.substring(0, dot);
        String rest = dot < 0 ? "" : path.substring(dot + 1);
        Object value;
        if ("param".equals(source)) {
            value = rest.isEmpty() ? new LinkedHashMap<>(ctx.pathParams())
                    : ctx.pathParams().get(rest);
        } else if ("query".equals(source)) {
            value = rest.isEmpty() ? flatten(ctx.queryMap())
                    : multiOrScalar(ctx.queryMap().get(findKey(ctx.queryMap(), rest)));
        } else if ("header".equals(source)) {
            value = rest.isEmpty() ? flatten(ctx.headersMap())
                    : multiOrScalar(ctx.headersMap().get(findKey(ctx.headersMap(), rest)));
        } else if ("body".equals(source)) {
            value = rest.isEmpty() ? bodyValue(ctx) : ctx.bodyPathOrRaw(rest);
        } else {
            return;
        }
        if (value == null) {
            return;
        }
        if (rest.isEmpty() && ("query".equals(source) || "header".equals(source)
                || "param".equals(source) || "body".equals(source))) {
            out.put(source, value);
            return;
        }
        // 嵌套落位：query.uid → out.query.uid；中间节点缺则补 Map
        Object existing = out.get(source);
        if (!(existing instanceof Map)) {
            existing = new LinkedHashMap<String, Object>();
            out.put(source, existing);
        }
        Map<String, Object> bucket = (Map<String, Object>) existing;
        bucket.put(rest, value);
    }

    /** header 取值容忍大小写；query 按原键精确取。 */
    private static String findKey(Map<String, List<String>> values, String wanted) {
        if (values.containsKey(wanted)) {
            return wanted;
        }
        for (String key : values.keySet()) {
            if (key != null && key.equalsIgnoreCase(wanted)) {
                return key;
            }
        }
        return wanted;
    }

    private static Object multiOrScalar(List<String> values) {
        if (values == null || values.isEmpty()) {
            return null;
        }
        return values.size() == 1 ? values.get(0) : new ArrayList<>(values);
    }

    private static Map<String, Object> flatten(Map<String, List<String>> multi) {
        Map<String, Object> out = new LinkedHashMap<>();
        if (multi == null) {
            return out;
        }
        for (Map.Entry<String, List<String>> e : multi.entrySet()) {
            Object value = multiOrScalar(e.getValue());
            if (value != null) {
                out.put(e.getKey(), value);
            }
        }
        return out;
    }

    private static Object bodyValue(MockRequestContext ctx) {
        String text = ctx.bodyText();
        if (text == null || text.trim().isEmpty()) {
            return null;
        }
        String trimmed = text.trim();
        if (trimmed.charAt(0) == '{' || trimmed.charAt(0) == '[') {
            try {
                return JSON.parse(trimmed);
            } catch (RuntimeException ignore) {
                // 非合法 JSON 回落原始文本
            }
        }
        return text;
    }

    private static void putIfNotEmpty(Map<String, Object> out, String key, Map<String, Object> value) {
        if (value != null && !value.isEmpty()) {
            out.put(key, value);
        }
    }
}
