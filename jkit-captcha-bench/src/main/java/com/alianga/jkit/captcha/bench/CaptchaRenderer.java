package com.alianga.jkit.captcha.bench;

import java.awt.AlphaComposite;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.Shape;
import java.awt.font.FontRenderContext;
import java.awt.font.GlyphVector;
import java.awt.geom.AffineTransform;
import java.awt.image.BufferedImage;
import java.util.Random;

/**
 * 按 {@link CaptchaStyle} 把一段文本画成验证码图。
 *
 * <p>绘制刻意覆盖真实验证码的常见干扰手段：字体、旋转、斜切、缩放、字距、正弦扭曲、
 * 描边、半透明、逐字变色、噪点、干扰线、背景纹理和明暗版面。每一张图的随机量全部来自
 * 传入的 {@link Random}，因此同一个种子总能重画出同一张图，便于复现失败样本。
 */
public final class CaptchaRenderer {
    private static final int[][] PALETTE = {
            {220, 40, 40}, {30, 140, 60}, {40, 90, 220}, {210, 140, 20},
            {150, 40, 170}, {25, 160, 160}, {110, 90, 60}, {230, 90, 30},
    };

    private CaptchaRenderer() {
    }

    /**
     * 画一张验证码。
     *
     * @param text  画面上的文本
     * @param style 绘制风格
     * @param width 画布宽度
     * @param height 画布高度
     * @param random 随机源，同一序列重画同一张图
     * @return 画好的图
     */
    public static BufferedImage render(String text, CaptchaStyle style, int width, int height, Random random) {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = image.createGraphics();
        try {
            graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            graphics.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
            paintSurface(graphics, style, width, height, random);
            paintTexture(graphics, style, width, height, random);
            paintLines(graphics, style, width, height, random);
            paintText(graphics, text, style, width, height, random);
            paintSpeckle(graphics, style, width, height, random);
        } finally {
            graphics.dispose();
        }
        if (style.getWave() > 0.01f) {
            wave(image, style, random);
        }
        return image;
    }

    private static void paintSurface(Graphics2D graphics, CaptchaStyle style, int width, int height,
                                     Random random) {
        switch (style.getSurface()) {
            case PLAIN_WHITE:
                graphics.setColor(Color.WHITE);
                graphics.fillRect(0, 0, width, height);
                break;
            case LIGHT_GRAY: {
                int base = 232 + random.nextInt(16);
                graphics.setColor(new Color(base, base, base));
                graphics.fillRect(0, 0, width, height);
                break;
            }
            case DARK: {
                int base = 22 + random.nextInt(28);
                graphics.setColor(new Color(base, base, base + random.nextInt(20)));
                graphics.fillRect(0, 0, width, height);
                break;
            }
            case GRADIENT: {
                int from = 210 + random.nextInt(40);
                int to = 130 + random.nextInt(60);
                for (int x = 0; x < width; x++) {
                    float ratio = width <= 1 ? 0f : (float) x / (float) (width - 1);
                    int level = Math.round(from + (to - from) * ratio);
                    graphics.setColor(new Color(level, level, level));
                    graphics.fillRect(x, 0, 1, height);
                }
                break;
            }
            default:
                graphics.setColor(Color.WHITE);
                graphics.fillRect(0, 0, width, height);
                break;
        }
    }

    private static void paintTexture(Graphics2D graphics, CaptchaStyle style, int width, int height,
                                     Random random) {
        switch (style.getTexture()) {
            case NOISE:
                paintNoise(graphics, style, width, height, random, 0.06f);
                break;
            case BLOBS: {
                int count = 6 + random.nextInt(10);
                for (int i = 0; i < count; i++) {
                    int level = 190 + random.nextInt(60);
                    graphics.setColor(new Color(level, level, level, 120));
                    int size = 6 + random.nextInt(22);
                    graphics.fillOval(random.nextInt(width), random.nextInt(height), size, size);
                }
                break;
            }
            case GRID: {
                graphics.setColor(new Color(210, 210, 210));
                for (int x = 0; x < width; x += 8 + random.nextInt(6)) {
                    graphics.drawLine(x, 0, x, height);
                }
                for (int y = 0; y < height; y += 8 + random.nextInt(6)) {
                    graphics.drawLine(0, y, width, y);
                }
                break;
            }
            case NONE:
            default:
                break;
        }
    }

    /** 字画完之后再撒一层，确保噪点同时压在笔画和背景上。 */
    private static void paintSpeckle(Graphics2D graphics, CaptchaStyle style, int width, int height,
                                     Random random) {
        if (style.getNoise() <= 0.001f) {
            return;
        }
        paintNoise(graphics, style, width, height, random, style.getNoise());
    }

    private static void paintNoise(Graphics2D graphics, CaptchaStyle style, int width, int height,
                                   Random random, float density) {
        boolean dark = style.getSurface() == CaptchaStyle.Surface.DARK;
        int count = (int) (width * height * density * 0.05f);
        for (int i = 0; i < count; i++) {
            int level = dark ? 150 + random.nextInt(105) : random.nextInt(150);
            graphics.setColor(new Color(level, level, level));
            graphics.fillRect(random.nextInt(width), random.nextInt(height), 1, 1);
        }
    }

