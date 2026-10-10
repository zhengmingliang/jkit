package com.alianga.jkit.captcha;

import java.awt.Graphics2D;
import java.awt.image.BufferedImage;

/**
 * 统一成 int RGB 的像素缓冲，供预处理读取。
 */
final class RgbImage {
    final int width;
    final int height;
    final int[] pixels;

    private RgbImage(int width, int height, int[] pixels) {
        this.width = width;
        this.height = height;
        this.pixels = pixels;
    }

    static RgbImage of(int width, int height, int[] pixels) {
        return new RgbImage(width, height, pixels);
    }

    static RgbImage from(BufferedImage source) {
        BufferedImage image = source;
        if (source.getType() != BufferedImage.TYPE_INT_RGB) {
            image = new BufferedImage(source.getWidth(), source.getHeight(), BufferedImage.TYPE_INT_RGB);
            Graphics2D graphics = image.createGraphics();
            graphics.drawImage(source, 0, 0, null);
            graphics.dispose();
        }
        int width = image.getWidth();
        int height = image.getHeight();
        int[] pixels = image.getRGB(0, 0, width, height, null, 0, width);
        return new RgbImage(width, height, pixels);
    }

    int red(int index) {
        return (pixels[index] >> 16) & 0xff;
    }

    int green(int index) {
        return (pixels[index] >> 8) & 0xff;
    }

    int blue(int index) {
        return pixels[index] & 0xff;
    }

    static int luminance(int packed) {
        int red = (packed >> 16) & 0xff;
        int green = (packed >> 8) & 0xff;
        int blue = packed & 0xff;
        return (red * 299 + green * 587 + blue * 114) / 1000;
    }

    static int saturation(int red, int green, int blue) {
        int max = Math.max(red, Math.max(green, blue));
        int min = Math.min(red, Math.min(green, blue));
        return max - min;
    }

    int borderMedianLuminance() {
        int count = width * 2 + height * 2;
        int[] samples = new int[count];
        int index = 0;
        for (int x = 0; x < width; x++) {
            samples[index] = luminance(pixels[x]);
            index++;
            samples[index] = luminance(pixels[(height - 1) * width + x]);
            index++;
        }
        for (int y = 0; y < height; y++) {
            samples[index] = luminance(pixels[y * width]);
            index++;
            samples[index] = luminance(pixels[y * width + width - 1]);
            index++;
        }
        java.util.Arrays.sort(samples);
        return samples[samples.length / 2];
    }
}
