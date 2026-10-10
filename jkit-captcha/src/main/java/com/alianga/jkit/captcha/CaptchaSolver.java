package com.alianga.jkit.captcha;

import java.awt.image.BufferedImage;

/**
 * 一种验证码的识别策略。默认实现覆盖字符型、干扰型和算术型。
 *
 * <p>新增验证码时实现本接口，再调用 {@link CaptchaRecognizer#register(CaptchaSolver)}。
 * 后注册的求解器排在前面，对同一类型优先生效。
 */
public interface CaptchaSolver {
    /**
     * 是否处理该类型。对 {@link CaptchaType#AUTO} 返回 true 表示由该求解器自己判断类型。
     *
     * @param type 调用方请求的类型
     * @return 能处理时为 true
     */
    boolean supports(CaptchaType type);

    /**
     * 识别一张验证码。
     *
     * @param image 验证码图像，不能为空
     * @param options 识别参数，不能为空
     * @return 识别结果
     */
    RecognizeResult recognize(BufferedImage image, RecognizeOptions options);
}
