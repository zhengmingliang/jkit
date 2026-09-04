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
 * 飞书自定义机器人渠道。
 *
 * <p>配置：{@link ChannelConfig#webhook(String)} 填群机器人 webhook 地址；
 * 安全设置选"签名校验"时再 {@link ChannelConfig#secret(String)} 填密钥。
 *
 * <p>消息：TEXT / MARKDOWN。TEXT 走 {@code msg_type=text}；MARKDOWN 转
 * {@code msg_type=interactive} 卡片（lark_md），标题取消息标题。
 * 正文按 UTF-8 字节上限自动截断（TEXT {@value #MAX_TEXT_BYTES}，卡片 {@value #MAX_CARD_BYTES}）。
 *
 * <p><b>签名与钉钉恰好相反</b>：飞书把 {@code timestamp}/{@code sign} 放在 <b>JSON 请求体</b>
 * （与 {@code msg_type} 同级），用**秒**级时间戳；HMAC-SHA256 的**密钥**是
 * {@code timestamp + "\n" + secret} 而**待签数据为空串**。钉钉是 query 加签、毫秒时间戳、
 * 密钥为 secret 本身。两者写法互换必然签名失败。
 *
 * <p>响应判定：HTTP 200 且业务码为 0（兼容 {@code code} 与 {@code StatusCode} 两种字段名）。
 *
 * @author 郑明亮
 * @since 2.0.1
 */
public class FeishuChannel extends AbstractHttpChannel {
    /**
     * 渠道 id。
     */
    public static final String ID = "feishu";

    /**
     * TEXT 正文 UTF-8 字节上限。
     */
    public static final int MAX_TEXT_BYTES = 20000;

    /**
     * interactive 卡片正文 UTF-8 字节上限。
     */
    public static final int MAX_CARD_BYTES = 30000;

    @Override
    public String id() {
        return ID;
    }

    @Override
    public String name() {
        return "飞书机器人";
    }

    @Override
    public boolean supports(MessageType type) {
        return type == MessageType.TEXT || type == MessageType.MARKDOWN;
    }

    @Override
    protected String[] usedConfigKeys() {
        return new String[]{"webhook", "secret", "timeoutMs"};
    }

    @Override
    protected int contentMaxBytes(Message message) {
        return message.type() == MessageType.MARKDOWN ? MAX_CARD_BYTES : MAX_TEXT_BYTES;
    }

    @Override
    protected String buildUrl(Message message, ChannelConfig config) {
        String webhook = config.webhook();
        if (webhook == null || webhook.isEmpty()) {
            throw new IllegalArgumentException("feishu webhook is required (ChannelConfig.webhook)");
        }
        return webhook;
    }

    @Override
    protected String buildPayload(Message message, ChannelConfig config) {
        Map<String, Object> root = NotifyUtils.map();
        putSignature(root, config);
        String content = limitedContent(message);
        if (message.type() == MessageType.MARKDOWN) {
            Map<String, Object> title = NotifyUtils.map();
            title.put("tag", "plain_text");
            title.put("content", message.title("通知"));
            Map<String, Object> header = NotifyUtils.map();
            header.put("title", title);
            Map<String, Object> text = NotifyUtils.map();
            text.put("tag", "lark_md");
            text.put("content", content);
            Map<String, Object> div = NotifyUtils.map();
            div.put("tag", "div");
            div.put("text", text);
            List<Map<String, Object>> elements = new ArrayList<Map<String, Object>>();
            elements.add(div);
            Map<String, Object> card = NotifyUtils.map();
            card.put("header", header);
            card.put("elements", elements);
            root.put("msg_type", "interactive");
            root.put("card", card);
        } else {
            Map<String, Object> body = NotifyUtils.map();
            body.put("text", content);
            root.put("msg_type", "text");
            root.put("content", body);
        }
        return NotifyUtils.toJson(root);
    }

    /**
     * 飞书签名必须进 JSON 请求体，不能放 URL query。
     *
     * @param root 请求体
     * @param config 渠道配置
     */
    private static void putSignature(Map<String, Object> root, ChannelConfig config) {
        String secret = config.secret();
        if (secret == null || secret.isEmpty()) {
            return;
        }
        long timestamp = System.currentTimeMillis() / 1000L;
        String sign = NotifyUtils.base64(NotifyUtils.hmacSha256(timestamp + "\n" + secret, ""));
        root.put("timestamp", String.valueOf(timestamp));
        root.put("sign", sign);
    }

    @Override
    protected boolean isAccepted(int httpStatus, String responseBody) {
        Integer code = NotifyUtils.jsonInt(responseBody, "code");
        if (code == null) {
            code = NotifyUtils.jsonInt(responseBody, "StatusCode");
        }
        return code != null && code == 0;
    }

    @Override
    protected FailureType classify(int httpStatus, String responseBody) {
        Integer code = NotifyUtils.jsonInt(responseBody, "code");
        if (code == null) {
            code = NotifyUtils.jsonInt(responseBody, "StatusCode");
        }
        if (code != null) {
            switch (code) {
                case 9499:
                    // too many request
                    return FailureType.THROTTLED;
                case 19021:
                    // sign match fail
                    return FailureType.CONFIG_ERROR;
                case 19001:
                case 19003:
                    // webhook 地址 / 参数非法
                    return FailureType.CONFIG_ERROR;
                default:
                    break;
            }
        }
        return super.classify(httpStatus, responseBody);
    }

    @Override
    protected String errorMessage(int httpStatus, String responseBody) {
        Integer code = NotifyUtils.jsonInt(responseBody, "code");
        String msg = NotifyUtils.jsonString(responseBody, "msg");
        if (code == null) {
            code = NotifyUtils.jsonInt(responseBody, "StatusCode");
        }
        if (msg == null) {
            msg = NotifyUtils.jsonString(responseBody, "StatusMessage");
        }
        if (code != null && msg != null) {
            return "feishu code " + code + ": " + msg;
        }
        return super.errorMessage(httpStatus, responseBody);
    }
}
