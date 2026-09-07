# 消息通知模块使用指南

`jkit-notify` 是轻量消息通知模块：一套 API 适配多个消息渠道，零第三方依赖（HTTP 复用 jkit 自研客户端，JSON 用 jkit 自研库，SMTP 为纯 Socket 实现，短信签名 HMAC/SHA-256 用 JDK `javax.crypto` 自实现）。

```xml
<dependency>
    <groupId>com.alianga</groupId>
    <artifactId>jkit-notify</artifactId>
    <version>2.0.1</version>
</dependency>
```

> Slack / Telegram / ntfy / 短信（阿里云、腾讯云、云片、华为云）已拆到可选模块 **`jkit-notify-extra`**。只依赖 `jkit-notify` 时不会带上这些渠道；需要时额外引入：
>
> ```xml
> <dependency>
>     <groupId>com.alianga</groupId>
>     <artifactId>jkit-notify-extra</artifactId>
>     <version>2.0.1</version>
> </dependency>
> ```


## 1. 三层模型

```
Message（发什么） + ChannelConfig（用哪个账号/地址发）
        │
        ▼
NotificationManager.send("渠道id", message, config)      ← 门面 + 渠道注册表
        │
        ▼
NotificationChannel（渠道 SPI：id / name / supports / send）
```

- **消息**与**配置**分离：同一渠道可配多套环境，一次业务告警可同时发多个渠道。标题/正文支持 `${key}` / `${a.b}` 模板，发送前替换。
- **多消息类型**：`Message.text()` / `Message.markdown()` / `Message.html()` / `Message.actionCard()`（钉钉） / `Message.markdownV2()`（企微）；渠道声明自己支持的类型，不支持会在发送前抛 `IllegalArgumentException`。
- **一次多渠道**：`NotificationManager.sendAll(message, targets)` 按 Map 顺序发送，部分失败仍返回各渠道 `SendResult`；`sendAllAggregated` 给出汇总。
- **扩展新渠道**：实现 `NotificationChannel`，代码注册 `NotificationManager.get().register(...)`，或放 `META-INF/services/com.alianga.jkit.notify.NotificationChannel` SPI 文件，零改动核心。`unregister(id)` 可摘掉渠道（测试用完记得清）。

## 2. 快速开始

