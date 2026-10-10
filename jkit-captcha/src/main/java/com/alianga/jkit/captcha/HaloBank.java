package com.alianga.jkit.captcha;

import javax.imageio.ImageIO;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 灰底白边、字顶贴着上沿的实心验证码。系统字体对不上这种锯齿字，用另一批已标注样例做模板。
 * 模板图放在 {@code halo/}，和测试图不是同一张。
 */
final class HaloBank {
    private static final int SHIFT = 2;
    private static final double HOLE_PENALTY = 0.06;
    private static final String[] NAMES = {
            "W6UH.gif", "GV1S.gif", "4Y23.gif", "AE35.gif",
            "8R1G.gif", "BAD8.gif", "TCXF.gif", "709V.gif"
    };

    private final List<Sample> samples = new ArrayList<Sample>();
    private boolean loaded;

    static boolean matches(RgbImage image) {
        if (image.width < 68 || image.width > 96 || image.height < 26 || image.height > 34) {
            return false;
        }
        int background = image.borderMedianLuminance();
        if (background < 160 || background > 235) {
            return false;
        }
        int dark = 0;
        int white = 0;
        int touch = 0;
        int width = image.width;
        for (int y = 0; y < image.height; y++) {
            for (int x = 0; x < width; x++) {
                int lum = RgbImage.luminance(image.pixels[y * width + x]);
                if (lum < 140) {
                    dark++;
                    continue;
                }
                if (lum <= 245) {
                    continue;
                }
                white++;
                if (nearDark(image, x, y)) {
                    touch++;
                }
            }
        }
        if (white == 0 || dark * 12 < image.pixels.length) {
            return false;
        }
        return touch * 10 > white * 7;
    }

    /**
     * 只保留深色字芯。白边亮度很高，不会进掩膜。
     */
    static List<RawGlyph> segment(RgbImage image) {
        BinaryImage binary = new BinaryImage(image.width, image.height);
        for (int i = 0; i < image.pixels.length; i++) {
            if (RgbImage.luminance(image.pixels[i]) < 150) {
                binary.data[i] = 1;
            }
        }
        List<RawGlyph> parts = binary.components(30, true);
        List<RawGlyph> glyphs = new ArrayList<RawGlyph>();
        for (int i = 0; i < parts.size(); i++) {
            RawGlyph glyph = parts.get(i);
            if (glyph.height >= 16) {
                glyphs.add(glyph);
            }
        }
        Collections.sort(glyphs, new Comparator<RawGlyph>() {
            @Override
            public int compare(RawGlyph left, RawGlyph right) {
                return left.x - right.x;
            }
        });
        return glyphs;
    }

    synchronized Scored best(byte[] grid, int holes, String alphabet) {
        ensureLoaded();
        if (alphabet == null || alphabet.length() == 0 || samples.isEmpty()) {
            return null;
        }
        Map<Character, Double> scores = new HashMap<Character, Double>();
        for (int i = 0; i < samples.size(); i++) {
            Sample sample = samples.get(i);
            if (alphabet.indexOf(sample.label) < 0) {
                continue;
            }
            double score = diceShift(grid, sample.grid);
            if (sample.holes != holes) {
                score -= HOLE_PENALTY;
            }
            Double previous = scores.get(Character.valueOf(sample.label));
            if (previous == null || score > previous.doubleValue()) {
                scores.put(Character.valueOf(sample.label), Double.valueOf(score));
            }
        }
        char label = 0;
        double bestScore = -1;
        for (Map.Entry<Character, Double> entry : scores.entrySet()) {
            if (entry.getValue().doubleValue() > bestScore) {
                bestScore = entry.getValue().doubleValue();
                label = entry.getKey().charValue();
            }
        }
        if (label == 0) {
            return null;
        }
        return new Scored(label, bestScore);
    }

    private void ensureLoaded() {
        if (loaded) {
            return;
        }
        loaded = true;
        for (int i = 0; i < NAMES.length; i++) {
            loadOne(NAMES[i]);
        }
    }

    private void loadOne(String name) {
        InputStream input = HaloBank.class.getResourceAsStream("halo/" + name);
        if (input == null) {
            return;
        }
        try {
            BufferedImage image = ImageIO.read(input);
            if (image == null) {
                return;
            }
            String label = name.substring(0, name.indexOf('.'));
            List<RawGlyph> glyphs = segment(RgbImage.from(image));
            if (glyphs.size() != label.length()) {
                return;
            }
            for (int i = 0; i < glyphs.size(); i++) {
                RawGlyph glyph = glyphs.get(i);
                byte[] grid = GlyphNormalizer.toGrid(glyph.pixels, glyph.width, glyph.height);
                int holes = HoleCount.count(glyph.pixels, glyph.width, glyph.height);
                samples.add(new Sample(label.charAt(i), grid, holes));
            }
        } catch (IOException ex) {
            // 某一张样例读失败时跳过，其余样例继续用。
        } finally {
            close(input);
        }
    }

    private static void close(InputStream input) {
        try {
            input.close();
        } catch (IOException ex) {
            // 关闭失败不影响已经读入的像素。
        }
    }

    private static boolean nearDark(RgbImage image, int x, int y) {
        int width = image.width;
        int height = image.height;
        for (int dy = -1; dy <= 1; dy++) {
            int ny = y + dy;
            if (ny < 0 || ny >= height) {
                continue;
            }
            for (int dx = -1; dx <= 1; dx++) {
                int nx = x + dx;
                if (nx < 0 || nx >= width) {
                    continue;
                }
                if (RgbImage.luminance(image.pixels[ny * width + nx]) < 140) {
                    return true;
                }
            }
        }
        return false;
    }

    private static double diceShift(byte[] left, byte[] right) {
        int leftInk = GlyphNormalizer.ink(left);
        int rightInk = GlyphNormalizer.ink(right);
        if (leftInk + rightInk == 0) {
            return 0;
        }
        double best = 0;
        int width = GlyphNormalizer.WIDTH;
        int height = GlyphNormalizer.HEIGHT;
        for (int dy = -SHIFT; dy <= SHIFT; dy++) {
            for (int dx = -SHIFT; dx <= SHIFT; dx++) {
                int inter = 0;
                for (int y = 0; y < height; y++) {
                    int sy = y - dy;
                    if (sy < 0 || sy >= height) {
                        continue;
                    }
                    int row = y * width;
                    int sourceRow = sy * width;
                    for (int x = 0; x < width; x++) {
                        if (left[row + x] == 0) {
                            continue;
                        }
                        int sx = x - dx;
                        if (sx >= 0 && sx < width && right[sourceRow + sx] != 0) {
                            inter++;
                        }
                    }
                }
                double score = 2.0 * inter / (leftInk + rightInk);
                if (score > best) {
                    best = score;
                }
            }
        }
        return best;
    }

    static final class Scored {
        final char label;
        final double score;

        Scored(char label, double score) {
            this.label = label;
            this.score = score;
        }
    }

    private static final class Sample {
        final char label;
        final byte[] grid;
        final int holes;

        Sample(char label, byte[] grid, int holes) {
            this.label = label;
            this.grid = grid;
            this.holes = holes;
        }
    }
}
