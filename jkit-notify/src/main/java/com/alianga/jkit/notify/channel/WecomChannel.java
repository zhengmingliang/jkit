package com.alianga.jkit.notify.channel;

import com.alianga.jkit.notify.ChannelConfig;
import com.alianga.jkit.notify.FailureType;
import com.alianga.jkit.notify.Message;
import com.alianga.jkit.notify.MessageType;
import com.alianga.jkit.notify.NotifyUtils;

import java.util.List;

/**
 * 企业微信群机器人渠道。
 *
 * <p>配置：{@link ChannelConfig#webhook(String)} 填群机器人 webhook 地址
 * （{@code https://qyapi.weixin.qq.com/cgi-bin/webhook/send?key=xxx}），无需加签。
 *
 * <p>消息：TEXT / MARKDOWN。正文按 UTF-8 字节上限自动截断——TEXT 为
 * {@value #MAX_TEXT_BYTES} 字节，MARKDOWN 为 {@value #MAX_MARKDOWN_BYTES} 字节
 * （企微按字节计，一个中文 3 字节）。
 *
 * <p><b>@人的类型差异</b>：TEXT 的 @ 走结构化字段 {@code mentioned_mobile_list} /
 * {@code mentioned_list}；而 MARKDOWN <b>不支持这两个字段</b>，只能在正文里内联
 * {@code <@userid>}，且要求的是 userid 而非手机号。因此本渠道在 MARKDOWN 下忽略
 * {@link Message#EXTRA_AT_MOBILES}（手机号无法用于 markdown @），需要 @ 时请用 TEXT，
 * 或自行在 markdown 正文里写 {@code <@userid>}。
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
     * MARKDOWN 正文 UTF-8 字节上限。
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
        return type == MessageType.TEXT || type == MessageType.MARKDOWN;
    }

    @Override
    protected int contentMaxBytes(Message message) {
        return message.type() == MessageType.MARKDOWN ? MAX_MARKDOWN_BYTES : MAX_TEXT_BYTES;
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
        String escaped = NotifyUtils.jsonEscape(limitedContent(message));
        if (message.type() == MessageType.MARKDOWN) {
            // 企微 markdown 不支持 mentioned_* 字段，此处不拼 @，见类 javadoc
            return "{\"msgtype\":\"markdown\",\"markdown\":{\"content\":\"" + escaped + "\"}}";
        }
        List<String> atMobiles = message.extraStrings(Message.EXTRA_AT_MOBILES);
        boolean atAll = message.extraBoolean(Message.EXTRA_AT_ALL, false);
        StringBuilder text = new StringBuilder();
        text.append("{\"content\":\"").append(escaped).append('"');
        if (atMobiles != null && !atMobiles.isEmpty()) {
            text.append(",\"mentioned_mobile_list\":[");
            for (int i = 0; i < atMobiles.size(); i++) {
                if (i > 0) {
                    text.append(',');
                }
                text.append('"').append(NotifyUtils.jsonEscape(atMobiles.get(i))).append('"');
            }
            text.append(']');
        }
        if (atAll) {
            text.append(",\"mentioned_list\":[\"@all\"]");
        }
        text.append('}');
        return "{\"msgtype\":\"text\",\"text\":" + text + "}";
    }

    @Override
    protected boolean isAccepted(int httpStatus, String responseBody) {
        Integer errcode = NotifyUtils.jsonInt(responseBody, "errcode");
        return errcode != null && errcode == 0;
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
        Integer errcode = NotifyUtils.jsonInt(responseBody, "errcode");
        String errmsg = NotifyUtils.jsonString(responseBody, "errmsg");
        if (errcode != null && errmsg != null) {
            return "wecom errcode " + errcode + ": " + errmsg;
        }
        return super.errorMessage(httpStatus, responseBody);
    }
}
