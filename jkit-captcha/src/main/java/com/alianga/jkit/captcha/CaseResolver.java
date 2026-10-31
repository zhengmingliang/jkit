package com.alianga.jkit.captcha;

import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.GraphicsEnvironment;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 判定通用模型给出的字母到底是大写还是小写。
 *
 * <p>ddddocr 的 {@code common_old.onnx} 在大小写混合的文本上几乎总输出小写：实测
 * 期望大写的位置里只有约 27% 保持大写，而期望小写的位置有 96% 保持小写。把模型串直接
 * 当答案会让大小写混合的验证码整串准确率被大小写一项压死。模型本身读的是字形，
 * 大小写这一位要另外判定。
 *
 * <p>这里用两条独立证据投票：
 * <ul>
 *   <li>字形证据：把单字在若干斜切/旋转候选下归一化，各比一次大写与小写模板，
 *       取"分差最大"的那次判定。验证码常带斜切和旋转，摆正后再比能明显拉开分差。</li>
 *   <li>几何证据：同一行里大写字母更高、顶部更靠上。用字高与字顶相对整行的位置判定。</li>
 * </ul>
 * 两条证据一致时才改大小写，不一致时保留模型结果。这样只在证据充分时动手，
 * 不会把原本就对的大小写改错。
 *
 * <p>模板按本机可用字体现渲染并缓存。字体缺失时这一路自动不参与，只留几何证据。
 */
final class CaseResolver {
    /** 只比这几个候选，太多会让单字识别变慢。 */
    private static final double[] SHEARS = {-0.15, -0.075, 0.0, 0.075, 0.15};
    private static final double[] DEGREES = {-10.0, -5.0, 0.0, 5.0, 10.0};

    private static final String[] FAMILIES = {
            "Verdana", "Arial", "DejaVu Sans", "Liberation Sans", "Tahoma", "Trebuchet MS",
            "Noto Sans", "Ubuntu", "Times New Roman", "Georgia", "Courier New",
    };
    private static final int[] STYLES = {Font.PLAIN, Font.BOLD, Font.ITALIC, Font.BOLD | Font.ITALIC};

    private static final byte[][][] UPPER = new byte[FAMILIES.length][][];
    private static final byte[][][] LOWER = new byte[FAMILIES.length][][];
    private static final boolean[] READY = new boolean[FAMILIES.length];
    private static volatile boolean built;

    private CaseResolver() {
    }