    private static void paintLines(Graphics2D graphics, CaptchaStyle style, int width, int height,
                                   Random random) {
        int count;
        switch (style.getLines()) {
            case FEW:
                count = 2 + random.nextInt(3);
                break;
            case MANY:
                count = 7 + random.nextInt(7);
                break;
            case CURVE:
                count = 3 + random.nextInt(4);
                break;
            case NONE:
            default:
                return;
        }
        boolean dark = style.getSurface() == CaptchaStyle.Surface.DARK;
        for (int i = 0; i < count; i++) {
            int level = dark ? 120 + random.nextInt(110) : 60 + random.nextInt(130);
            graphics.setColor(new Color(level, level, level, 150 + random.nextInt(90)));
            graphics.setStroke(new BasicStroke(1f + random.nextFloat()));
            if (style.getLines() == CaptchaStyle.Lines.CURVE) {
                int y = random.nextInt(height);
                graphics.drawPolyline(curveX(width, random), curveY(height, y, random), 12);
            } else {
                graphics.drawLine(random.nextInt(width), random.nextInt(height),
                        random.nextInt(width), random.nextInt(height));
            }
        }
    }

    private static int[] curveX(int width, Random random) {
        int[] xs = new int[12];
        int step = width / (xs.length - 1);
        for (int i = 0; i < xs.length; i++) {
            xs[i] = Math.min(width - 1, Math.max(0, i * step + random.nextInt(5) - 2));
        }
        return xs;
    }

    private static int[] curveY(int height, int base, Random random) {
        int[] ys = new int[12];
        for (int i = 0; i < ys.length; i++) {
            ys[i] = Math.min(height - 1, Math.max(0, base + (int) (Math.sin(i * 0.7d) * 8) + random.nextInt(7) - 3));
        }
        return ys;
    }

    private static void paintText(Graphics2D graphics, String text, CaptchaStyle style, int width, int height,
                                  Random random) {
        Font font = new Font(style.getFontFamily(), style.getFontStyle(), Math.round(style.getFontSize()));
        FontMetrics metrics = graphics.getFontMetrics(font);
        int total = 0;
        int[] widths = new int[text.length()];
        for (int i = 0; i < text.length(); i++) {
            widths[i] = metrics.charWidth(text.charAt(i));
            total += widths[i];
        }
        float gap = style.getSpacing() * (metrics.getAscent() * 0.25f);
        float span = total + gap * Math.max(0, text.length() - 1);
        float left = Math.max(2f, (width - span) / 2f);
        float baseline = (height + metrics.getAscent() - metrics.getDescent()) / 2f;

        graphics.setFont(font);
        if (style.isAlphaBlend()) {
            graphics.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, 0.7f));
        }
        float cursor = left;
        for (int i = 0; i < text.length(); i++) {
            char ch = text.charAt(i);
            float charWidth = widths[i];
            float centerX = cursor + charWidth / 2f;
            float charBaseline = baseline;
            Graphics2D slot = (Graphics2D) graphics.create();
            try {
                AffineTransform shift = AffineTransform.getTranslateInstance(centerX, charBaseline);
                float angle = (random.nextFloat() * 2f - 1f) * style.getRotation();
                if (angle != 0f) {
                    shift.rotate(angle * Math.PI / 180d);
                }
                if (style.getShear() != 0f) {
                    shift.shear(style.getShear(), 0d);
                }
                if (style.getScaleX() != 1f) {
                    shift.scale(style.getScaleX(), 1d);
                }
                shift.translate(-centerX, -charBaseline);
                slot.setTransform(merge(graphics.getTransform(), shift));
                slot.setColor(pickColor(style, i, random));
                drawGlyph(slot, font, ch, centerX, charBaseline, style);
            } finally {
                slot.dispose();
            }
            cursor += charWidth + gap;
        }
        graphics.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, 1f));
    }

    private static void drawGlyph(Graphics2D graphics, Font font, char ch, float x, float y,
                                  CaptchaStyle style) {
        if (!style.isOutline()) {
            graphics.drawString(String.valueOf(ch), x, y);
            return;
        }
        FontRenderContext context = graphics.getFontRenderContext();
        GlyphVector vector = font.createGlyphVector(context, String.valueOf(ch));
        Shape shape = vector.getOutline(x, y);
        graphics.setStroke(new BasicStroke(2.2f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        graphics.draw(shape);
    }

    private static Color pickColor(CaptchaStyle style, int index, Random random) {
        if (!style.isColorPerChar()) {
            return style.getSurface() == CaptchaStyle.Surface.DARK
                    ? new Color(200 + random.nextInt(55), 200 + random.nextInt(55), 200 + random.nextInt(55))
                    : new Color(20 + random.nextInt(90), 20 + random.nextInt(90), 20 + random.nextInt(90));
        }
        int[] rgb = PALETTE[random.nextInt(PALETTE.length)];
        int jitter = random.nextInt(30) - 15;
        return new Color(clamp(rgb[0] + jitter), clamp(rgb[1] + jitter), clamp(rgb[2] + jitter));
    }

    private static int clamp(int value) {
        return Math.max(0, Math.min(255, value));
    }

    private static AffineTransform merge(AffineTransform base, AffineTransform local) {
        AffineTransform merged = new AffineTransform(base);
        merged.concatenate(local);
        return merged;
    }

    /**
     * 整幅按正弦做水平位移。与 jkit-captcha 里 {@code WaveStraightener} 的三种相位对齐，
     * 这样扭曲组里既有能被反向矫正的样本，也有模型需要自己扛的样本。
     */
    static void wave(BufferedImage image, CaptchaStyle style, Random random) {
        int width = image.getWidth();
        int height = image.getHeight();
        float amplitude = style.getWave();
        double phase = random.nextInt(4);
        BufferedImage copy = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        copy.createGraphics().drawImage(image, 0, 0, null);
        for (int y = 0; y < height; y++) {
            double offset = Math.sin((y / 6d) + phase) * amplitude;
            for (int x = 0; x < width; x++) {
                int from = (int) Math.round(x + offset);
                if (from < 0 || from >= width) {
                    image.setRGB(x, y, Color.WHITE.getRGB());
                    continue;
                }
                image.setRGB(x, y, copy.getRGB(from, y));
            }
        }
    }
}
