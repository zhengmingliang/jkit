package com.alianga.jkit.notify.channel;

import com.alianga.jkit.http.HttpRequest;
import com.alianga.jkit.notify.ChannelConfig;
import com.alianga.jkit.notify.FailureType;
import com.alianga.jkit.notify.Message;
import com.alianga.jkit.notify.MessageType;
import com.alianga.jkit.notify.NotifyUtils;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

/**
 * ntfy 渠道（{@code https://ntfy.sh} 或自建服务，HTTP JSON publish）。
 *
 * <p>配置（主题三选一，优先级从高到低）：
 * <ul>
 * <li>单条消息用 {@link Message#EXTRA_GROUP} 覆盖主题；</li>
 * <li>{@link ChannelConfig#to(String...)} 填主题名（多个时取第一个）；</li>
 * <li>{@link ChannelConfig#webhook(String)} 填完整发布地址，路径即主题，如
 * {@code https://ntfy.sh/mytopic} 或自建 {@code https://ntfy.example.com/mytopic}
 * （支持反向代理路径前缀，取路径末段为主题）。</li>
 * </ul>
 * webhook 未设置时服务地址缺省 {@code https://ntfy.sh}；主题来自 to()/EXTRA_GROUP 时
 * webhook 只填服务器根地址（自带路径会被剥掉）。
 *
 * <p>私有主题鉴权（可选）：{@link ChannelConfig#token(String)} 填 ntfy 访问令牌
 * （{@code tk_} 开头，请求头 {@code Authorization: Bearer ...}），或
 * {@link ChannelConfig#username(String)} + {@link ChannelConfig#password(String)}
 * 走 Basic 认证；两者都配时 Bearer 优先。
 *
 * <p>消息：TEXT / MARKDOWN（MARKDOWN 请求体带 {@code markdown: true}，ntfy v2.1+ 支持，
 * 旧服务端会忽略该字段）。标签 {@link #EXTRA_TAGS}（逗号分隔字符串或集合，
 * 支持 emoji 短代码）、优先级 {@link #EXTRA_PRIORITY}（{@code 1}~{@code 5} 或
 * min/low/default/high/max/urgent）、点击跳转 {@link Message#EXTRA_URL}。
 * 正文按 UTF-8 {@value #MAX_MESSAGE_BYTES} 字节截断，标题按 {@value #MAX_TITLE_BYTES} 字节截断。
 *
 * <p>响应判定：HTTP 2xx。错误响应 JSON 形如 {@code {"code":40301,"http":403,"error":"..."}}；
 * 鉴权失败（40101/40301）与主题非法（40401）归为 {@link FailureType#CONFIG_ERROR}，
 * 限流（42901/42902）归为 {@link FailureType#THROTTLED}。
 *
 * @author 郑明亮
 * @since 2.0.1
 */
public class NtfyChannel extends AbstractHttpChannel {
    /**
     * 渠道 id。
     */
    public static final String ID = "ntfy";

    /**
     * 消息标签（emoji 短代码或文本标签），值为逗号分隔字符串或集合，
     * 如 {@code "warning,rotating_light"}。
     */
    public static final String EXTRA_TAGS = "tags";

    /**
     * 消息优先级：{@code 1}~{@code 5} 或
     * {@code min} / {@code low} / {@code default} / {@code high} / {@code max} / {@code urgent}。
     */
    public static final String EXTRA_PRIORITY = "priority";

    /**
     * 正文 UTF-8 字节上限（ntfy 默认 message-size-limit 为 4096 字节）。
     */
    public static final int MAX_MESSAGE_BYTES = 4096;

    /**
     * 标题 UTF-8 字节上限。
     */
    public static final int MAX_TITLE_BYTES = 512;

    private static final String DEFAULT_ENDPOINT = "https://ntfy.sh";

    @Override
    public String id() {
        return ID;
    }

    @Override
    public String name() {
        return "ntfy";
    }

    @Override
    public boolean supports(MessageType type) {
        return type == MessageType.TEXT || type == MessageType.MARKDOWN;
    }

    @Override
    protected String[] usedConfigKeys() {
        return new String[]{"token", "webhook", "to", "username", "password", "timeoutMs"};
    }

    @Override
    protected int contentMaxBytes(Message message) {
        return MAX_MESSAGE_BYTES;
    }

    @Override
    protected String buildUrl(Message message, ChannelConfig config) {
        String topic = topicOf(message, config);
        String webhook = trimmed(config.webhook());
        String base = webhook == null ? DEFAULT_ENDPOINT : webhook;
        if (topicFromConfig(message, config)) {
            // 主题来自 to()/EXTRA_GROUP：webhook 视为服务器根地址，自带路径剥掉
            return serverRoot(base) + "/" + topic;
        }
        // 主题来自 webhook 路径：地址原样使用（保留反向代理路径前缀）
        return base;
    }

