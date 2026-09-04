package com.alianga.jkit.notify;

/**
 * 发送失败的类别，供调用方决定"该不该重试、怎么重试"。
 *
 * <p>只判断"成功/失败"不足以驱动重试：把限流当配置错误会白白丢消息，把配置错误当网络抖动
 * 会无意义地反复冲击接口。各渠道按平台错误码映射到本枚举，未识别的业务错误码保守归为
 * {@link #PERMANENT}（HTTP 已经 200、平台明确拒绝，重发同一份内容通常没有意义）。
 *
 * @author 郑明亮
 * @since 2.0.1
 */
public enum FailureType {
    /**
     * 未失败（发送成功时的取值）。
     */
    NONE,

    /**
     * 临时故障：网络超时、连接重置、HTTP 5xx、SMTP 4xx 等。
     *
     * <p>可按退避策略重试，建议加随机抖动避免多实例同步重试。
     */
    RETRYABLE,

    /**
     * 被限流：平台明确返回频率超限。
     *
     * <p>应当用**更长**的退避再试，且不要因此把这套凭证判定为不可用——
     * 限流是暂时的，把凭证拉黑会导致渠道长期不可用。
     */
    THROTTLED,

    /**
     * 配置或凭证错误：webhook 地址错、加签密钥不匹配、令牌失效、缺必填参数、
     * 钉钉自定义关键词未命中等。
     *
     * <p>重试无意义，需要人工改配置。
     */
    CONFIG_ERROR,

    /**
     * 其它永久失败：平台明确拒绝且重发同样内容不会成功（如内容违规、收件人不存在）。
     */
    PERMANENT;

    /**
     * 是否值得重试。
     *
     * @return {@link #RETRYABLE} 与 {@link #THROTTLED} 为 {@code true}，其余为 {@code false}
     */
    public boolean isRetryable() {
        return this == RETRYABLE || this == THROTTLED;
    }
}
