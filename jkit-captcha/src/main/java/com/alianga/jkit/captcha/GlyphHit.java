package com.alianga.jkit.captcha;

/**
 * 一个已识别字符及其在原图中的位置。
 */
public final class GlyphHit {
    private final char value;
    private final double confidence;
    private final int x;
    private final int y;
    private final int width;
    private final int height;

    GlyphHit(char value, double confidence, int x, int y, int width, int height) {
        this.value = value;
        this.confidence = confidence;
        this.x = x;
        this.y = y;
        this.width = width;
        this.height = height;
    }

    /**
     * 返回识别出的字符。
     *
     * @return 单个字符
     */
    public char getValue() {
        return value;
    }

    /**
     * 返回该字符的匹配置信度，范围大约 0 到 1。
     *
     * @return 置信度
     */
    public double getConfidence() {
        return confidence;
    }

    /**
     * 返回字形左边界，单位像素。
     *
     * @return x
     */
    public int getX() {
        return x;
    }

    /**
     * 返回字形上边界，单位像素。
     *
     * @return y
     */
    public int getY() {
        return y;
    }

    /**
     * 返回字形宽度，单位像素。
     *
     * @return 宽度
     */
    public int getWidth() {
        return width;
    }

    /**
     * 返回字形高度，单位像素。
     *
     * @return 高度
     */
    public int getHeight() {
        return height;
    }

    @Override
    public String toString() {
        return value + "@" + x + "," + y + " " + width + "x" + height
                + " " + String.format("%.2f", Double.valueOf(confidence));
    }
}
