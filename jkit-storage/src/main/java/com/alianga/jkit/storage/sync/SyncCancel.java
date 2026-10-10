package com.alianga.jkit.storage.sync;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 同步取消令牌：传入 {@link SyncOptions#cancelToken(SyncCancel)}，
 * 在任意线程调用 {@link #cancel()} 后，同步引擎会在当前文件完成后停止调度剩余动作，
 * 结果以 {@code aborted=true} 返回。
 *
 * @author 郑明亮
 */
public final class SyncCancel {
    private final AtomicBoolean cancelled = new AtomicBoolean();

    /**
     * 请求取消（幂等，可从任意线程调用）。
     */
    public void cancel() {
        cancelled.set(true);
    }

    /**
     * 是否已请求取消。
     *
     * @return true 表示应停止
     */
    public boolean isCancelled() {
        return cancelled.get();
    }
}
