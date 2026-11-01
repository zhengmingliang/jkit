package com.alianga.jkit.captcha.behavior;

import java.awt.Graphics2D;
import java.awt.image.BufferedImage;

/**
 * ARGB 像素缓冲。行为验证码的背景图按不透明 RGB 读取，模板条需要保留 alpha 通道，
 * 因此统一转成 TYPE_INT_ARGB 后取像素。
 */
final class ArgbImage {
    final int width;
    final int height;
    final int[] pixels;

    private ArgbImage(int width, int height, int[] pixels) {
        this.width = width;
        this.height = height;
        this.pixels = pixels;
    }

    static ArgbImage from(BufferedImage source) {
        BufferedImage image = source;
        if (source.getType() != BufferedImage.TYPE_INT_ARGB) {
            image = new BufferedImage(source.getWidth(), source.getHeight(), BufferedImage.TYPE_INT_ARGB);
            Graphics2D graphics = image.createGraphics();
            graphics.drawImage(source, 0, 0, null);
            graphics.dispose();
        }
        int width = image.getWidth();
        int height = image.getHeight();
        int[] pixels = image.getRGB(0, 0, width, height, null, 0, width);
        return new ArgbImage(width, height, pixels);
    }

    static ArgbImage of(int width, int height, int[] pixels) {
        return new ArgbImage(width, height, pixels);
    }

    int alpha(int index) {
        return (pixels[index] >>> 24) & 0xff;
    }

    static int luminance(int packed) {
        int red = (packed >> 16) & 0xff;
        int green = (packed >> 8) & 0xff;
        int blue = packed & 0xff;
        return (red * 299 + green * 587 + blue * 114) / 1000;
    }

    static int saturation(int packed) {
        int red = (packed >> 16) & 0xff;
        int green = (packed >> 8) & 0xff;
        int blue = packed & 0xff;
        int max = Math.max(red, Math.max(green, blue));
        int min = Math.min(red, Math.min(green, blue));
        return max - min;
    }
}
