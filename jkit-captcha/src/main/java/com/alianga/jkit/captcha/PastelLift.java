package com.alianga.jkit.captcha;

import java.awt.image.BufferedImage;

/**
 * 浅色大图预处理。笔画和背景挤在一起，先按亮度的最小最大把每个通道拉开
 * 再走原链路。拉伸后必有暗通道，不会再次进入这里。
 */
final class PastelLift {
    private static final int MIN_WIDTH = 200;
    private static final int MIN_HEIGHT = 80;
    private static final int MIN_INK = 100;
    private static final int CHANNEL = 80;
    private static final int INK = 40;

    private PastelLift() {
    }

    /**
     * 宽高都够大、墨迹不少于 100、并且没有一个墨迹通道低于 80，才算浅色大图。
     * 饱和的政务图和 130×48 的彩色字都有深色通道，不会被拉开。
     */
    static boolean matches(BufferedImage image) {
        int width = image.getWidth();
        int height = image.getHeight();
        if (width < MIN_WIDTH || height < MIN_HEIGHT) {
            return false;
        }
        int[] argb = image.getRGB(0, 0, width, height, null, 0, width);
        int ink = 0;
        int limit = INK * INK;
        for (int i = 0; i < argb.length; i++) {
            int rgb = argb[i];
            int red = (rgb >> 16) & 255;
            int green = (rgb >> 8) & 255;
            int blue = rgb & 255;
            int dr = 255 - red;
            int dg = 255 - green;
            int db = 255 - blue;
            if (dr * dr + dg * dg + db * db < limit) {
                continue;
            }
            if (red < CHANNEL || green < CHANNEL || blue < CHANNEL) {
                return false;
            }
            ink++;
        }
        return ink >= MIN_INK;
    }

    /** 按亮度的最小最大把每个通道拉开。公式和测过 FGIP 的那次拉伸一致。 */
    static BufferedImage stretch(BufferedImage src) {
        int width = src.getWidth();
        int height = src.getHeight();
        int[] argb = src.getRGB(0, 0, width, height, null, 0, width);
        int min = 255;
        int max = 0;
        for (int i = 0; i < argb.length; i++) {
            int rgb = argb[i];
            int luminance = ((rgb >> 16) & 255) * 3 + ((rgb >> 8) & 255) * 6 + (rgb & 255);
            luminance /= 10;
            if (luminance < min) {
                min = luminance;
            }
            if (luminance > max) {
                max = luminance;
            }
        }
        int span = Math.max(1, max - min);
        int[] out = new int[argb.length];
        for (int i = 0; i < argb.length; i++) {
            int rgb = argb[i];
            int red = scaleChannel((rgb >> 16) & 255, min, span);
            int green = scaleChannel((rgb >> 8) & 255, min, span);
            int blue = scaleChannel(rgb & 255, min, span);
            out[i] = (red << 16) | (green << 8) | blue;
        }
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        image.setRGB(0, 0, width, height, out, 0, width);
        return image;
    }

    private static int scaleChannel(int value, int min, int span) {
        int scaled = (value - min) * 255 / span;
        if (scaled < 0) {
            return 0;
        }
        if (scaled > 255) {
            return 255;
        }
        return scaled;
    }
}
