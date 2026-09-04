package com.alianga.jkit.notify;

import com.alianga.jkit.log.Log;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 渠道配置：webhook 地址、令牌、账号等与业务无关的发送参数。
 *
 * <p>配置与 {@link Message} 分离，同一套渠道实例可服务于多套环境；
 * 链式设置，例如：
 * <pre>{@code
 * ChannelConfig cfg = ChannelConfig.webhook("https://oapi.dingtalk.com/robot/send?access_token=xxx")
 *         .secret("SEC...")
 *         .timeoutMs(5000);
 * ChannelConfig mail = ChannelConfig.smtp("smtp.example.com", 465)
 *         .ssl(true)
 *         .username("bot@example.com")
 *         .password("pwd")
 *         .from("bot@example.com")
 *         .to("ops@example.com");
 * }</pre>
 *
 * <p>各渠道需要哪些字段、哪些可缺省，见各渠道实现类的 javadoc。
 * 配了本渠道不认识的字段会被忽略，debug 日志会列出被忽略的键。
 *
 * @author 郑明亮
 * @since 2.0.1
 */
public final class ChannelConfig {
    /**
     * 自动拆分附件时的默认单封上限（10 MB）。
     */
    public static final long DEFAULT_MAX_ATTACHMENT_SIZE = 10L * 1024 * 1024;

    /**
     * 自动拆分附件时的默认分块大小（5 MB）。
     */
    public static final long DEFAULT_SPLIT_CHUNK_SIZE = 5L * 1024 * 1024;

    private String webhook;
    private String smtpHost;
    private int smtpPort;
    private String secret;
    private String token;
    private String username;
    private String password;
    private String from;
    private String fromName;
    private List<String> to;
    private List<String> cc;
    private List<String> bcc;
    private String replyTo;
    private boolean ssl;
    private boolean starttls;
    private String sslProtocols;
    private boolean trustAllCerts;
    private int timeoutMs;
    private String payloadTemplate;
    private Map<String, String> headers;
    private boolean autoSplit;
    private long maxAttachmentSize;
    private long splitChunkSize;

    private ChannelConfig() {
    }

    /**
     * 创建以 webhook 地址为核心的配置（钉钉/企微/飞书/通用 Webhook 等）。
     *
     * @param url webhook 地址
     * @return 配置
     */
    public static ChannelConfig webhook(String url) {
        ChannelConfig config = new ChannelConfig();
        config.webhook = url;
        return config;
    }

    /**
     * 创建以令牌为核心的配置（Server酱 SendKey、Bark DeviceKey 等只需一个令牌的渠道）。
     *
     * <p>这类渠道的服务地址有内置默认值，需要自建服务时再链式补
     * {@link #webhookUrl(String)} 覆盖。
     *
     * @param token 令牌
     * @return 配置
     */
    public static ChannelConfig ofToken(String token) {
        ChannelConfig config = new ChannelConfig();
        config.token = token;
        return config;
    }

    /**
     * 创建以 SMTP 服务器为核心的配置。
     *
     * @param host SMTP 服务器地址
     * @param port 端口，常见：25 明文 / 465 隐式 SSL / 587 STARTTLS
     * @return 配置
     */
    public static ChannelConfig smtp(String host, int port) {
        ChannelConfig config = new ChannelConfig();
        config.smtpHost = host;
        config.smtpPort = port;
        return config;
    }

    /**
     * 设置 webhook 地址（覆盖 {@link #webhook(String)}，或为 {@link #ofToken(String)}
     * 创建的配置指定自建服务地址）。
     *
     * @param url webhook 地址
     * @return this
     */
    public ChannelConfig webhookUrl(String url) {
        this.webhook = url;
        return this;
    }

    /**
     * 设置 SMTP 服务器地址（覆盖 {@link #smtp(String, int)} 的 host）。
     *
     * @param host SMTP 服务器地址
     * @return this
     */
    public ChannelConfig smtpHost(String host) {
        this.smtpHost = host;
        return this;
    }