    /**
     * 逐个位置判定大小写，只改字母位。
     *
     * @param glyphs 从左到右的单字，与 {@code text} 一一对应
     * @param text   模型给出的文本
     * @return 修正大小写后的文本
     */
    static String resolve(List<RawGlyph> glyphs, String text) {
        if (glyphs == null || text == null || glyphs.size() != text.length() || text.isEmpty()) {
            return text;
        }
        boolean hasLetter = false;
        for (int i = 0; i < text.length(); i++) {
            if (isLetter(text.charAt(i))) {
                hasLetter = true;
                break;
            }
        }
        if (!hasLetter) {
            return text;
        }
        ensureBuilt();
        int maxHeight = 0;
        int minTop = Integer.MAX_VALUE;
        for (int i = 0; i < glyphs.size(); i++) {
            RawGlyph glyph = glyphs.get(i);
            if (glyph.height > maxHeight) {
                maxHeight = glyph.height;
            }
            if (glyph.y < minTop) {
                minTop = glyph.y;
            }
        }
        StringBuilder builder = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) {
            char ch = text.charAt(i);
            if (!isLetter(ch)) {
                builder.append(ch);
                continue;
            }
            builder.append(decide(glyphs.get(i), ch, maxHeight, minTop));
        }
        return builder.toString();
    }

    private static char decide(RawGlyph glyph, char letter, int maxHeight, int minTop) {
        char upper = Character.toUpperCase(letter);
        char lower = Character.toLowerCase(letter);
        Boolean byShape = shapeSays(glyph, upper, lower);
        Boolean byGeometry = geometrySays(glyph, maxHeight, minTop);
        // 两条证据必须一致才改；任何一条缺失就听另一条，都没有就保留原样。
        if (byShape != null && byGeometry != null) {
            return byShape.booleanValue() == byGeometry.booleanValue()
                    ? (byShape.booleanValue() ? upper : lower) : letter;
        }
        if (byShape != null) {
            return byShape.booleanValue() ? upper : lower;
        }
        if (byGeometry != null) {
            return byGeometry.booleanValue() ? upper : lower;
        }
        return letter;
    }

    /** 摆正后比大小写模板，取分差最大的一次。分差太小时不给结论。 */
    private static Boolean shapeSays(RawGlyph glyph, char upper, char lower) {
        if (!built) {
            return null;
        }
        int index = upper - 'A';
        double bestMargin = -1d;
        boolean bestUpper = false;
        boolean any = false;
        for (int s = 0; s < SHEARS.length; s++) {
            for (int d = 0; d < DEGREES.length; d++) {
                byte[] grid = normalize(glyph, SHEARS[s], DEGREES[d]);
                if (grid == null) {
                    continue;
                }
                double bestU = 0d;
                double bestL = 0d;
                for (int f = 0; f < FAMILIES.length; f++) {
                    if (!READY[f]) {
                        continue;
                    }
                    bestU = Math.max(bestU, GlyphNormalizer.dice(grid, UPPER[f][index]));
                    bestL = Math.max(bestL, GlyphNormalizer.dice(grid, LOWER[f][index]));
                }
                any = true;
                double margin = Math.abs(bestU - bestL);
                if (margin > bestMargin) {
                    bestMargin = margin;
                    bestUpper = bestU >= bestL;
                }
            }
        }
        if (!any || bestMargin < 0.004d) {
            return null;
        }
        return Boolean.valueOf(bestUpper);
    }

    /** 大写更高、顶部更靠上。两个信号一致才给结论。 */
    private static Boolean geometrySays(RawGlyph glyph, int maxHeight, int minTop) {
        boolean tall = glyph.height >= maxHeight - 1;
        boolean high = glyph.y <= minTop + 2;
        if (tall && high) {
            return Boolean.TRUE;
        }
        if (!tall && !high) {
            return Boolean.FALSE;
        }
        return null;
    }

    private static byte[] normalize(RawGlyph glyph, double shear, double degrees) {
        byte[] grid = GlyphNormalizer.toGrid(glyph.pixels, glyph.width, glyph.height);
        if (shear == 0d && degrees == 0d) {
            return grid;
        }
        BufferedImage image = GlyphGrid.toImage(grid);
        int width = image.getWidth();
        int height = image.getHeight();
        BufferedImage out = new BufferedImage(width + 8, height + 8, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = out.createGraphics();
        try {
            graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            graphics.setColor(java.awt.Color.WHITE);
            graphics.fillRect(0, 0, out.getWidth(), out.getHeight());
            graphics.translate(4, 4);
            if (degrees != 0d) {
                graphics.rotate(degrees * Math.PI / 180d, width / 2d, height / 2d);
            }
            if (shear != 0d) {
                graphics.shear(shear, 0d);
            }
            graphics.drawImage(image, 0, 0, null);
        } finally {
            graphics.dispose();
        }
        return GlyphGrid.fromImage(out);
    }

    private static void ensureBuilt() {
        if (built) {
            return;
        }
        synchronized (CaseResolver.class) {
            if (built) {
                return;
            }
            Set<String> installed = new HashSet<String>(Arrays.asList(
                    GraphicsEnvironment.getLocalGraphicsEnvironment().getAvailableFontFamilyNames()));
            for (int f = 0; f < FAMILIES.length; f++) {
                if (!installed.contains(FAMILIES[f])) {
                    continue;
                }
                byte[][] uppers = new byte[26][0];
                byte[][] lowers = new byte[26][0];
                List<byte[]> upperList = new ArrayList<byte[]>(26);
                List<byte[]> lowerList = new ArrayList<byte[]>(26);
                boolean ok = true;
                for (int i = 0; i < 26; i++) {
                    char big = (char) ('A' + i);
                    byte[] up = render(FAMILIES[f], big);
                    byte[] low = render(FAMILIES[f], Character.toLowerCase(big));
                    if (up == null || low == null || empty(up) || empty(low)) {
                        ok = false;
                        break;
                    }
                    upperList.add(up);
                    lowerList.add(low);
                }
                if (!ok) {
                    continue;
                }
                UPPER[f] = upperList.toArray(uppers);
                LOWER[f] = lowerList.toArray(lowers);
                READY[f] = true;
            }
            built = true;
        }
    }

    private static boolean empty(byte[] grid) {
        for (int i = 0; i < grid.length; i++) {
            if (grid[i] != 0) {
                return false;
            }
        }
        return true;
    }

    /** 渲染一个字符并归一化到与单字图相同的网格。 */
    private static byte[] render(String family, char ch) {
        for (int s = 0; s < STYLES.length; s++) {
            BufferedImage cell = new BufferedImage(96, 96, BufferedImage.TYPE_INT_RGB);
            Graphics2D graphics = cell.createGraphics();
            Font font = new Font(family, STYLES[s], 64);
            try {
                graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
                        RenderingHints.VALUE_ANTIALIAS_ON);
                graphics.setColor(java.awt.Color.WHITE);
                graphics.fillRect(0, 0, 96, 96);
                graphics.setColor(java.awt.Color.BLACK);
                graphics.setFont(font);
                graphics.drawString(String.valueOf(ch), 16, 80);
            } finally {
                graphics.dispose();
            }
            byte[] grid = GlyphGrid.fromImage(cell);
            if (!empty(grid)) {
                return grid;
            }
        }
        return null;
    }

    private static boolean isLetter(char ch) {
        return ch >= 'A' && ch <= 'Z' || ch >= 'a' && ch <= 'z';
    }

    /** 网格与位图互转，与 {@link GlyphNormalizer} 的归一化规则保持一致。 */
    static final class GlyphGrid {
        private static final int WIDTH = GlyphNormalizer.WIDTH;
        private static final int HEIGHT = GlyphNormalizer.HEIGHT;

        private GlyphGrid() {
        }

        static BufferedImage toImage(byte[] grid) {
            BufferedImage image = new BufferedImage(WIDTH, HEIGHT, BufferedImage.TYPE_INT_RGB);
            for (int y = 0; y < HEIGHT; y++) {
                for (int x = 0; x < WIDTH; x++) {
                    image.setRGB(x, y, grid[y * WIDTH + x] != 0 ? 0x000000 : 0xffffff);
                }
            }
            return image;
        }

        /** 裁到笔画外框后按长宽比缩到网格，与单字归一化同一套规则。 */
        static byte[] fromImage(BufferedImage cell) {
            int width = cell.getWidth();
            int height = cell.getHeight();
            int left = width;
            int top = height;
            int right = -1;
            int bottom = -1;
            for (int y = 0; y < height; y++) {
                for (int x = 0; x < width; x++) {
                    if (dark(cell.getRGB(x, y))) {
                        if (x < left) {
                            left = x;
                        }
                        if (x > right) {
                            right = x;
                        }
                        if (y < top) {
                            top = y;
                        }
                        if (y > bottom) {
                            bottom = y;
                        }
                    }
                }
            }
            if (right < 0) {
                return new byte[WIDTH * HEIGHT];
            }
            int boxWidth = right - left + 1;
            int boxHeight = bottom - top + 1;
            BufferedImage cropped =
                    new BufferedImage(boxWidth, boxHeight, BufferedImage.TYPE_INT_RGB);
            Graphics2D graphics = cropped.createGraphics();
            try {
                graphics.setColor(java.awt.Color.WHITE);
                graphics.fillRect(0, 0, boxWidth, boxHeight);
                graphics.drawImage(cell, -left, -top, null);
            } finally {
                graphics.dispose();
            }
            BufferedImage scaled = new BufferedImage(WIDTH, HEIGHT, BufferedImage.TYPE_INT_RGB);
            Graphics2D target = scaled.createGraphics();
            try {
                target.setColor(java.awt.Color.WHITE);
                target.fillRect(0, 0, WIDTH, HEIGHT);
                target.drawImage(cropped, 1, 1, WIDTH - 2, HEIGHT - 2, null);
            } finally {
                target.dispose();
            }
            byte[] grid = new byte[WIDTH * HEIGHT];
            for (int y = 0; y < HEIGHT; y++) {
                for (int x = 0; x < WIDTH; x++) {
                    grid[y * WIDTH + x] = (byte) (dark(scaled.getRGB(x, y)) ? 1 : 0);
                }
            }
            return grid;
        }

        private static boolean dark(int rgb) {
            int red = (rgb >> 16) & 0xff;
            int green = (rgb >> 8) & 0xff;
            int blue = rgb & 0xff;
            return (red * 299 + green * 587 + blue * 114) / 1000 < 128;
        }
    }
}