```java
import com.alianga.jkit.notify.*;
import com.alianga.jkit.notify.channel.*;

// 钉钉机器人（安全设置选"加签"时补 .secret("SECxxx")）
SendResult r = NotificationManager.send("dingtalk",
        Message.text("告警 ${host}", "CPU ${value}")
                .var("host", "web-1")
                .var("value", "95%"),
        ChannelConfig.webhook("https://oapi.dingtalk.com/robot/send?access_token=xxx")
                .secret("SECxxx")
                .timeoutMs(5000));
if (r.isFailed()) {
    System.err.println(r.error());
}

// 企业微信群机器人
NotificationManager.send("wecom", Message.markdown("部署", "## v1.2.3 发布成功"),
        ChannelConfig.webhook("https://qyapi.weixin.qq.com/cgi-bin/webhook/send?key=xxx"));

// 飞书机器人（签名校验时补 .secret）
NotificationManager.send("feishu", Message.text("告警", "内存不足"),
        ChannelConfig.webhook("https://open.feishu.cn/open-apis/bot/v2/hook/xxx"));

// Server酱（微信推送）
NotificationManager.send("serverchan", Message.markdown("日报", "## 今日完成\n- A"),
        ChannelConfig.ofToken("SCTxxxx"));

// Bark（iOS 推送）
NotificationManager.send("bark",
        Message.text("带宽告警", "出口流量打满")
                .extra(Message.EXTRA_SOUND, "minuet")
                .extra(Message.EXTRA_GROUP, "ops"),
        ChannelConfig.ofToken("deviceKey"));            // 自建服务再补 .webhookUrl("https://bark.my.com")

// ntfy（https://ntfy.sh 或自建；webhook 路径即主题；私有主题用 token(tk_ 开头) 或用户名密码鉴权）
NotificationManager.send("ntfy",
        Message.text("磁盘告警", "使用率 95%")
                .extra(NtfyChannel.EXTRA_TAGS, "warning,rotating_light")
                .extra(NtfyChannel.EXTRA_PRIORITY, "high")
                .extra(Message.EXTRA_URL, "https://grafana.example.com/dash"),
        ChannelConfig.webhook("https://ntfy.sh/mytopic"));
// 自建服务换完整地址；或 ofToken("tk_xxx").to("mytopic").webhookUrl("https://ntfy.example.com")
// 按条覆盖主题：.extra(Message.EXTRA_GROUP, "other-topic")

// 邮件（附件自动识别 MIME，不必手填 application/zip；拆包可用 10MB / 512KB）
NotificationManager.send("smtp", Message.markdown("报表", "## 营收\n- 100 万")
                .attachment(Attachment.of(new File("report.zip"))),   // 或 Attachment.of("a.zip", bytes)
        ChannelConfig.smtp("smtp.example.com", 465)
                .ssl(true)
                .username("bot@example.com")
                .password("授权码")
                .from("bot@example.com")
                .to("ops@example.com,boss@example.com")
                .cc("archive@example.com")
                .autoSplit(true)
                .maxAttachmentSize("10MB")
                .splitChunkSize("5MB"));

// 钉钉卡片 / 企微图文与图片
NotificationManager.send("dingtalk",
        Message.actionCard("发布", "v1.2.3 已上线", "查看", "https://ci.example.com/42"), dingtalkCfg);
NotificationManager.send("wecom",
        Message.news("发布", "构建成功", "https://ci.example.com/42", "https://example.com/cover.png"), wecomCfg);
NotificationManager.send("wecom", Message.image("截图", pngBytes), wecomCfg);

// 同一条告警发多个渠道
Map<String, ChannelConfig> targets = new LinkedHashMap<>();
targets.put("dingtalk", dingtalkCfg);
targets.put("wecom", wecomCfg);
List<SendResult> results = NotificationManager.sendAll(Message.text("告警", "CPU 95%"), targets);

// 静默时段 / 5 分钟去重 / 本地 20 条每分钟；同一渠道多账号故障转移
NotifyPolicy policy = NotifyPolicy.create()
        .quietHours("23:00", "07:00")
        .dedupWindowMs(5 * 60_000L)
        .rateLimit(20, 60_000L);
NotificationManager.send("dingtalk", msg, cfg, policy);
NotificationManager.sendFailover("dingtalk", msg,
        Arrays.asList(primaryCfg, backupCfg), policy);

// 任意"POST JSON"接口（Slack 风格、自建网关）
NotificationManager.send("webhook", Message.text("构建失败", "job #42"),
        ChannelConfig.webhook("https://hooks.example.com/services/xxx")
                .payloadTemplate("{\"channel\":\"#ci\",\"text\":\"${title}: ${content}\"}")
                .header("Authorization", "Bearer token"));

// Slack（Incoming Webhook；频道用 EXTRA_GROUP，机器人名/颜色/跳转按钮走 extras）
NotificationManager.send("slack",
        Message.text("告警", "CPU 95%")
                .extra(Message.EXTRA_GROUP, "#ops")
                .extra(SlackChannel.EXTRA_COLOR, "danger")
                .extra(Message.EXTRA_URL, "https://dash.example.com/42"),
        ChannelConfig.webhook("https://hooks.slack.com/services/T000/B000/XXX"));

// Slack 官方 API（chat.postMessage）：token 填 xoxb- 开头的 bot token
NotificationManager.send("slack", Message.text("hi", "内容"),
        ChannelConfig.ofToken("xoxb-...").name("发布机器人"));

// Telegram Bot（chat_id 配在 ChannelConfig.to，或按消息用 TelegramChannel.EXTRA_CHAT_ID 覆盖）
// MARKDOWN 会转成 Telegram HTML 子集发送：标题/加粗→<b>，列表→• 行，表格→竖线分隔
NotificationManager.send("telegram", Message.markdown("发布", "## v1.2.3 上线\n- 构建\n- 部署"),
        ChannelConfig.ofToken("123456:ABC-xxx").to("@ops_channel"));
// 国内网络可把 webhookUrl 指到自建反代：.webhookUrl("https://tg.example.com")

// 短信：阿里云（命名参数 code=min=5 风格见下）
NotificationManager.send("sms-aliyun", Message.text("验证码短信")
                .extra(AbstractSmsChannel.EXTRA_SMS_PARAMS, "code=9527,min=5"),
        ChannelConfig.ofToken("AccessKeyId")              // token = AccessKeyId
                .secret("AccessKeySecret")
                .name("短信签名")                           // name = 签名名称
                .template("SMS_123456789")
                .to("13800000001,13800000002"));

// 短信：腾讯云（有序参数，按模板 {1}{2} 顺序）
NotificationManager.send("sms-tencent", Message.text("验证码短信")
                .extra(AbstractSmsChannel.EXTRA_SMS_PARAMS, "9527,5"),
        ChannelConfig.ofToken("SecretId")
                .secret("SecretKey")
                .appId("1400006666")                        // SdkAppId
                .name("短信签名")
                .template("1234567")
                .to("13800000001"));

// 短信：云片（全文短信，不是模板 + 参数；签名在云片后台配置或写进正文）
NotificationManager.send("sms-yunpian", Message.text("【签名】您的验证码是9527"),
        ChannelConfig.ofToken("APIKEY").to("13800000001"));

// 短信：华为云（webhook 填 APP 接入地址；appId 填通道号；有序参数）
NotificationManager.send("sms-huawei", Message.text("验证码短信")
                .extra(AbstractSmsChannel.EXTRA_SMS_PARAMS, "9527,5"),
        ChannelConfig.ofToken("AppKey")
                .secret("AppSecret")
                .appId("8823120512345")                     // 短信通道号 sender
                .name("签名名称")
                .template("12345678")
                .webhookUrl("https://smsapi.cn-north-4.myhuaweicloud.com:443/sms/batchSendSmsV1")
                .to("13800000001"));

// 异步发送（本模块独立守护线程池，默认 8 线程，不和 HTTP/SSE 共用；校验同步完成）
Future<SendResult> future = NotificationManager.sendAsync("dingtalk", msg, cfg);
```

