package com.alianga.jkit.notify;

import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 发送策略：同一渠道多账号故障转移、静默时段、短窗口去重、本地限流。
 *
 * <p>默认全部关闭，渠道本身保持无状态。需要时：
 * <pre>{@code
 * NotifyPolicy policy = NotifyPolicy.create()
 *         .quietHours("23:00", "07:00")
 *         .dedupWindowMs(5 * 60_000L)
 *         .rateLimit(20, 60_000L);
 * NotificationManager.send("dingtalk", msg, cfg, policy);
 * NotificationManager.sendFailover("dingtalk", msg, Arrays.asList(primary, backup), policy);
 * }</pre>
 *
 * <p>去重与限流是进程内内存实现，多实例不共享。
 *
 * @author 郑明亮
 * @since 2.0.1
 */
public final class NotifyPolicy {
    private LocalTime quietStart;
    private LocalTime quietEnd;
    private ZoneId zone;
    private long dedupWindowMs;
    private int rateLimitMax;
    private long rateLimitWindowMs;
    private Clock clock = Clock.SYSTEM;

    private final ConcurrentHashMap<String, Long> dedupUntil = new ConcurrentHashMap<String, Long>();
    private final ConcurrentHashMap<String, WindowCounter> rateWindows = new ConcurrentHashMap<String, WindowCounter>();

    private NotifyPolicy() {
    }

    /**
     * @return 新策略（全部关闭）
     */
    public static NotifyPolicy create() {
        return new NotifyPolicy();
    }

    /**
     * 静默时段，闭开区间。跨午夜如 {@code 23:00-07:00} 表示晚上 11 点到次日 7 点。
     *
     * @param start 开始，{@code HH:mm}
     * @param end 结束，{@code HH:mm}
     * @return this
     */
    public NotifyPolicy quietHours(String start, String end) {
        return quietHours(start, end, ZoneId.systemDefault());
    }

    /**
     * 静默时段，指定时区。
     *
     * @param start 开始
     * @param end 结束
     * @param zoneId 时区
     * @return this
     */
    public NotifyPolicy quietHours(String start, String end, ZoneId zoneId) {
        this.quietStart = LocalTime.parse(start);
        this.quietEnd = LocalTime.parse(end);
        this.zone = zoneId == null ? ZoneId.systemDefault() : zoneId;
        return this;
    }

    /**
     * 同一渠道 + 标题 + 正文在窗口内只发一次，后续返回 {@link FailureType#SUPPRESSED}。
     *
     * @param windowMs 窗口毫秒，非正数关闭
     * @return this
     */
    public NotifyPolicy dedupWindowMs(long windowMs) {
        this.dedupWindowMs = windowMs;
        return this;
    }

    /**
     * 本地限流：每个渠道在窗口内最多发送 {@code max} 次（被抑制的不计）。
     *
     * @param max 窗口内上限
     * @param windowMs 窗口毫秒
     * @return this
     */
    public NotifyPolicy rateLimit(int max, long windowMs) {
        this.rateLimitMax = max;
        this.rateLimitWindowMs = windowMs;
        return this;
    }

    /**
     * 测试用时钟。
     *
     * @param clock 时钟
     * @return this
     */
    NotifyPolicy clock(Clock clock) {
        this.clock = clock == null ? Clock.SYSTEM : clock;
        return this;
    }

    /**
     * 是否处于静默时段。
     *
     * @return 是则不应发送
     */
    public boolean inQuietHours() {
        if (quietStart == null || quietEnd == null) {
            return false;
        }
        LocalTime now = java.time.ZonedDateTime.ofInstant(
                java.time.Instant.ofEpochMilli(clock.now()), zone).toLocalTime();
        if (quietStart.equals(quietEnd)) {
            return true;
        }
        if (quietStart.isBefore(quietEnd)) {
            return !now.isBefore(quietStart) && now.isBefore(quietEnd);
        }
        return !now.isBefore(quietStart) || now.isBefore(quietEnd);
    }

    /**
     * 发送前检查：静默 / 去重 / 限流。通过返回 {@code null}。
     *
     * @param channelId 渠道
     * @param message 已渲染消息
     * @return 抑制结果；通过为 {@code null}
     */
    public SendResult beforeSend(String channelId, Message message) {
        prune();
        if (inQuietHours()) {
            return SendResult.fail(channelId, "suppressed: quiet hours", FailureType.SUPPRESSED);
        }
        String key = dedupKey(channelId, message);
        long now = clock.now();
        if (dedupWindowMs > 0) {
            Long until = dedupUntil.get(key);
            if (until != null && until > now) {
                return SendResult.fail(channelId, "suppressed: duplicate within " + dedupWindowMs + "ms",
                        FailureType.SUPPRESSED);
            }
        }
        if (rateLimitMax > 0 && rateLimitWindowMs > 0) {
            WindowCounter counter = rateWindows.get(channelId);
            if (counter != null && now - counter.windowStart < rateLimitWindowMs
                    && counter.count.get() >= rateLimitMax) {
                return SendResult.fail(channelId, "suppressed: local rate limit " + rateLimitMax
                        + "/" + rateLimitWindowMs + "ms", FailureType.THROTTLED);
            }
        }
        return null;
    }

    /**
     * 真正发出去之后记账（成功或平台失败都占额度；本地抑制不占）。
     *
     * @param channelId 渠道
     * @param message 消息
     */
    public void afterAttempt(String channelId, Message message) {
        long now = clock.now();
        if (dedupWindowMs > 0) {
            dedupUntil.put(dedupKey(channelId, message), now + dedupWindowMs);
        }
        if (rateLimitMax > 0 && rateLimitWindowMs > 0) {
            WindowCounter counter = rateWindows.get(channelId);
            if (counter == null || now - counter.windowStart >= rateLimitWindowMs) {
                counter = new WindowCounter(now);
                rateWindows.put(channelId, counter);
            }
            counter.count.incrementAndGet();
        }
    }

    /**
     * 清空去重与限流计数（测试或热更新用）。
     */
    public void reset() {
        dedupUntil.clear();
        rateWindows.clear();
    }

    private void prune() {
        long now = clock.now();
        if (dedupUntil.size() > 2048) {
            Iterator<Map.Entry<String, Long>> it = dedupUntil.entrySet().iterator();
            while (it.hasNext()) {
                Map.Entry<String, Long> entry = it.next();
                if (entry.getValue() <= now) {
                    it.remove();
                }
            }
        }
    }

    private static String dedupKey(String channelId, Message message) {
        String title = message == null ? "" : String.valueOf(message.title());
        String content = message == null ? "" : String.valueOf(message.content());
        return channelId + "|" + title + "|" + content;
    }

    /**
     * 可替换时钟，便于单测。
     */
    interface Clock {
        Clock SYSTEM = new Clock() {
            @Override
            public long now() {
                return System.currentTimeMillis();
            }
        };

        /**
         * @return 当前毫秒
         */
        long now();
    }

    private static final class WindowCounter {
        private final long windowStart;
        private final AtomicInteger count = new AtomicInteger();

        private WindowCounter(long windowStart) {
            this.windowStart = windowStart;
        }
    }

    /**
     * 故障转移候选账号。
     *
     * @param configs 同一渠道的多套配置，按优先顺序
     * @return 只读列表
     */
    public static List<ChannelConfig> accounts(ChannelConfig... configs) {
        List<ChannelConfig> list = new ArrayList<ChannelConfig>();
        if (configs != null) {
            Collections.addAll(list, configs);
        }
        return list;
    }
}
