package com.alianga.jkit.http;

import com.alianga.jkit.json.JSONNode;

/**
 * 将 SSE / NDJSON 增量按厂商协议拼成完整文本，并可实时回调累计结果。
 *
 * @author 郑明亮
 */
public final class SseMerger {
    private final SseMergeFormat format;
    private final StringBuilder content = new StringBuilder();
    private final StringBuilder thinking = new StringBuilder();
    private final StringBuilder toolCall = new StringBuilder();
    private boolean done;
    private String finishReason;
    private String error;
    private String detectedName;

    /**
     * @param format 合并格式
     */
    public SseMerger(SseMergeFormat format) {
        if (format == null) {
            throw new IllegalArgumentException("format is required");
        }
        this.format = format;
    }

    /**
     * @return 当前累计正文
     */
    public String getContent() {
        return content.toString();
    }

    /**
     * @return 当前累计思考过程
     */
    public String getThinking() {
        return thinking.toString();
    }

    /**
     * @return 当前累计工具调用参数（partial JSON）
     */
    public String getToolCall() {
        return toolCall.toString();
    }

    /**
     * @return 是否已结束
     */
    public boolean isDone() {
        return done;
    }

    /**
     * @return 结束原因，未结束时为 {@code null}
     */
    public String getFinishReason() {
        return finishReason;
    }

    /**
     * @return 错误信息，无错误时为 {@code null}
     */
    public String getError() {
        return error;
    }

    /**
     * 处理一条 SSE 事件。
     *
     * @param event 事件
     * @return 合并快照；若本片无增量且未结束则仍返回当前累计
     */
    public SseMergeResult accept(SseEvent event) {
        String data = event == null ? null : event.getData();
        String name = event == null ? null : event.getEvent();
        return acceptData(data, name, event);
    }

    /**
     * 处理一段 data（JSON 或纯文本）。
     *
     * @param data 数据
     * @return 快照
     */
    public SseMergeResult acceptData(String data) {
        return acceptData(data, null, null);
    }

    private SseMergeResult acceptData(String data, String eventName, SseEvent event) {
        String contentDelta = "";
        String thinkingDelta = "";
        String toolCallDelta = "";
        if (data != null) {
            String trimmed = data.trim();
            if ("[DONE]".equals(trimmed)) {
                done = true;
            } else if ("raw".equals(format.getName())) {
                contentDelta = data;
            } else {
                Extracted extracted = extract(trimmed, eventName);
                contentDelta = extracted.content;
                thinkingDelta = extracted.thinking;
                toolCallDelta = extracted.toolCall;
                applyMeta(extracted);
            }
        }
        if (isStreamStopEvent(eventName)) {
            done = true;
        }
        if (!contentDelta.isEmpty()) {
            content.append(contentDelta);
        }
        if (!thinkingDelta.isEmpty()) {
            thinking.append(thinkingDelta);
        }
        if (!toolCallDelta.isEmpty()) {
            toolCall.append(toolCallDelta);
        }
        return snapshot(contentDelta, thinkingDelta, toolCallDelta, event);
    }

    /**
     * @return 当前快照
     */
    public SseMergeResult snapshot() {
        return snapshot("", "", "", null);
    }

    private SseMergeResult snapshot(String contentDelta, String thinkingDelta, String toolCallDelta,
                                    SseEvent event) {
        return new SseMergeResult(content.toString(), thinking.toString(), contentDelta, thinkingDelta,
                toolCall.toString(), toolCallDelta, done, finishReason, error, event);
    }

    private void applyMeta(Extracted extracted) {
        if (extracted.done) {
            done = true;
        }
        if (extracted.finishReason != null && !extracted.finishReason.isEmpty()) {
            finishReason = extracted.finishReason;
        }
        if (extracted.error != null && !extracted.error.isEmpty()) {
            error = extracted.error;
        }
    }

    private static boolean isStreamStopEvent(String eventName) {
        return "message_stop".equals(eventName)
                || "response.completed".equals(eventName)
                || "response.failed".equals(eventName)
                || "error".equals(eventName);
    }

