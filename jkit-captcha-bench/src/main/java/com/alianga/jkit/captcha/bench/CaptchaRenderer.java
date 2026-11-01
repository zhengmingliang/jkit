package com.alianga.jkit.captcha.bench;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.util.Random;

/**
 * 按 {@link CaptchaStyle} 把文本画成验证码。
 *
 * <p>画面跟 jkit-captcha 已经读得稳的版式对齐：浅色底板用固定高度、字符之间留出空隙、
 * 干扰线只画在上下边，不把字连成一块；深色算术板用色块画加减号和斜杠，乘号用
 * Verdana 的星号。旋转、噪点、纹理、字距、缩放仍然按风格轴变化，但幅度停在
 * 分割和大小写还能站住的范围内。
 */
public final class CaptchaRenderer {
    /** 浅色字母/数字板高度。避开 48 的划线擦除，也避开会被当成 Spec 的窄板。 */
    public static final int PLATE_HEIGHT = 47;
    /** 深色算术板高度，跟内置算术样本同一量级。 */
    public static final int ARITHMETIC_HEIGHT = 54;
    private static final int[][] PALETTE = {
            {255, 64, 64}, {48, 210, 90}, {64, 112, 255}, {255, 196, 48},
            {186, 96, 255}, {255, 140, 48}, {64, 210, 210}
    };

    private CaptchaRenderer() {
    }

