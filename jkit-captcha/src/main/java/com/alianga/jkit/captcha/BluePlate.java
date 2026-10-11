package com.alianga.jkit.captcha;

import java.awt.image.BufferedImage;
import java.util.Arrays;

/**
 * 深色底上的蓝色印刷字。模板切不开这种笔画，交给通用模型读一张对比度拉开的图。
 * 颜色用像素里存的 RGB，不把透明通道先合成到黑底上，避免淡蓝抗锯齿被抹掉。
 * 较宽的图（政务网一类四位大写）用 {@code common_old.onnx} 后把字母转成大写。
 * 较窄的图用 {@code common.onnx}，大小写留在模型结果里。
 */
final class BluePlate {
    private static final int MIN_HEIGHT = 70;
    private static final int WIDE = 260;
    private static final int MIN_BLUE = 400;

    private BluePlate() {
    }

    static boolean matches(BufferedImage image) {
        return image != null && eligible(image);
    }

    static String read(BufferedImage image, TextModel model, String alphabet) {
        if (!eligible(image)) {
            return null;
        }
        int width = image.getWidth();
        int height = image.getHeight();
        int[] argb = image.getRGB(0, 0, width, height, null, 0, width);
        boolean wide = width >= WIDE;
        BufferedImage plate = blackOnWhite(argb, width, height);
        String text;
        if (wide) {
            text = model.read(plate, alphabet);
        } else {
            text = model.readBeta(plate, alphabet);
            if (text.isEmpty()) {
                text = model.read(plate, alphabet);
            }
        }
        if (text.isEmpty()) {
            return null;
        }
        return wide ? upperLetters(text) : text;
    }

    private static boolean eligible(BufferedImage image) {
        int width = image.getWidth();
        int height = image.getHeight();
        if (height < MIN_HEIGHT || width <= 0) {
            return false;
        }
        int[] argb = image.getRGB(0, 0, width, height, null, 0, width);
        return borderMedian(argb, width, height) < 80 && countBlue(argb) >= MIN_BLUE;
    }

    private static int borderMedian(int[] argb, int width, int height) {
        int count = width * 2 + height * 2;
        int[] samples = new int[count];
        int index = 0;
        for (int x = 0; x < width; x++) {
            samples[index] = luminance(argb[x]);
            index++;
            samples[index] = luminance(argb[(height - 1) * width + x]);
            index++;
        }
        for (int y = 0; y < height; y++) {
            samples[index] = luminance(argb[y * width]);
            index++;
            samples[index] = luminance(argb[y * width + width - 1]);
            index++;
        }
        Arrays.sort(samples);
        return samples[samples.length / 2];
    }

    private static int luminance(int packed) {
        int red = (packed >> 16) & 0xff;
        int green = (packed >> 8) & 0xff;
        int blue = packed & 0xff;
        return (red * 299 + green * 587 + blue * 114) / 1000;
    }

    private static int countBlue(int[] argb) {
        int count = 0;
        for (int i = 0; i < argb.length; i++) {
            if (isBlue(argb[i])) {
                count++;
            }
        }
        return count;
    }

    private static boolean isBlue(int packed) {
        int red = (packed >> 16) & 0xff;
        int green = (packed >> 8) & 0xff;
        int blue = packed & 0xff;
        return blue > red + 25 && blue > green + 15 && blue > 40;
    }

    private static BufferedImage blackOnWhite(int[] argb, int width, int height) {
        int[] pixels = new int[argb.length];
        for (int i = 0; i < argb.length; i++) {
            pixels[i] = isBlue(argb[i]) ? 0 : 0xffffff;
        }
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        image.setRGB(0, 0, width, height, pixels, 0, width);
        return image;
    }

    private static String upperLetters(String text) {
        StringBuilder builder = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) {
            char value = text.charAt(i);
            if (value >= 'a' && value <= 'z') {
                builder.append((char) (value - 32));
            } else {
                builder.append(value);
            }
        }
        return builder.toString();
    }
}
