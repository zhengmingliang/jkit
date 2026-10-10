package com.alianga.jkit.captcha;

/**
 * 把轻度正弦扭曲拉回近似原图。位移只试几组常见幅度，调用方选匹配置信度最高的一张。
 */
final class WaveStraightener {
    private WaveStraightener() {
    }

    static RgbImage undo(RgbImage source, int horizontal, int vertical) {
        int width = source.width;
        int height = source.height;
        int[] pixels = new int[source.pixels.length];
        for (int y = 0; y < height; y++) {
            int shiftX = (int) Math.round(horizontal * Math.sin(y / 6.0));
            for (int x = 0; x < width; x++) {
                int shiftY = (int) Math.round(vertical * Math.sin(x / 10.0));
                int sx = x - shiftX;
                int sy = y - shiftY;
                int packed = 0xffffff;
                if (sx >= 0 && sy >= 0 && sx < width && sy < height) {
                    packed = source.pixels[sy * width + sx];
                }
                pixels[y * width + x] = packed;
            }
        }
        return RgbImage.of(width, height, pixels);
    }
}
