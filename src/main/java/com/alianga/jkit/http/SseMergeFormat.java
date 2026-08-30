package com.alianga.jkit.http;

/**
 * SSE / NDJSON 自动合并格式。覆盖主流大模型流式协议：
 * OpenAI Chat Completions / Responses、Claude、Gemini、DashScope 原生、文心一言原生、Ollama。
 *
 * @author 郑明亮
 */
public final class SseMergeFormat {
    /**
     * 逐条拼接 {@code data} 原文
     */
    public static final SseMergeFormat RAW = new SseMergeFormat("raw", null);
    /**
     * OpenAI Chat Completions 及兼容层（DeepSeek / GLM / Qwen compatible / Kimi / OpenRouter）：
     * {@code choices[0].delta.content}，思维链 {@code reasoning_content} / {@code reasoning}
     */
    public static final SseMergeFormat OPENAI = new SseMergeFormat("openai", null);
    /**
     * OpenAI Responses API（{@code /v1/responses}）：
     * {@code response.output_text.delta}，结束于 {@code response.completed}
     */
    public static final SseMergeFormat OPENAI_RESPONSES = new SseMergeFormat("openai-responses", null);
    /**
     * Gemini {@code streamGenerateContent?alt=sse}：{@code candidates[0].content.parts[].text}，
     * {@code parts[].thought:true} 为思考内容，结束于非空 {@code finishReason}
     */
    public static final SseMergeFormat GEMINI = new SseMergeFormat("gemini", null);
    /**
     * Anthropic Claude Messages：{@code text_delta} / {@code thinking_delta}，
     * 结束于 {@code message_stop}（不是 {@code content_block_stop}）
     */
    public static final SseMergeFormat CLAUDE = new SseMergeFormat("claude", null);
    /**
     * 通义千问 DashScope 原生（请求头 {@code X-DashScope-SSE: enable}）：
     * {@code output.choices[0].message.content}，结束于非空 {@code finish_reason}
     */
    public static final SseMergeFormat DASHSCOPE = new SseMergeFormat("dashscope", null);
    /**
     * 百度文心一言 / 千帆原生：顶层 {@code result}，结束于 {@code is_end: true}
     */
    public static final SseMergeFormat ERNIE = new SseMergeFormat("ernie", null);
    /**
     * Ollama {@code /api/generate}：{@code response}
     */
    public static final SseMergeFormat OLLAMA_GENERATE = new SseMergeFormat("ollama-generate", null);
    /**
     * Ollama {@code /api/chat}：{@code message.content}
     */
    public static final SseMergeFormat OLLAMA_CHAT = new SseMergeFormat("ollama-chat", null);
    /**
     * 按事件名 / JSON 结构自动识别厂商格式
     */
    public static final SseMergeFormat AUTO = new SseMergeFormat("auto", null);

    private final String name;
    private final String jsonPath;

    private SseMergeFormat(String name, String jsonPath) {
        this.name = name;
        this.jsonPath = jsonPath;
    }

    /**
     * 自定义 JSON 路径提取，支持 {@code /choices/0/delta/content} 或 {@code $.choices[0].delta.content}。
     *
     * @param path 路径
     * @return 格式
     */
    public static SseMergeFormat jsonPath(String path) {
        if (path == null || path.trim().isEmpty()) {
            throw new IllegalArgumentException("jsonPath is required");
        }
        return new SseMergeFormat("jsonpath", path.trim());
    }

    /**
     * @return 格式名
     */
    public String getName() {
        return name;
    }

    /**
     * @return 自定义路径，非 JSONPath 格式时为 {@code null}
     */
    public String getJsonPath() {
        return jsonPath;
    }

    boolean isJsonPath() {
        return "jsonpath".equals(name);
    }

    @Override
    public String toString() {
        return jsonPath == null ? name : name + "(" + jsonPath + ")";
    }
}
