package com.alianga.jkit.json.internal.utils;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * @time 2021/8/9 22:42
 */
public final class ExecutorServiceUtils {
    /**
     * 关闭线程池
     *
     * @param executorService 待关闭的线程池，等待 5 秒未终止则强制关闭
     */
    public static void shutdownExecutorService(ExecutorService executorService) {
        try {
            executorService.shutdown();
            if (!executorService.awaitTermination(5000, TimeUnit.MILLISECONDS)) {
                executorService.shutdownNow();
            }
        } catch (Throwable e) {
            try {
                executorService.shutdownNow();
            } catch (Throwable throwable) {
            }
        }
    }

}
