package com.alianga.jkit.captcha;

import java.awt.AlphaComposite;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.GraphicsEnvironment;
import java.awt.RenderingHints;
import java.awt.font.FontRenderContext;
import java.awt.font.TextLayout;
import java.awt.geom.AffineTransform;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 用系统字体渲染的字形模板。比对前把字符缩放到同一网格，分数是 Dice 系数。
 * {@link #learn(byte[], char)} 可以把真实样本补进模板库。
 */
final class TemplateBank {
    static final String ALPHANUMERIC = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz";
    /** 与 SpecCaptcha 一样去掉 0、1、O、I、l。 */
    static final String SPEC_SAFE = "23456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz";
    static final String ARITHMETIC = "0123456789+-=?*/xX";

    private final List<Template> templates = new ArrayList<Template>();
    private boolean built;

    synchronized void ensureBuilt() {
        if (built) {
            return;
        }
        String alphabet = ALPHANUMERIC + "+-=?*/xX";
        Set<String> families = new HashSet<String>(Arrays.asList(
                GraphicsEnvironment.getLocalGraphicsEnvironment().getAvailableFontFamilyNames()));
        addFamily(families, "Verdana", Font.BOLD, alphabet);
        addFamily(families, "Verdana", Font.BOLD | Font.ITALIC, alphabet);
        addBlended(families, "Verdana", Font.BOLD | Font.ITALIC, alphabet);
        addWarped(families, "Verdana", Font.BOLD | Font.ITALIC, TemplateBank.ALPHANUMERIC);
        addStroke(families, "Verdana", Font.BOLD, TemplateBank.ALPHANUMERIC);
        addSpecStyle(families, TemplateBank.ALPHANUMERIC);
        addSineStyle(families, TemplateBank.ALPHANUMERIC);
        addOutlineStyle(families, TemplateBank.ALPHANUMERIC);
        addFamily(families, "Arial", Font.BOLD, alphabet);
        addFamily(families, "Arial", Font.PLAIN, alphabet);
        addFamily(families, "Arial Black", Font.PLAIN, alphabet);
        addFamily(families, "DejaVu Sans", Font.BOLD, alphabet);
        addFamily(families, "Liberation Sans", Font.BOLD, alphabet);
        addFamily(families, "Times New Roman", Font.BOLD, alphabet);
        addFamily(families, "Dialog", Font.BOLD, alphabet);
        if (templates.isEmpty()) {
            addFamily(families, "SansSerif", Font.BOLD, alphabet);
        }
        built = true;
    }

    synchronized void learn(byte[] grid, char label) {
        ensureBuilt();
        templates.add(new Template(label, grid));
    }

    synchronized List<Scored> rank(byte[] grid, String alphabet, int limit) {
        return rank(grid, alphabet, limit, false);
    }

    synchronized List<Scored> rank(byte[] grid, String alphabet, int limit, boolean plainOnly) {
        return rank(grid, alphabet, limit, plainOnly, false);
    }

    /**
     * @param strokeOnly 为 true 时只和空心描边模板比，实心字不会走到这里
     */
    synchronized List<Scored> rank(byte[] grid, String alphabet, int limit, boolean plainOnly, boolean strokeOnly) {
        ensureBuilt();
        Map<Character, Double> best = new HashMap<Character, Double>();
        for (int i = 0; i < templates.size(); i++) {
            Template template = templates.get(i);
            if (strokeOnly != template.stroke) {
                continue;
            }
            if (plainOnly && !template.plain) {
                continue;
            }
            if (alphabet.indexOf(template.label) < 0) {
                continue;
            }
            double score = GlyphNormalizer.dice(grid, template.grid);
            Double previous = best.get(Character.valueOf(template.label));
            if (previous == null || score > previous.doubleValue()) {
                best.put(Character.valueOf(template.label), Double.valueOf(score));
            }
        }
        List<Scored> scored = new ArrayList<Scored>();
        for (Map.Entry<Character, Double> entry : best.entrySet()) {
            scored.add(new Scored(entry.getKey().charValue(), entry.getValue().doubleValue()));
        }
        Collections.sort(scored, new Comparator<Scored>() {
            @Override
            public int compare(Scored left, Scored right) {
                return Double.compare(right.score, left.score);
            }
        });
        if (scored.size() > limit) {
            return new ArrayList<Scored>(scored.subList(0, limit));
        }
        return scored;
    }

    private void addFamily(Set<String> families, String family, int style, String alphabet) {
        if (!families.contains(family) && !"Dialog".equals(family) && !"SansSerif".equals(family)) {
            return;
        }
        Set<Character> seen = new HashSet<Character>();
        Font font = new Font(family, style, 48);
        for (int i = 0; i < alphabet.length(); i++) {
            char label = alphabet.charAt(i);
            if (!seen.add(Character.valueOf(label))) {
                continue;
            }
            byte[] grid = render(font, label);
            int ink = GlyphNormalizer.ink(grid);
            if (ink < 8 || ink > GlyphNormalizer.WIDTH * GlyphNormalizer.HEIGHT * 0.82) {
                continue;
            }
            templates.add(new Template(label, grid));
        }
    }

    /**
     * 按 SpecCaptcha 的方式画：白底、0.7 透明度、亮度阈值。这种模板更接近 jkit 生成的字符。
     */
    private void addBlended(Set<String> families, String family, int style, String alphabet) {
        if (!families.contains(family) && !"Dialog".equals(family) && !"SansSerif".equals(family)) {
            return;
        }
        Font font = new Font(family, style, 48);
        for (int pass = 0; pass < 2; pass++) {
            boolean antialias = pass == 1;
            for (int i = 0; i < alphabet.length(); i++) {
                char label = alphabet.charAt(i);
                byte[] grid = renderBlended(font, label, antialias);
                int ink = GlyphNormalizer.ink(grid);
                if (ink < 8 || ink > GlyphNormalizer.WIDTH * GlyphNormalizer.HEIGHT * 0.82) {
                    continue;
                }
                templates.add(new Template(label, grid));
            }
        }
    }

    private static byte[] renderBlended(Font font, char label, boolean antialias) {
        int size = 96;
        BufferedImage image = new BufferedImage(size, size, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = image.createGraphics();
        graphics.setColor(Color.WHITE);
        graphics.fillRect(0, 0, size, size);
        Object hint = antialias ? RenderingHints.VALUE_TEXT_ANTIALIAS_ON : RenderingHints.VALUE_TEXT_ANTIALIAS_OFF;
        graphics.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, hint);
        graphics.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, 0.7f));
        graphics.setColor(new Color(70, 80, 90));
        graphics.setFont(font);
        FontMetrics metrics = graphics.getFontMetrics();
        graphics.drawString(String.valueOf(label), 4, 6 + metrics.getAscent());
        graphics.dispose();
        byte[] mask = new byte[size * size];
        for (int y = 0; y < size; y++) {
            for (int x = 0; x < size; x++) {
                if (RgbImage.luminance(image.getRGB(x, y)) < 165) {
                    mask[y * size + x] = 1;
                }
            }
        }
        return GlyphNormalizer.toGrid(mask, size, size);
    }

    /**
     * 正弦位移后的斜体模板，用来贴近轻度扭曲的字符。
     */
    private void addWarped(Set<String> families, String family, int style, String alphabet) {
        if (!families.contains(family)) {
            return;
        }
        Font font = new Font(family, style, 48);
        for (int amplitude = 2; amplitude <= 3; amplitude++) {
            for (int i = 0; i < alphabet.length(); i++) {
                accept(renderWarped(font, alphabet.charAt(i), amplitude), alphabet.charAt(i));
            }
        }
    }

    /**
     * 空心描边模板。笔画宽度按 32 号字上 2.2 像素的比例放大。
     */
    private void addStroke(Set<String> families, String family, int style, String alphabet) {
        if (!families.contains(family)) {
            return;
        }
        Font font = new Font(family, style, 48);
        float[] widths = {2.6f, 3.3f, 4.0f};
        for (int pass = 0; pass < widths.length; pass++) {
            for (int i = 0; i < alphabet.length(); i++) {
                accept(renderStroke(font, alphabet.charAt(i), widths[pass]), alphabet.charAt(i));
            }
        }
    }

    /**
     * 按 SpecCaptcha 的字号、斜体和 0.7 透明度画，阈值与浅色分割一致。
     */
    private void addSpecStyle(Set<String> families, String alphabet) {
        if (!families.contains("Verdana")) {
            return;
        }
        Font font = new Font("Verdana", Font.BOLD | Font.ITALIC, 28);
        for (int i = 0; i < alphabet.length(); i++) {
            accept(renderSpec(font, alphabet.charAt(i)), alphabet.charAt(i));
        }
    }

    /**
     * 按空心验证码的做法画：灰底、白填充、2.2 像素描边，再走同一套闭运算。
     */
    private void addOutlineStyle(Set<String> families, String alphabet) {
        if (!families.contains("Verdana")) {
            return;
        }
        for (int i = 0; i < alphabet.length(); i++) {
            char label = alphabet.charAt(i);
            byte[] grid = renderOutline(label);
            if (grid == null) {
                continue;
            }
            int ink = GlyphNormalizer.ink(grid);
            if (ink < 8 || ink > GlyphNormalizer.WIDTH * GlyphNormalizer.HEIGHT * 0.82) {
                continue;
            }
            templates.add(new Template(label, grid, false, true));
        }
    }

    /**
     * 按测试里的正弦位移画斜体字，覆盖几个水平相位。
     */
    private void addSineStyle(Set<String> families, String alphabet) {
        if (!families.contains("Verdana")) {
            return;
        }
        Font font = new Font("Verdana", Font.BOLD | Font.ITALIC, 32);
        int[] origins = {8, 48, 88, 128};
        for (int pass = 0; pass < origins.length; pass++) {
            for (int i = 0; i < alphabet.length(); i++) {
                accept(renderSine(font, alphabet.charAt(i), origins[pass]), alphabet.charAt(i));
            }
        }
    }

    private static byte[] renderSpec(Font font, char label) {
        int size = 64;
        BufferedImage image = new BufferedImage(size, size, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = image.createGraphics();
        graphics.setColor(Color.WHITE);
        graphics.fillRect(0, 0, size, size);
        graphics.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_OFF);
        graphics.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, 0.7f));
        graphics.setColor(new Color(70, 80, 90));
        graphics.setFont(font);
        FontMetrics metrics = graphics.getFontMetrics();
        graphics.drawString(String.valueOf(label), 4, 4 + metrics.getAscent());
        graphics.dispose();
        return maskOf(image, 168);
    }

    private static byte[] renderSine(Font font, char label, int originX) {
        int width = originX + 72;
        int height = 52;
        BufferedImage raw = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = raw.createGraphics();
        graphics.setColor(Color.WHITE);
        graphics.fillRect(0, 0, width, height);
        graphics.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_OFF);
        graphics.setColor(new Color(40, 40, 40));
        graphics.setFont(font);
        graphics.drawString(String.valueOf(label), originX, 38);
        graphics.dispose();
        BufferedImage warped = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int sx = x + (int) (3 * Math.sin(y / 6.0));
                int sy = y + (int) (2 * Math.sin(x / 10.0));
                int packed = 0xffffff;
                if (sx >= 0 && sy >= 0 && sx < width && sy < height) {
                    packed = raw.getRGB(sx, sy);
                }
                warped.setRGB(x, y, packed);
            }
        }
        return maskOf(warped, 180);
    }

    private static byte[] renderOutline(char label) {
        int width = 52;
        int height = 48;
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = image.createGraphics();
        graphics.setColor(new Color(214, 214, 214));
        graphics.fillRect(0, 0, width, height);
        graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_OFF);
        Font font = new Font("Verdana", Font.BOLD, 32);
        FontRenderContext context = graphics.getFontRenderContext();
        TextLayout layout = new TextLayout(String.valueOf(label), font, context);
        AffineTransform transform = AffineTransform.getTranslateInstance(8, 36);
        graphics.setColor(Color.WHITE);
        graphics.fill(layout.getOutline(transform));
        graphics.setColor(new Color(25, 45, 120));
        graphics.setStroke(new BasicStroke(2.2f));
        graphics.draw(layout.getOutline(transform));
        graphics.dispose();
        List<RawGlyph> glyphs = new OutlineSegmenter().segment(RgbImage.from(image));
        RawGlyph best = null;
        for (int i = 0; i < glyphs.size(); i++) {
            RawGlyph glyph = glyphs.get(i);
            if (best == null || glyph.area > best.area) {
                best = glyph;
            }
        }
        if (best == null) {
            return null;
        }
        return GlyphNormalizer.toGrid(best.pixels, best.width, best.height);
    }

    private void accept(byte[] grid, char label) {
        int ink = GlyphNormalizer.ink(grid);
        if (ink < 8 || ink > GlyphNormalizer.WIDTH * GlyphNormalizer.HEIGHT * 0.82) {
            return;
        }
        templates.add(new Template(label, grid, false));
    }

    private static byte[] renderWarped(Font font, char label, int amplitude) {
        int size = 96;
        BufferedImage image = drawPlain(font, label, size);
        byte[] mask = new byte[size * size];
        for (int y = 0; y < size; y++) {
            int shift = (int) Math.round(amplitude * Math.sin((y - 40) / 6.0));
            for (int x = 0; x < size; x++) {
                int sx = x + shift;
                int sy = y + (int) Math.round(Math.sin(x / 10.0));
                if (sx < 0 || sy < 0 || sx >= size || sy >= size) {
                    continue;
                }
                if (RgbImage.luminance(image.getRGB(sx, sy)) < 180) {
                    mask[y * size + x] = 1;
                }
            }
        }
        return GlyphNormalizer.toGrid(mask, size, size);
    }

    private static byte[] renderStroke(Font font, char label, float strokeWidth) {
        int size = 96;
        BufferedImage image = new BufferedImage(size, size, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = image.createGraphics();
        graphics.setColor(Color.WHITE);
        graphics.fillRect(0, 0, size, size);
        graphics.setColor(Color.BLACK);
        graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_OFF);
        FontRenderContext context = graphics.getFontRenderContext();
        TextLayout layout = new TextLayout(String.valueOf(label), font, context);
        AffineTransform transform = AffineTransform.getTranslateInstance(8, 8 + layout.getAscent());
        graphics.setStroke(new BasicStroke(strokeWidth, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        graphics.draw(layout.getOutline(transform));
        graphics.dispose();
        return maskOf(image, 180);
    }

    private static BufferedImage drawPlain(Font font, char label, int size) {
        BufferedImage image = new BufferedImage(size, size, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = image.createGraphics();
        graphics.setColor(Color.WHITE);
        graphics.fillRect(0, 0, size, size);
        graphics.setColor(Color.BLACK);
        graphics.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_OFF);
        graphics.setFont(font);
        FontMetrics metrics = graphics.getFontMetrics();
        graphics.drawString(String.valueOf(label), 8, 10 + metrics.getAscent());
        graphics.dispose();
        return image;
    }

    private static byte[] maskOf(BufferedImage image, int threshold) {
        int size = image.getWidth();
        byte[] mask = new byte[size * image.getHeight()];
        for (int y = 0; y < image.getHeight(); y++) {
            for (int x = 0; x < size; x++) {
                if (RgbImage.luminance(image.getRGB(x, y)) < threshold) {
                    mask[y * size + x] = 1;
                }
            }
        }
        return GlyphNormalizer.toGrid(mask, size, image.getHeight());
    }

    private static byte[] render(Font font, char label) {
        int size = 96;
        BufferedImage image = new BufferedImage(size, size, BufferedImage.TYPE_BYTE_GRAY);
        Graphics2D graphics = image.createGraphics();
        graphics.setColor(Color.WHITE);
        graphics.fillRect(0, 0, size, size);
        graphics.setColor(Color.BLACK);
        graphics.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_OFF);
        graphics.setFont(font);
        FontMetrics metrics = graphics.getFontMetrics();
        graphics.drawString(String.valueOf(label), 4, 6 + metrics.getAscent());
        graphics.dispose();
        byte[] mask = new byte[size * size];
        for (int y = 0; y < size; y++) {
            for (int x = 0; x < size; x++) {
                if (image.getRaster().getSample(x, y, 0) < 180) {
                    mask[y * size + x] = 1;
                }
            }
        }
        return GlyphNormalizer.toGrid(mask, size, size);
    }

    static final class Scored {
        final char label;
        final double score;

        Scored(char label, double score) {
            this.label = label;
            this.score = score;
        }
    }

    private static final class Template {
        final char label;
        final byte[] grid;
        final boolean plain;
        final boolean stroke;

        Template(char label, byte[] grid) {
            this(label, grid, true, false);
        }

        Template(char label, byte[] grid, boolean plain) {
            this(label, grid, plain, false);
        }

        Template(char label, byte[] grid, boolean plain, boolean stroke) {
            this.label = label;
            this.grid = grid;
            this.plain = plain;
            this.stroke = stroke;
        }
    }
}
