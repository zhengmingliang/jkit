package com.alianga.jkit.storage.sync;

import com.alianga.jkit.storage.StorageErrorCode;
import com.alianga.jkit.storage.StorageException;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.util.EnumSet;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 传输重试：只对「重试有可能成功」的失败重试，且退避后再试。
 *
 * <p>对象存储上最常见的失败是瞬时抖动：连接被服务端掐断、限流 429、5xx、
 * socket 超时。这类错误立刻放弃会把一次本可完成的上传/下载判死，用户只能
 * 手动重来整批。反过来，鉴权失败、对象不存在、冲突这类错误重试多少次都一样，
 * 只会拖慢并掩盖真正的原因，所以直接放行。</p>
 *
 * <p>退避带抖动：并发上传 4 个文件时若同时失败，固定间隔会让它们在同一个
 * 瞬间一起重试，把刚恢复的服务端再打一次。</p>
 *
 * @author 郑明亮
 */
public final class TransferRetry {
    /** 含首次在内的最大尝试次数。 */
    public static final int MAX_ATTEMPTS = 3;
    /** 基础退避毫秒（第 n 次重试前等待 base * 2^(n-1)）。 */
    public static final long BASE_BACKOFF_MILLIS = 200L;

    /** 值得重试的错误码：瞬时故障，重试可能成功。 */
    private static final Set<StorageErrorCode> RETRYABLE = EnumSet.of(
            StorageErrorCode.CONNECT_FAILED,
            StorageErrorCode.TIMEOUT,
            StorageErrorCode.IO_ERROR,
            StorageErrorCode.RATE_LIMITED,
            StorageErrorCode.SERVER_ERROR);

    private TransferRetry() {
    }

    /**
     * 该异常是否值得重试。
     *
     * @param error 异常
     * @return true 表示瞬时故障
     */
    public static boolean isRetryable(Throwable error) {
        if (error instanceof StorageException) {
            return RETRYABLE.contains(((StorageException) error).code());
        }
        // 中断是调用方主动取消，不是故障
        if (error instanceof InterruptedException || error instanceof InterruptedIOException) {
            return false;
        }
        return error instanceof IOException;
    }

    /**
     * 执行并重试用尽为止。
     *
     * @param task    单次尝试（每次都重新执行，因此内部要自己打开流）
     * @param onRetry 每次重试前的回调，参数是第几次重试（1 起）；可为 null
     * @param <T>     结果类型
     * @return 任务结果
     * @throws Exception 最后一次的异常；不可重试的异常原样抛出
     */
    public static <T> T call(Callable<T> task, java.util.function.IntConsumer onRetry)
            throws Exception {
        int attempt = 1;
        while (true) {
            try {
                return task.call();
            } catch (Throwable error) {
                if (attempt >= MAX_ATTEMPTS || !isRetryable(error)) {
                    throw error;
                }
                if (onRetry != null) {
                    onRetry.accept(attempt);
                }
                backoff(attempt);
                attempt++;
            }
        }
    }

    /** 指数退避 + 抖动，避免并发任务同时重试。 */
    private static void backoff(int attempt) {
        long base = BASE_BACKOFF_MILLIS * (1L << Math.max(0, attempt - 1));
        long jitter = ThreadLocalRandom.current().nextLong(base / 2 + 1);
        try {
            Thread.sleep(base + jitter);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("重试等待被中断", interrupted);
        }
    }
}
