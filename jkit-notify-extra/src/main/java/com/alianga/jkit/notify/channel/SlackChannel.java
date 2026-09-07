package com.alianga.jkit.notify.channel;

import com.alianga.jkit.notify.ChannelConfig;
import com.alianga.jkit.notify.FailureType;
import com.alianga.jkit.notify.Message;
import com.alianga.jkit.notify.MessageType;
import com.alianga.jkit.notify.NotifyUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Slack 渠道（Incoming Webhook / chat.postMessage）。
 *
 * <p>配置：{@link ChannelConfig#webhook(String)} 填 Incoming Webhook 地址
 * （形如 {@code https://hooks.slack.com/services/T00000000/B00000000/XXXX}），
 * 或 {@code ofToken(「T…/B…/XXX」)} + {@code webhookUrl("https://slack.com/api/chat.postMessage")}
 * 走官方 API（token 需有 {@code chat:write} 权限，以 {@code xoxb-} 开头）。
 * 可选 {@link ChannelConfig#name(String)} 覆盖机器人显示名，
 * {@link ChannelConfig#header(String, String)} 追加请求头（API 方式可配
 * {@code Authorization: Bearer xoxb-...}）。
 *
 * <p>消息：TEXT / MARKDOWN / HTML。MARKDOWN 用 Slack mrkdwn 原文发送；
 * 频道用 {@link Message#EXTRA_GROUP}（如 {@code #ops}，webhook 里已绑死频道时忽略）；
 * 机器人名用 {@link #EXTRA_USERNAME}（或配置 {@code name}）；
 * 消息气泡颜色用 {@link #EXTRA_COLOR}（good/warning/danger 或 #RRGGBB）；
 * 跳转链接用 {@link Message#EXTRA_URL}。正文按 UTF-8 {@value #MAX_TEXT_BYTES} 字节截断。
 *
 * <p>API 方式（chat.postMessage）按 JSON {@code ok == true} 判定；webhook 方式按 HTTP 2xx。
 *
 * @author 郑明亮
 * @since 2.0.1
 */
public class SlackChannel extends AbstractHttpChannel {
    /**
     * 渠道 id。
     */
    public static final String ID = "slack";

    /**
     * 机器人显示名（覆盖 webhook 绑定的默认名）。
     */
    public static final String EXTRA_USERNAME = "username";

    /**
     * 消息侧边条颜色：{@code good} / {@code warning} / {@code danger} 或 {@code #RRGGBB}。
     */
    public static final String EXTRA_COLOR = "color";

    /**
     * 消息正文 UTF-8 字节上限（Slack 官方约 40000 字符，按字节保守取 40000）。
     */
    public static final int MAX_TEXT_BYTES = 40000;

    private static final String API_ENDPOINT = "https://slack.com/api/chat.postMessage";

    @Override
    public String id() {
        return ID;
    }

    @Override
    public String name() {
        return "Slack";
    }

    @Override
    public boolean supports(MessageType type) {
        return type == MessageType.TEXT || type == MessageType.MARKDOWN || type == MessageType.HTML;
    }

    @Override
    protected String[] usedConfigKeys() {
        return new String[]{"webhook", "token", "name", "timeoutMs"};
    }

    @Override
    protected int contentMaxBytes(Message message) {
        return MAX_TEXT_BYTES;
    }

    @Override
    protected String buildUrl(Message message, ChannelConfig config) {
        String webhook = config.webhook();
        if (webhook != null && !webhook.trim().isEmpty()) {
            return webhook.trim();
        }
        String token = config.token();
        if (token == null || token.isEmpty()) {
            throw new IllegalArgumentException(
                    "slack webhook url is required (ChannelConfig.webhook) or bot token (ChannelConfig.token)");
        }
        // 只给了 bot token：走官方 API
        return API_ENDPOINT;
    }

    @Override
    protected String buildPayload(Message message, ChannelConfig config) {
        String text = message.title("通知") + "\n" + limitedContent(message);
        Map<String, Object> payload = NotifyUtils.map();
        String channel = message.extraString(Message.EXTRA_GROUP);
        if (channel != null && !channel.isEmpty()) {
            payload.put("channel", channel);
        }
        String username = firstNonEmpty(message.extraString(EXTRA_USERNAME), config.name());
        if (username != null && !username.isEmpty()) {
            payload.put("username", username);
        }
        payload.put("text", text);

        String url = message.extraString(Message.EXTRA_URL);
        String color = message.extraString(EXTRA_COLOR);
        if ((url != null && !url.isEmpty()) || (color != null && !color.isEmpty())) {
            // attachments 形式：带颜色侧边条与链接按钮
            Map<String, Object> attachment = NotifyUtils.map();
            if (color != null && !color.isEmpty()) {
                attachment.put("color", color);
            }
            List<Map<String, Object>> actions = new ArrayList<Map<String, Object>>();
            if (url != null && !url.isEmpty()) {
                Map<String, Object> action = NotifyUtils.map();
                action.put("type", "button");
                action.put("text", "Open");
                action.put("url", url);
                actions.add(action);
            }
            if (!actions.isEmpty()) {
                attachment.put("actions", actions);
            }
            List<Map<String, Object>> attachments = new ArrayList<Map<String, Object>>();
            attachments.add(attachment);
            payload.put("attachments", attachments);
        }

        // chat.postMessage 约定 token 放在 payload；Incoming Webhook 的凭证在 URL 里，
        // 不需要也不应该配置 token，因此配置了 token 即视为 API 方式
        String token = config.token();
        if (token != null && !token.isEmpty()) {
            payload.put("token", token);
        }
        return NotifyUtils.toJson(payload);
    }

    private static String firstNonEmpty(String a, String b) {
        if (a != null && !a.isEmpty()) {
            return a;
        }
        return b;
    }

    @Override
    protected boolean isAccepted(int httpStatus, String responseBody) {
        if (httpStatus < 200 || httpStatus >= 300) {
            return false;
        }
        // Incoming Webhook 返回纯文本 "ok"；chat.postMessage 返回 {"ok":true}
        Object parsed = NotifyUtils.parseJson(responseBody);
        if (parsed instanceof Map) {
            Object ok = ((Map<?, ?>) parsed).get("ok");
            return Boolean.TRUE.equals(ok) || "true".equals(String.valueOf(ok));
        }
        return true;
    }

    @Override
    protected FailureType classify(int httpStatus, String responseBody) {
        Object parsed = NotifyUtils.parseJson(responseBody);
        if (parsed instanceof Map) {
            Object error = ((Map<?, ?>) parsed).get("error");
            if (error != null) {
                String code = String.valueOf(error);
                if ("rate_limited".equals(code) || "ratelimited".equals(code)) {
                    return FailureType.THROTTLED;
                }
                if ("invalid_auth".equals(code) || "account_inactive".equals(code)
                        || "token_revoked".equals(code) || "channel_not_found".equals(code)
                        || "not_in_channel".equals(code) || "no_text".equals(code)
                        || "no_service_id".equals(code)) {
                    return FailureType.CONFIG_ERROR;
                }
            }
        }
        if (httpStatus == 429) {
            return FailureType.THROTTLED;
        }
        return super.classify(httpStatus, responseBody);
    }

    @Override
    protected String errorMessage(int httpStatus, String responseBody) {
        Object parsed = NotifyUtils.parseJson(responseBody);
        if (parsed instanceof Map) {
            Object error = ((Map<?, ?>) parsed).get("error");
            if (error != null) {
                return "slack error: " + error;
            }
        }
        if (httpStatus == 404 || httpStatus == 410) {
            return "slack webhook invalid or revoked (http " + httpStatus + ")";
        }
        return super.errorMessage(httpStatus, responseBody);
    }
}
