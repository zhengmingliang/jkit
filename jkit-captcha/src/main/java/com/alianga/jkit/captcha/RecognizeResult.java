package com.alianga.jkit.captcha;

import java.util.Collections;
import java.util.List;

/**
 * 一次识别的结果。
 */
public final class RecognizeResult {
    private final String text;
    private final String expression;
    private final Integer value;
    private final double confidence;
    private final CaptchaType type;
    private final long elapsedMillis;
    private final List<GlyphHit> glyphs;

    RecognizeResult(String text, String expression, Integer value, double confidence,
                    CaptchaType type, long elapsedMillis, List<GlyphHit> glyphs) {
        this.text = text;
        this.expression = expression;
        this.value = value;
        this.confidence = confidence;
        this.type = type;
        this.elapsedMillis = elapsedMillis;
        this.glyphs = glyphs;
    }

    /**
     * 返回从左到右拼出的原始字符串，例如 {@code 9050} 或 {@code 14+8=?}。
     *
     * @return 识别文本，没有字形时为空字符串
     */
    public String getText() {
        return text;
    }

    /**
     * 返回算术表达式本体，不含末尾的 {@code =?}。
     *
     * @return 表达式；不是算术题时为 {@code null}
     */
    public String getExpression() {
        return expression;
    }

    /**
     * 返回算术题的整数结果。
     *
     * @return 结果；不是可计算的算术题时为 {@code null}
     */
    public Integer getValue() {
        return value;
    }

    /**
     * 返回各字符置信度的平均值。
     *
     * @return 0 到 1 之间的置信度
     */
    public double getConfidence() {
        return confidence;
    }

    /**
     * 返回实际采用的验证码类型。自动模式下这里是判断后的具体类型。
     *
     * @return 类型
     */
    public CaptchaType getType() {
        return type;
    }

    /**
     * 返回这次识别的耗时。
     *
     * @return 毫秒
     */
    public long getElapsedMillis() {
        return elapsedMillis;
    }

    /**
     * 返回从左到右的字符列表。
     *
     * @return 不可变列表
     */
    public List<GlyphHit> getGlyphs() {
        return glyphs;
    }

    /**
     * 是否算出了算术结果。
     *
     * @return 有结果时为 true
     */
    public boolean isArithmetic() {
        return value != null;
    }

    /**
     * 返回适合日志的一行摘要。
     *
     * @return 摘要
     */
    public String summary() {
        StringBuilder builder = new StringBuilder();
        builder.append(type).append(" text=").append(text);
        if (value != null) {
            builder.append(" value=").append(value);
        }
        builder.append(" confidence=").append(String.format("%.2f", Double.valueOf(confidence)));
        builder.append(" elapsedMs=").append(elapsedMillis);
        return builder.toString();
    }

    @Override
    public String toString() {
        return summary();
    }

    static List<GlyphHit> freeze(List<GlyphHit> glyphs) {
        return Collections.unmodifiableList(glyphs);
    }
}
