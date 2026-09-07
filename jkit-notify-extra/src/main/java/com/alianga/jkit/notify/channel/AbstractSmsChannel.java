package com.alianga.jkit.notify.channel;

import com.alianga.jkit.notify.ChannelConfig;
import com.alianga.jkit.notify.Message;
import com.alianga.jkit.notify.MessageType;
import com.alianga.jkit.notify.NotifyUtils;
import com.alianga.jkit.notify.SendResult;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 模板短信渠道的公共骨架（参考 WePush 的短信发送器族）：
 * 收件人解析、模板参数解析、逐号码发送与结果聚合由本类统一处理；
 * 子类只需按"当前收件人"组 URL 与请求体——当前收件人经
 * {@link #currentReceiver(Message)} 从消息 extras 取。
 *
 * <p><b>收件人</b>：{@link ChannelConfig#to(String...)}，支持逗号 / 分号 / 空白分隔多个号码，
 * 逐号码各发一次；单号码直接返回该次结果，多号码任一成功即整体成功（与故障转移同语义），
 * 失败明细在 {@link SendResult#parts()}。
 *
 * <p><b>模板参数</b>（{@link #EXTRA_SMS_PARAMS}）按两类渠道区分：
 * <ul>
 * <li>有序参数（腾讯云 / 华为云）：逗号分隔字符串或集合，按模板里
 * {@code {1}{2}{3}} 出现顺序填入；</li>
 * <li>命名参数（阿里云）：逗号分隔的 {@code name=value}，如 {@code "code=9527,min=5"}。</li>
 * </ul>
 *
 * <p>子类实现 {@link #buildUrl} / {@link #buildPayload} 时用
 * {@link #currentReceiver(Message)} 拿当前号码；签名、超时、异常兜底、
 * 失败分类沿用 {@link AbstractHttpChannel}。
 *
 * @author 郑明亮
 * @since 2.0.1
 */
public abstract class AbstractSmsChannel extends AbstractHttpChannel {
    /**
     * 短信模板参数。值为：
     * <ul>
     * <li>有序参数渠道（腾讯云、华为云）：逗号分隔字符串、数组或集合，
     * 按模板里 {@code {1}{2}{3}} / {@code ${1}} 出现顺序填入；</li>
     * <li>命名参数渠道（阿里云）：逗号分隔的 {@code name=value}，如
     * {@code "code=9527,min=5"}。</li>
     * </ul>
     */
    public static final String EXTRA_SMS_PARAMS = "smsParams";

    /**
     * 逐号码发送时，当前收件人写入消息 extras 的键（内部使用）。
     */
    public static final String EXTRA_CURRENT_RECEIVER = "smsReceiver";

    /**
     * 短信只支持纯文本（模板 + 参数，没有富文本概念）。
     */
    @Override
    public boolean supports(MessageType type) {
        return type == MessageType.TEXT;
    }

    @Override
    public SendResult send(Message message, ChannelConfig config) {
        List<String> receivers = NotifyUtils.parseReceivers(config.to());
        if (receivers.isEmpty()) {
            throw new IllegalArgumentException(id() + " receiver is required (ChannelConfig.to)");
        }
        List<SendResult> parts = new ArrayList<SendResult>(receivers.size());
        for (String receiver : receivers) {
            parts.add(super.send(withReceiver(message, receiver), config));
        }
        if (parts.size() == 1) {
            return parts.get(0);
        }
        return SendResult.aggregate(id(), parts, true);
    }

    /**
     * @param message 消息
     * @return 当前正在发送的收件人号码
     */
    protected final String currentReceiver(Message message) {
        return message.extraString(EXTRA_CURRENT_RECEIVER);
    }

    private static Message withReceiver(Message message, String receiver) {
        return message.extra(EXTRA_CURRENT_RECEIVER, receiver);
    }

    /**
     * 解析有序模板参数（腾讯云 / 华为云风格）：逗号分隔字符串、数组或集合 → 参数值列表。
     *
     * @param message 消息
     * @return 参数值列表；未配置时为空列表
     */
    protected static List<String> positionalParams(Message message) {
        return NotifyUtils.parseReceivers(message.extraString(EXTRA_SMS_PARAMS));
    }

    /**
     * 解析命名模板参数（阿里云风格）：{@code "code=9527,min=5"} → {@code {"code":"9527","min":"5"}}。
     *
     * @param message 消息
     * @return 参数键值对；未配置时为空 Map
     */
    protected static Map<String, String> namedParams(Message message) {
        Map<String, String> params = NotifyUtils.strMap();
        String raw = message.extraString(EXTRA_SMS_PARAMS);
        if (raw == null || raw.isEmpty()) {
            return params;
        }
        for (String pair : raw.split(",")) {
            String item = pair.trim();
            if (item.isEmpty()) {
                continue;
            }
            int eq = item.indexOf('=');
            if (eq <= 0) {
                throw new IllegalArgumentException("sms params must be name=value pairs: " + item);
            }
            params.put(item.substring(0, eq).trim(), item.substring(eq + 1).trim());
        }
        return params;
    }

    /**
     * @return 模板 ID；未配置时抛编程错误
     */
    protected static String requiredTemplate(ChannelConfig config, String channelId) {
        String template = config.template();
        if (template == null || template.isEmpty()) {
            throw new IllegalArgumentException(
                    channelId + " template id is required (ChannelConfig.template)");
        }
        return template;
    }
}
