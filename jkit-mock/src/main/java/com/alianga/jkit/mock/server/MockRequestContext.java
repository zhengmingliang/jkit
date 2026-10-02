package com.alianga.jkit.mock.server;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 一次入站请求的上下文：解码后的路径、路径参数、query、请求头与请求体，
 * 供响应方案规则取值与模板占位符替换使用。
 *
 * @author 郑明亮
 * @since 2.0.4
 */
public final class MockRequestContext {
    private final String method;
    private final String path;
    private final Map<String, String> pathParams;
    private final Map<String, List<String>> query;
    private final Map<String, List<String>> headers;
    private final byte[] body;
    private final String bodyText;
    private final Map<String, String> variables;
    /** EVENT 推送策略下，触发本次响应的客户端消息文本（无则 null）。 */
    private final String eventText;

    private MockRequestContext(String method, String path, Map<String, String> pathParams,
                               Map<String, List<String>> query, Map<String, List<String>> headers,
                               byte[] body, Map<String, String> variables, String eventText) {
        this.method = method;
        this.path = path;
        this.pathParams = pathParams;
        this.query = query;
        this.headers = headers;
        this.body = body;
        this.bodyText = new String(body, StandardCharsets.UTF_8);
        this.variables = variables;
        this.eventText = eventText;
    }

    static MockRequestContext of(String method, String path, Map<String, String> pathParams,
                                 String rawQuery, Map<String, List<String>> headers, byte[] body) {
        return of(method, path, pathParams, rawQuery, headers, body,
                Collections.<String, String>emptyMap());
    }

    static MockRequestContext of(String method, String path, Map<String, String> pathParams,
                                 String rawQuery, Map<String, List<String>> headers, byte[] body,
                                 Map<String, String> variables) {
        return of(method, path, pathParams, rawQuery, headers, body, variables, null);
    }

    static MockRequestContext of(String method, String path, Map<String, String> pathParams,
                                 String rawQuery, Map<String, List<String>> headers, byte[] body,
                                 Map<String, String> variables, String eventText) {
        return new MockRequestContext(method, path,
                pathParams == null ? Collections.<String, String>emptyMap() : pathParams,
                parseQuery(rawQuery),
                headers == null ? Collections.<String, List<String>>emptyMap() : headers,
                body == null ? new byte[0] : body,
                variables == null ? Collections.<String, String>emptyMap() : variables,
                eventText);
    }

    /** 携带触发事件文本派生一个上下文（EVENT 推送模板渲染用）。 */
    MockRequestContext withEvent(String text) {
        return new MockRequestContext(method, path, pathParams, query, headers,
                body, variables, text);
    }

    /** WebSocket EVENT 推送：以客户端消息作为请求体与事件文本派生上下文。 */
    static MockRequestContext forMessage(MockRequestContext base, String message) {
        byte[] bytes = message == null ? new byte[0]
                : message.getBytes(StandardCharsets.UTF_8);
        return new MockRequestContext(base.method, base.path, base.pathParams,
                base.query, base.headers, bytes, base.variables, message);
    }

    public String method() {
        return method;
    }

    /** 解码后的请求路径。 */
    public String path() {
        return path;
    }

    /** 路径模板 {@code {name}} 段捕获的参数。 */
    public Map<String, String> pathParams() {
        return pathParams;
    }

    /** query 多值表（原始键）。 */
    public Map<String, List<String>> queryMap() {
        return query;
    }

    /** 请求头多值表（原始键，取值需自行忽略大小写）。 */
    public Map<String, List<String>> headersMap() {
        return headers;
    }

    /** 触发本次 EVENT 推送的客户端消息文本；非事件响应为 {@code null}。 */
    public String eventText() {
        return eventText;
    }

    /** query 第一个值；不存在返回 {@code null}。 */
    public String queryFirst(String name) {
        return MockResponseRule.firstValue(query, name);
    }

    /** 请求头第一个值（忽略大小写）；不存在返回 {@code null}。 */
    public String headerFirst(String name) {
        return MockResponseRule.firstValue(headers, name);
    }

    /** 服务级全局变量取值；不存在返回 {@code null}。 */
    public String variable(String name) {
        return variables.get(name);
    }

    /** 请求体字节。 */
    public byte[] body() {
        return body;
    }

    /** 请求体 UTF-8 文本。 */
    public String bodyText() {
        return bodyText;
    }

    /**
     * body 取值：key 为空返回整段文本；body 是 JSON 时按点分路径下钻（数组用下标，
     * 如 {@code items.0.id}），取不到返回 {@code null}；body 非 JSON 且 key 非空返回 {@code null}。
     */
    public String bodyPathOrRaw(String key) {
        if (key == null || key.trim().isEmpty()) {
            return bodyText;
        }
        Object parsed = parseJsonQuietly(bodyText);
        if (parsed == null) {
            return null;
        }
        Object current = parsed;
        for (String seg : key.trim().split("\\.")) {
            current = child(current, seg);
            if (current == null) {
                return null;
            }
        }
        return current instanceof String ? (String) current : String.valueOf(current);
    }

    private static Object child(Object node, String seg) {
        if (node instanceof Map) {
            return ((Map<?, ?>) node).get(seg);
        }
        if (node instanceof List) {
            try {
                return ((List<?>) node).get(Integer.parseInt(seg));
            } catch (NumberFormatException | IndexOutOfBoundsException e) {
                return null;
            }
        }
        return null;
    }

    private static Object parseJsonQuietly(String text) {
        String t = text == null ? "" : text.trim();
        if (t.isEmpty() || (t.charAt(0) != '{' && t.charAt(0) != '[')) {
            return null;
        }
        try {
            return com.alianga.jkit.json.JSON.parse(t);
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** 解析 query 串（不含前导 ?）；{@code +} 按空格处理，符合表单惯例。 */
    static Map<String, List<String>> parseQuery(String rawQuery) {
        Map<String, List<String>> out = new LinkedHashMap<>();
        if (rawQuery == null || rawQuery.isEmpty()) {
            return out;
        }
        for (String pair : rawQuery.split("&")) {
            if (pair.isEmpty()) {
                continue;
            }
            int eq = pair.indexOf('=');
            String key = decodeQueryComponent(eq < 0 ? pair : pair.substring(0, eq));
            String value = eq < 0 ? "" : decodeQueryComponent(pair.substring(eq + 1));
            List<String> list = out.get(key);
            if (list == null) {
                list = new java.util.ArrayList<>();
                out.put(key, list);
            }
            list.add(value);
        }
        return out;
    }

    private static String decodeQueryComponent(String value) {
        if (value.indexOf('+') < 0 && value.indexOf('%') < 0) {
            return value;
        }
        ByteArrayOutputStream bytes = new ByteArrayOutputStream(value.length());
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == '+') {
                bytes.write(' ');
            } else if (c == '%' && i + 2 < value.length()) {
                int hi = Character.digit(value.charAt(i + 1), 16);
                int lo = Character.digit(value.charAt(i + 2), 16);
                if (hi >= 0 && lo >= 0) {
                    bytes.write((hi << 4) | lo);
                    i += 2;
                    continue;
                }
                bytes.write(c);
            } else {
                bytes.write(c & 0xFF);
            }
        }
        return new String(bytes.toByteArray(), StandardCharsets.UTF_8);
    }
}