    /**
     * 设置 SMTP 端口（覆盖 {@link #smtp(String, int)} 的 port）。
     *
     * @param port 端口
     * @return this
     */
    public ChannelConfig smtpPort(int port) {
        this.smtpPort = port;
        return this;
    }

    /**
     * 设置加签密钥（钉钉机器人加签 / 飞书机器人签名校验）。
     *
     * @param secret 密钥
     * @return this
     */
    public ChannelConfig secret(String secret) {
        this.secret = secret;
        return this;
    }

    /**
     * 设置令牌（Server酱 SendKey、Bark DeviceKey、自定义渠道 token 等）。
     *
     * @param token 令牌
     * @return this
     */
    public ChannelConfig token(String token) {
        this.token = token;
        return this;
    }

    /**
     * 设置账号（SMTP 登录用户名，通常是完整邮箱地址）。
     *
     * @param username 用户名
     * @return this
     */
    public ChannelConfig username(String username) {
        this.username = username;
        return this;
    }

    /**
     * 设置密码（SMTP 登录密码或授权码）。
     *
     * @param password 密码
     * @return this
     */
    public ChannelConfig password(String password) {
        this.password = password;
        return this;
    }

    /**
     * 设置发件人邮箱（SMTP）。
     *
     * @param from 发件人
     * @return this
     */
    public ChannelConfig from(String from) {
        this.from = from;
        return this;
    }

    /**
     * 设置发件人显示名（SMTP），出现在 {@code From: 显示名 <addr>}。
     *
     * @param fromName 显示名
     * @return this
     */
    public ChannelConfig fromName(String fromName) {
        this.fromName = fromName;
        return this;
    }

    /**
     * 设置收件人邮箱（SMTP），可多次调用累加。单个参数支持逗号或分号分隔多个地址。
     *
     * @param to 收件人，支持逗号/分号分隔多个
     * @return this
     */
    public ChannelConfig to(String... to) {
        this.to = addAddresses(this.to, to);
        return this;
    }

    /**
     * 设置收件人邮箱列表（SMTP），整体覆盖之前设置的值。
     *
     * @param to 收件人列表
     * @return this
     */
    public ChannelConfig to(List<String> to) {
        this.to = copyAddresses(to);
        return this;
    }

    /**
     * 设置抄送（SMTP），可多次调用累加。单个参数支持逗号或分号分隔多个地址。
     *
     * @param cc 抄送地址
     * @return this
     */
    public ChannelConfig cc(String... cc) {
        this.cc = addAddresses(this.cc, cc);
        return this;
    }

    /**
     * 设置抄送列表（SMTP），整体覆盖之前设置的值。
     *
     * @param cc 抄送列表
     * @return this
     */
    public ChannelConfig cc(List<String> cc) {
        this.cc = copyAddresses(cc);
        return this;
    }

    /**
     * 设置密送（SMTP），可多次调用累加。密送地址会走 {@code RCPT TO}，但不会出现在 MIME 头里。
     *
     * @param bcc 密送地址
     * @return this
     */
    public ChannelConfig bcc(String... bcc) {
        this.bcc = addAddresses(this.bcc, bcc);
        return this;
    }

    /**
     * 设置密送列表（SMTP），整体覆盖之前设置的值。
     *
     * @param bcc 密送列表
     * @return this
     */
    public ChannelConfig bcc(List<String> bcc) {
        this.bcc = copyAddresses(bcc);
        return this;
    }

    /**
     * 设置 Reply-To（SMTP）。
     *
     * @param replyTo 回复地址
     * @return this
     */
    public ChannelConfig replyTo(String replyTo) {
        this.replyTo = replyTo;
        return this;
    }

    /**
     * 是否使用隐式 SSL（SMTP 465 端口场景）。
     *
     * @param ssl {@code true} 表示连接即走 SSL
     * @return this
     */
    public ChannelConfig ssl(boolean ssl) {
        this.ssl = ssl;
        return this;
    }