    /**
     * OpenAI / Claude / 各兼容网关的错误体，不一定带 {@code event: error} 或 {@code type:error}。
     */
    private static Extracted apiError(JSONNode node, String eventName) {
        String message = firstText(node, "/error/message", "/error/msg");
        boolean namedError = "error".equals(eventName)
                || "error".equals(pathText(node, "/type"))
                || "response.failed".equals(pathText(node, "/type"));
        if (message.isEmpty() && namedError) {
            message = firstText(node, "/message", "/error/type", "/error/code");
        }
        if (message.isEmpty()) {
            return null;
        }
        String reason = firstText(node, "/error/type", "/error/code");
        if (reason.isEmpty()) {
            reason = "error";
        }
        return new Extracted("", "", "", true, reason, message);
    }

    private Extracted extract(String data, String eventName) {
        if (data == null || data.isEmpty() || data.charAt(0) != '{') {
            return Extracted.EMPTY;
        }
        JSONNode node;
        try {
            node = JSONNode.parse(data);
        } catch (RuntimeException e) {
            return Extracted.EMPTY;
        }
        if (node == null) {
            return Extracted.EMPTY;
        }
        try {
            Extracted apiError = apiError(node, eventName);
            if (apiError != null) {
                return apiError;
            }
            if (format.isJsonPath()) {
                return new Extracted(pathText(node, format.getJsonPath()), "", "", false, null, null);
            }
            String name = resolveName(node, eventName);
            if ("openai".equals(name)) {
                return openai(node);
            }
            if ("openai-responses".equals(name)) {
                return openaiResponses(node);
            }
            if ("gemini".equals(name)) {
                return gemini(node);
            }
            if ("claude".equals(name)) {
                return claude(node, eventName);
            }
            if ("dashscope".equals(name)) {
                return dashscope(node);
            }
            if ("ernie".equals(name)) {
                return ernie(node);
            }
            if ("ollama-generate".equals(name)) {
                return ollamaGenerate(node);
            }
            if ("ollama-chat".equals(name)) {
                return ollamaChat(node);
            }
            return Extracted.EMPTY;
        } catch (RuntimeException e) {
            return Extracted.EMPTY;
        }
    }

    private String resolveName(JSONNode node, String eventName) {
        String name = format.getName();
        if (!"auto".equals(name)) {
            return name;
        }
        if (detectedName != null) {
            return detectedName;
        }
        String detected = detectFormat(node, eventName);
        if (detected != null) {
            detectedName = detected;
        }
        return detected;
    }

    static String detectFormat(JSONNode node, String eventName) {
        if (isClaudeEvent(eventName)) {
            return "claude";
        }
        String type = pathText(node, "/type");
        if (isClaudeType(type)) {
            return "claude";
        }
        if (type.startsWith("response.")) {
            return "openai-responses";
        }
        if (!pathText(node, "/candidates/0/content/parts/0/text").isEmpty()
                || !pathText(node, "/candidates/0/finishReason").isEmpty()) {
            return "gemini";
        }
        if (!pathText(node, "/output/choices/0/message/role").isEmpty()
                || !pathText(node, "/output/choices/0/message/content").isEmpty()) {
            return "dashscope";
        }
        if (!pathText(node, "/choices/0/delta/content").isEmpty()
                || !pathText(node, "/choices/0/delta/reasoning").isEmpty()
                || !pathText(node, "/choices/0/delta/reasoning_content").isEmpty()
                || !pathText(node, "/choices/0/text").isEmpty()
                || !pathText(node, "/data/choices/0/delta/content").isEmpty()
                || !pathText(node, "/data/choices/0/reasoning_content").isEmpty()
                || !leafText(node, "/choices/0/delta").isEmpty()
                || !leafText(node, "/data/choices/0/delta").isEmpty()
                || pathText(node, "/object").contains("chat.completion")) {
            return "openai";
        }
        if (!pathText(node, "/is_end").isEmpty()) {
            return "ernie";
        }
        if (!pathText(node, "/message/content").isEmpty() && !pathText(node, "/done").isEmpty()) {
            return "ollama-chat";
        }
        if (!pathText(node, "/response").isEmpty() && !pathText(node, "/done").isEmpty()) {
            return "ollama-generate";
        }
        return null;
    }

