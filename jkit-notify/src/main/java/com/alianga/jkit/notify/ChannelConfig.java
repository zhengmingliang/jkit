package com.alianga.jkit.notify;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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
 *
 * @author 郑明亮
 * @since 2.0.1
 */
public final class ChannelConfig {
    private String webhook;
    private String smtpHost;
    private int smtpPort;
    private String secret;
    private String token;
    private String username;
    private String password;
    private String from;
    private List<String> to;
    private boolean ssl;
    private boolean starttls;
    private String sslProtocols;
    private boolean trustAllCerts;
    private int timeoutMs;
    private String payloadTemplate;
    private Map<String, String> headers;

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
     * 设置收件人邮箱（SMTP），可多次调用累加。
     *
     * @param to 收件人，支持逗号分隔多个
     * @return this
     */
    public ChannelConfig to(String... to) {
        if (this.to == null) {
            this.to = new ArrayList<String>();
        }
        for (String item : to) {
            if (item != null && !item.trim().isEmpty()) {
                this.to.add(item.trim());
            }
        }
        return this;
    }

    /**
     * 设置收件人邮箱列表（SMTP），整体覆盖之前设置的值。
     *
     * @param to 收件人列表
     * @return this
     */
    public ChannelConfig to(List<String> to) {
        this.to = to == null ? null : new ArrayList<String>(to);
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
     * @return 收件人列表（只读），未设置时为 {@code null}
     */
    public List<String> to() {
        return to == null ? null : Arrays.asList(to.toArray(new String[0]));
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
}
