package com.alianga.jkit.notify;

import com.alianga.jkit.json.JSON;
import com.alianga.jkit.notify.channel.DingTalkChannel;
import com.alianga.jkit.notify.channel.SmtpChannel;
import com.alianga.jkit.notify.channel.WecomChannel;

import org.junit.Assume;
import org.junit.BeforeClass;
import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * 用 z-notify-hub 库里的真实渠道配置做实发探测。默认跳过；加 {@code -Djkit.notify.live=true} 才跑。
 *
 * <p>凭证只从本地 sqlite 读取（经 python3），不写进源码。飞书在该库中没有配置，对应用例会 skip。
 *
 * @author 郑明亮
 * @since 2.0.1
 */
public class LiveNotifyTest {

    private static final String DEFAULT_DB = "/opt/workspace/zml/z-notify-hub/z-notify.db";
    private static Map<String, Map<String, String>> configs;

    /**
     * 未显式打开实发开关，或库文件不存在时整类跳过。
     */
    @BeforeClass
    public static void loadHubConfigs() throws Exception {
        Assume.assumeTrue("set -Djkit.notify.live=true to run live sends",
                Boolean.parseBoolean(System.getProperty("jkit.notify.live", "false")));
        File db = new File(System.getProperty("jkit.notify.hub.db", DEFAULT_DB));
        Assume.assumeTrue("z-notify-hub db missing: " + db.getAbsolutePath(), db.isFile());
        String json = dumpConfigs(db.getAbsolutePath());
        configs = new LinkedHashMap<String, Map<String, String>>();
        Object parsed = JSON.parse(json);
        Assume.assumeTrue("channel_config dump is not a list: " + json, parsed instanceof List);
        for (Object item : (List<?>) parsed) {
            if (!(item instanceof Map)) {
                continue;
            }
            Map<?, ?> row = (Map<?, ?>) item;
            String id = String.valueOf(row.get("config_id"));
            Map<String, String> flat = new LinkedHashMap<String, String>();
            flat.put("_id", id);
            flat.put("_type", String.valueOf(row.get("channel_type")));
            Object cfg = row.get("config");
            if (cfg instanceof Map) {
                for (Map.Entry<?, ?> entry : ((Map<?, ?>) cfg).entrySet()) {
                    if (entry.getKey() != null && entry.getValue() != null) {
                        flat.put(String.valueOf(entry.getKey()), String.valueOf(entry.getValue()));
                    }
                }
            }
            configs.put(id, flat);
        }
    }

    /**
     * 钉钉加签机器人实发。
     */
    @Test
    public void liveDingTalk() {
        Map<String, String> cfg = find("DINGTALK");
        Assume.assumeNotNull(cfg);
        SendResult result = NotificationManager.send(DingTalkChannel.ID,
                Message.markdown("jkit-notify 实发", "钉钉渠道连通性检查 " + System.currentTimeMillis())
                        .extra(Message.EXTRA_AT_MOBILES, cfg.get("atMobiles")),
                ChannelConfig.webhook(cfg.get("webhookUrl")).secret(cfg.get("secret")).timeoutMs(15000));
        assertTrue(result.toString(), result.isSuccess());
    }

    /**
     * 企微群机器人实发。
     */
    @Test
    public void liveWecom() {
        Map<String, String> cfg = find("WECHAT");
        Assume.assumeNotNull(cfg);
        SendResult result = NotificationManager.send(WecomChannel.ID,
                Message.text("jkit-notify 实发", "企微渠道连通性检查 " + System.currentTimeMillis())
                        .extra(Message.EXTRA_AT_MOBILES, firstNonBlank(cfg.get("mentionedList"), cfg.get("atMobiles"))),
                ChannelConfig.webhook(cfg.get("webhookUrl")).timeoutMs(15000));
        assertTrue(result.toString(), result.isSuccess());
    }

    /**
     * 飞书：z-notify-hub 当前库没有该渠道，确认后跳过。
     */
    @Test
    public void liveFeishuSkippedWhenMissing() {
        Map<String, String> cfg = find("FEISHU");
        Assume.assumeTrue("z-notify-hub 无飞书配置，跳过实发", cfg != null);
    }

    /**
     * 阿里云企业邮实发（含一个小附件）。
     */
    @Test
    public void liveAliyunEmail() {
        Map<String, String> cfg = findById("email-aliyun");
        Assume.assumeNotNull(cfg);
        SendResult result = NotificationManager.send(SmtpChannel.ID,
                Message.markdown("jkit-notify 实发", "阿里云邮箱连通性检查\n\n- 时间 `"
                        + System.currentTimeMillis() + "`")
                        .attachment(Attachment.of("probe.txt",
                                "jkit-notify live probe".getBytes(StandardCharsets.UTF_8), "text/plain")),
                smtpConfig(cfg).to("mpro@vip.qq.com").timeoutMs(20000));
        assertTrue(result.toString(), result.isSuccess());
    }

