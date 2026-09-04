package com.alianga.jkit.notify;

import org.junit.Test;

import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

/**
 * NotificationManager 注册表与门面行为测试（内置渠道均为静态注册，只验证注册表逻辑，
 * 不实际发送网络请求）。
 *
 * @author 郑明亮
 * @since 2.0.1
 */
public class NotificationManagerTest {

    /**
     * 内置渠道全部可按 id 取到（含 SPI 注册）。
     */
    @Test
    public void builtinChannelsRegistered() {
        String[] ids = {"dingtalk", "wecom", "feishu", "serverchan", "bark", "webhook", "smtp"};
        for (String id : ids) {
            assertNotNull("channel missing: " + id, NotificationManager.get().get(id));
        }
        assertTrue(NotificationManager.get().list().size() >= ids.length);
    }

    /**
     * 未知渠道直接抛出。
     */
    @Test(expected = IllegalArgumentException.class)
    public void unknownChannelRejected() {
        NotificationManager.send("no-such-channel", Message.text("hi"), ChannelConfig.webhook("http://x"));
    }

    /**
     * null 消息 / 空正文 / null 配置直接抛出。
     */
    @Test
    public void invalidArgumentsRejected() {
        try {
            NotificationManager.send("webhook", null, ChannelConfig.webhook("http://x"));
            throw new AssertionError("null message should be rejected");
        } catch (IllegalArgumentException expected) {
            // 预期
        }
        try {
            NotificationManager.send("webhook", Message.text(" "), ChannelConfig.webhook("http://x"));
            throw new AssertionError("blank content should be rejected");
        } catch (IllegalArgumentException expected) {
            // 预期
        }
        try {
            NotificationManager.send("webhook", Message.text("hi"), null);
            throw new AssertionError("null config should be rejected");
        } catch (IllegalArgumentException expected) {
            // 预期
        }
    }

    /**
     * 渠道不支持的消息类型被拒绝（钉钉不支持 HTML）。
     */
    @Test(expected = IllegalArgumentException.class)
    public void unsupportedTypeRejected() {
        NotificationManager.send("dingtalk", Message.html("t", "<b>hi</b>"),
                ChannelConfig.webhook("http://x"));
    }

    /**
     * 自定义渠道可通过 register 注册并覆盖内置渠道。
     */
    @Test
    public void registerCustomChannel() {
        NotificationChannel custom = new NotificationChannel() {
            @Override
            public String id() {
                return "custom-test";
            }

            @Override
            public String name() {
                return "自定义测试渠道";
            }

            @Override
            public boolean supports(MessageType type) {
                return type == MessageType.TEXT;
            }

            @Override
            public SendResult send(Message message, ChannelConfig config) {
                return SendResult.ok(id(), 200, "custom:" + message.content(), 1L);
            }
        };
        NotificationManager.get().register(custom);
        try {
            assertSame(custom, NotificationManager.get().get("custom-test"));
            SendResult result = NotificationManager.send("custom-test",
                    Message.text("hi"), ChannelConfig.webhook("ignored"));
            assertTrue(result.isSuccess());
            assertEquals("custom:hi", result.response());

            // 不支持的类型在门面层被拒绝
            try {
                NotificationManager.send("custom-test", Message.markdown("t", "m"),
                        ChannelConfig.webhook("ignored"));
                throw new AssertionError("markdown should be rejected");
            } catch (IllegalArgumentException expected) {
                assertTrue(expected.getMessage().contains("does not support"));
            }
        } finally {
            NotificationManager.get().register(replacementFor("custom-test"));
        }
    }

    private static NotificationChannel replacementFor(final String id) {
        return new NotificationChannel() {
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
                return false;
            }

            @Override
            public SendResult send(Message message, ChannelConfig config) {
                return SendResult.fail(id(), "placeholder");
            }
        };
    }

    /**
     * 异步发送：校验同步完成，结果从 Future 返回。
     */
    @Test
    public void asyncSendReturnsFuture() throws Exception {
        final String marker = "async-marker-" + System.nanoTime();
        NotificationChannel custom = new NotificationChannel() {
            @Override
            public String id() {
                return "custom-async";
            }

            @Override
            public String name() {
                return "自定义异步渠道";
            }

            @Override
            public boolean supports(MessageType type) {
                return true;
            }

            @Override
            public SendResult send(Message message, ChannelConfig config) {
                return SendResult.ok(id(), 200, marker, 1L);
            }
        };
        NotificationManager.get().register(custom);
        Future<SendResult> future = NotificationManager.sendAsync("custom-async",
                Message.text("hi"), ChannelConfig.webhook("ignored"));
        SendResult result = future.get(10, TimeUnit.SECONDS);
        assertTrue(result.isSuccess());
        assertEquals(marker, result.response());

        // 非法参数在提交前同步抛出，Future 不会产生
        try {
            NotificationManager.sendAsync("unknown-x", Message.text("hi"),
                    ChannelConfig.webhook("x"));
            throw new AssertionError("unknown channel should fail fast");
        } catch (IllegalArgumentException expected) {
            // 预期
        }
    }

    /**
     * Future.get 的受检异常签名确认（编译期契约，不触发实际异常）。
     */
    @Test
    public void futureCheckedExceptionContract() throws Exception {
        Future<SendResult> future = NotificationManager.sendAsync("webhook",
                Message.text("content"), ChannelConfig.webhook("http://127.0.0.1:1/x"));
        try {
            future.get(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (ExecutionException e) {
            // SendResult.fail 会作为正常结果返回，不会走到这里；不额外断言
        } catch (TimeoutException e) {
            // 超时容忍
        }
    }
}