    /**
     * 是否启用 STARTTLS 升级（SMTP 587 端口场景）。
     *
     * @param starttls {@code true} 表示明文连接后协商升级为 TLS
     * @return this
     */
    public ChannelConfig starttls(boolean starttls) {
        this.starttls = starttls;
        return this;
    }

    /**
     * 指定 TLS 握手使用的协议版本（SMTP），如 {@code TLSv1.2}；多个用逗号分隔。
     *
     * <p>不设置时用 JDK 默认启用的协议集。国内多家邮箱服务器只接受特定协议版本，
     * 而 JDK 大版本升级会调整默认启用集合，握手失败时报的又是笼统的
     * {@code SSLHandshakeException}——显式钉扎协议版本是排掉这类问题的最快手段。
     *
     * @param protocols 协议版本，如 {@code TLSv1.2}
     * @return this
     */
    public ChannelConfig sslProtocols(String protocols) {
        this.sslProtocols = protocols;
        return this;
    }

    /**
     * 是否信任所有证书、跳过主机名校验（SMTP）。
     *
     * <p>仅用于企业自建邮件网关用自签证书的场景。开启后无法防御中间人攻击，
     * 公网邮箱服务商不要开。
     *
     * @param trustAllCerts 是否信任所有证书
     * @return this
     */
    public ChannelConfig trustAllCerts(boolean trustAllCerts) {
        this.trustAllCerts = trustAllCerts;
        return this;
    }

    /**
     * 设置发送超时毫秒数，仅对本次发送生效；不设置时用 HTTP 客户端默认值。
     *
     * @param timeoutMs 超时毫秒数，非正数忽略
     * @return this
     */
    public ChannelConfig timeoutMs(int timeoutMs) {
        this.timeoutMs = timeoutMs;
        return this;
    }

    /**
     * 设置请求体模板（通用 Webhook 渠道使用），占位符形如 {@code ${title}}、{@code ${content}}。
     *
     * @param payloadTemplate 模板
     * @return this
     */
    public ChannelConfig payloadTemplate(String payloadTemplate) {
        this.payloadTemplate = payloadTemplate;
        return this;
    }

    /**
     * 追加一个自定义请求头（通用 Webhook 等渠道使用），同名键覆盖。
     *
     * @param name 头名称
     * @param value 头值
     * @return this
     */
    public ChannelConfig header(String name, String value) {
        if (this.headers == null) {
            this.headers = new LinkedHashMap<String, String>();
        }
        headers.put(name, value);
        return this;
    }

    /**
     * 超大附件是否自动拆成多封邮件（SMTP）。默认关闭。
     *
     * <p>开启后，超过 {@link #maxAttachmentSize(long)} 的单个附件会按
     * {@link #splitChunkSize(long)} 切成 {@code filename.partN} 分多封发送，
     * 正文附带 SHA-256 与拼接说明。
     *
     * @param autoSplit 是否自动拆分
     * @return this
     */
    public ChannelConfig autoSplit(boolean autoSplit) {
        this.autoSplit = autoSplit;
        return this;
    }

    /**
     * 自动拆分时的单附件上限（字节）。未设置或非正数时用 {@link #DEFAULT_MAX_ATTACHMENT_SIZE}。
     *
     * @param maxAttachmentSize 字节上限
     * @return this
     */
    public ChannelConfig maxAttachmentSize(long maxAttachmentSize) {
        this.maxAttachmentSize = maxAttachmentSize;
        return this;
    }

    /**
     * 自动拆分时的单附件上限，支持 {@code 10MB}、{@code 512KB}、{@code 1.5G} 等写法。
     *
     * @param spec 容量描述
     * @return this
     */
    public ChannelConfig maxAttachmentSize(String spec) {
        this.maxAttachmentSize = NotifyUtils.parseDataSize(spec);
        return this;
    }

    /**
     * 自动拆分时的分块大小（字节）。未设置或非正数时用 {@link #DEFAULT_SPLIT_CHUNK_SIZE}。
     *
     * @param splitChunkSize 分块字节数
     * @return this
     */
    public ChannelConfig splitChunkSize(long splitChunkSize) {
        this.splitChunkSize = splitChunkSize;
        return this;
    }