    @Override
    protected String buildPayload(Message message, ChannelConfig config) {
        Map<String, Object> payload = NotifyUtils.map();
        payload.put("topic", topicOf(message, config));
        payload.put("message", limitedContent(message));
        String title = message.title();
        if (title != null && !title.isEmpty()) {
            payload.put("title", NotifyUtils.truncateUtf8(title, MAX_TITLE_BYTES));
        }
        List<String> tags = message.extraStrings(EXTRA_TAGS);
        if (tags != null && !tags.isEmpty()) {
            payload.put("tags", tags);
        }
        String priority = message.extraString(EXTRA_PRIORITY);
        if (priority != null && !priority.isEmpty()) {
            payload.put("priority", priorityValue(priority));
        }
        String click = message.extraString(Message.EXTRA_URL);
        if (click != null && !click.isEmpty()) {
            payload.put("click", click);
        }
        if (message.type() == MessageType.MARKDOWN) {
            payload.put("markdown", Boolean.TRUE);
        }
        return NotifyUtils.toJson(payload);
    }

    @Override
    protected void applyHeaders(HttpRequest request, ChannelConfig config, String payload) {
        super.applyHeaders(request, config, payload);
        String token = trimmed(config.token());
        if (token != null) {
            request.header("Authorization", "Bearer " + token);
            return;
        }
        String username = trimmed(config.username());
        if (username != null) {
            String password = config.password() == null ? "" : config.password();
            String credentials = username + ":" + password;
            request.header("Authorization", "Basic "
                    + NotifyUtils.base64(credentials.getBytes(StandardCharsets.UTF_8)));
        }
    }

    @Override
    protected FailureType classify(int httpStatus, String responseBody) {
        Integer code = NotifyUtils.jsonInt(responseBody, "code");
        if (code != null) {
            if (code == 40101 || code == 40301 || code == 40401) {
                // 40101 未鉴权 / 40301 无权限 / 40401 主题非法
                return FailureType.CONFIG_ERROR;
            }
            if (code == 42901 || code == 42902) {
                // 访客请求超限 / 每日消息超限
                return FailureType.THROTTLED;
            }
        }
        return super.classify(httpStatus, responseBody);
    }

    @Override
    protected String errorMessage(int httpStatus, String responseBody) {
        String mapped = jsonCodeMessage("ntfy code", responseBody, "code", "error");
        return mapped != null ? mapped : super.errorMessage(httpStatus, responseBody);
    }

    /**
     * 解析主题，优先级：EXTRA_GROUP（消息级）→ to()（配置级）→ webhook 路径。
     *
     * @param message 消息
     * @param config 渠道配置
     * @return 主题名
     */
    private static String topicOf(Message message, ChannelConfig config) {
        String override = message.extraString(Message.EXTRA_GROUP);
        if (override != null && !override.trim().isEmpty()) {
            return override.trim();
        }
        List<String> topics = NotifyUtils.parseReceivers(config.to());
        if (!topics.isEmpty()) {
            return topics.get(0);
        }
        String webhook = trimmed(config.webhook());
        if (webhook != null) {
            String topic = topicFromWebhookPath(webhook);
            if (topic != null) {
                return topic;
            }
        }
        throw new IllegalArgumentException(
                "ntfy topic is required (webhook path / ChannelConfig.to / Message.EXTRA_GROUP)");
    }

    /**
     * @return 主题是否来自 to()/EXTRA_GROUP（而非 webhook 路径）
     */
    private static boolean topicFromConfig(Message message, ChannelConfig config) {
        String override = message.extraString(Message.EXTRA_GROUP);
        if (override != null && !override.trim().isEmpty()) {
            return true;
        }
        return !NotifyUtils.parseReceivers(config.to()).isEmpty();
    }

    /**
     * @return webhook 地址路径末段作为主题；无路径（纯服务器根地址）时为 {@code null}
     */
    private static String topicFromWebhookPath(String url) {
        int schemeEnd = url.indexOf("://");
        int pathStart = url.indexOf('/', schemeEnd < 0 ? 0 : schemeEnd + 3);
        if (pathStart < 0) {
            return null;
        }
        String path = url.substring(pathStart + 1);
        int query = path.indexOf('?');
        if (query >= 0) {
            path = path.substring(0, query);
        }
        path = path.trim();
        while (path.endsWith("/")) {
            path = path.substring(0, path.length() - 1);
        }
        if (path.isEmpty()) {
            return null;
        }
        int slash = path.lastIndexOf('/');
        return path.substring(slash + 1);
    }

    /**
     * @return 去掉路径后的服务器根地址（scheme://host[:port]）
     */
    private static String serverRoot(String url) {
        int schemeEnd = url.indexOf("://");
        int pathStart = url.indexOf('/', schemeEnd < 0 ? 0 : schemeEnd + 3);
        if (pathStart < 0) {
            return url;
        }
        return url.substring(0, pathStart);
    }

    private static Object priorityValue(String raw) {
        // ntfy 同时接受数字与名称（min/low/default/high/max/urgent）；纯数字转成 int
        if (raw.length() == 1 && raw.charAt(0) >= '1' && raw.charAt(0) <= '5') {
            return Integer.valueOf(raw);
        }
        return raw;
    }

    private static String trimmed(String value) {
        return value == null || value.trim().isEmpty() ? null : value.trim();
    }
}
