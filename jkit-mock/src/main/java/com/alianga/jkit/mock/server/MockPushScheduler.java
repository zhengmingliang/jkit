package com.alianga.jkit.mock.server;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;

/**
 * 长连接推送调度：把 {@link MockPushPolicy} 的时机描述翻译成具体的延时 / 周期任务，
 * 供 SSE 与 WebSocket 两个协议适配层共用，避免两种连接各写一套定时器。
 *
 * <p>任务在调度器线程回调，适配层负责把帧写出与线程安全；客户端断开 / 连接关闭时
 * 必须 {@link java.util.concurrent.ScheduledFuture#cancel(boolean) cancel} 对应任务，
 * 避免泄漏。共享调度器随服务停止统一关闭。</p>
 *
 * @author 郑明亮
 * @since 2.0.4
 */
public final class MockPushScheduler {
    private MockPushScheduler() {
    }

    /**
     * 创建 daemon 线程的调度器（一个服务共享一个；停止时 shutdownNow）。
     *
     * @param name 线程名前缀
     * @return 调度器
     */
    public static ScheduledExecutorService newDaemonScheduler(String name) {
        return Executors.newScheduledThreadPool(2, r -> {
            Thread thread = new Thread(r, name);
            thread.setDaemon(true);
            return thread;
        });
    }

    /**
     * 按策略安排推送；EVENT 模式不安排定时器（由协议层在收到客户端消息时直接调
     * {@code push.run()}），返回 {@code null}。
     *
     * @param scheduler 共享调度器
     * @param policy    推送策略
     * @param push      每次推送动作（内部须自行捕获异常，避免周期任务被静默摘除）
     * @return 可取消的定时任务；EVENT 模式返回 {@code null}
     */
    public static java.util.concurrent.ScheduledFuture<?> schedule(
            ScheduledExecutorService scheduler, MockPushPolicy policy, Runnable push) {
        if (scheduler == null || policy == null || push == null) {
            return null;
        }
        if (policy.mode == MockPushPolicy.Mode.EVENT) {
            return null;
        }
        if (policy.mode == MockPushPolicy.Mode.ONCE) {
            return scheduler.schedule(wrap(push), policy.initialDelayMs,
                    java.util.concurrent.TimeUnit.MILLISECONDS);
        }
        // INTERVAL：repeat 为 0 表示不限次；有限次在 runnable 内部计数，到点自取消
        final int maxTimes = policy.repeat;
        final java.util.concurrent.ScheduledFuture<?>[] holder =
                new java.util.concurrent.ScheduledFuture<?>[1];
        final int[] sent = new int[]{0};
        Runnable counted = () -> {
            push.run();
            sent[0]++;
            if (maxTimes > 0 && sent[0] >= maxTimes && holder[0] != null) {
                holder[0].cancel(false);
            }
        };
        java.util.concurrent.ScheduledFuture<?> future = scheduler.scheduleAtFixedRate(
                wrap(counted), policy.initialDelayMs, policy.intervalMs,
                java.util.concurrent.TimeUnit.MILLISECONDS);
        holder[0] = future;
        return future;
    }

    private static Runnable wrap(final Runnable push) {
        return () -> {
            try {
                push.run();
            } catch (RuntimeException ignore) {
                // 单帧写出失败（客户端已断开等）不摘除周期任务，连接关闭由适配层 cancel
            }
        };
    }
}