    /**
     * 自动拆分时的分块大小，支持 {@code 5MB}、{@code 512KB} 等写法。
     *
     * @param spec 容量描述
     * @return this
     */
    public ChannelConfig splitChunkSize(String spec) {
        this.splitChunkSize = NotifyUtils.parseDataSize(spec);
        return this;
    }

    /**
     * @return webhook 地址，未设置时为 {@code null}
     */
    public String webhook() {
        return webhook;
    }

    /**
     * @return SMTP 服务器地址，未设置时为 {@code null}
     */
    public String smtpHost() {
        return smtpHost;
    }

    /**
     * @return SMTP 端口，未设置时为 {@code 0}
     */
    public int smtpPort() {
        return smtpPort;
    }

    /**
     * @return 加签密钥，未设置时为 {@code null}
     */
    public String secret() {
        return secret;
    }

    /**
     * @return 令牌，未设置时为 {@code null}
     */
    public String token() {
        return token;
    }

    /**
     * @return SMTP 登录用户名，未设置时为 {@code null}
     */
    public String username() {
        return username;
    }

    /**
     * @return SMTP 登录密码，未设置时为 {@code null}
     */
    public String password() {
        return password;
    }

    /**
     * @return 发件人，未设置时为 {@code null}
     */
    public String from() {
        return from;
    }

    /**
     * @return 发件人显示名，未设置时为 {@code null}
     */
    public String fromName() {
        return fromName;
    }

    /**
     * @return 收件人列表（只读），未设置时为 {@code null}
     */
    public List<String> to() {
        return snapshot(to);
    }

    /**
     * @return 抄送列表（只读），未设置时为 {@code null}
     */
    public List<String> cc() {
        return snapshot(cc);
    }

    /**
     * @return 密送列表（只读），未设置时为 {@code null}
     */
    public List<String> bcc() {
        return snapshot(bcc);
    }

    /**
     * @return Reply-To，未设置时为 {@code null}
     */
    public String replyTo() {
        return replyTo;
    }

    /**
     * @return 是否隐式 SSL
     */
    public boolean ssl() {
        return ssl;
    }

    /**
     * @return 是否启用 STARTTLS
     */
    public boolean starttls() {
        return starttls;
    }

    /**
     * @return TLS 协议版本，未设置时为 {@code null}（用 JDK 默认）
     */
    public String sslProtocols() {
        return sslProtocols;
    }

    /**
     * @return 是否信任所有证书
     */
    public boolean trustAllCerts() {
        return trustAllCerts;
    }

    /**
     * @return 超时毫秒数，未设置时为 {@code 0}（用默认值）
     */
    public int timeoutMs() {
        return timeoutMs;
    }

    /**
     * @return 请求体模板，未设置时为 {@code null}
     */
    public String payloadTemplate() {
        return payloadTemplate;
    }

    /**
     * @return 自定义请求头（只读），未设置时为 {@code null}
     */
    public Map<String, String> headers() {
        return headers == null ? null : new LinkedHashMap<String, String>(headers);
    }

    /**
     * @return 是否自动拆分超大附件
     */
    public boolean autoSplit() {
        return autoSplit;
    }

    /**
     * @return 单附件上限；未设置时为 {@code 0}
     */
    public long maxAttachmentSize() {
        return maxAttachmentSize;
    }

    /**
     * 实际生效的单附件上限（未设置时回落到默认 10 MB）。
     *
     * @return 字节上限
     */
    public long resolvedMaxAttachmentSize() {
        return maxAttachmentSize > 0 ? maxAttachmentSize : DEFAULT_MAX_ATTACHMENT_SIZE;
    }

    /**
     * @return 分块大小；未设置时为 {@code 0}
     */
    public long splitChunkSize() {
        return splitChunkSize;
    }