    private static boolean isClaudeEvent(String eventName) {
        return "message_start".equals(eventName)
                || "message_delta".equals(eventName)
                || "message_stop".equals(eventName)
                || "content_block_start".equals(eventName)
                || "content_block_delta".equals(eventName)
                || "content_block_stop".equals(eventName)
                || "ping".equals(eventName);
    }

    private static boolean isClaudeType(String type) {
        return "message_start".equals(type)
                || "message_delta".equals(type)
                || "message_stop".equals(type)
                || "content_block_start".equals(type)
                || "content_block_delta".equals(type)
                || "content_block_stop".equals(type)
                || "ping".equals(type);
    }

    private static Extracted openai(JSONNode node) {
        String content = firstText(node,
                "/choices/0/delta/content",
                "/data/choices/0/delta/content",
                "/choices/0/text",
                "/delta/content");
        if (content.isEmpty()) {
            content = leafText(node, "/choices/0/delta");
            if (content.isEmpty()) {
                content = leafText(node, "/data/choices/0/delta");
            }
        }
        String thinking = firstText(node,
                "/choices/0/delta/reasoning_content",
                "/choices/0/delta/reasoning",
                "/choices/0/delta/reasoning_text",
                "/choices/0/reasoning_content",
                "/choices/0/reasoning",
                "/data/choices/0/delta/reasoning_content",
                "/data/choices/0/delta/reasoning",
                "/data/choices/0/reasoning_content");
        if (thinking.isEmpty()) {
            thinking = joinIndexedText(node, "/choices/0/delta/reasoning_details", "/text");
        }
        String tool = joinIndexedText(node, "/choices/0/delta/tool_calls", "/function/arguments");
        if (tool.isEmpty()) {
            tool = joinIndexedText(node, "/data/choices/0/delta/tool_calls", "/function/arguments");
        }
        if (tool.isEmpty()) {
            tool = joinIndexedText(node, "/data/choices/0/tool_calls", "/function/arguments");
        }
        String finish = firstText(node,
                "/choices/0/finish_reason",
                "/data/choices/0/finish_reason",
                "/finish_reason");
        boolean ended = !finish.isEmpty();
        return new Extracted(content, thinking, tool, ended, emptyToNull(finish), null);
    }

    /**
     * 仅当路径是字符串/数字/布尔叶子时取值，避免把 {@code delta:{...}} 对象 toString 进正文。
     */
    private static String leafText(JSONNode node, String path) {
        if (node == null || path == null) {
            return "";
        }
        try {
            JSONNode child = node.get(path);
            if (child == null || child.isObject() || child.isArray() || child.isNull()) {
                return "";
            }
            String value = child.getStringValue();
            if (value == null || value.isEmpty() || "null".equals(value)) {
                return "";
            }
            return value;
        } catch (RuntimeException e) {
            return "";
        }
    }

    private static Extracted openaiResponses(JSONNode node) {
        String type = pathText(node, "/type");
        if ("response.completed".equals(type) || "response.failed".equals(type) || "error".equals(type)) {
            String err = firstText(node, "/error/message", "/response/error/message", "/error");
            String status = pathText(node, "/response/status");
            return new Extracted("", "", "", true, emptyToNull(status), emptyToNull(err));
        }
        if ("response.output_text.delta".equals(type)) {
            return new Extracted(firstText(node, "/delta", "/text"), "", "", false, null, null);
        }
        if ("response.reasoning_text.delta".equals(type)
                || "response.reasoning.delta".equals(type)) {
            return new Extracted("", firstText(node, "/delta", "/text"), "", false, null, null);
        }
        if ("response.output_text.done".equals(type) || "response.created".equals(type)) {
            return Extracted.EMPTY;
        }
        if ("response.function_call_arguments.delta".equals(type)) {
            return new Extracted("", "", firstText(node, "/delta"), false, null, null);
        }
        return Extracted.EMPTY;
    }

