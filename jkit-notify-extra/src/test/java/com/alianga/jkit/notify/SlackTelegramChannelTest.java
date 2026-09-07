package com.alianga.jkit.notify;

import com.alianga.jkit.HttpUtils;
import com.alianga.jkit.notify.channel.SlackChannel;
import com.alianga.jkit.notify.channel.TelegramChannel;

import org.junit.Assume;
import org.junit.Ignore;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Slack / Telegram 渠道黑盒测试。
 *
 * @author 郑明亮
 * @since 2.0.1
 */
public class SlackTelegramChannelTest extends AbstractHttpChannelTest {

    /**
     * Slack Incoming Webhook：channel/username 进 payload，成功判定为 HTTP 2xx + "ok"。
     */
    @Test
    public void slackWebhookPayload() {
        respond(200, "ok");
        SendResult result = NotificationManager.send(SlackChannel.ID,
                Message.text("部署通知", "v1.2.3 已上线")
                        .extra(Message.EXTRA_GROUP, "#ops")
                        .extra(SlackChannel.EXTRA_USERNAME, "发布机器人"),
                ChannelConfig.webhook(baseUrl() + "/services/T0/B0/XXX"));
        assertTrue(result.isSuccess());
        Captured request = take();
        assertJsonEquals("{\"channel\":\"#ops\",\"username\":\"发布机器人\","
                + "\"text\":\"部署通知\\nv1.2.3 已上线\"}", request.body());
        assertTrue(isEmpty());
    }

    /**
     * Slack：color/url 生成 attachments（侧边条 + Open 按钮）。
     */
    @Test
    public void slackAttachmentsWithColorAndUrl() {
        respond(200, "ok");
        NotificationManager.send(SlackChannel.ID,
                Message.text("告警", "CPU 95%")
                        .extra(SlackChannel.EXTRA_COLOR, "danger")
                        .extra(Message.EXTRA_URL, "https://example.com/dash"),
                ChannelConfig.webhook(baseUrl()));
        Captured request = take();
        assertJsonEquals("{\"text\":\"告警\\nCPU 95%\",\"attachments\":["
                + "{\"color\":\"danger\",\"actions\":["
                + "{\"type\":\"button\",\"text\":\"Open\",\"url\":\"https://example.com/dash\"}"
                + "]}]}", request.body());
    }

    /**
     * Slack：只给 bot token 时走 chat.postMessage，token 进 payload，ok==true 判成功。
     */
    @Test
    public void slackBotTokenUsesApi() {
        respond(200, "{\"ok\":true,\"channel\":\"C123\",\"ts\":\"1.2\"}");
        SendResult result = NotificationManager.send(SlackChannel.ID,
                Message.text("hi"),
                ChannelConfig.ofToken("xoxb-1-2-abc").webhookUrl(baseUrl() + "/api/chat.postMessage"));
        assertTrue(result.isSuccess());
        Captured request = take();
        assertJsonEquals("{\"token\":\"xoxb-1-2-abc\",\"text\":\"通知\\nhi\"}", request.body());
    }

    /**
     * Slack：API 返回 ok=false 判失败，error 进 error 信息。
     */
    @Test
    public void slackApiFailureMapped() {
        respond(200, "{\"ok\":false,\"error\":\"invalid_auth\"}");
        SendResult result = NotificationManager.send(SlackChannel.ID,
                Message.text("hi"), ChannelConfig.ofToken("xoxb-bad").webhookUrl(baseUrl()));
        assertFalse(result.isSuccess());
        assertEquals("slack error: invalid_auth", result.error());
        assertEquals(FailureType.CONFIG_ERROR, result.failureType());
    }

    /**
     * Slack：429 归为限流。
     */
    @Test
    public void slackThrottled() {
        respond(429, "rate limited");
        SendResult result = NotificationManager.send(SlackChannel.ID,
                Message.text("hi"), ChannelConfig.webhook(baseUrl()));
        assertFalse(result.isSuccess());
        assertEquals(FailureType.THROTTLED, result.failureType());
        assertTrue(result.isRetryable());
    }

    /**
     * Slack：webhook 与 token 都缺时抛编程错误。
     */
    @Test(expected = IllegalArgumentException.class)
    public void slackMissingCredentialsRejected() {
        NotificationManager.send(SlackChannel.ID, Message.text("hi"),
                ChannelConfig.webhook(" "));
    }

    /**
     * Telegram：bot token 组 URL，chatId 进 payload，无标题纯文本不带 parse_mode。
     */
    @Test
    public void telegramSendMessage() {
        respond(200, "{\"ok\":true,\"result\":{\"message_id\":42}}");
        SendResult result = NotificationManager.send(TelegramChannel.ID,
                Message.text("服务器重启完成"),
                ChannelConfig.ofToken("12345:ABC-def").webhookUrl(baseUrl()).to("-1001234567890"));
        assertTrue(result.isSuccess());
        Captured request = take();
        assertEquals("/bot12345:ABC-def/sendMessage", request.path());
        assertJsonEquals("{\"chat_id\":\"-1001234567890\",\"text\":\"服务器重启完成\"}", request.body());
    }

