package com.alianga.jkit.captcha;

/**
 * 分割后、识别前的一块前景。像素是裁切后的二值图，1 为墨水。
 */
final class RawGlyph {
    final int x;
    final int y;
    final int width;
    final int height;
    final byte[] pixels;
    final int area;
    /** 几何规则已经确定的字符，0 表示还要做模板匹配。 */
    char forced;

    RawGlyph(int x, int y, int width, int height, byte[] pixels, int area) {
        this.x = x;
        this.y = y;
        this.width = width;
        this.height = height;
        this.pixels = pixels;
        this.area = area;
        this.forced = 0;
    }

    double density() {
        return area / (double) (width * height);
    }

    int centerY() {
        return y + height / 2;
    }
}
