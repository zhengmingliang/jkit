package com.alianga.jkit.http;

/**
 * SSE 自动合并的一份快照：累计正文、思考过程、工具调用参数、本片增量。
 *
 * @author 郑明亮
 */
public final class SseMergeResult {
    private final String content;
    private final String thinking;
    private final String contentDelta;
    private final String thinkingDelta;
    private final String toolCall;
    private final String toolCallDelta;
    private final boolean done;
    private final String finishReason;
    private final String error;
    private final SseEvent event;

    /**
     * @param content 已合并的正文
     * @param thinking 已合并的思考过程
     * @param contentDelta 本片正文增量
     * @param thinkingDelta 本片思考增量
     * @param done 是否结束
     * @param event 原始事件，可为 {@code null}
     */
    public SseMergeResult(String content, String thinking, String contentDelta, String thinkingDelta,
                          boolean done, SseEvent event) {
        this(content, thinking, contentDelta, thinkingDelta, "", "", done, null, null, event);
    }

    /**
     * @param content 已合并的正文
     * @param thinking 已合并的思考过程
     * @param contentDelta 本片正文增量
     * @param thinkingDelta 本片思考增量
     * @param toolCall 已合并的工具调用参数（partial JSON）
     * @param toolCallDelta 本片工具调用参数增量
     * @param done 是否结束
     * @param finishReason 结束原因，未结束时为 {@code null}
     * @param error 错误信息，无错误时为 {@code null}
     * @param event 原始事件，可为 {@code null}
     */
    public SseMergeResult(String content, String thinking, String contentDelta, String thinkingDelta,
                          String toolCall, String toolCallDelta, boolean done, String finishReason,
                          String error, SseEvent event) {
        this.content = content == null ? "" : content;
        this.thinking = thinking == null ? "" : thinking;
        this.contentDelta = contentDelta == null ? "" : contentDelta;
        this.thinkingDelta = thinkingDelta == null ? "" : thinkingDelta;
        this.toolCall = toolCall == null ? "" : toolCall;
        this.toolCallDelta = toolCallDelta == null ? "" : toolCallDelta;
        this.done = done;
        this.finishReason = emptyToNull(finishReason);
        this.error = emptyToNull(error);
        this.event = event;
    }

    private static String emptyToNull(String value) {
        return value == null || value.isEmpty() ? null : value;
    }

    /**
     * @return 累计正文（自动合并结果）
     */
    public String getContent() {
        return content;
    }

    /**
     * @return 累计思考过程
     */
    public String getThinking() {
        return thinking;
    }

    /**
     * @return 本片正文
     */
    public String getContentDelta() {
        return contentDelta;
    }

    /**
     * @return 本片思考
     */
    public String getThinkingDelta() {
        return thinkingDelta;
    }

    /**
     * @return 累计工具调用参数（partial JSON 拼接）
     */
    public String getToolCall() {
        return toolCall;
    }

    /**
     * @return 本片工具调用参数增量
     */
    public String getToolCallDelta() {
        return toolCallDelta;
    }

    /**
     * @return 流是否结束（如 OpenAI 的 {@code [DONE]}、Claude 的 {@code message_stop}）
     */
    public boolean isDone() {
        return done;
    }

    /**
     * @return 结束原因（如 {@code stop} / {@code STOP} / {@code end_turn}），未结束时为 {@code null}
     */
    public String getFinishReason() {
        return finishReason;
    }

    /**
     * @return 流上的错误信息，无错误时为 {@code null}
     */
    public String getError() {
        return error;
    }

    /**
     * @return 原始 SSE 事件
     */
    public SseEvent getEvent() {
        return event;
    }

    @Override
    public String toString() {
        return content;
    }
}
