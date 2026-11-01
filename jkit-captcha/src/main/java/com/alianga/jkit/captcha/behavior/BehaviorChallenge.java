package com.alianga.jkit.captcha.behavior;

import java.awt.image.BufferedImage;
import java.util.Collections;
import java.util.Map;

/**
 * 厂商无关的验证码挑战：{@link BehaviorCaptchaProtocol#fetch()} 的产出，
 * 解算层与轨迹层的输入。字段语义按滑块类约定，其他类型（旋转/点选）复用同一结构：
 * templateImage 为空时表示该类型需要厂商专属解算器。
 */
public final class BehaviorChallenge {
    /** 厂商标识（如 tianai / geetest / yidun）。 */
    private final String vendor;
    /** 验证码类型（如 SLIDER / ROTATE）。 */
    private final String type;
    /** 厂商会话标识（secretKey / challenge / token 等），提交时原样回传。 */
    private final String sessionId;
    /** 背景图（自然尺寸）。 */
    private final BufferedImage backgroundImage;
    /** 模板条 / 滑块图（自然尺寸，含 alpha；无则为 null）。 */
    private final BufferedImage templateImage;
    /** 背景图自然宽。 */
    private final int backgroundWidth;
    /** 背景图自然高。 */
    private final int backgroundHeight;
    /** 厂商特有扩展数据（shuffle、提示图等），供专属解算器按需读取。 */
    private final Map<String, Object> extra;

    public BehaviorChallenge(String vendor, String type, String sessionId,
                             BufferedImage backgroundImage, BufferedImage templateImage,
                             int backgroundWidth, int backgroundHeight, Map<String, Object> extra) {
        this.vendor = vendor;
        this.type = type;
        this.sessionId = sessionId;
        this.backgroundImage = backgroundImage;
        this.templateImage = templateImage;
        this.backgroundWidth = backgroundWidth;
        this.backgroundHeight = backgroundHeight;
        this.extra = extra == null ? Collections.emptyMap() : Collections.unmodifiableMap(extra);
    }

    public String getVendor() {
        return vendor;
    }

    public String getType() {
        return type;
    }

    public String getSessionId() {
        return sessionId;
    }

    public BufferedImage getBackgroundImage() {
        return backgroundImage;
    }

    public BufferedImage getTemplateImage() {
        return templateImage;
    }

    public int getBackgroundWidth() {
        return backgroundWidth;
    }

    public int getBackgroundHeight() {
        return backgroundHeight;
    }

    public Map<String, Object> getExtra() {
        return extra;
    }
}