    /**
     * Telegram：EXTRA_CHAT_ID 优先于配置 to；silent / threadId 进 payload。
     */
    @Test
    public void telegramExtrasMapping() {
        respond(200, "{\"ok\":true}");
        NotificationManager.send(TelegramChannel.ID,
                Message.text("静默消息")
                        .extra(TelegramChannel.EXTRA_CHAT_ID, "@mychannel")
                        .extra(TelegramChannel.EXTRA_SILENT, true)
                        .extra(TelegramChannel.EXTRA_THREAD_ID, "7"),
                ChannelConfig.ofToken("t").webhookUrl(baseUrl()));
        Captured request = take();
        assertJsonEquals("{\"chat_id\":\"@mychannel\",\"text\":\"静默消息\","
                + "\"disable_notification\":true,\"message_thread_id\":\"7\"}", request.body());
    }

    /**
     * Telegram：MARKDOWN 转成 HTML 子集发送（标题加粗、列表转 • 行）；标题用 <b>x</b> 加粗。
     */
    @Test
    public void telegramMarkdownParseMode() {
        respond(200, "{\"ok\":true}");
        NotificationManager.send(TelegramChannel.ID,
                Message.markdown("发布", "## v1.2.3 上线\n- 甲\n- 乙"),
                ChannelConfig.ofToken("t").webhookUrl(baseUrl()).to("@ops"));
        Captured request = take();
        assertJsonEquals("{\"chat_id\":\"@ops\",\"text\":\"<b>发布</b>\\n<b>v1.2.3 上线</b>\\n\\n"
                + "• 甲\\n• 乙\",\"parse_mode\":\"HTML\"}", request.body());
    }

    /**
     * Telegram：MARKDOWN→HTML 实发；标题加粗，任务列表用 ✅/⬜ 勾选框。
     * 需在 {@code ~/jkit/application.yml} 配置 {@code telegram.botToken} / {@code telegram.chatId}
     * （模板见 {@code jkit-application.yml.example}）；缺配置时跳过，勿把真实 token 写进源码。
     */
    @Ignore("需要配置 telegram.botToken / telegram.chatId")
    @Test
    public void telegramMarkdownParseMode2() {
        String botToken = NotifyTestConfig.requiredString("telegram.botToken");
        String chatId = NotifyTestConfig.requiredString("telegram.chatId");
        Assume.assumeTrue("缺少 telegram.botToken，见 ~/jkit/application.yml", botToken != null);
        Assume.assumeTrue("缺少 telegram.chatId，见 ~/jkit/application.yml", chatId != null);
        HttpUtils.debug = true;
        HttpUtils.printCurl = true;
        SendResult sendResult = NotificationManager.send(TelegramChannel.ID,
                Message.markdown("发布", "### 脚本引擎部分\n" +
                        "- [x] 修改加载配置文件方式改为使用sorinResourePattenResolver类加载，配置文件加载顺序同springboot，优先加载jar包同级目录，其次加载iar包内的\n" +
                        "- [x] 脚本引警增加统一资源释放逻辑：关闭可能遗漏的数据库连接 ，删除python临时脚本文件、清除内存 (Redis) 变量的值等\n" +
                        "- [x] 代码库、系统函数 添加 语言类型隔离，eg: 例如封装的python函数getconnection，只能够在python脚本使用，SOL脚本下不应该展示\n" +
                        "- [ ] Java类型与python类型互转问题，涉及到将python数据存储到内存变量，内存变量容器用的Java的HashMap，需要转为Java对象才能存入，py4j\n" +
                        "- [ ] hive、gbase数据库造两个千万级大表，测试大表数据python脚本处理情况以及内存溢出可能导致的一些情况\n" +
                        "- [ ] 添加 使用指定的普通用户 执行 脚本的功能（处理中行部署执行可能出现的问题）\n" +
                        "- [ ] 添加文件写出到指定位置，并将数据存储到数据库的逻辑\n" +
                        "- [ ] 添加访问文件的接口（python等生成的图片文件等）\n" +
                        "- [x] python控制执行的内存大小\n"),
                ChannelConfig.ofToken(botToken).to(chatId));
        System.out.println("sendResult = " + sendResult);
    }

    /**
     * Telegram：ok=false 判失败，description 进错误信息。
     */
    @Test
    public void telegramFailureMapped() {
        respond(200, "{\"ok\":false,\"error_code\":400,\"description\":\"chat not found\"}");
        SendResult result = NotificationManager.send(TelegramChannel.ID,
                Message.text("hi").extra(TelegramChannel.EXTRA_CHAT_ID, "-1"),
                ChannelConfig.ofToken("t").webhookUrl(baseUrl()));
        assertFalse(result.isSuccess());
        assertEquals("telegram 400: chat not found", result.error());
        assertEquals(FailureType.CONFIG_ERROR, result.failureType());
    }

    /**
     * Telegram：chat_id 缺失抛编程错误。
     */
    @Test(expected = IllegalArgumentException.class)
    public void telegramMissingChatIdRejected() {
        NotificationManager.send(TelegramChannel.ID, Message.text("hi"),
                ChannelConfig.ofToken("t").webhookUrl(baseUrl()));
    }
}