    private Extracted dashscope(JSONNode node) {
        String message = pathText(node, "/output/choices/0/message/content");
        String reason = pathText(node, "/output/choices/0/message/reasoning_content");
        String finish = firstText(node,
                "/output/choices/0/finish_reason",
                "/output/finish_reason");
        boolean ended = !finish.isEmpty();
        return new Extracted(snapshotDelta(content, message), snapshotDelta(thinking, reason),
                "", ended, emptyToNull(finish), null);
    }

    private static Extracted ernie(JSONNode node) {
        String result = pathText(node, "/result");
        boolean ended = "true".equalsIgnoreCase(pathText(node, "/is_end"));
        String needClear = pathText(node, "/need_clear_history");
        String err = "true".equalsIgnoreCase(needClear) ? "need_clear_history" : null;
        return new Extracted(result, "", "", ended, ended ? "stop" : null, err);
    }

    private static Extracted gemini(JSONNode node) {
        StringBuilder content = new StringBuilder();
        StringBuilder thinking = new StringBuilder();
        JSONNode parts = node.get("/candidates/0/content/parts");
        int count = parts != null && parts.isArray() ? parts.getElementCount() : 0;
        if (count <= 0) {
            count = 32;
        }
        for (int i = 0; i < count; i++) {
            String prefix = "/candidates/0/content/parts/" + i;
            String text = pathText(node, prefix + "/text");
            if (text.isEmpty()) {
                if (parts == null || !parts.isArray()) {
                    break;
                }
                continue;
            }
            if ("true".equalsIgnoreCase(pathText(node, prefix + "/thought"))) {
                thinking.append(text);
            } else {
                content.append(text);
            }
        }
        String finish = pathText(node, "/candidates/0/finishReason");
        boolean ended = !finish.isEmpty();
        return new Extracted(content.toString(), thinking.toString(), "", ended, emptyToNull(finish), null);
    }

    private static Extracted claude(JSONNode node, String eventName) {
        String type = pathText(node, "/type");
        if (type.isEmpty()) {
            type = eventName == null ? "" : eventName;
        }
        if ("ping".equals(type) || "content_block_stop".equals(type)
                || "content_block_start".equals(type) || "message_start".equals(type)) {
            return Extracted.EMPTY;
        }
        if ("message_stop".equals(type)) {
            return new Extracted("", "", "", true, "end_turn", null);
        }
        if ("error".equals(type) || "error".equals(eventName)) {
            String err = firstText(node, "/error/message", "/message", "/error/type");
            return new Extracted("", "", "", true, "error", emptyToNull(err));
        }
        if ("message_delta".equals(type)) {
            String stop = pathText(node, "/delta/stop_reason");
            return new Extracted("", "", "", false, emptyToNull(stop), null);
        }
        String deltaType = pathText(node, "/delta/type");
        if ("thinking_delta".equals(deltaType)) {
            return new Extracted("", firstText(node, "/delta/thinking", "/delta/text"),
                    "", false, null, null);
        }
        if ("input_json_delta".equals(deltaType)) {
            return new Extracted("", "", firstText(node, "/delta/partial_json"), false, null, null);
        }
        if ("signature_delta".equals(deltaType)) {
            return Extracted.EMPTY;
        }
        if ("text_delta".equals(deltaType) || type.isEmpty() || "content_block_delta".equals(type)) {
            return new Extracted(firstText(node, "/delta/text"), "", "", false, null, null);
        }
        return Extracted.EMPTY;
    }