## 3. 内置渠道一览

| id | 渠道 | 必填配置 | 消息类型 | 成功判定 |
| --- | --- | --- | --- | --- |
| `dingtalk` | 钉钉机器人 | `webhook`（含 access_token）；加签再配 `secret` | TEXT / MARKDOWN / ACTION_CARD / NEWS(feedCard) / IMAGE(公网图) | HTTP 200 且 `errcode==0` |
| `wecom` | 企业微信机器人 | `webhook` | TEXT / MARKDOWN / MARKDOWN_V2 / NEWS / IMAGE | HTTP 200 且 `errcode==0` |
| `feishu` | 飞书机器人 | `webhook`；签名校验再配 `secret`（进 JSON 请求体） | TEXT / MARKDOWN | `code`/`StatusCode==0` |
| `serverchan` | Server酱 | `ofToken(SendKey)` 或 `webhook` 完整地址 | TEXT / MARKDOWN | `code==0` |
| `bark` | Bark | `ofToken(device_key)`，`webhookUrl` 可覆盖自建地址 | TEXT / MARKDOWN | `code==200` |
| `ntfy`（`jkit-notify-extra`） | ntfy 推送 | `webhook` 完整地址（路径即主题）或 `to(主题)`（服务地址缺省 `https://ntfy.sh`）；鉴权可选 `token(tk_...)`(Bearer) 或 `username`+`password`(Basic) | TEXT / MARKDOWN | HTTP 2xx |
| `webhook` | 通用 Webhook | `webhook` | TEXT / MARKDOWN / HTML | HTTP 2xx |
| `slack`（`jkit-notify-extra`） | Slack | `webhook`（Incoming Webhook）或 `ofToken(bot token)`（chat.postMessage） | TEXT / MARKDOWN / HTML | 2xx 且（JSON 时）`ok==true` |
| `telegram`（`jkit-notify-extra`） | Telegram Bot | `ofToken(bot token)` + `to(chat_id)` 或消息 `TelegramChannel.EXTRA_CHAT_ID` | TEXT / MARKDOWN / HTML；MARKDOWN 转 HTML 子集 | 2xx 且 `ok==true` |
| `sms-aliyun`（`jkit-notify-extra`） | 阿里云短信 | `ofToken(AccessKeyId)` + `secret` + `name(签名)` + `template` + `to` | TEXT | `Code=="OK"` |
| `sms-tencent`（`jkit-notify-extra`） | 腾讯云短信 | `ofToken(SecretId)` + `secret` + `appId(SdkAppId)` + `name(签名)` + `template` + `to` | TEXT | `SendStatusSet[0].Code=="Ok"` |
| `sms-yunpian`（`jkit-notify-extra`） | 云片短信 | `ofToken(APIKEY)` + `to` | TEXT | `code==0` |
| `sms-huawei`（`jkit-notify-extra`） | 华为云短信 | `ofToken(AppKey)` + `secret` + `appId(通道号)` + `template` + `webhook(接入地址)` + `to` | TEXT | `code=="000000"` |
| `smtp` | 邮件 | `smtp(host, port)` + `username`/`password`/`to`；可选 `cc`/`bcc`/`replyTo`/`autoSplit` | TEXT / HTML；MARKDOWN 转 HTML | DATA 后 `250` |