    /**
     * 超大附件自动拆包：把上限压到很小，确认会拆成多封且都能发出。
     */
    @Test
    public void liveEmailAutoSplit() {
        Map<String, String> cfg = findById("email-aliyun");
        Assume.assumeNotNull(cfg);
        byte[] payload = new byte[24];
        for (int i = 0; i < payload.length; i++) {
            payload[i] = (byte) ('A' + (i % 26));
        }
        SendResult result = NotificationManager.send(SmtpChannel.ID,
                Message.text("jkit-notify 拆包实发", "大附件自动拆成多封")
                        .attachment(Attachment.of("split.bin", payload, "application/octet-stream")),
                smtpConfig(cfg).to("mpro@vip.qq.com")
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
     * 约 100MB 真实安装包按 3 等份拆成三封发出（自动拆包）。
     */
    @Test
    public void liveEmailSplit100MbIntoThree() throws Exception {
        Map<String, String> cfg = findById("email-aliyun");
        Assume.assumeNotNull(cfg);
        File file = new File(System.getProperty("jkit.notify.live.file",
                "/home/zml/下载/gcexcel-java-8.1.3.zip"));
        Assume.assumeTrue("100MB 样例文件不存在: " + file.getAbsolutePath(), file.isFile());
        long size = file.length();
        Assume.assumeTrue("样例应接近 100MB，实际 " + size, size >= 80L * 1024 * 1024);
        long chunk = (size + 2) / 3;
        byte[] bytes = Files.readAllBytes(file.toPath());
        String markdown = "把 `" + file.getName() + "`（"
                + String.format("%.1f", size / (1024.0 * 1024.0))
                + " MB）按自动拆包切成 **3** 封发送。\n\n"
                + "拼接：`cat " + file.getName() + ".part1 " + file.getName()
                + ".part2 " + file.getName() + ".part3 > " + file.getName() + "`\n\n"
                + "- SHA-256：`" + NotifyUtils.sha256Hex(bytes) + "`\n"
                + "- 分块：`" + chunk + "` 字节\n";
        SendResult result = NotificationManager.send(SmtpChannel.ID,
                Message.markdown("jkit-notify 100MB 附件拆包", markdown)
                        .attachment(Attachment.of(file.getName(), bytes, "application/zip")),
                smtpConfig(cfg).to("mpro@vip.qq.com")
                        .autoSplit(true)
                        .maxAttachmentSize(chunk - 1)
                        .splitChunkSize(chunk)
                        .timeoutMs(300000));
        System.out.println(result);
        assertTrue(result.toString(), result.isSuccess());
        assertEquals("100MB 应拆成 3 封", 3, result.parts().size());
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
        String markdown = classpathText("/gbase-table.md");
        Assume.assumeTrue("缺少 gbase-table.md", markdown != null && !markdown.isEmpty());

        Map<String, String> mail = findById("email-aliyun");
        Assume.assumeNotNull(mail);
        SendResult smtp = NotificationManager.send(SmtpChannel.ID,
                Message.markdown("GBase 集群表分布查询（Markdown 实发）", markdown),
                smtpConfig(mail).to("mpro@vip.qq.com").timeoutMs(20000));
        System.out.println("smtp markdown: " + smtp);
        assertTrue(smtp.toString(), smtp.isSuccess());

        Map<String, String> ding = find("DINGTALK");
        Assume.assumeNotNull(ding);
        SendResult dingtalk = NotificationManager.send(DingTalkChannel.ID,
                Message.markdown("GBase 表分布（Markdown）", markdown)
                        .extra(Message.EXTRA_AT_MOBILES, ding.get("atMobiles")),
                ChannelConfig.webhook(ding.get("webhookUrl")).secret(ding.get("secret")).timeoutMs(15000));
        System.out.println("dingtalk markdown: " + dingtalk);
        assertTrue(dingtalk.toString(), dingtalk.isSuccess());

        Map<String, String> wecom = find("WECHAT");
        Assume.assumeNotNull(wecom);
        SendResult wecomResult = NotificationManager.send(WecomChannel.ID,
                Message.markdown("GBase 表分布（Markdown）", markdown)
                        .extra(Message.EXTRA_AT_MOBILES, wecom.get("mentionedList")),
                ChannelConfig.webhook(wecom.get("webhookUrl")).timeoutMs(15000));
        System.out.println("wecom markdown: " + wecomResult);
        assertTrue(wecomResult.toString(), wecomResult.isSuccess());
    }

    /**
     * 真实 HTML 富文本：Trivy 安全扫描报告原样发给邮箱。
     */
    @Test
    public void liveHtmlTrivyReport() throws Exception {
        Map<String, String> mail = findById("email-aliyun");
        Assume.assumeNotNull(mail);
        File report = firstExistingFile(
                "/opt/workspace/icell/common-model/common-model/report-zh.html",
                "/opt/workspace/zml/ZmlTools/report-zh.html");
        Assume.assumeNotNull("Trivy HTML 报告不存在", report);
        String html = new String(Files.readAllBytes(report.toPath()), StandardCharsets.UTF_8);
        SendResult result = NotificationManager.send(SmtpChannel.ID,
                Message.html("Trivy 安全扫描与供应链依赖分析报告", html),
                smtpConfig(mail).to("mpro@vip.qq.com").timeoutMs(30000));
        System.out.println("smtp html: " + result);
        assertTrue(result.toString(), result.isSuccess());
    }

    /**
     * 钉钉真实卡片（actionCard + feedCard）与图片（公网 picUrl 内嵌 markdown）。
     */
    @Test
    public void liveDingTalkCardAndImage() {
        Map<String, String> cfg = find("DINGTALK");
        Assume.assumeNotNull(cfg);
        ChannelConfig hook = ChannelConfig.webhook(cfg.get("webhookUrl")).secret(cfg.get("secret")).timeoutMs(15000);

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
     * 企微真实图文卡片与本地 PNG 图片（base64 + md5）。
     */
    @Test
    public void liveWecomCardAndImage() throws Exception {
        Map<String, String> cfg = find("WECHAT");
        Assume.assumeNotNull(cfg);
        ChannelConfig hook = ChannelConfig.webhook(cfg.get("webhookUrl")).timeoutMs(15000);

        SendResult news = NotificationManager.send(WecomChannel.ID,
                Message.news("jkit-notify 图文卡片", "企业微信 news 实发：构建成功，点击查看",
                        "https://github.com/alianga/jkit",
                        "https://res.wx.qq.com/a/wx_fed/assets/res/NTI4MWU5.ico"),
                hook);
        System.out.println("wecom news: " + news);
        assertTrue(news.toString(), news.isSuccess());

        File png = firstExistingFile(
                "/opt/workspace/zml/zfile/img/logo-zfile.png",
                "/opt/workspace/zml/zfile-vue/public/logo.png",
                "/opt/workspace/zml/jkit/target1/reports/apidocs/resources/glass.png");
        Assume.assumeNotNull("找不到测试 PNG", png);
        byte[] bytes = Files.readAllBytes(png.toPath());
        SendResult image = NotificationManager.send(WecomChannel.ID,
                Message.image("jkit-notify 图片", bytes), hook);
        System.out.println("wecom image: " + image);
        assertTrue(image.toString(), image.isSuccess());
    }

    private static String dumpConfigs(String dbPath) throws Exception {
        ProcessBuilder builder = new ProcessBuilder("python3", "-c",
                "import json,sqlite3,sys\n"
                        + "conn=sqlite3.connect(sys.argv[1])\n"
                        + "cur=conn.cursor()\n"
                        + "out=[]\n"
                        + "for row in cur.execute('SELECT config_id, channel_type, config_json FROM channel_config WHERE enabled=1'):\n"
                        + "    cfg={}\n"
                        + "    try:\n"
                        + "        cfg=json.loads(row[2] or '{}')\n"
                        + "    except Exception:\n"
                        + "        cfg={}\n"
                        + "    out.append({'config_id':row[0],'channel_type':row[1],'config':cfg})\n"
                        + "print(json.dumps(out,ensure_ascii=False))\n",
                dbPath);
        builder.redirectErrorStream(true);
        Process process = builder.start();
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        InputStream in = process.getInputStream();
        byte[] chunk = new byte[4096];
        int read = in.read(chunk);
        while (read >= 0) {
            buffer.write(chunk, 0, read);
            read = in.read(chunk);
        }
        int code = process.waitFor();
        String output = new String(buffer.toByteArray(), StandardCharsets.UTF_8);
        Assume.assumeTrue("python dump failed (" + code + "): " + output, code == 0);
        return output;
    }

    private static ChannelConfig smtpConfig(Map<String, String> cfg) {
        int port = 465;
        try {
            port = Integer.parseInt(cfg.get("port"));
        } catch (Exception ignored) {
            // 缺省 465
        }
        boolean ssl = "true".equalsIgnoreCase(cfg.get("mail.smtp.ssl.enable")) || port == 465;
        return ChannelConfig.smtp(cfg.get("host"), port)
                .ssl(ssl)
                .sslProtocols("TLSv1.2")
                .username(cfg.get("username"))
                .password(cfg.get("password"))
                .from(firstNonBlank(cfg.get("from"), cfg.get("username")));
    }

    private static Map<String, String> find(String channelType) {
        for (Map<String, String> cfg : configs.values()) {
            if (channelType.equalsIgnoreCase(cfg.get("_type"))) {
                return cfg;
            }
        }
        return null;
    }

    private static Map<String, String> findById(String configId) {
        return configs.get(configId);
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

    private static File firstExistingFile(String... paths) {
        if (paths == null) {
            return null;
        }
        for (String path : paths) {
            File file = new File(path);
            if (file.isFile()) {
                return file;
            }
        }
        return null;
    }

    private static String firstNonBlank(String... values) {
        if (values == null) {
            return null;
        }
        for (String value : values) {
            if (value != null && !value.trim().isEmpty()) {
                return value.trim();
            }
        }
        return null;
    }
}
