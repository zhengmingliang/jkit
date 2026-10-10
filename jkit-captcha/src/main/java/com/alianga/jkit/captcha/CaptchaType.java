package com.alianga.jkit.captcha;

/**
 * 验证码类型。
 *
 * <p>识别器按类型选择字符集和预处理。{@link #AUTO} 会根据背景、干扰线和字形结构自行判断。
 */
public enum CaptchaType {
    /**
     * 自动判断类型。
     */
    AUTO,

    /**
     * 数字与大小写字母组合的字符型验证码。
     */
    ALPHANUMERIC,

    /**
     * 带扭曲、干扰线或噪点的字符型验证码，也包含空心描边字符。
     */
    DISTORTED,

    /**
     * 需要识别数字与运算符并计算结果的算术型验证码，例如 {@code 14+8=?}。
     */
    ARITHMETIC
}