注：飞书 MARKDOWN 会转成 interactive 卡片（lark_md）；SMTP 的 MARKDOWN 会转成 HTML。各平台的 @人规则、长度上限、限流与签名差异见第 5 节——这些是最容易踩的部分。

## 4. 消息 extras（渠道参数）

`Message.extra(k, v)` 携带渠道相关参数，各渠道只取自己认识的键。核心渠道常量在 `Message` 上；`jkit-notify-extra` 的渠道 extras 在各自渠道类上（如 `SlackChannel.EXTRA_COLOR`、`TelegramChannel.EXTRA_CHAT_ID`）：

| 常量 | 渠道 | 说明 |
| --- | --- | --- |
| `Message.EXTRA_AT_MOBILES` | 钉钉 / 企微 | @指定手机号，逗号分隔字符串或集合 |
| `Message.EXTRA_AT_USERIDS` | 钉钉 / 企微 | @指定 userid；企微 MARKDOWN 会把缺失的 `<@userid>` 补进正文 |
| `Message.EXTRA_AT_ALL` | 钉钉 / 企微 | @所有人 |
| `Message.EXTRA_SOUND` / `EXTRA_GROUP` / `EXTRA_LEVEL` / `EXTRA_URL` | Bark；钉钉 actionCard 用 `EXTRA_URL` | 铃声 / 分组 / 时效性 / 点击跳转 |
| `SlackChannel.EXTRA_USERNAME` / `EXTRA_COLOR` | Slack | 机器人显示名 / 消息侧边条颜色（good/warning/danger/#RRGGBB） |
| `NtfyChannel.EXTRA_TAGS` / `EXTRA_PRIORITY`；`Message.EXTRA_URL` | ntfy | 标签（emoji 短代码，逗号分隔或集合）/ 优先级（1-5 或 min/low/default/high/max/urgent）/ 点击跳转；`Message.EXTRA_GROUP` 可按条覆盖主题 |
| `TelegramChannel.EXTRA_CHAT_ID` / `EXTRA_SILENT` / `EXTRA_THREAD_ID` | Telegram | 聊天目标 / 静默发送 / 话题 ID |
| `AbstractSmsChannel.EXTRA_SMS_PARAMS` | 短信渠道 | 模板参数：有序渠道（腾讯/华为）填逗号分隔值；命名渠道（阿里云）填 `name=value` 对 |
| `Message.EXTRA_BTN_TITLE` | 钉钉 actionCard | 单按钮文案 |
| `Message.EXTRA_PIC_URL` | 钉钉 IMAGE / 企微 NEWS | 封面或公网图片地址 |
| `Message.EXTRA_BASE64` / `EXTRA_MD5` | 企微 IMAGE | 由 `Message.image(title, bytes)` 自动填充 |

## 5. 平台约束与踩坑点

这些是各平台"不看文档就会中"的行为，模块已按此实现，列出来便于排障。

### 钉钉 @人：at 数组只是声明

钉钉光有 `at.atMobiles` **不会**高亮提醒，被 @ 的手机号必须以**字面文本**出现在正文里，否则静默失效。本模块会自动把缺失的 `@手机号` 追加到正文末尾，已出现的不重复追加：

