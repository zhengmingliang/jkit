package com.alianga.jkit.notify.channel;

import com.alianga.jkit.notify.ChannelConfig;
import com.alianga.jkit.notify.FailureType;
import com.alianga.jkit.notify.Message;
import com.alianga.jkit.notify.MessageType;
import com.alianga.jkit.notify.NotifyUtils;

import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * Server酱（Turbo 版）渠道，把消息推送到个人微信。
 *
 * <p>配置：{@link ChannelConfig#token(String)} 填 SendKey（{@code SCT...} 开头），
 * 或者直接 {@link ChannelConfig#webhook(String)} 填完整地址。
 *
 * <p>消息：TEXT / MARKDOWN。标题取消息标题（缺失时截取正文首行），正文放 {@code desp}
 * 字段，原生 markdown；Server酱要求 desp 至少 5 字节，不足时自动补空格。
 *
 * <p>响应判定：HTTP 2xx 且 JSON {@code code == 0}。
 *
 * @author 郑明亮
 * @since 2.0.1
 */
public class ServerChanChannel extends AbstractHttpChannel {
    /**
     * 渠道 id。
     */
    public static final String ID = "serverchan";

    /**
     * 标题字符数上限（Server酱按字符计）。
     */
    public static final int MAX_TITLE_CHARS = 32;

    /**
     * 正文 UTF-8 字节上限。
     */
    public static final int MAX_DESP_BYTES = 32 * 1024;

    private static final String DEFAULT_ENDPOINT = "https://sctapi.ftqq.com";

    @Override
    public String id() {
        return ID;
    }

    @Override
    public String name() {
        return "Server酱";
    }

    @Override
    public boolean supports(MessageType type) {
        return type == MessageType.TEXT || type == MessageType.MARKDOWN;
    }

    @Override
    protected String[] usedConfigKeys() {
        return new String[]{"token", "webhook", "timeoutMs"};
    }

    @Override
    protected String buildUrl(Message message, ChannelConfig config) {
        String sendKey = config.token();
        String base = config.webhook();
        if (base != null && !base.isEmpty()) {
            return base;
        }
        if (sendKey == null || sendKey.isEmpty()) {
            throw new IllegalArgumentException("serverchan sendkey is required (ChannelConfig.token)");
        }
        return DEFAULT_ENDPOINT + "/" + sendKey + ".send";
    }

    @Override
    protected String buildPayload(Message message, ChannelConfig config) {
        String title = message.title();
        if (title == null || title.isEmpty()) {
            String content = message.content();
            int lineBreak = content.indexOf('\n');
            title = lineBreak > 0 ? content.substring(0, lineBreak) : content;
        }
        // Server酱标题上限 32 字符（按字符计，非字节），超出会被拒
        if (title.length() > MAX_TITLE_CHARS) {
            title = title.substring(0, MAX_TITLE_CHARS);
        }
        String desp = limitedContent(message);
        if (desp.getBytes(StandardCharsets.UTF_8).length < 5) {
            desp = desp + "     ";
        }
        Map<String, Object> root = NotifyUtils.map();
        root.put("title", title);
        root.put("desp", desp);
        return NotifyUtils.toJson(root);
    }

    @Override
    protected int contentMaxBytes(Message message) {
        return MAX_DESP_BYTES;
    }

    @Override
    protected FailureType classify(int httpStatus, String responseBody) {
        if (jsonIntEquals(responseBody, "code", 40001)) {
            // sendkey 非法
            return FailureType.CONFIG_ERROR;
        }
        return super.classify(httpStatus, responseBody);
    }

    @Override
    protected boolean isAccepted(int httpStatus, String responseBody) {
        if (httpStatus < 200 || httpStatus >= 300) {
            return false;
        }
        return jsonIntEquals(responseBody, "code", 0);
    }

    @Override
    protected String errorMessage(int httpStatus, String responseBody) {
        String mapped = jsonCodeMessage("serverchan code", responseBody, "code", "message");
        return mapped != null ? mapped : super.errorMessage(httpStatus, responseBody);
    }
}
