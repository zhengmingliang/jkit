package com.alianga.jkit.captcha;

import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.util.List;

/**
 * 高图上的大小写用字模和原图对一下。
 * 只改模型时间步能对上的字母，而且两种大小写要拉开差距才动。
 * 高度不到 49 的窄图（SpecCaptcha）和高于 72 的问卷网页不进这里。
 */
final class CaseVote {
    private static final double MARGIN = 0.12;

    private CaseVote() {
    }

    static String vote(BufferedImage image, String text, TextModel model) {
        if (image == null || text == null || text.length() < 2 || model == null
                || image.getHeight() < 49 || image.getHeight() > 72) {
            return text == null ? "" : text;
        }
        TextModel.Reading reading = model.readReading(image, TemplateBank.ALPHANUMERIC);
        if (reading == null || reading.steps <= 0 || reading.tokens == null
                || reading.tokens.size() != text.length()
                || !reading.text.equalsIgnoreCase(text)) {
            return text;
        }
        StringBuilder builder = new StringBuilder(text);
        List<TextModel.Token> tokens = reading.tokens;
        int slot = Math.max(8, image.getWidth() / text.length());
        for (int i = 0; i < text.length(); i++) {
            char ch = text.charAt(i);
            if (ch < 'A' || (ch > 'Z' && ch < 'a') || ch > 'z') {
                continue;
            }
            int center = tokens.get(i).time * image.getWidth() / reading.steps;
            BufferedImage crop = crop(image, center, slot);
            char upper = Character.toUpperCase(ch);
            char lower = Character.toLowerCase(ch);
            double up = likeness(crop, upper);
            double low = likeness(crop, lower);
            if (up >= low + MARGIN) {
                builder.setCharAt(i, upper);
            } else if (low >= up + MARGIN) {
                builder.setCharAt(i, lower);
            }
        }
        return builder.toString();
    }

    private static BufferedImage crop(BufferedImage image, int center, int slot) {
        int width = Math.min(image.getWidth(), slot);
        int left = center - width / 2;
        if (left < 0) {
            left = 0;
        }
        if (left + width > image.getWidth()) {
            left = image.getWidth() - width;
        }
        BufferedImage out = new BufferedImage(width, image.getHeight(), BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = out.createGraphics();
        graphics.drawImage(image, -left, 0, null);
        graphics.dispose();
        return out;
    }

    private static double likeness(BufferedImage crop, char ch) {
        byte[] left = ink(crop);
        byte[] right = render(ch, crop.getWidth(), crop.getHeight());
        int both = 0;
        int either = 0;
        for (int i = 0; i < left.length; i++) {
            if (left[i] != 0 && right[i] != 0) {
                both++;
            }
            if (left[i] != 0 || right[i] != 0) {
                either++;
            }
        }
        if (either == 0) {
            return 0;
        }
        return both / (double) either;
    }

    private static byte[] ink(BufferedImage image) {
        int background = border(image);
        byte[] mask = new byte[image.getWidth() * image.getHeight()];
        for (int y = 0; y < image.getHeight(); y++) {
            for (int x = 0; x < image.getWidth(); x++) {
                int packed = image.getRGB(x, y);
                int luminance = RgbImage.luminance(packed);
                boolean on = background >= 128 ? luminance < background - 30 : luminance > background + 30;
                if (on) {
                    mask[y * image.getWidth() + x] = 1;
                }
            }
        }
        return mask;
    }

    private static int border(BufferedImage image) {
        int width = image.getWidth();
        int height = image.getHeight();
        long sum = 0;
        int count = 0;
        for (int x = 0; x < width; x++) {
            sum += RgbImage.luminance(image.getRGB(x, 0));
            sum += RgbImage.luminance(image.getRGB(x, height - 1));
            count += 2;
        }
        return count == 0 ? 128 : (int) (sum / count);
    }

    private static byte[] render(char ch, int width, int height) {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = image.createGraphics();
        graphics.setColor(Color.WHITE);
        graphics.fillRect(0, 0, width, height);
        graphics.setColor(Color.BLACK);
        int size = Math.max(12, height * 2 / 3);
        graphics.setFont(new Font("Verdana", Font.BOLD, size));
        int x = Math.max(0, width / 8);
        int y = height * 3 / 4;
        graphics.drawString(String.valueOf(ch), x, y);
        graphics.dispose();
        return ink(image);
    }
}