```java
// 正文里没写手机号 → 渠道自动补成 "服务异常 @13800000000"
Message.text("服务异常").extra(Message.EXTRA_AT_MOBILES, "13800000000");

// 正文已含该号码（含 markdown 装饰）→ 原样保留，不重复追加
Message.markdown("告警", "**13800000000** 请处理").extra(Message.EXTRA_AT_MOBILES, "13800000000");
```

钉钉按子串匹配，所以手机号外面套 `**...**` 之类的 markdown 装饰也能生效。

### 企微 @人：TEXT 与 MARKDOWN 行为不同

TEXT 的 @ 走结构化字段 `mentioned_mobile_list` / `mentioned_list`；MARKDOWN **完全不支持这两个字段**，只能在正文内联 `<@userid>`，且要求 userid 而非手机号。因此本模块在 MARKDOWN 下忽略 `EXTRA_AT_MOBILES`，但会把 `EXTRA_AT_USERIDS` 以 `<@userid>` 自动补进正文。需要 @ 手机号时请用 TEXT。

### 消息长度上限按字节计

一个中文占 3 字节，按字符数估算会严重超限；直接按字节切片又会把汉字劈成两半产出乱码、被平台整条拒收。本模块按 UTF-8 字节上限做**字符边界安全**截断（代理对不劈开），超限时追加可见标记 `...[内容过长已截断]`：

| 渠道 | 上限 | 常量 |
| --- | --- | --- |
| 钉钉 text / markdown | 20000 字节 | `DingTalkChannel.MAX_CONTENT_BYTES` |
| 企微 text | 2048 字节 | `WecomChannel.MAX_TEXT_BYTES` |
| 企微 markdown | 4096 字节 | `WecomChannel.MAX_MARKDOWN_BYTES` |
| Server酱 标题 / 正文 | 32 **字符** / 32 KB | `ServerChanChannel.MAX_TITLE_CHARS` / `MAX_DESP_BYTES` |
| 飞书 text / 卡片 | 20000 / 30000 字节 | `FeishuChannel.MAX_TEXT_BYTES` / `MAX_CARD_BYTES` |
| Bark body | 4096 字节 | `BarkChannel.MAX_BODY_BYTES` |
| ntfy message / title | 4096 / 512 字节 | `NtfyChannel.MAX_MESSAGE_BYTES` / `MAX_TITLE_BYTES` |

钉钉截断时会为尚未出现的 `@手机号` 预留字节，避免超长正文把 @ 顶出上限。自定义渠道覆写 `contentMaxBytes(Message)` 即可接入同一套截断逻辑。通用 Webhook、SMTP 不设上限。

### 限流

钉钉机器人与企微群机器人都是 **20 条/分钟**，钉钉超限会被禁言约 10 分钟。渠道本身仍无状态；需要本地保护时用 `NotifyPolicy`：

```java
NotifyPolicy policy = NotifyPolicy.create()
        .quietHours("23:00", "07:00")          // 跨午夜静默
        .dedupWindowMs(5 * 60_000L)            // 同渠道+同标题正文 5 分钟只发一次
        .rateLimit(20, 60_000L);               // 每渠道每分钟最多 20 条
NotificationManager.send("dingtalk", msg, cfg, policy);
NotificationManager.sendFailover("dingtalk", msg, Arrays.asList(a, b), policy);
```

去重命中 / 静默时段返回 `FailureType.SUPPRESSED`（不重试）；本地限流返回 `THROTTLED`。平台限流仍由渠道映射。去重与限流是**进程内**内存实现，多实例不共享。

### 钉钉加签的时钟漂移

加签用的 timestamp 与钉钉服务器相差超过 **1 小时**即失败。容器时区错误或 NTP 未同步是这个错误的头号原因，排查时先核对宿主机时间。另外安全设置若选"自定义关键词"，正文必须含关键词，否则 `errcode 310000`——这个错误码同时覆盖签名不匹配、关键词未命中、IP 白名单不通过三种原因。

### 签名算法：钉钉与飞书恰好相反

| | 时间戳单位 | HMAC 密钥 | 待签数据 |
| --- | --- | --- | --- |
| 钉钉 | 毫秒 | `secret` | `timestamp + "\n" + secret` |
| 飞书 | **秒** | `timestamp + "\n" + secret` | **空串** |

