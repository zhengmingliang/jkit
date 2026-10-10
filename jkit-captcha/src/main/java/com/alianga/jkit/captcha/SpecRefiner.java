package com.alianga.jkit.captcha;

import java.awt.AlphaComposite;
import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 当前两名的模板分很接近时，用 SpecCaptcha 的字号和透明度再比一次，并允许几个像素的平移。
 */
final class SpecRefiner {
    private final Map<Character, RawGlyph> masks = new HashMap<Character, RawGlyph>();

    char choose(RawGlyph glyph, List<TemplateBank.Scored> ranked) {
        int limit = Math.min(3, ranked.size());
        char bestLabel = ranked.get(0).label;
        double best = -1;
        for (int i = 0; i < limit; i++) {
            TemplateBank.Scored scored = ranked.get(i);
            if (ranked.get(0).score - scored.score > 0.08) {
                break;
            }
            double dice = shifted(glyph, mask(scored.label));
            if (dice > best) {
                best = dice;
                bestLabel = scored.label;
            }
        }
        return bestLabel;
    }

    private RawGlyph mask(char label) {
        synchronized (masks) {
            RawGlyph cached = masks.get(Character.valueOf(label));
            if (cached != null) {
                return cached;
            }
            RawGlyph rendered = render(label);
            masks.put(Character.valueOf(label), rendered);
            return rendered;
        }
    }

    private static RawGlyph render(char label) {
        int size = 64;
        BufferedImage image = new BufferedImage(size, size, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = image.createGraphics();
        graphics.setColor(Color.WHITE);
        graphics.fillRect(0, 0, size, size);
        graphics.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_OFF);
        graphics.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, 0.7f));
        graphics.setColor(new Color(50, 50, 50));
        graphics.setFont(new Font("Verdana", Font.BOLD | Font.ITALIC, 28));
        FontMetrics metrics = graphics.getFontMetrics();
        graphics.drawString(String.valueOf(label), 4, 4 + metrics.getAscent());
        graphics.dispose();
        int minX = size;
        int minY = size;
        int maxX = -1;
        int maxY = -1;
        int area = 0;
        byte[] pixels = new byte[size * size];
        for (int y = 0; y < size; y++) {
            for (int x = 0; x < size; x++) {
                if (RgbImage.luminance(image.getRGB(x, y)) >= 168) {
                    continue;
                }
                pixels[y * size + x] = 1;
                area++;
                if (x < minX) {
                    minX = x;
                }
                if (y < minY) {
                    minY = y;
                }
                if (x > maxX) {
                    maxX = x;
                }
                if (y > maxY) {
                    maxY = y;
                }
            }
        }
        if (maxX < minX) {
            return new RawGlyph(0, 0, 1, 1, new byte[] {0}, 0);
        }
        int width = maxX - minX + 1;
        int height = maxY - minY + 1;
        byte[] cropped = new byte[width * height];
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                cropped[y * width + x] = pixels[(minY + y) * size + minX + x];
            }
        }
        return new RawGlyph(0, 0, width, height, cropped, area);
    }

    private static double shifted(RawGlyph glyph, RawGlyph template) {
        double scale = Math.min(glyph.width / (double) template.width, glyph.height / (double) template.height);
        int targetWidth = Math.max(1, (int) Math.round(template.width * scale));
        int targetHeight = Math.max(1, (int) Math.round(template.height * scale));
        byte[] scaled = scale(template.pixels, template.width, template.height, targetWidth, targetHeight);
        double best = 0;
        for (int dy = -2; dy <= 2; dy++) {
            for (int dx = -2; dx <= 2; dx++) {
                double dice = placed(glyph, scaled, targetWidth, targetHeight, dx, dy);
                if (dice > best) {
                    best = dice;
                }
            }
        }
        return best;
    }

    private static byte[] scale(byte[] pixels, int width, int height, int targetWidth, int targetHeight) {
        byte[] scaled = new byte[targetWidth * targetHeight];
        for (int y = 0; y < targetHeight; y++) {
            int sourceY = Math.min(height - 1, (int) (y * (height / (double) targetHeight)));
            for (int x = 0; x < targetWidth; x++) {
                int sourceX = Math.min(width - 1, (int) (x * (width / (double) targetWidth)));
                scaled[y * targetWidth + x] = pixels[sourceY * width + sourceX];
            }
        }
        return scaled;
    }

    private static double placed(RawGlyph glyph, byte[] scaled, int scaledWidth, int scaledHeight, int dx, int dy) {
        int offsetX = (glyph.width - scaledWidth) / 2 + dx;
        int offsetY = (glyph.height - scaledHeight) / 2 + dy;
        int intersection = 0;
        int leftInk = glyph.area;
        int rightInk = 0;
        for (int y = 0; y < scaledHeight; y++) {
            int glyphY = offsetY + y;
            for (int x = 0; x < scaledWidth; x++) {
                if (scaled[y * scaledWidth + x] == 0) {
                    continue;
                }
                rightInk++;
                int glyphX = offsetX + x;
                if (glyphX < 0 || glyphY < 0 || glyphX >= glyph.width || glyphY >= glyph.height) {
                    continue;
                }
                if (glyph.pixels[glyphY * glyph.width + glyphX] != 0) {
                    intersection++;
                }
            }
        }
        int total = leftInk + rightInk;
        if (total == 0) {
            return 0;
        }
        return (2.0 * intersection) / total;
    }
}
