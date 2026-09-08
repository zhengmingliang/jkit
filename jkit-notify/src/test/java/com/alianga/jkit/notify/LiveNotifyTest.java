package com.alianga.jkit.notify;

import com.alianga.jkit.notify.channel.DingTalkChannel;
import com.alianga.jkit.notify.channel.FeishuChannel;
import com.alianga.jkit.notify.channel.SmtpChannel;
import com.alianga.jkit.notify.channel.WecomChannel;

import org.junit.Assume;
import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * 实发探测：凭证与收件人/样例路径一律从 {@code ~/jkit/application.yml} 读取
 * （模板见 {@code jkit-application.yml.example}），不写进源码、不读 sqlite。
 *
 * <p>默认跳过；须 {@link NotifyTestConfig#assumeLiveEnabled()}（{@code -Djkit.notify.live=true}）
 * 且对应配置齐全时才真正发送。单项缺配置用 {@link Assume} 跳过，勿让 CI 因缺密钥失败。
 *
 * @author 郑明亮
 * @since 2.0.1
 */
public class LiveNotifyTest {

    private static void assumeLiveEnabled() {
        NotifyTestConfig.assumeLiveEnabled();
    }

    private static String cfg(String key) {
        return NotifyTestConfig.requiredString(key);
    }

    private static String requireCfg(String key) {
        String value = cfg(key);
        Assume.assumeTrue("缺少 " + key + "，见 ~/jkit/application.yml", value != null);
        return value;
    }

    private static String recipientEmail() {
        return requireCfg("live.recipientEmail");
    }

    private static ChannelConfig dingtalk() {
        ChannelConfig config = ChannelConfig.webhook(requireCfg("dingtalk.webhookUrl"))
                .timeoutMs(15000);
        String secret = cfg("dingtalk.secret");
        if (secret != null) {
            config = config.secret(secret);
        }
        return config;
    }

    private static ChannelConfig wecom() {
        return ChannelConfig.webhook(requireCfg("wecom.webhookUrl")).timeoutMs(15000);
    }

    private static ChannelConfig feishu() {
        ChannelConfig config = ChannelConfig.webhook(requireCfg("feishu.webhookUrl"))
                .timeoutMs(15000);
        String secret = cfg("feishu.secret");
        if (secret != null) {
            config = config.secret(secret);
        }
        return config;
    }

    private static ChannelConfig smtp() {
        String host = requireCfg("smtp.host");
        int port = 465;
        String portText = cfg("smtp.port");
        if (portText != null) {
            try {
                port = Integer.parseInt(portText);
            } catch (NumberFormatException ignored) {
                // 缺省 465
            }
        }
        boolean ssl = port == 465;
        String sslText = cfg("smtp.ssl");
        if (sslText != null) {
            ssl = Boolean.parseBoolean(sslText);
        }
        ChannelConfig config = ChannelConfig.smtp(host, port)
                .ssl(ssl)
                .username(requireCfg("smtp.username"))
                .password(requireCfg("smtp.password"))
                .from(NotifyUtils.firstNonEmpty(cfg("smtp.from"), cfg("smtp.username")));
        String protocols = cfg("smtp.sslProtocols");
        if (protocols != null) {
            config = config.sslProtocols(protocols);
        }
        return config;
    }

    /**
     * 钉钉加签机器人实发。
     */
    @Test
    public void liveDingTalk() {
        assumeLiveEnabled();
        ChannelConfig hook = dingtalk();
        SendResult result = NotificationManager.send(DingTalkChannel.ID,
                Message.markdown("jkit-notify 实发", "钉钉渠道连通性检查 " + System.currentTimeMillis())
                        .extra(Message.EXTRA_AT_MOBILES, cfg("dingtalk.atMobiles")),
                hook);
        assertTrue(result.toString(), result.isSuccess());
    }

    /**
     * 企微群机器人实发。
     */
    @Test
    public void liveWecom() {
        assumeLiveEnabled();
        ChannelConfig hook = wecom();
        SendResult result = NotificationManager.send(WecomChannel.ID,
                Message.text("jkit-notify 实发", "企微渠道连通性检查 " + System.currentTimeMillis())
                        .extra(Message.EXTRA_AT_MOBILES,
                                NotifyUtils.firstNonEmpty(cfg("wecom.mentionedList"), cfg("wecom.atMobiles"))),
                hook);
        assertTrue(result.toString(), result.isSuccess());
    }

    /**
     * 飞书机器人实发；缺配置时跳过。
     */
    @Test
    public void liveFeishu() {
        assumeLiveEnabled();
        ChannelConfig hook = feishu();
        SendResult result = NotificationManager.send(FeishuChannel.ID,
                Message.text("jkit-notify 实发", "飞书渠道连通性检查 " + System.currentTimeMillis()),
                hook);
        assertTrue(result.toString(), result.isSuccess());
    }

    /**
     * SMTP 实发（含一个小附件）。
     */
    @Test
    public void liveSmtpEmail() {
        assumeLiveEnabled();
        SendResult result = NotificationManager.send(SmtpChannel.ID,
                Message.markdown("jkit-notify 实发", "邮箱连通性检查\n\n- 时间 `"
                        + System.currentTimeMillis() + "`")
                        .attachment(Attachment.of("probe.txt",
                                "jkit-notify live probe".getBytes(StandardCharsets.UTF_8), "text/plain")),
                smtp().to(recipientEmail()).timeoutMs(20000));
        assertTrue(result.toString(), result.isSuccess());
    }

    /**
     * 超大附件自动拆包：把上限压到很小，确认会拆成多封且都能发出。
     */
    @Test
    public void liveEmailAutoSplit() {
        assumeLiveEnabled();
        byte[] payload = new byte[24];
        for (int i = 0; i < payload.length; i++) {
            payload[i] = (byte) ('A' + (i % 26));
        }
        SendResult result = NotificationManager.send(SmtpChannel.ID,
                Message.text("jkit-notify 拆包实发", "大附件自动拆成多封")
                        .attachment(Attachment.of("split.bin", payload, "application/octet-stream")),
                smtp().to(recipientEmail())
                        .autoSplit(true)
                        .maxAttachmentSize(10)
                        .splitChunkSize(8)
                        .timeoutMs(20000));
        assertTrue(result.toString(), result.isSuccess());
        assertTrue("应拆成多封: " + result.parts().size(), result.parts().size() >= 2);
        for (SendResult part : result.parts()) {
            assertTrue(part.toString(), part.isSuccess());
        }
    }

    /**
     * 大附件按配置路径读取并拆成三封发出（自动拆包）。
     *
     * <p>路径优先系统属性 {@code jkit.notify.live.file}，否则 {@code live.largeAttachmentPath}。
     */
    @Test
    public void liveEmailSplitLargeAttachment() throws Exception {
        assumeLiveEnabled();
        String path = System.getProperty("jkit.notify.live.file");
        if (path == null || path.trim().isEmpty()) {
            path = requireCfg("live.largeAttachmentPath");
        }
        File file = new File(path);
        Assume.assumeTrue("大附件样例不存在: " + file.getAbsolutePath(), file.isFile());
        long size = file.length();
        Assume.assumeTrue("样例应较大（>= 1MB），实际 " + size, size >= 1024L * 1024L);
        long chunk = (size + 2) / 3;
        byte[] bytes = Files.readAllBytes(file.toPath());
        String markdown = "把 `" + file.getName() + "`（"
                + String.format("%.1f", size / (1024.0 * 1024.0))
                + " MB）按自动拆包切成 **3** 封发送。\n\n"
                + "- SHA-256：`" + NotifyUtils.sha256Hex(bytes) + "`\n"
                + "- 分块：`" + chunk + "` 字节\n";
        SendResult result = NotificationManager.send(SmtpChannel.ID,
                Message.markdown("jkit-notify 大附件拆包", markdown)
                        .attachment(Attachment.of(file.getName(), bytes, "application/zip")),
                smtp().to(recipientEmail())
                        .autoSplit(true)
                        .maxAttachmentSize(chunk - 1)
                        .splitChunkSize(chunk)
                        .timeoutMs(300000));
        System.out.println(result);
        assertTrue(result.toString(), result.isSuccess());
        assertEquals("应拆成 3 封", 3, result.parts().size());
        for (SendResult part : result.parts()) {
            assertTrue(part.toString(), part.isSuccess());
        }
    }

    /**
     * 真实 Markdown：GBase 集群表分布说明（含 SQL 围栏、GFM 表），SMTP 转 HTML 发送；
     * 钉钉/企微走平台原生 markdown。
     */
    @Test
    public void liveMarkdownGbaseDoc() throws Exception {
        assumeLiveEnabled();
        String markdown = classpathText("/gbase-table.md");
        Assume.assumeTrue("缺少 gbase-table.md", markdown != null && !markdown.isEmpty());

        SendResult smtpResult = NotificationManager.send(SmtpChannel.ID,
                Message.markdown("GBase 集群表分布查询（Markdown 实发）", markdown),
                smtp().to(recipientEmail()).timeoutMs(20000));
        System.out.println("smtp markdown: " + smtpResult);
        assertTrue(smtpResult.toString(), smtpResult.isSuccess());

        ChannelConfig ding = dingtalk();
        SendResult dingtalk = NotificationManager.send(DingTalkChannel.ID,
                Message.markdown("GBase 表分布（Markdown）", markdown)
                        .extra(Message.EXTRA_AT_MOBILES, cfg("dingtalk.atMobiles")),
                ding);
        System.out.println("dingtalk markdown: " + dingtalk);
        assertTrue(dingtalk.toString(), dingtalk.isSuccess());

        ChannelConfig wecomHook = wecom();
        SendResult wecomResult = NotificationManager.send(WecomChannel.ID,
                Message.markdown("GBase 表分布（Markdown）", markdown)
                        .extra(Message.EXTRA_AT_MOBILES, cfg("wecom.mentionedList")),
                wecomHook);
        System.out.println("wecom markdown: " + wecomResult);
        assertTrue(wecomResult.toString(), wecomResult.isSuccess());
    }

    /**
     * 真实 HTML 富文本：报告路径来自 {@code live.htmlReportPaths}（逗号分隔，取第一个存在的文件）。
     */
    @Test
    public void liveHtmlReport() throws Exception {
        assumeLiveEnabled();
        File report = firstExistingFromConfig("live.htmlReportPaths");
        Assume.assumeNotNull("HTML 报告不存在（配置 live.htmlReportPaths）", report);
        String html = new String(Files.readAllBytes(report.toPath()), StandardCharsets.UTF_8);
        SendResult result = NotificationManager.send(SmtpChannel.ID,
                Message.html("HTML 报告实发", html),
                smtp().to(recipientEmail()).timeoutMs(30000));
        System.out.println("smtp html: " + result);
        assertTrue(result.toString(), result.isSuccess());
    }

    /**
     * 钉钉真实卡片（actionCard + feedCard）与图片（公网 picUrl 内嵌 markdown）。
     */
    @Test
    public void liveDingTalkCardAndImage() {
        assumeLiveEnabled();
        ChannelConfig hook = dingtalk();

        SendResult card = NotificationManager.send(DingTalkChannel.ID,
                Message.actionCard("jkit-notify 发布卡片",
                        "### 构建成功\n- 模块 `jkit-notify`\n- 耗时 46s\n\n请点按钮查看详情",
                        "打开仓库", "https://github.com/alianga/jkit"),
                hook);
        System.out.println("dingtalk actionCard: " + card);
        assertTrue(card.toString(), card.isSuccess());

        SendResult news = NotificationManager.send(DingTalkChannel.ID,
                Message.news("jkit-notify 图文卡片", "钉钉 FeedCard 实发",
                        "https://github.com/alianga/jkit",
                        "https://www.dingtalk.com/favicon.ico"),
                hook);
        System.out.println("dingtalk feedCard: " + news);
        assertTrue(news.toString(), news.isSuccess());

        SendResult image = NotificationManager.send(DingTalkChannel.ID,
                Message.image("钉钉图片", new byte[0])
                        .extra(Message.EXTRA_PIC_URL, "https://www.dingtalk.com/favicon.ico"),
                hook);
        System.out.println("dingtalk image: " + image);
        assertTrue(image.toString(), image.isSuccess());
    }

    /**
     * 企微真实图文卡片与本地 PNG（路径来自 {@code live.imagePaths}）。
     */
    @Test
    public void liveWecomCardAndImage() throws Exception {
        assumeLiveEnabled();
        ChannelConfig hook = wecom();

        SendResult news = NotificationManager.send(WecomChannel.ID,
                Message.news("jkit-notify 图文卡片", "企业微信 news 实发：构建成功，点击查看",
                        "https://github.com/alianga/jkit",
                        "https://res.wx.qq.com/a/wx_fed/assets/res/NTI4MWU5.ico"),
                hook);
        System.out.println("wecom news: " + news);
        assertTrue(news.toString(), news.isSuccess());

        File png = firstExistingFromConfig("live.imagePaths");
        Assume.assumeNotNull("找不到测试 PNG（配置 live.imagePaths）", png);
        byte[] bytes = Files.readAllBytes(png.toPath());
        SendResult image = NotificationManager.send(WecomChannel.ID,
                Message.image("jkit-notify 图片", bytes), hook);
        System.out.println("wecom image: " + image);
        assertTrue(image.toString(), image.isSuccess());
    }

    private static String classpathText(String path) throws Exception {
        InputStream in = LiveNotifyTest.class.getResourceAsStream(path);
        if (in == null) {
            return null;
        }
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        byte[] chunk = new byte[4096];
        int read = in.read(chunk);
        while (read >= 0) {
            buffer.write(chunk, 0, read);
            read = in.read(chunk);
        }
        in.close();
        return new String(buffer.toByteArray(), StandardCharsets.UTF_8);
    }

    private static File firstExistingFromConfig(String key) {
        String raw = cfg(key);
        if (raw == null) {
            return null;
        }
        List<String> paths = new ArrayList<String>();
        for (String part : raw.split("[,;]")) {
            String trimmed = part.trim();
            if (!trimmed.isEmpty()) {
                paths.add(trimmed);
            }
        }
        for (String path : paths) {
            File file = new File(path);
            if (file.isFile()) {
                return file;
            }
        }
        return null;
    }
}