两者写法互换必然签名失败。钉钉的签名放在 **URL query**；飞书的 `timestamp` / `sign` 必须放在 **JSON 请求体**（与 `msg_type` 同级），放进 query 会被当成未签名。钉钉的 Base64 还要再 URL 编码（会产出 `+` `/` `=`）。

## 6. SMTP 细节与国内邮箱实测经验

- **端口与加密**：国内邮箱普遍用 **465 + `.ssl(true)`**（隐式 SSL；端口 465 未显式开启也会走 SMTPS）。明文连 465 时客户端等 `220`、服务端等 TLS ClientHello，双方空等到超时。587 配 `.starttls(true)`；25 明文且多数云厂商已封禁出站。
- **密码是授权码**：各家邮箱都要在网页端单独生成授权码，不是登录密码。注意授权码有有效期（如阿里云 180 天），过期后表现为"密码错误"。
- **TLS 协议钉扎**：`.sslProtocols("TLSv1.2")`。JDK 大版本会调整默认启用的协议集，握手失败时只报笼统的 `SSLHandshakeException`，显式钉扎是最快的排除手段。
- **自签证书**：企业自建网关用 `.trustAllCerts(true)`（跳过证书与主机名校验，公网邮箱不要开）。
- **认证**：AUTH LOGIN（用户名/密码 Base64）。`from` 缺省取 `username`。`to("a@x.com,b@x.com")` 会按逗号/分号拆成多个收件人；`cc` / `bcc` / `replyTo` 可用。Bcc 走 `RCPT TO` 但不出现在 MIME 头。
- **MIME**：必带 `Date` 与 `Message-ID`。Subject 用 `=?UTF-8?B?...?=`，正文 UTF-8 Base64 按 76 字符折行；DATA 阶段做 RFC 5321 dot-stuffing（行首 `.` 写成 `..`）。HTML 直发 `text/html`；MARKDOWN 经 `NotifyUtils.markdownToHtml` 转成 HTML 再发。转换覆盖标题、嵌套列表（列表项里的代码块/表格）、GFM 表格、`----+----` 形式的 CLI 宽表、缩进围栏代码块（``` / ~~~）、链接与加粗等，不是完整 CommonMark。钉钉/企微/飞书/Server酱本身渲染 markdown，不会走这步转换。
- **附件与拆包**：`Attachment.of(file)` 只保留路径，发送/拆包时按块读，100MB 级文件不必整段进堆。`Attachment.of(name, bytes)` 仍是内存附件。MIME 按后缀和文件头自动识别。开启 `.autoSplit(true)` 后，超过 `maxAttachmentSize`（默认 10 MB）的单个附件会按 `splitChunkSize`（默认 5 MB）切成 `filename.partN` 分多封发送。大小可用 `10MB`、`512KB`、`1.5G` 或纯字节数。正文附带 SHA-256 与 `cat` 拼接说明。
- **模板变量**：`.var("host", "web-1")` 或 `.vars(map)`。标题和正文里的 `${host}` / `${cpu.value}` 在 `send` / `sendAll` / `sendAsync` 前替换；缺键变空串。原 `Message` 不被改写。
- **Markdown 预览**：SMTP 把 MARKDOWN 转成 HTML 时默认套响应式文档壳（viewport + 手机/桌面 `@media`）。只要片段时用 `NotifyUtils.markdownToHtml`；完整文档用 `NotifyUtils.markdownToDocument(md, true/false)`。

### 服务商实测速率与坑（来自本仓库历史项目的实测记录）

| 服务商 | SMTP 地址 | 实测/注意 |
| --- | --- | --- |
| 163 | smtp.163.com | 间隔 5 秒发一次，连续 30 条无问题 |
| 天翼 189 | smtp.189.cn | 间隔 5 秒发一次连续 30 条无问题；**提示密码错误时，登录网页版关闭 POP/IMAP 再重新开启** |
| Zoho | smtp.zoho.eu | 每分钟 10 次 |
| 2980 | smtp.2980.com | 间隔 5 秒发一次，连续 20 条无问题 |
| 阿里云企业邮 | smtp.mxhichina.com | 授权码有效期 180 天，到期需重新生成 |
| 腾讯企业邮 | **hwsmtp**.exmail.qq.com | 注意是 `hwsmtp.` 前缀，不是 `smtp.`。AUTH 已成功但 MAIL FROM 报 `501 请登录exmail.qq.com修改密码`（回复常为 GBK）时，先登录网页改密，新账号几乎必遇；授权码可能要重新生成 |
| 移动 139 | smtp.139.com | 授权码 20 位（不是常见的 16 位） |

```java
// 国内邮箱推荐写法
ChannelConfig.smtp("smtp.163.com", 465)
        .ssl(true)
        .sslProtocols("TLSv1.2")
        .username("bot@163.com")
        .password("授权码")
        .to("ops@example.com")
        .timeoutMs(10000);
