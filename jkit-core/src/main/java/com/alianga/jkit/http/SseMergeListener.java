package com.alianga.jkit.http;

import java.io.IOException;

/**
 * SSE 自动合并回调。每次抽出增量后立即 {@link #onDelta(SseMergeResult)}，便于实时输出。
 *
 * @author 郑明亮
 */
public interface SseMergeListener {
    /**
     * 连接已建立。
     *
     * @param response 响应
     */
    default void onOpen(HttpResponse response) {
    }

    /**
     * 合并结果更新（含累计文本与本片增量）。
     *
     * @param snapshot 快照
     */
    void onDelta(SseMergeResult snapshot);

    /**
     * 原始事件，可选。
     *
     * @param event 事件
     */
    default void onEvent(SseEvent event) {
    }

    /**
     * 流结束时的最终合并结果。
     *
     * @param result 最终快照
     */
    default void onComplete(SseMergeResult result) {
    }

    /**
     * 出错。
     *
     * @param e 错误
     */
    default void onError(IOException e) {
    }
}
