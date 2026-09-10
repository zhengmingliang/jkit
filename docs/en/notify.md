# Notification Module Guide

`jkit-notify` is a lightweight message notification module: one API adapts to multiple messaging channels with zero third-party dependencies (HTTP reuses jkit's own client, JSON uses jkit's own library, SMTP is a pure Socket implementation, and SMS signing HMAC/SHA-256 is implemented on the JDK's `javax.crypto`).

```xml
<dependency>
    <groupId>com.alianga</groupId>
    <artifactId>jkit-notify</artifactId>
    <version>2.0.1</version>
</dependency>
```

> Slack / Telegram / ntfy / SMS (Alibaba Cloud, Tencent Cloud, Yunpian, Huawei Cloud) have been split into the optional module **`jkit-notify-extra`**. Depending only on `jkit-notify` will not bring in these channels; add it when needed:
>
> ```xml
> <dependency>
>     <groupId>com.alianga</groupId>
>     <artifactId>jkit-notify-extra</artifactId>
>     <version>2.0.1</version>
> </dependency>
> ```


## 1. Three-Layer Model

```
Message (what to send) + ChannelConfig (which account/address to send with)
        │
        ▼
NotificationManager.send("channelId", message, config)      ← facade + channel registry
        │
        ▼
NotificationChannel (channel SPI: id / name / supports / send)
```

- **Message** and **configuration** are separated: one channel can be configured with multiple environments, and one business alert can be sent to multiple channels at once. Title/body support `${key}` / `${a.b}` templates, substituted before sending.
- **Multiple message types**: `Message.text()` / `Message.markdown()` / `Message.html()` / `Message.actionCard()` (DingTalk) / `Message.markdownV2()` (WeCom); channels declare the types they support, and unsupported types throw `IllegalArgumentException` before sending.
- **Multiple channels in one call**: `NotificationManager.sendAll(message, targets)` sends in Map order, and partial failures still return each channel's `SendResult`; `sendAllAggregated` produces an aggregate.
- **Extending with new channels**: core built-in channels are registered in code by `NotificationManager.defaults()`; optional modules use the `META-INF/services/com.alianga.jkit.notify.NotificationChannel` SPI (e.g. `jkit-notify-extra`). You can also register in code via `NotificationManager.get().register(...)`. A later registration with the same id overrides an earlier one. `unregister(id)` removes a channel (remember to clean up after tests).

## 2. Quick Start

```java
import com.alianga.jkit.notify.*;
import com.alianga.jkit.notify.channel.*;

// DingTalk bot (add .secret("SECxxx") when the security setting uses "signing")
SendResult r = NotificationManager.send("dingtalk",
        Message.text("Alert ${host}", "CPU ${value}")
                .var("host", "web-1")
                .var("value", "95%"),
        ChannelConfig.webhook("https://oapi.dingtalk.com/robot/send?access_token=xxx")
                .secret("SECxxx")
                .timeoutMs(5000));
if (r.isFailed()) {
    System.err.println(r.error());
}

// WeCom group bot
NotificationManager.send("wecom", Message.markdown("Deploy", "## v1.2.3 released"),
        ChannelConfig.webhook("https://qyapi.weixin.qq.com/cgi-bin/webhook/send?key=xxx"));

// Feishu bot (add .secret when signature verification is enabled)
NotificationManager.send("feishu", Message.text("Alert", "Out of memory"),
        ChannelConfig.webhook("https://open.feishu.cn/open-apis/bot/v2/hook/xxx"));

// ServerChan (WeChat push)
NotificationManager.send("serverchan", Message.markdown("Daily report", "## Done today\n- A"),
        ChannelConfig.ofToken("SCTxxxx"));

// Bark (iOS push)
NotificationManager.send("bark",
        Message.text("Bandwidth alert", "Outbound traffic saturated")
                .extra(Message.EXTRA_SOUND, "minuet")
                .extra(Message.EXTRA_GROUP, "ops"),
        ChannelConfig.ofToken("deviceKey"));            // for self-hosted servers also add .webhookUrl("https://bark.my.com")

// ntfy (https://ntfy.sh or self-hosted; the webhook path is the topic; private topics authenticate with token (tk_ prefix) or username/password)
NotificationManager.send("ntfy",
        Message.text("Disk alert", "Usage 95%")
                .extra(NtfyChannel.EXTRA_TAGS, "warning,rotating_light")
                .extra(NtfyChannel.EXTRA_PRIORITY, "high")
                .extra(Message.EXTRA_URL, "https://grafana.example.com/dash"),
        ChannelConfig.webhook("https://ntfy.sh/mytopic"));
// For self-hosted servers use the full address; or ofToken("tk_xxx").to("mytopic").webhookUrl("https://ntfy.example.com")
// Per-message topic override: .extra(Message.EXTRA_GROUP, "other-topic")

// Email (attachments get their MIME type detected automatically, no need to hand-fill application/zip; splitting supports 10MB / 512KB)
NotificationManager.send("smtp", Message.markdown("Report", "## Revenue\n- 1 million")
                .attachment(Attachment.of(new File("report.zip"))),   // or Attachment.of("a.zip", bytes)
        ChannelConfig.smtp("smtp.example.com", 465)
                .ssl(true)
                .username("bot@example.com")
                .password("authorization code")
                .from("bot@example.com")
                .to("ops@example.com,boss@example.com")
                .cc("archive@example.com")
                .autoSplit(true)
                .maxAttachmentSize("10MB")
                .splitChunkSize("5MB"));

// DingTalk card / WeCom news and image
NotificationManager.send("dingtalk",
        Message.actionCard("Release", "v1.2.3 is live", "View", "https://ci.example.com/42"), dingtalkCfg);
NotificationManager.send("wecom",
        Message.news("Release", "Build succeeded", "https://ci.example.com/42", "https://example.com/cover.png"), wecomCfg);
NotificationManager.send("wecom", Message.image("Screenshot", pngBytes), wecomCfg);

// Send one alert to multiple channels
Map<String, ChannelConfig> targets = new LinkedHashMap<>();
targets.put("dingtalk", dingtalkCfg);
targets.put("wecom", wecomCfg);
List<SendResult> results = NotificationManager.sendAll(Message.text("Alert", "CPU 95%"), targets);

// Quiet hours / 5-minute dedup / local 20 messages per minute; failover across multiple accounts on the same channel
NotifyPolicy policy = NotifyPolicy.create()
        .quietHours("23:00", "07:00")
        .dedupWindowMs(5 * 60_000L)
        .rateLimit(20, 60_000L);
NotificationManager.send("dingtalk", msg, cfg, policy);
NotificationManager.sendFailover("dingtalk", msg,
        Arrays.asList(primaryCfg, backupCfg), policy);

// Any "POST JSON" endpoint (Slack style, self-built gateway)
NotificationManager.send("webhook", Message.text("Build failed", "job #42"),
        ChannelConfig.webhook("https://hooks.example.com/services/xxx")
                .payloadTemplate("{\"channel\":\"#ci\",\"text\":\"${title}: ${content}\"}")
                .header("Authorization", "Bearer token"));

// Slack (Incoming Webhook; channel via SlackChannel.EXTRA_CHANNEL; do not confuse with Bark's EXTRA_GROUP)
NotificationManager.send("slack",
        Message.text("Alert", "CPU 95%")
                .extra(SlackChannel.EXTRA_CHANNEL, "#ops")
                .extra(SlackChannel.EXTRA_COLOR, "danger")
                .extra(Message.EXTRA_URL, "https://dash.example.com/42"),
        ChannelConfig.webhook("https://hooks.slack.com/services/T000/B000/XXX"));

// Slack official API (chat.postMessage): token is the bot token starting with xoxb-
NotificationManager.send("slack", Message.text("hi", "content"),
        ChannelConfig.ofToken("xoxb-...").name("Release Bot"));

// Telegram Bot (chat_id configured in ChannelConfig.to, or overridden per message with TelegramChannel.EXTRA_CHAT_ID)
// MARKDOWN is converted to the Telegram HTML subset before sending: headings/bold → <b>, lists → • lines, tables → pipe-separated
NotificationManager.send("telegram", Message.markdown("Release", "## v1.2.3 is live\n- Build\n- Deploy"),
        ChannelConfig.ofToken("123456:ABC-xxx").to("@ops_channel"));
// Networks in mainland China can point webhookUrl to a self-hosted reverse proxy: .webhookUrl("https://tg.example.com")

// SMS: Alibaba Cloud (named parameters in code=min=5 style, see below)
NotificationManager.send("sms-aliyun", Message.text("Verification code SMS")
                .extra(AbstractSmsChannel.EXTRA_SMS_PARAMS, "code=9527,min=5"),
        ChannelConfig.ofToken("AccessKeyId")              // token = AccessKeyId
                .secret("AccessKeySecret")
                .name("SMS signature")                    // name = signature name
                .extra(AbstractSmsChannel.CFG_TEMPLATE, "SMS_123456789")
                .to("13800000001,13800000002"));

// SMS: Tencent Cloud (ordered parameters, following template {1}{2} order)
NotificationManager.send("sms-tencent", Message.text("Verification code SMS")
                .extra(AbstractSmsChannel.EXTRA_SMS_PARAMS, "9527,5"),
        ChannelConfig.ofToken("SecretId")
                .secret("SecretKey")
                .extra(AbstractSmsChannel.CFG_APP_ID, "1400006666")  // SdkAppId
                .name("SMS signature")
                .extra(AbstractSmsChannel.CFG_TEMPLATE, "1234567")
                .to("13800000001"));

// SMS: Yunpian (full-text SMS, not template + parameters; signature configured in the Yunpian console or written into the body)
NotificationManager.send("sms-yunpian", Message.text("【Signature】Your verification code is 9527"),
        ChannelConfig.ofToken("APIKEY").to("13800000001"));

// SMS: Huawei Cloud (webhook is the APP access address; appId is the sender channel number; ordered parameters)
NotificationManager.send("sms-huawei", Message.text("Verification code SMS")
                .extra(AbstractSmsChannel.EXTRA_SMS_PARAMS, "9527,5"),
        ChannelConfig.ofToken("AppKey")
                .secret("AppSecret")
                .extra(AbstractSmsChannel.CFG_APP_ID, "8823120512345")  // sender channel number
                .name("Signature name")
                .extra(AbstractSmsChannel.CFG_TEMPLATE, "12345678")
                .webhookUrl("https://smsapi.cn-north-4.myhuaweicloud.com:443/sms/batchSendSmsV1")
                .to("13800000001"));

// Async send (this module has its own independent daemon thread pool, 8 threads by default, not shared with HTTP/SSE; validation is done synchronously)
Future<SendResult> future = NotificationManager.sendAsync("dingtalk", msg, cfg);
```

## 3. Built-in Channel Overview

| id | Channel | Required config | Message types | Success criterion |
| --- | --- | --- | --- | --- |
| `dingtalk` | DingTalk bot | `webhook` (with access_token); also configure `secret` when signing is enabled | TEXT / MARKDOWN / ACTION_CARD / NEWS(feedCard) / IMAGE(public image) | HTTP 200 and `errcode==0` |
| `wecom` | WeCom bot | `webhook` | TEXT / MARKDOWN / MARKDOWN_V2 / NEWS / IMAGE | HTTP 200 and `errcode==0` |
| `feishu` | Feishu bot | `webhook`; also configure `secret` for signature verification (goes into the JSON request body) | TEXT / MARKDOWN | `code`/`StatusCode==0` |
| `serverchan` | ServerChan | `ofToken(SendKey)` or a full `webhook` address | TEXT / MARKDOWN | `code==0` |
| `bark` | Bark | `ofToken(device_key)`; `webhookUrl` can override with a self-hosted address | TEXT / MARKDOWN | `code==200` |
| `ntfy`（`jkit-notify-extra`） | ntfy push | full `webhook` address (path is the topic) or `to(topic)` (server address defaults to `https://ntfy.sh`); optional auth via `token(tk_...)`(Bearer) or `username`+`password`(Basic) | TEXT / MARKDOWN | HTTP 2xx |
| `webhook` | Generic Webhook | `webhook` | TEXT / MARKDOWN / HTML | HTTP 2xx |
| `slack`（`jkit-notify-extra`） | Slack | `webhook` (Incoming Webhook) or `ofToken(bot token)` (chat.postMessage) | TEXT / MARKDOWN / HTML | 2xx and (when JSON) `ok==true` |
| `telegram`（`jkit-notify-extra`） | Telegram Bot | `ofToken(bot token)` + `to(chat_id)` or message-level `TelegramChannel.EXTRA_CHAT_ID` | TEXT / MARKDOWN / HTML; MARKDOWN converted to HTML subset | 2xx and `ok==true` |
| `sms-aliyun`（`jkit-notify-extra`） | Alibaba Cloud SMS | `ofToken(AccessKeyId)` + `secret` + `name(signature)` + `extra(CFG_TEMPLATE)` + `to` | TEXT | `Code=="OK"` |
| `sms-tencent`（`jkit-notify-extra`） | Tencent Cloud SMS | `ofToken(SecretId)` + `secret` + `extra(CFG_APP_ID)` + `name(signature)` + `extra(CFG_TEMPLATE)` + `to` | TEXT | `SendStatusSet[0].Code=="Ok"` |
| `sms-yunpian`（`jkit-notify-extra`） | Yunpian SMS | `ofToken(APIKEY)` + `to` | TEXT | `code==0` |
| `sms-huawei`（`jkit-notify-extra`） | Huawei Cloud SMS | `ofToken(AppKey)` + `secret` + `extra(CFG_APP_ID)` + `extra(CFG_TEMPLATE)` + `webhook(access address)` + `to` | TEXT | `code=="000000"` |
| `smtp` | Email | `smtp(host, port)` + `username`/`password`/`to`; optional `cc`/`bcc`/`replyTo`/`autoSplit` | TEXT / HTML; MARKDOWN converted to HTML | `250` after DATA |

Note: Feishu MARKDOWN is converted into an interactive card (lark_md); SMTP MARKDOWN is converted to HTML. The @-mention rules, length limits, rate limits, and signature differences of each platform are covered in Section 5 — those are the easiest traps to fall into.

## 4a. ChannelConfig.extra (SMS and other extended configuration)

The core `ChannelConfig` no longer exposes SMS-specific fields (`template` / `appId` / `region`). SMS channels take them via `ChannelConfig.extra(key, value)`, with keys defined on `AbstractSmsChannel`:

| Key | Description |
| --- | --- |
| `AbstractSmsChannel.CFG_TEMPLATE` | Template ID (Alibaba Cloud TemplateCode / Tencent TemplateId / Huawei templateId) |
| `AbstractSmsChannel.CFG_APP_ID` | Tencent SdkAppId / Huawei sender channel number |
| `AbstractSmsChannel.CFG_REGION` | Region (Tencent defaults to `ap-guangzhou`; optional for Alibaba Cloud) |

The SMS **signature** still uses the generic `ChannelConfig.name(...)` (shared with the Slack bot display name; semantics are interpreted by each channel).

## 4. Message extras (channel parameters)

`Message.extra(k, v)` carries channel-specific parameters; each channel only reads the keys it recognizes. Core channel constants live on `Message`; extras for `jkit-notify-extra` channels live on their respective channel classes (e.g. `SlackChannel.EXTRA_COLOR`, `TelegramChannel.EXTRA_CHAT_ID`):

| Constant | Channel | Description |
| --- | --- | --- |
| `Message.EXTRA_AT_MOBILES` | DingTalk / WeCom | @ specific mobile numbers, comma-separated string or collection |
| `Message.EXTRA_AT_USERIDS` | DingTalk / WeCom | @ specific userids; WeCom MARKDOWN appends missing `<@userid>` entries to the body |
| `Message.EXTRA_AT_ALL` | DingTalk / WeCom | @ everyone |
| `Message.EXTRA_SOUND` / `EXTRA_GROUP` / `EXTRA_LEVEL` / `EXTRA_URL` | Bark; DingTalk actionCard uses `EXTRA_URL` | Ringtone / group / time-sensitivity / tap-through link |
| `SlackChannel.EXTRA_CHANNEL` / `EXTRA_USERNAME` / `EXTRA_COLOR` | Slack | Channel (#ops / C…; takes precedence over the temporarily compatible `Message.EXTRA_GROUP`) / bot display name / sidebar color |
| `NtfyChannel.EXTRA_TAGS` / `EXTRA_PRIORITY`; `Message.EXTRA_URL` | ntfy | Tags (emoji shortcodes, comma-separated or collection) / priority (1-5 or min/low/default/high/max/urgent) / tap-through link; `Message.EXTRA_GROUP` can override the topic per message |
| `TelegramChannel.EXTRA_CHAT_ID` / `EXTRA_SILENT` / `EXTRA_THREAD_ID` | Telegram | Chat target / silent send / topic ID |
| `AbstractSmsChannel.EXTRA_SMS_PARAMS` | SMS channels | Template parameters: ordered channels (Tencent/Huawei) take comma-separated values; named channels (Alibaba Cloud) take `name=value` pairs |
| `Message.EXTRA_BTN_TITLE` | DingTalk actionCard | Single-button label |
| `Message.EXTRA_PIC_URL` | DingTalk IMAGE / WeCom NEWS | Cover or public image URL |
| `Message.EXTRA_BASE64` / `EXTRA_MD5` | WeCom IMAGE | Filled automatically by `Message.image(title, bytes)` |

## 5. Platform Constraints and Pitfalls

These are behaviors of each platform that you will hit "if you don't read the docs". The module already implements accordingly; they are listed here to aid troubleshooting.

### DingTalk @mentions: the at array is only a declaration

On DingTalk, `at.atMobiles` alone will **not** trigger a highlighted notification — the @-mentioned mobile numbers must appear as **literal text** in the body, otherwise they silently fail. This module automatically appends missing `@number` entries to the end of the body; numbers already present are not duplicated:

```java
// Body doesn't contain the number → the channel automatically turns it into "Service anomaly @13800000000"
Message.text("Service anomaly").extra(Message.EXTRA_AT_MOBILES, "13800000000");

// Body already contains the number (including markdown decoration) → kept as-is, not duplicated
Message.markdown("Alert", "**13800000000** please handle").extra(Message.EXTRA_AT_MOBILES, "13800000000");
```

DingTalk matches by substring, so wrapping the number in markdown decoration like `**...**` still works.

### WeCom @mentions: TEXT and MARKDOWN behave differently

TEXT @-mentions go through the structured fields `mentioned_mobile_list` / `mentioned_list`; MARKDOWN **does not support these two fields at all** — you can only inline `<@userid>` in the body, and it requires userids rather than mobile numbers. Therefore this module ignores `EXTRA_AT_MOBILES` under MARKDOWN, but automatically appends `EXTRA_AT_USERIDS` to the body as `<@userid>`. Use TEXT when you need to @ mobile numbers.

### Message length limits are measured in bytes

A Chinese character takes 3 bytes, so estimating by character count will badly exceed limits; slicing directly by bytes splits Chinese characters in half, producing garbled text that platforms reject entirely. This module truncates against UTF-8 byte limits in a **character-boundary-safe** way (surrogate pairs are never split), appending the visible marker `...[content too long, truncated]` when over the limit:

| Channel | Limit | Constant |
| --- | --- | --- |
| DingTalk text / markdown | 20000 bytes | `DingTalkChannel.MAX_CONTENT_BYTES` |
| WeCom text | 2048 bytes | `WecomChannel.MAX_TEXT_BYTES` |
| WeCom markdown | 4096 bytes | `WecomChannel.MAX_MARKDOWN_BYTES` |
| ServerChan title / body | 32 **characters** / 32 KB | `ServerChanChannel.MAX_TITLE_CHARS` / `MAX_DESP_BYTES` |
| Feishu text / card | 20000 / 30000 bytes | `FeishuChannel.MAX_TEXT_BYTES` / `MAX_CARD_BYTES` |
| Bark body | 4096 bytes | `BarkChannel.MAX_BODY_BYTES` |
| ntfy message / title | 4096 / 512 bytes | `NtfyChannel.MAX_MESSAGE_BYTES` / `MAX_TITLE_BYTES` |

When truncating, DingTalk reserves bytes for `@number` entries not yet present, so an overlong body won't push the @-mentions beyond the limit. Custom channels can override `contentMaxBytes(Message)` to plug into the same truncation logic. Generic Webhook and SMTP have no limit.

### Rate limiting

Both DingTalk bots and WeCom group bots are limited to **20 messages/minute**; exceeding the limit on DingTalk gets the bot muted for about 10 minutes. Channels themselves remain stateless; use `NotifyPolicy` when you need local protection:

```java
NotifyPolicy policy = NotifyPolicy.create()
        .quietHours("23:00", "07:00")          // quiet hours spanning midnight
        .dedupWindowMs(5 * 60_000L)            // same channel + same title/body sent only once per 5 minutes
        .rateLimit(20, 60_000L);               // at most 20 messages per channel per minute
NotificationManager.send("dingtalk", msg, cfg, policy);
NotificationManager.sendFailover("dingtalk", msg, Arrays.asList(a, b), policy);
```

Dedup hits / quiet hours return `FailureType.SUPPRESSED` (no retry); local rate limiting returns `THROTTLED`. Platform-side rate limiting is still mapped by the channel. Dedup and rate limiting are **in-process**, memory-based implementations and are not shared across instances.

### Clock drift with DingTalk signing

Signing fails if the timestamp differs from DingTalk's servers by more than **1 hour**. Wrong container timezones or unsynced NTP are the number one cause of this error — check the host time first when troubleshooting. Also, if the security setting uses "custom keywords", the body must contain the keyword, otherwise you get `errcode 310000` — this error code covers three causes at once: signature mismatch, keyword miss, and IP whitelist rejection.

### Signing algorithms: DingTalk and Feishu are exact opposites

| | Timestamp unit | HMAC key | Data to sign |
| --- | --- | --- | --- |
| DingTalk | milliseconds | `secret` | `timestamp + "\n" + secret` |
| Feishu | **seconds** | `timestamp + "\n" + secret` | **empty string** |

Swapping the two styles guarantees signature failure. DingTalk's signature goes in the **URL query**; Feishu's `timestamp` / `sign` must go in the **JSON request body** (at the same level as `msg_type`) — putting them in the query is treated as unsigned. DingTalk's Base64 must additionally be URL-encoded (it can produce `+` `/` `=`).

## 6. SMTP Details and Field-Tested Experience with Chinese Mailboxes

- **Ports and encryption**: Chinese mailbox providers generally use **465 + `.ssl(true)`** (implicit SSL; port 465 uses SMTPS even if not explicitly enabled). Connecting in plaintext to 465 has the client waiting for `220` while the server waits for a TLS ClientHello — both sides wait until timeout. Use 587 with `.starttls(true)`; 25 is plaintext and most cloud providers already block it outbound.
- **Password is an authorization code**: every provider requires generating an authorization code separately on the web portal — it is not the login password. Note that authorization codes expire (e.g. Alibaba Cloud 180 days), and expiry manifests as "wrong password".
- **TLS protocol pinning**: `.sslProtocols("TLSv1.2")`. Major JDK versions change the default enabled protocol set, and handshake failures are only reported as a generic `SSLHandshakeException` — explicit pinning is the fastest way to rule this out.
- **Self-signed certificates**: for self-hosted enterprise gateways use `.trustAllCerts(true)` (skips certificate and hostname verification; do not enable for public mailbox providers).
- **Authentication**: AUTH LOGIN (username/password Base64). `from` defaults to `username`. `to("a@x.com,b@x.com")` is split into multiple recipients on comma/semicolon; `cc` / `bcc` / `replyTo` are available. Bcc goes through `RCPT TO` but does not appear in the MIME headers.
- **MIME**: `Date` and `Message-ID` are always included. Subject uses `=?UTF-8?B?...?=`, body is UTF-8 Base64 folded at 76 characters; the DATA phase does RFC 5321 dot-stuffing (a leading `.` is written as `..`). HTML is sent directly as `text/html`; MARKDOWN is converted to HTML via `NotifyUtils.markdownToHtml` before sending. The conversion covers headings, nested lists (code blocks/tables inside list items), GFM tables, CLI-style wide tables of the `----+----` form, indented fenced code blocks (``` / ~~~), links, bold, and more — it is not full CommonMark. DingTalk/WeCom/Feishu/ServerChan render markdown themselves and do not go through this conversion.
- **Attachments and splitting**: `Attachment.of(file)` only keeps the path and reads in chunks when sending/splitting — 100MB-scale files don't need to enter the heap in full. `Attachment.of(name, bytes)` is still an in-memory attachment. MIME types are detected automatically from extension and file header. With `.autoSplit(true)` enabled, a single attachment exceeding `maxAttachmentSize` (default 10 MB) is cut into `filename.partN` chunks of `splitChunkSize` (default 5 MB) and sent across multiple emails. Sizes accept `10MB`, `512KB`, `1.5G`, or a plain byte count. The body includes the SHA-256 and `cat` reassembly instructions.
- **Template variables**: `.var("host", "web-1")` or `.vars(map)`. `${host}` / `${cpu.value}` in the title and body are substituted before `send` / `sendAll` / `sendAsync`; missing keys become empty strings. The original `Message` is not modified.
- **Markdown preview**: when SMTP converts MARKDOWN to HTML it wraps it in a responsive document shell by default (viewport + mobile/desktop `@media`). If you only want a fragment, use `NotifyUtils.markdownToHtml`; for a full document use `NotifyUtils.markdownToDocument(md, true/false)`.

### Field-tested provider rates and pitfalls (from real-world records in this repository's historical projects)

| Provider | SMTP address | Field-tested / notes |
| --- | --- | --- |
| 163 | smtp.163.com | One send every 5 seconds, 30 consecutive sends without issues |
| Tianyi 189 | smtp.189.cn | One send every 5 seconds, 30 consecutive sends without issues; **when it reports a wrong password, log into the web portal, turn POP/IMAP off and back on** |
| Zoho | smtp.zoho.eu | 10 per minute |
| 2980 | smtp.2980.com | One send every 5 seconds, 20 consecutive sends without issues |
| Alibaba Cloud Enterprise Mail | smtp.mxhichina.com | Authorization code valid for 180 days; must be regenerated on expiry |
| Tencent Exmail | **hwsmtp**.exmail.qq.com | Note the `hwsmtp.` prefix, not `smtp.`. When AUTH succeeds but MAIL FROM reports `501 please log in to exmail.qq.com to change your password` (replies are often in GBK), log into the web portal and change the password first — almost guaranteed on new accounts; the authorization code may need regenerating |
| China Mobile 139 | smtp.139.com | Authorization code is 20 characters (not the usual 16) |

```java
// Recommended configuration for Chinese mailboxes
ChannelConfig.smtp("smtp.163.com", 465)
        .ssl(true)
        .sslProtocols("TLSv1.2")
        .username("bot@163.com")
        .password("authorization code")
        .to("ops@example.com")
        .timeoutMs(10000);
```

## 7. Extending with Custom Channels

```java
public class MySmsChannel implements NotificationChannel {
    @Override
    public String id() { return "mysms"; }
    @Override
    public String name() { return "Self-hosted SMS"; }
    @Override
    public boolean supports(MessageType type) { return type == MessageType.TEXT; }
    @Override
    public SendResult send(Message message, ChannelConfig config) {
        // Config validation (throw IllegalArgumentException for missing params — a programming error)
        // On network failure return SendResult.fail(id(), "reason"), do not throw
        ...
    }
}

// Option 1: register in code
NotificationManager.get().register(new MySmsChannel());

// Option 2: SPI (place META-INF/services/...NotificationChannel inside the optional/extra module jar)
//        containing the fully qualified implementation class names; core built-ins are registered by defaults(), so do not add them to the core SPI
//        A later registration with the same id overrides the earlier one
```

For "POST JSON"-style channels, it is recommended to extend `AbstractHttpChannel` and only implement the three template methods `buildUrl` / `buildPayload` / `isAccepted`; timeouts, custom headers, and exception fallback are handled uniformly by the skeleton. There are two additional optional template points: `classify(status, body)` maps platform error codes to `FailureType`, and `contentMaxBytes(message)` declares length limits.

## 8. Result and Error Conventions

`SendResult` provides `isSuccess() / isFailed()`, `status()` (HTTP status code; protocol code or 0 for non-HTTP channels), `response()`, `error()`, `elapsedMs()`, `failureType()`, `isRetryable()`.

### Failure classification: deciding whether to retry

A success/failure boolean alone is not enough to drive retries — treating rate limiting as a config error silently drops messages, and treating a config error as a network blip hammers the API pointlessly.

| `FailureType` | Meaning | Recommended action |
| --- | --- | --- |
| `RETRYABLE` | Network timeouts, connection resets, HTTP 5xx/408, SMTP 4xx | Retry with backoff, **plus random jitter** to avoid synchronized retries across instances |
| `THROTTLED` | Explicit platform rate limiting (HTTP 429, DingTalk 130101, WeCom 45009, Feishu 9499, SMTP 421/450/451/452) | Retry with a **longer** backoff; **do not** mark the credentials as unusable because of this |
| `CONFIG_ERROR` | Wrong address/key/token, missing parameters, DingTalk keyword miss, SMTP 535 auth failure | Retrying is pointless; requires manual config fixes |
| `PERMANENT` | Other permanent failures; conservative fallback when HTTP 200 returns an unrecognized business code | Do not retry |

```java
SendResult r = NotificationManager.send("dingtalk", msg, cfg);
if (r.isFailed() && r.isRetryable()) {
    long backoff = r.failureType() == FailureType.THROTTLED ? 60_000 : 2_000;
    // Retry after backoff; adding random jitter is recommended
}
```

Mapped platform error codes: DingTalk `130101`(rate limited) / `300001`(invalid token) / `310000`(signature or keyword); WeCom `45009`(over limit) / `-1`(system busy) / `93000`(invalid key); Feishu `9499`(rate limited) / `19021`(signature failure) / `19001`,`19003`(invalid parameters); ServerChan `40001`(invalid sendkey); Bark `400`(device key does not exist). Unlisted business error codes fall back to HTTP status code classification.

### Exceptions vs return values

- **Network/protocol/business failures** return `SendResult.fail` without throwing, which suits retries and aggregation.
- **Programming errors** throw `IllegalArgumentException` directly: unknown channel, `null` message or config, empty body, message type unsupported by the channel, missing required channel configuration. In the SMTP channel these errors are also **raised before opening the connection** — it will not connect to the server first and then fail.

## 9. Local Live-Send Probing (Optional)

Credentials and recipients/sample paths live in `~/jkit/application.yml` outside the repository (template: `jkit-notify/src/test/resources/jkit-application.yml.example`). **Never** commit real secrets to git.

**`mvn test` is always offline by default**: even if the yml already contains ServerChan / Telegram / DingTalk keys, nothing will hit real third parties. All live-send tests are uniformly gated:

1. Explicitly enable `-Djkit.notify.live=true` (canonical; off by default)
2. The corresponding credentials are complete (JUnit `Assume` skips when keys are missing, so CI won't fail over absent secrets)

If either condition is missing, tests are skipped and nothing is actually sent. `@Ignore` has been removed; having keys in the yml is not enough.

Relevant test cases: `ServerChanBarkChannelTest#serverChanSend`, `SlackTelegramChannelTest#telegramMarkdownParseMode2`, `LiveNotifyTest` (DingTalk/WeCom/Feishu/SMTP etc., config keys `dingtalk.*` / `wecom.*` / `feishu.*` / `smtp.*` / `live.recipientEmail` etc.). Ntfy / SMS currently have no live-send cases (mock only).

```bash
# Default: offline unit tests (no live sends even if ~/jkit/application.yml has keys)
mvn -pl jkit-notify,jkit-notify-extra test

# Manual live send: switch + yml credentials
mvn -pl jkit-notify,jkit-notify-extra test -Djkit.notify.live=true
# Or run only LiveNotifyTest
mvn -pl jkit-notify test -Dtest=LiveNotifyTest -Djkit.notify.live=true
```