```

## 7. 扩展自定义渠道

```java
public class MySmsChannel implements NotificationChannel {
    @Override
    public String id() { return "mysms"; }
    @Override
    public String name() { return "自建短信"; }
    @Override
    public boolean supports(MessageType type) { return type == MessageType.TEXT; }
    @Override
    public SendResult send(Message message, ChannelConfig config) {
        // 配置校验（缺参抛 IllegalArgumentException，属编程错误）
        // 网络失败 return SendResult.fail(id(), "reason")，不抛异常
        ...
    }
}

// 方式一：代码注册
NotificationManager.get().register(new MySmsChannel());

// 方式二：SPI（jar 内放 META-INF/services/com.alianga.jkit.notify.NotificationChannel）
//        写上实现类全限定名，后注册的同 id 覆盖先注册的
```

"POST JSON" 型渠道建议继承 `AbstractHttpChannel`，只实现 `buildUrl` / `buildPayload` / `isAccepted` 三个模板方法，超时、自定义 header、异常兜底由骨架统一处理。另有两个可选模板点：`classify(status, body)` 把平台错误码映射到 `FailureType`，`contentMaxBytes(message)` 声明长度上限。

## 8. 结果与错误约定

`SendResult` 提供 `isSuccess() / isFailed()`、`status()`（HTTP 状态码，非 HTTP 渠道为协议码或 0）、`response()`、`error()`、`elapsedMs()`、`failureType()`、`isRetryable()`。

### 失败分类：决定该不该重试

只判断成功/失败不足以驱动重试——把限流当配置错误会白丢消息，把配置错误当网络抖动会无意义地反复冲击接口。

| `FailureType` | 含义 | 建议动作 |
| --- | --- | --- |
| `RETRYABLE` | 网络超时、连接重置、HTTP 5xx/408、SMTP 4xx | 退避重试，**加随机抖动**避免多实例同步重试 |
| `THROTTLED` | 平台明确限流（HTTP 429、钉钉 130101、企微 45009、飞书 9499、SMTP 421/450/451/452） | 用**更长**退避重试；**不要**因此把凭证判定为不可用 |
| `CONFIG_ERROR` | 地址/密钥/令牌错误、缺参、钉钉关键词未命中、SMTP 535 认证失败 | 重试无意义，需人工改配置 |
| `PERMANENT` | 其它永久失败；HTTP 200 但业务码未识别时的保守取值 | 不重试 |

```java
SendResult r = NotificationManager.send("dingtalk", msg, cfg);
if (r.isFailed() && r.isRetryable()) {
    long backoff = r.failureType() == FailureType.THROTTLED ? 60_000 : 2_000;
    // 退避后重试，建议叠加随机抖动
}
```

已映射的平台错误码：钉钉 `130101`(限流) / `300001`(token 无效) / `310000`(签名或关键词)；企微 `45009`(超限) / `-1`(系统繁忙) / `93000`(key 非法)；飞书 `9499`(限流) / `19021`(签名失败) / `19001`,`19003`(参数非法)；Server酱 `40001`(sendkey 非法)；Bark `400`(device key 不存在)。未收录的业务错误码回退到 HTTP 状态码分类。

### 异常 vs 返回值

- **网络/协议/业务失败**返回 `SendResult.fail`，不抛异常，适合重试与聚合。
- **编程错误**直接抛 `IllegalArgumentException`：未知渠道、消息或配置为 `null`、正文为空、类型不被渠道支持、渠道必填配置缺失。这类错误在 SMTP 渠道也**前置到发起连接之前**，不会先连服务器再报错。