    /**
     * 实际生效的分块大小（未设置时回落到默认 5 MB）。
     *
     * @return 分块字节数
     */
    public long resolvedSplitChunkSize() {
        return splitChunkSize > 0 ? splitChunkSize : DEFAULT_SPLIT_CHUNK_SIZE;
    }

    /**
     * 把本渠道不认识、但调用方已经设置的字段打到 debug 日志，避免配错渠道时完全静默。
     *
     * @param log 日志
     * @param channelId 渠道 id
     * @param usedKeys 本渠道会读取的字段名（如 {@code webhook}、{@code secret}）
     */
    public void logUnused(Log log, String channelId, String... usedKeys) {
        if (log == null || !log.isDebugEnabled()) {
            return;
        }
        Set<String> used = new LinkedHashSet<String>();
        if (usedKeys != null) {
            Collections.addAll(used, usedKeys);
        }
        List<String> unused = new ArrayList<String>();
        addIfUnused(unused, used, "webhook", webhook != null && !webhook.isEmpty());
        addIfUnused(unused, used, "secret", secret != null && !secret.isEmpty());
        addIfUnused(unused, used, "token", token != null && !token.isEmpty());
        addIfUnused(unused, used, "username", username != null && !username.isEmpty());
        addIfUnused(unused, used, "password", password != null && !password.isEmpty());
        addIfUnused(unused, used, "from", from != null && !from.isEmpty());
        addIfUnused(unused, used, "fromName", fromName != null && !fromName.isEmpty());
        addIfUnused(unused, used, "to", to != null && !to.isEmpty());
        addIfUnused(unused, used, "cc", cc != null && !cc.isEmpty());
        addIfUnused(unused, used, "bcc", bcc != null && !bcc.isEmpty());
        addIfUnused(unused, used, "replyTo", replyTo != null && !replyTo.isEmpty());
        addIfUnused(unused, used, "smtpHost", smtpHost != null && !smtpHost.isEmpty());
        addIfUnused(unused, used, "smtpPort", smtpPort > 0);
        addIfUnused(unused, used, "ssl", ssl);
        addIfUnused(unused, used, "starttls", starttls);
        addIfUnused(unused, used, "sslProtocols", sslProtocols != null && !sslProtocols.isEmpty());
        addIfUnused(unused, used, "trustAllCerts", trustAllCerts);
        addIfUnused(unused, used, "payloadTemplate", payloadTemplate != null && !payloadTemplate.isEmpty());
        addIfUnused(unused, used, "headers", headers != null && !headers.isEmpty());
        addIfUnused(unused, used, "autoSplit", autoSplit);
        addIfUnused(unused, used, "maxAttachmentSize", maxAttachmentSize > 0);
        addIfUnused(unused, used, "splitChunkSize", splitChunkSize > 0);
        if (!unused.isEmpty()) {
            log.debug("[{}] ignoring config fields: {}", channelId, unused);
        }
    }

    private static void addIfUnused(List<String> unused, Set<String> used, String key, boolean set) {
        if (set && !used.contains(key)) {
            unused.add(key);
        }
    }

    private static List<String> addAddresses(List<String> current, String... items) {
        List<String> target = current == null ? new ArrayList<String>() : current;
        if (items == null) {
            return target;
        }
        for (String item : items) {
            if (item == null) {
                continue;
            }
            for (String part : item.split("[,;]")) {
                String trimmed = part.trim();
                if (!trimmed.isEmpty()) {
                    target.add(trimmed);
                }
            }
        }
        return target;
    }

    private static List<String> copyAddresses(List<String> source) {
        if (source == null) {
            return null;
        }
        List<String> copy = new ArrayList<String>();
        for (String item : source) {
            if (item == null) {
                continue;
            }
            for (String part : item.split("[,;]")) {
                String trimmed = part.trim();
                if (!trimmed.isEmpty()) {
                    copy.add(trimmed);
                }
            }
        }
        return copy;
    }

    private static List<String> snapshot(List<String> source) {
        return source == null ? null : Arrays.asList(source.toArray(new String[0]));
    }
}