    private static Extracted ollamaGenerate(JSONNode node) {
        String response = pathText(node, "/response");
        String thinking = firstText(node, "/thinking", "/message/thinking");
        boolean ended = "true".equalsIgnoreCase(pathText(node, "/done"));
        return new Extracted(response, thinking, "", ended, ended ? "stop" : null, null);
    }

    private static Extracted ollamaChat(JSONNode node) {
        String response = firstText(node, "/message/content", "/response");
        String thinking = firstText(node, "/message/thinking", "/thinking");
        boolean ended = "true".equalsIgnoreCase(pathText(node, "/done"));
        return new Extracted(response, thinking, "", ended, ended ? "stop" : null, null);
    }

    private static String snapshotDelta(StringBuilder accumulated, String incoming) {
        if (incoming == null || incoming.isEmpty()) {
            return "";
        }
        String current = accumulated.toString();
        if (incoming.equals(current)) {
            return "";
        }
        if (!current.isEmpty() && incoming.startsWith(current)) {
            return incoming.substring(current.length());
        }
        return incoming;
    }

    private static String joinIndexedText(JSONNode node, String arrayPath, String suffix) {
        JSONNode array = null;
        try {
            array = node.get(arrayPath);
        } catch (RuntimeException e) {
            return "";
        }
        int count = array != null && array.isArray() ? array.getElementCount() : 0;
        int limit = count > 0 ? count : 8;
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < limit; i++) {
            String value = pathText(node, arrayPath + "/" + i + suffix);
            if (!value.isEmpty()) {
                builder.append(value);
            } else if (count <= 0 && i == 0) {
                break;
            }
        }
        return builder.toString();
    }

    private static String firstText(JSONNode node, String... paths) {
        for (String path : paths) {
            String value = pathText(node, path);
            if (value != null && !value.isEmpty()) {
                return value;
            }
        }
        return "";
    }

    private static String emptyToNull(String value) {
        return value == null || value.isEmpty() ? null : value;
    }

    static String pathText(JSONNode node, String path) {
        if (node == null || path == null || path.isEmpty()) {
            return "";
        }
        String normalized = toNodePath(path);
        try {
            Object value = node.getPathValue(normalized, Object.class);
            if (value == null) {
                return "";
            }
            String text = String.valueOf(value);
            if ("null".equals(text)) {
                return "";
            }
            return text;
        } catch (RuntimeException e) {
            return "";
        }
    }

    static String toNodePath(String path) {
        String p = path.trim();
        if (p.startsWith("$.")) {
            p = p.substring(2);
        } else if (p.startsWith("$")) {
            p = p.substring(1);
        }
        if (p.startsWith("/")) {
            return p;
        }
        StringBuilder builder = new StringBuilder();
        int i = 0;
        while (i < p.length()) {
            char c = p.charAt(i);
            if (c == '.') {
                i++;
                continue;
            }
            if (c == '[') {
                int end = p.indexOf(']', i);
                if (end < 0) {
                    break;
                }
                builder.append('/').append(p.substring(i + 1, end));
                i = end + 1;
                continue;
            }
            int nextDot = p.indexOf('.', i);
            int nextBracket = p.indexOf('[', i);
            int next = p.length();
            if (nextDot >= 0) {
                next = nextDot;
            }
            if (nextBracket >= 0 && nextBracket < next) {
                next = nextBracket;
            }
            builder.append('/').append(p.substring(i, next));
            i = next;
        }
        return builder.length() == 0 ? "/" : builder.toString();
    }

    private static final class Extracted {
        static final Extracted EMPTY = new Extracted("", "", "", false, null, null);
        final String content;
        final String thinking;
        final String toolCall;
        final boolean done;
        final String finishReason;
        final String error;

        Extracted(String content, String thinking, String toolCall, boolean done,
                  String finishReason, String error) {
            this.content = content == null ? "" : content;
            this.thinking = thinking == null ? "" : thinking;
            this.toolCall = toolCall == null ? "" : toolCall;
            this.done = done;
            this.finishReason = finishReason;
            this.error = error;
        }
    }
}
