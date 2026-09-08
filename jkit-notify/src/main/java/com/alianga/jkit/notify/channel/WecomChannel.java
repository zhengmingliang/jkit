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
 * 企业微信群机器人渠道。
 *
 * <p>配置：{@link ChannelConfig#webhook(String)} 填群机器人 webhook 地址
 * （{@code https://qyapi.weixin.qq.com/cgi-bin/webhook/send?key=xxx}），无需加签。
 *
 * <p>消息：TEXT / MARKDOWN / MARKDOWN_V2。正文按 UTF-8 字节上限自动截断——TEXT 为
 * {@value #MAX_TEXT_BYTES} 字节，MARKDOWN / MARKDOWN_V2 为 {@value #MAX_MARKDOWN_BYTES} 字节。
 *
 * <p><b>@人的类型差异</b>：TEXT 的 @ 走结构化字段 {@code mentioned_mobile_list} /
 * {@code mentioned_list}；而 MARKDOWN / MARKDOWN_V2 <b>不支持这两个字段</b>，只能在正文里内联
 * {@code <@userid>}。因此本渠道在富文本下忽略 {@link Message#EXTRA_AT_MOBILES}（手机号无法用于
 * markdown @），但会把 {@link Message#EXTRA_AT_USERIDS} 以 {@code <@userid>} 补进正文。
 *
 * <p>平台约束：群机器人 20 条/分钟。
 *
 * <p>响应判定：HTTP 200 且 {@code errcode == 0}。
 *
 * @author 郑明亮
 * @since 2.0.1
 */
public class WecomChannel extends AbstractHttpChannel {
    /**
     * 渠道 id。
     */
    public static final String ID = "wecom";

    /**
     * TEXT 正文 UTF-8 字节上限。
     */
    public static final int MAX_TEXT_BYTES = 2048;

    /**
     * MARKDOWN / MARKDOWN_V2 正文 UTF-8 字节上限。
     */
    public static final int MAX_MARKDOWN_BYTES = 4096;

    @Override
    public String id() {
        return ID;
    }

    @Override
    public String name() {
        return "企业微信机器人";
    }

    @Override
    public boolean supports(MessageType type) {
        return type == MessageType.TEXT || type == MessageType.MARKDOWN || type == MessageType.MARKDOWN_V2
                || type == MessageType.IMAGE || type == MessageType.NEWS;
    }

    @Override
    protected String[] usedConfigKeys() {
        return new String[]{"webhook", "timeoutMs"};
    }

    @Override
    protected int contentMaxBytes(Message message) {
        return message.type() == MessageType.TEXT ? MAX_TEXT_BYTES : MAX_MARKDOWN_BYTES;
    }

    @Override
    protected String buildUrl(Message message, ChannelConfig config) {
        String webhook = config.webhook();
        if (webhook == null || webhook.isEmpty()) {
            throw new IllegalArgumentException("wecom webhook is required (ChannelConfig.webhook)");
        }
        return webhook;
    }

    @Override
    protected String buildPayload(Message message, ChannelConfig config) {
        List<String> atUserIds = message.extraStrings(Message.EXTRA_AT_USERIDS);
        boolean markdown = message.type() != MessageType.TEXT;
        String content = markdown ? withInlineUserIds(message.content(), atUserIds) : limitedContent(message);

        Map<String, Object> root = NotifyUtils.map();
        if (message.type() == MessageType.IMAGE) {
            String base64 = message.extraString(Message.EXTRA_BASE64);
            String md5 = message.extraString(Message.EXTRA_MD5);
            if (base64 == null || base64.isEmpty() || md5 == null || md5.isEmpty()) {
                throw new IllegalArgumentException("wecom image requires base64 and md5 (Message.image)");
            }
            Map<String, Object> image = NotifyUtils.map();
            image.put("base64", base64);
            image.put("md5", md5);
            root.put("msgtype", "image");
            root.put("image", image);
            return NotifyUtils.toJson(root);
        }
        if (message.type() == MessageType.NEWS) {
            String jump = message.extraString(Message.EXTRA_URL);
            if (jump == null || jump.isEmpty()) {
                throw new IllegalArgumentException("wecom news url is required (Message.EXTRA_URL)");
            }
            Map<String, Object> article = NotifyUtils.map();
            article.put("title", message.title("通知"));
            article.put("description", message.content());
            article.put("url", jump);
            String pic = message.extraString(Message.EXTRA_PIC_URL);
            if (pic != null && !pic.isEmpty()) {
                article.put("picurl", pic);
            }
            List<Map<String, Object>> articles = new java.util.ArrayList<Map<String, Object>>();
            articles.add(article);
            Map<String, Object> news = NotifyUtils.map();
            news.put("articles", articles);
            root.put("msgtype", "news");
            root.put("news", news);
            return NotifyUtils.toJson(root);
        }
        if (message.type() == MessageType.MARKDOWN_V2) {
            Map<String, Object> body = NotifyUtils.map();
            body.put("content", content);
            root.put("msgtype", "markdown_v2");
            root.put("markdown_v2", body);
            return NotifyUtils.toJson(root);
        }
        if (message.type() == MessageType.MARKDOWN) {
            Map<String, Object> body = NotifyUtils.map();
            body.put("content", content);
            root.put("msgtype", "markdown");
            root.put("markdown", body);
            return NotifyUtils.toJson(root);
        }
        Map<String, Object> text = NotifyUtils.map();
        text.put("content", content);
        List<String> atMobiles = message.extraStrings(Message.EXTRA_AT_MOBILES);
        if (atMobiles != null && !atMobiles.isEmpty()) {
            text.put("mentioned_mobile_list", atMobiles);
        }
        List<String> mentioned = new ArrayList<String>();
        if (atUserIds != null) {
            mentioned.addAll(atUserIds);
        }
        if (message.extraBoolean(Message.EXTRA_AT_ALL, false)) {
            mentioned.add("@all");
        }
        if (!mentioned.isEmpty()) {
            text.put("mentioned_list", mentioned);
        }
        root.put("msgtype", "text");
        root.put("text", text);
        return NotifyUtils.toJson(root);
    }

    /**
     * MARKDOWN 下把尚未出现的 {@code <@userid>} 追加到正文，截断时预留后缀字节。
     *
     * @param content 原文
     * @param userIds userid 列表
     * @return 截断并补齐 @ 之后的正文
     */
    protected String withInlineUserIds(String content, List<String> userIds) {
        String suffix = missingUserIdSuffix(content, userIds);
        String truncated = limitedContent(content, MAX_MARKDOWN_BYTES, NotifyUtils.utf8Length(suffix));
        if (suffix.isEmpty()) {
            return truncated;
        }
        return truncated + suffix;
    }

    private static String missingUserIdSuffix(String content, List<String> userIds) {
        if (userIds == null || userIds.isEmpty()) {
            return "";
        }
        String haystack = content == null ? "" : content;
        StringBuilder suffix = new StringBuilder();
        for (String userId : userIds) {
            if (userId == null || userId.isEmpty()) {
                continue;
            }
            String token = "<@" + userId + ">";
            if (haystack.indexOf(token) < 0 && haystack.indexOf(userId) < 0) {
                suffix.append(' ').append(token);
            }
        }
        return suffix.toString();
    }

    @Override
    protected boolean isAccepted(int httpStatus, String responseBody) {
        return jsonIntEquals(responseBody, "errcode", 0);
    }

    @Override
    protected FailureType classify(int httpStatus, String responseBody) {
        Integer errcode = NotifyUtils.jsonInt(responseBody, "errcode");
        if (errcode != null) {
            switch (errcode) {
                case 45009:
                    // 接口调用超过限制
                    return FailureType.THROTTLED;
                case -1:
                    // 系统繁忙，稍后重试
                    return FailureType.RETRYABLE;
                case 93000:
                    // webhook key 非法
                    return FailureType.CONFIG_ERROR;
                default:
                    break;
            }
        }
        return super.classify(httpStatus, responseBody);
    }

    @Override
    protected String errorMessage(int httpStatus, String responseBody) {
        String mapped = jsonCodeMessage("wecom errcode", responseBody, "errcode", "errmsg");
        return mapped != null ? mapped : super.errorMessage(httpStatus, responseBody);
    }
}
