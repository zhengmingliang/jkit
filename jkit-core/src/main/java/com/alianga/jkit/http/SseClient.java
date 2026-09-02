package com.alianga.jkit.http;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

/**
 * 按 HTML SSE 规范解析事件流。
 *
 * @author 郑明亮
 */
public final class SseClient {
    private SseClient() {
    }

    /**
     * 阻塞读取直到流结束或 {@link HttpCall#isCanceled()}。
     *
     * @param in 事件流
     * @param listener 回调
     * @param call 可用于取消
     * @throws IOException 读取失败
     */
    public static void parse(InputStream in, SseListener listener, HttpCall call) throws IOException {
        if (in == null) {
            return;
        }
        BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
        String id = null;
        String event = null;
        StringBuilder data = new StringBuilder();
        long retry = -1L;
        String line;
        while ((call == null || !call.isCanceled()) && (line = reader.readLine()) != null) {
            if (line.isEmpty()) {
                emitBuffered(listener, id, event, data, retry);
                event = null;
                retry = -1L;
                continue;
            }
            if (line.charAt(0) == ':') {
                listener.onComment(line.length() > 1 && line.charAt(1) == ' '
                        ? line.substring(2) : line.substring(1));
                continue;
            }
            String trimmed = line.trim();
            if ("[DONE]".equals(trimmed)
                    || ((trimmed.startsWith("{") || trimmed.startsWith("["))
                    && !isSseFieldLine(trimmed))) {
                listener.onEvent(new SseEvent(id, eventName(event), trimmed, retry));
                continue;
            }
            int colon = line.indexOf(':');
            String field;
            String value;
            if (colon < 0) {
                field = line;
                value = "";
            } else {
                field = line.substring(0, colon);
                value = line.substring(colon + 1);
                if (!value.isEmpty() && value.charAt(0) == ' ') {
                    value = value.substring(1);
                }
            }
            if ("data".equals(field)) {
                // LLM 流常见「每行一条完整 JSON、没有空行分隔」；完整 payload 立即成事件
                if (isStandaloneSsePayload(value)) {
                    emitBuffered(listener, id, event, data, retry);
                    listener.onEvent(new SseEvent(id, eventName(event), value.trim(), retry));
                } else {
                    data.append(value).append('\n');
                }
            } else if ("event".equals(field)) {
                event = value;
            } else if ("id".equals(field)) {
                id = value;
            } else if ("retry".equals(field)) {
                try {
                    retry = Long.parseLong(value.trim());
                } catch (NumberFormatException e) {
                    // ignore invalid retry
                }
            }
        }
        emitBuffered(listener, id, event, data, retry);
    }

    private static void emitBuffered(SseListener listener, String id, String event,
                                     StringBuilder data, long retry) {
        if (data.length() == 0 && (event == null || event.isEmpty())) {
            return;
        }
        String payload = data.length() > 0 && data.charAt(data.length() - 1) == '\n'
                ? data.substring(0, data.length() - 1)
                : data.toString();
        if (payload.isEmpty() && (event == null || event.isEmpty())) {
            data.setLength(0);
            return;
        }
        listener.onEvent(new SseEvent(id, eventName(event), payload, retry));
        data.setLength(0);
    }

    private static String eventName(String event) {
        return event == null || event.isEmpty() ? "message" : event;
    }

    /**
     * 单行即可构成一条事件的 payload：{@code [DONE]} 或成对括号的完整 JSON。
     */
    static boolean isStandaloneSsePayload(String value) {
        if (value == null) {
            return false;
        }
        String trimmed = value.trim();
        return "[DONE]".equals(trimmed) || isBalancedJson(trimmed);
    }

    private static boolean isBalancedJson(String s) {
        if (s.length() < 2) {
            return false;
        }
        char first = s.charAt(0);
        char last = s.charAt(s.length() - 1);
        if (!(first == '{' && last == '}') && !(first == '[' && last == ']')) {
            return false;
        }
        int depth = 0;
        boolean inString = false;
        boolean escape = false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (inString) {
                if (escape) {
                    escape = false;
                } else if (c == '\\') {
                    escape = true;
                } else if (c == '"') {
                    inString = false;
                }
                continue;
            }
            if (c == '"') {
                inString = true;
            } else if (c == '{' || c == '[') {
                depth++;
            } else if (c == '}' || c == ']') {
                depth--;
                if (depth < 0) {
                    return false;
                }
            }
        }
        return depth == 0 && !inString;
    }

    private static boolean isSseFieldLine(String line) {
        return line.startsWith("data:") || line.startsWith("event:")
                || line.startsWith("id:") || line.startsWith("retry:");
    }
}
