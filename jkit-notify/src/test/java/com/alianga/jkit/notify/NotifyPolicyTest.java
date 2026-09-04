package com.alianga.jkit.notify;

import org.junit.Test;

import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * 静默时段、去重、本地限流、故障转移。
 *
 * @author 郑明亮
 * @since 2.0.1
 */
public class NotifyPolicyTest {

    /**
     * 去重窗口内第二条被抑制，窗口过后放行。
     */
    @Test
    public void dedupSuppressesSecondSend() {
        final AtomicLong now = new AtomicLong(1_000_000L);
        NotifyPolicy policy = NotifyPolicy.create().dedupWindowMs(5 * 60_000L).clock(new NotifyPolicy.Clock() {
            @Override
            public long now() {
                return now.get();
            }
        });
        CountingChannel channel = register("policy-dedup");
        try {
            ChannelConfig cfg = ChannelConfig.webhook("x");
            Message msg = Message.text("告警", "CPU 95%");
            assertTrue(NotificationManager.send("policy-dedup", msg, cfg, policy).isSuccess());
            SendResult dup = NotificationManager.send("policy-dedup", msg, cfg, policy);
            assertEquals(FailureType.SUPPRESSED, dup.failureType());
            assertEquals(1, channel.calls.get());

            now.addAndGet(5 * 60_000L + 1);
            assertTrue(NotificationManager.send("policy-dedup", msg, cfg, policy).isSuccess());
            assertEquals(2, channel.calls.get());
        } finally {
            NotificationManager.get().unregister("policy-dedup");
        }
    }

    /**
     * 本地限流：窗口内第 3 次被 THROTTLED。
     */
    @Test
    public void localRateLimit() {
        NotifyPolicy policy = NotifyPolicy.create().rateLimit(2, 60_000L);
        CountingChannel channel = register("policy-rl");
        try {
            ChannelConfig cfg = ChannelConfig.webhook("x");
            assertTrue(NotificationManager.send("policy-rl", Message.text("a"), cfg, policy).isSuccess());
            assertTrue(NotificationManager.send("policy-rl", Message.text("b"), cfg, policy).isSuccess());
            SendResult third = NotificationManager.send("policy-rl", Message.text("c"), cfg, policy);
            assertEquals(FailureType.THROTTLED, third.failureType());
            assertEquals(2, channel.calls.get());
        } finally {
            NotificationManager.get().unregister("policy-rl");
        }
    }

    /**
     * 静默时段覆盖当前时间则不发。
     */
    @Test
    public void quietHoursBlocks() {
        ZoneId zone = ZoneId.of("Asia/Shanghai");
        LocalTime now = ZonedDateTime.now(zone).toLocalTime();
        String start = now.minusMinutes(10).withSecond(0).withNano(0).toString();
        if (start.length() > 5) {
            start = start.substring(0, 5);
        }
        String end = now.plusMinutes(10).withSecond(0).withNano(0).toString();
        if (end.length() > 5) {
            end = end.substring(0, 5);
        }
        NotifyPolicy policy = NotifyPolicy.create().quietHours(start, end, zone);
        CountingChannel channel = register("policy-quiet");
        try {
            SendResult result = NotificationManager.send("policy-quiet", Message.text("hi"),
                    ChannelConfig.webhook("x"), policy);
            assertEquals(FailureType.SUPPRESSED, result.failureType());
            assertTrue(result.error().contains("quiet hours"));
            assertEquals(0, channel.calls.get());
        } finally {
            NotificationManager.get().unregister("policy-quiet");
        }
    }

    /**
     * 故障转移：第一套失败后改打第二套。
     */
    @Test
    public void failoverSwitchesAccount() {
        final AtomicInteger calls = new AtomicInteger();
        NotificationChannel channel = new NotificationChannel() {
            @Override
            public String id() {
                return "policy-fo";
            }

            @Override
            public String name() {
                return "fo";
            }

            @Override
            public boolean supports(MessageType type) {
                return true;
            }

            @Override
            public SendResult send(Message message, ChannelConfig config) {
                int n = calls.incrementAndGet();
                if ("bad".equals(config.token())) {
                    return SendResult.fail(id(), "bad token", FailureType.CONFIG_ERROR);
                }
                return SendResult.ok(id(), 200, "ok-" + n, 1L);
            }
        };
        NotificationManager.get().register(channel);
        try {
            SendResult result = NotificationManager.sendFailover("policy-fo", Message.text("hi"),
                    Arrays.asList(ChannelConfig.ofToken("bad"), ChannelConfig.ofToken("good")));
            assertTrue(result.toString(), result.isSuccess());
            assertEquals(2, calls.get());
            assertEquals(2, result.parts().size());
        } finally {
            NotificationManager.get().unregister("policy-fo");
        }
    }

    private static CountingChannel register(String id) {
        CountingChannel channel = new CountingChannel(id);
        NotificationManager.get().register(channel);
        return channel;
    }

    private static final class CountingChannel implements NotificationChannel {
        private final String id;
        private final AtomicInteger calls = new AtomicInteger();

        private CountingChannel(String id) {
            this.id = id;
        }

        @Override
        public String id() {
            return id;
        }

        @Override
        public String name() {
            return id;
        }

        @Override
        public boolean supports(MessageType type) {
            return true;
        }

        @Override
        public SendResult send(Message message, ChannelConfig config) {
            calls.incrementAndGet();
            return SendResult.ok(id, 200, message.content(), 1L);
        }
    }
}