    /**
     * 画一张验证码。宽高由调用方给定，绘制内容由文本和风格决定。
     *
     * @param text 画面上的文本。算术题带 {@code =?}
     * @param style 绘制风格
     * @param width 画布宽度
     * @param height 画布高度
     * @param random 随机源
     * @return 画好的图
     */
    public static BufferedImage render(String text, CaptchaStyle style, int width, int height, Random random) {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = image.createGraphics();
        try {
            graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_OFF);
            graphics.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_OFF);
            paintSurface(graphics, style, width, height);
            paintTexture(graphics, style, width, height, random);
            if (isArithmetic(text)) {
                paintArithmetic(graphics, text, style, random);
            } else {
                paintPlate(graphics, text, style, height);
            }
            paintLines(graphics, style, width, height);
            paintSpeckle(graphics, style, width, height, random);
        } finally {
            graphics.dispose();
        }
        return image;
    }

    static boolean isArithmetic(String text) {
        for (int i = 0; i < text.length(); i++) {
            char ch = text.charAt(i);
            if (ch == '+' || ch == '-' || ch == '*' || ch == '/') {
                return true;
            }
        }
        return false;
    }

    private static void paintSurface(Graphics2D graphics, CaptchaStyle style, int width, int height) {
        if (style.getSurface() == CaptchaStyle.Surface.DARK) {
            graphics.setColor(new Color(18, 18, 24));
        } else if (style.getSurface() == CaptchaStyle.Surface.LIGHT_GRAY) {
            graphics.setColor(new Color(236, 236, 236));
        } else {
            graphics.setColor(Color.WHITE);
        }
        graphics.fillRect(0, 0, width, height);
    }

    /**
     * 背景纹理用贴近底色的细格，不画成会被当成字符的斑块。
     * 和底色只差几个灰阶，人眼能看出格子，分割器不会把它收成笔画。
     */
    private static void paintTexture(Graphics2D graphics, CaptchaStyle style, int width, int height,
                                     Random random) {
        if (style.getTexture() == CaptchaStyle.Texture.NONE) {
            return;
        }
        boolean dark = style.getSurface() == CaptchaStyle.Surface.DARK;
        int level = dark ? 32 : 246;
        graphics.setColor(new Color(level, level, level));
        int step = 10 + random.nextInt(4);
        for (int x = 0; x < width; x += step) {
            graphics.drawLine(x, 0, x, height);
        }
        for (int y = 0; y < height; y += step) {
            graphics.drawLine(0, y, width, y);
        }
    }

    /** 干扰线只留在上下边缘，避免把相邻字符粘成一个连通域。 */
    private static void paintLines(Graphics2D graphics, CaptchaStyle style, int width, int height) {
        if (style.getLines() == CaptchaStyle.Lines.NONE) {
            return;
        }
        boolean dark = style.getSurface() == CaptchaStyle.Surface.DARK;
        graphics.setColor(dark ? new Color(70, 170, 190) : new Color(168, 168, 168));
        graphics.setStroke(new BasicStroke(1.2f));
        int yTop = 3;
        int yBottom = height - 4;
        graphics.drawLine(4, yTop, Math.min(width / 3, 80), yTop + 1);
        graphics.drawLine(width * 2 / 3, yBottom, width - 6, yBottom);
    }

    private static void paintSpeckle(Graphics2D graphics, CaptchaStyle style, int width, int height,
                                     Random random) {
        if (style.getNoise() <= 0.001f) {
            return;
        }
        boolean dark = style.getSurface() == CaptchaStyle.Surface.DARK;
        int count = (int) (width * height * style.getNoise() * 0.04f);
        for (int i = 0; i < count; i++) {
            int level = dark ? 150 + random.nextInt(80) : 70 + random.nextInt(60);
            graphics.setColor(new Color(level, level, level));
            graphics.fillRect(random.nextInt(width), random.nextInt(height), 1, 1);
        }
    }

    private static void paintPlate(Graphics2D graphics, String text, CaptchaStyle style, int height) {
        Font font = new Font(style.getFontFamily(), Font.BOLD, Math.round(style.getFontSize()));
        graphics.setFont(font);
        FontMetrics metrics = graphics.getFontMetrics();
        int gap = gap(style);
        int baseline = height - 12;
        int x = 12;
        for (int i = 0; i < text.length(); i++) {
            int tone = 20 + (i * 17) % 50;
            graphics.setColor(new Color(tone, 24, 42));
            drawChar(graphics, font, text.charAt(i), x, baseline, style, i);
            x += metrics.charWidth(text.charAt(i)) + gap;
        }
    }

    private static void paintArithmetic(Graphics2D graphics, String text, CaptchaStyle style, Random random) {
        Font font = new Font(style.getFontFamily(), Font.BOLD, Math.round(style.getFontSize()));
        graphics.setFont(font);
        FontMetrics metrics = graphics.getFontMetrics();
        int gap = gap(style);
        int x = 10;
        for (int i = 0; i < text.length(); i++) {
            char ch = text.charAt(i);
            int[] rgb = PALETTE[i % PALETTE.length];
            int jitter = random.nextInt(16) - 8;
            graphics.setColor(new Color(clamp(rgb[0] + jitter), clamp(rgb[1] + jitter), clamp(rgb[2] + jitter)));
            if (ch == '+') {
                graphics.fillRect(x + 1, 24, 18, 5);
                graphics.fillRect(x + 7, 16, 5, 20);
                x += 26;
            } else if (ch == '-') {
                graphics.fillRect(x, 24, 13, 5);
                x += 20;
            } else if (ch == '*') {
                Font star = new Font("Verdana", Font.BOLD, 32);
                graphics.setFont(star);
                graphics.drawString("*", x, 40);
                graphics.drawString("*", x + 1, 40);
                graphics.setFont(font);
                x += 22;
            } else if (ch == '/') {
                graphics.setStroke(new BasicStroke(4f));
                graphics.drawLine(x + 14, 14, x + 2, 40);
                x += 22;
            } else if (ch == '=') {
                graphics.fillRect(x, 18, 22, 4);
                graphics.fillRect(x, 26, 22, 4);
                x += 28;
            } else if (ch == '?') {
                graphics.drawString("?", x, 42);
                x += 16;
            } else {
                drawChar(graphics, font, ch, x, 42, style, i);
                x += (int) (metrics.charWidth(ch) * Math.max(0.9f, style.getScaleX())) + gap;
            }
        }
    }

    private static void drawChar(Graphics2D graphics, Font font, char ch, int x, int baseline,
                                 CaptchaStyle style, int index) {
        Graphics2D slot = (Graphics2D) graphics.create();
        try {
            slot.setFont(font);
            slot.translate(x, baseline);
            slot.scale(style.getScaleX(), 1d);
            if (style.getRotation() > 0.1f) {
                double degrees = index % 2 == 0 ? -style.getRotation() : style.getRotation();
                slot.rotate(Math.toRadians(degrees));
            }
            String token = String.valueOf(ch);
            slot.drawString(token, 0, 0);
            slot.drawString(token, 1, 0);
        } finally {
            slot.dispose();
        }
    }

    private static int gap(CaptchaStyle style) {
        return style.getSpacing() < 1.2f ? 14 : 22;
    }

    private static int clamp(int value) {
        return Math.max(0, Math.min(255, value));
    }
}
