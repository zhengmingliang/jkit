package com.alianga.jkit.captcha;

/**
 * 识别参数。实例不可变，用 {@code withXxx} 派生新参数。
 */
public final class RecognizeOptions {
    private final CaptchaType type;
    private final String alphabet;
    private final Integer expectedLength;

    private RecognizeOptions(CaptchaType type, String alphabet, Integer expectedLength) {
        this.type = type;
        this.alphabet = alphabet;
        this.expectedLength = expectedLength;
    }

    /**
     * 自动判断验证码类型，字符集使用默认识别库。
     *
     * @return 默认参数
     */
    public static RecognizeOptions auto() {
        return new RecognizeOptions(CaptchaType.AUTO, null, null);
    }

    /**
     * 指定验证码类型。类型决定默认字符集：算术型只用数字和 {@code + - * / = ?}。
     *
     * @param type 验证码类型，不能为空
     * @return 新的参数
     */
    public RecognizeOptions withType(CaptchaType type) {
        if (type == null) {
            throw new IllegalArgumentException("type");
        }
        return new RecognizeOptions(type, alphabet, expectedLength);
    }

    /**
     * 限定候选字符。未设置时按类型使用内置字符集。
     *
     * @param alphabet 允许出现的字符，空字符串视为不限制
     * @return 新的参数
     */
    public RecognizeOptions withAlphabet(String alphabet) {
        return new RecognizeOptions(type, alphabet, expectedLength);
    }

    /**
     * 期望的字符个数。当前流水线只把它记录在参数里，分割仍按图像结构进行。
     *
     * @param expectedLength 字符个数，必须大于 0
     * @return 新的参数
     */
    public RecognizeOptions withExpectedLength(int expectedLength) {
        if (expectedLength <= 0) {
            throw new IllegalArgumentException("expectedLength");
        }
        return new RecognizeOptions(type, alphabet, Integer.valueOf(expectedLength));
    }

    /**
     * 返回验证码类型。
     *
     * @return 类型，默认 {@link CaptchaType#AUTO}
     */
    public CaptchaType getType() {
        return type;
    }

    /**
     * 返回调用方限定的字符集。
     *
     * @return 字符集；未设置时为 {@code null}
     */
    public String getAlphabet() {
        return alphabet;
    }

    /**
     * 返回期望字符个数。
     *
     * @return 个数；未设置时为 {@code null}
     */
    public Integer getExpectedLength() {
        return expectedLength;
    }
}
