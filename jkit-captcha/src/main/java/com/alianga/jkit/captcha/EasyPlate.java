package com.alianga.jkit.captcha;

import java.awt.image.BufferedImage;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * easy-captcha 默认 130×48 白底彩色字。字符按固定槽位画出，十套内置字体的大小写和笔画都不一样。
 * 把每种字体的字形画在同一槽位上，只统计和某一个调色板颜色重合的像素：干扰线是另一种颜色，不能给更大的字形加分。
 * 得分是命中像素减去没命中的像素，细字形盖住粗字形的一部分时不会胜出。
 * 置信度够高才采用；汉字和这十套字体对不上，仍交给 {@code common.onnx}。
 */
final class EasyPlate {
    private static final int WIDTH = 130;
    private static final int HEIGHT = 48;
    private static final int LENGTH = 5;
    private static final int SHIFT = 1;
    private static final int COLORS = 12;
    private static final int MIN_INK = 8;
    private static final double MIN_DICE = 0.72;
    private static final String[] ARITH_SLOTS = {"0123456789", "+-x", "0123456789", "=", "?"};
    private static final double MARK_DICE = 0.75;
    private static final String LETTERS =
            "23456789ABCDEFGHJKMNPQRSTUVWXYZabcdefghjkmnpqrstuvwxyz";
    private static final String OPERATORS = "0123456789+-x=?";
    private static final int[][] PALETTE = {
        {0, 135, 255}, {51, 153, 51}, {255, 102, 102}, {255, 153, 0},
        {153, 102, 0}, {153, 102, 153}, {51, 153, 153}, {102, 102, 255},
        {0, 102, 204}, {204, 51, 51}, {0, 153, 204}, {0, 51, 102}
    };

    private static boolean loaded;
    private static Glyph[][] letterBanks;
    private static Glyph[][] operatorBanks;

    private EasyPlate() {
    }

    static RecognizeResult read(BufferedImage image, RecognizeOptions options) {
        if (!canvas(image) || !ensure()) {
            return null;
        }
        long started = System.nanoTime();
        byte[][] frames = new byte[][] {colors(image)};
        return decide(frames, options, false, started);
    }

    static RecognizeResult readFrames(List<BufferedImage> frames, RecognizeOptions options) {
        if (frames == null || frames.size() != LENGTH || !ensure()) {
            return null;
        }
        long started = System.nanoTime();
        byte[][] ink = new byte[frames.size()][];
        for (int i = 0; i < frames.size(); i++) {
            if (!canvas(frames.get(i))) {
                return null;
            }
            ink[i] = colors(frames.get(i));
        }
        CaptchaType type = options.getType();
        if (type == CaptchaType.ARITHMETIC) {
            return accept(match(ink, operatorBanks, allow(options, OPERATORS), true, true), true, started);
        }
        return accept(match(ink, letterBanks, allow(options, LETTERS), true, false), false, started);
    }

    private static RecognizeResult decide(byte[][] frames, RecognizeOptions options, boolean opaque,
                                          long started) {
        CaptchaType type = options.getType();
        String user = options.getAlphabet();
        if (type == CaptchaType.ARITHMETIC) {
            return accept(match(frames, operatorBanks, allow(options, OPERATORS), opaque, true), true, started);
        }
        if (type == CaptchaType.ALPHANUMERIC || type == CaptchaType.DISTORTED) {
            return accept(match(frames, letterBanks, allow(options, LETTERS), opaque, false), false, started);
        }
        if (user != null && user.length() > 0 && !containsOperator(user)) {
            return accept(match(frames, letterBanks, user, opaque, false), false, started);
        }
        Attempt letters = match(frames, letterBanks, allow(options, LETTERS), opaque, false);
        Attempt expression = match(frames, operatorBanks, allow(options, OPERATORS), opaque, true);
        if (expression != null && expression.marked && expression.dice >= MIN_DICE
                && (letters == null || expression.net > letters.net)) {
            return accept(expression, true, started);
        }
        return accept(letters, false, started);
    }

    private static RecognizeResult accept(Attempt attempt, boolean arithmetic, long started) {
        if (attempt == null || attempt.dice < MIN_DICE || attempt.text.length() != LENGTH) {
            return null;
        }
        long elapsed = (System.nanoTime() - started) / 1000000L;
        List<GlyphHit> glyphs = RecognizeResult.freeze(new ArrayList<GlyphHit>());
        if (!arithmetic) {
            return new RecognizeResult(attempt.text, null, null, attempt.dice, CaptchaType.ALPHANUMERIC,
                    elapsed, glyphs);
        }
        String expression = attempt.text.substring(0, 3);
        ArithmeticEvaluator.Value value = ArithmeticEvaluator.evaluate(expression);
        if (value == null) {
            return new RecognizeResult(attempt.text, null, null, attempt.dice, CaptchaType.ALPHANUMERIC,
                    elapsed, glyphs);
        }
        return new RecognizeResult(value.expression, value.expression, Integer.valueOf(value.result),
                attempt.dice, CaptchaType.ARITHMETIC, elapsed, glyphs);
    }

    private static Attempt match(byte[][] frames, Glyph[][] banks, String allow, boolean opaque,
                                 boolean arithmetic) {
        if (allow != null && allow.length() == 0) {
            return null;
        }
        Attempt best = null;
        for (int b = 0; b < banks.length; b++) {
            Attempt attempt = readFont(frames, banks[b], allow, opaque, arithmetic);
            if (best == null || attempt.net > best.net) {
                best = attempt;
            }
        }
        return best;
    }

    private static Attempt readFont(byte[][] frames, Glyph[] glyphs, String allow, boolean opaque,
                                    boolean arithmetic) {
        StringBuilder text = new StringBuilder(LENGTH);
        double net = 0;
        double dice = 0;
        boolean marked = true;
        for (int slot = 0; slot < LENGTH; slot++) {
            byte[] ink = opaque ? frames[LENGTH - 1 - slot] : frames[0];
            String slotAllow = allow;
            if (arithmetic) {
                slotAllow = intersect(allow, ARITH_SLOTS[slot]);
            }
            Pick pick = bestGlyph(ink, glyphs, slot, slotAllow);
            text.append(pick.ch);
            net += pick.net;
            dice += pick.dice;
            if (slot == 3 && (pick.ch != '=' || pick.dice < MARK_DICE)) {
                marked = false;
            }
            if (slot == 4 && (pick.ch != '?' || pick.dice < MARK_DICE)) {
                marked = false;
            }
        }
        return new Attempt(text.toString(), net, dice / LENGTH, marked);
    }

    private static Pick bestGlyph(byte[] ink, Glyph[] glyphs, int slot, String allow) {
        char bestChar = '?';
        double bestNet = Double.NEGATIVE_INFINITY;
        double bestDice = 0;
        int origin = slot * (WIDTH / LENGTH);
        for (int g = 0; g < glyphs.length; g++) {
            Glyph glyph = glyphs[g];
            if (glyph.count < MIN_INK) {
                continue;
            }
            if (allow != null && allow.indexOf(glyph.ch) < 0) {
                continue;
            }
            for (int dy = -SHIFT; dy <= SHIFT; dy++) {
                for (int dx = -SHIFT; dx <= SHIFT; dx++) {
                    double[] scored = score(ink, glyph, origin + dx, dy);
                    if (scored[0] > bestNet) {
                        bestNet = scored[0];
                        bestDice = scored[1];
                        bestChar = glyph.ch;
                    }
                }
            }
        }
        return new Pick(bestChar, bestNet, bestDice);
    }

    /** @return net, dice。命中只算在同一种颜色上，其他颜色和空白都算没命中。 */
    private static double[] score(byte[] ink, Glyph glyph, int dx, int dy) {
        int[] hits = new int[COLORS];
        for (int p = 0; p < glyph.count; p++) {
            int x = glyph.xs[p] + dx;
            int y = glyph.ys[p] + dy;
            if (x < 0 || y < 0 || x >= WIDTH || y >= HEIGHT) {
                continue;
            }
            int color = ink[y * WIDTH + x];
            if (color >= 0) {
                hits[color]++;
            }
        }
        int bestHits = 0;
        int bestColor = 0;
        for (int c = 0; c < COLORS; c++) {
            if (hits[c] > bestHits) {
                bestHits = hits[c];
                bestColor = c;
            }
        }
        int x0 = clamp(glyph.minX + dx, WIDTH - 1);
        int y0 = clamp(glyph.minY + dy, HEIGHT - 1);
        int x1 = clamp(glyph.maxX + dx, WIDTH - 1);
        int y1 = clamp(glyph.maxY + dy, HEIGHT - 1);
        int area = 0;
        for (int y = y0; y <= y1; y++) {
            int row = y * WIDTH;
            for (int x = x0; x <= x1; x++) {
                if (ink[row + x] == bestColor) {
                    area++;
                }
            }
        }
        double net = bestHits - (glyph.count - bestHits);
        double dice = area == 0 ? 0 : 2.0 * bestHits / (glyph.count + area);
        return new double[] {net, dice};
    }

    private static int clamp(int value, int hi) {
        if (value < 0) {
            return 0;
        }
        if (value > hi) {
            return hi;
        }
        return value;
    }

    private static byte[] colors(BufferedImage image) {
        byte[] ink = new byte[WIDTH * HEIGHT];
        for (int i = 0; i < ink.length; i++) {
            ink[i] = -1;
        }
        double[] length = new double[COLORS];
        int[] drPalette = new int[COLORS];
        int[] dgPalette = new int[COLORS];
        int[] dbPalette = new int[COLORS];
        for (int c = 0; c < COLORS; c++) {
            drPalette[c] = 255 - PALETTE[c][0];
            dgPalette[c] = 255 - PALETTE[c][1];
            dbPalette[c] = 255 - PALETTE[c][2];
            length[c] = Math.sqrt(drPalette[c] * drPalette[c] + dgPalette[c] * dgPalette[c]
                    + dbPalette[c] * dbPalette[c]);
        }
        int w = Math.min(WIDTH, image.getWidth());
        int h = Math.min(HEIGHT, image.getHeight());
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int rgb = image.getRGB(x, y);
                int dr = 255 - ((rgb >> 16) & 0xff);
                int dg = 255 - ((rgb >> 8) & 0xff);
                int db = 255 - (rgb & 0xff);
                int mag = dr * dr + dg * dg + db * db;
                if (mag < 45 * 45) {
                    continue;
                }
                double scale = Math.sqrt(mag);
                int best = 0;
                double bestCos = -2;
                for (int c = 0; c < COLORS; c++) {
                    double cos = (dr * drPalette[c] + dg * dgPalette[c] + db * dbPalette[c])
                            / (scale * length[c]);
                    if (cos > bestCos) {
                        bestCos = cos;
                        best = c;
                    }
                }
                if (bestCos >= 0.72) {
                    ink[y * WIDTH + x] = (byte) best;
                }
            }
        }
        return ink;
    }

    private static boolean canvas(BufferedImage image) {
        return image != null && image.getWidth() == WIDTH && image.getHeight() == HEIGHT;
    }

    private static String allow(RecognizeOptions options, String fallback) {
        String user = options.getAlphabet();
        if (user == null || user.length() == 0) {
            return fallback;
        }
        return user;
    }

    private static String intersect(String allow, String slot) {
        if (allow == null) {
            return slot;
        }
        StringBuilder builder = new StringBuilder(slot.length());
        for (int i = 0; i < slot.length(); i++) {
            char ch = slot.charAt(i);
            if (allow.indexOf(ch) >= 0) {
                builder.append(ch);
            }
        }
        return builder.toString();
    }

    private static boolean containsOperator(String alphabet) {
        return alphabet.indexOf('+') >= 0 || alphabet.indexOf('-') >= 0 || alphabet.indexOf('x') >= 0;
    }

    private static boolean ensure() {
        if (loaded) {
            return letterBanks != null;
        }
        loaded = true;
        InputStream input = EasyPlate.class.getResourceAsStream("ocr/easy-plate.bin");
        if (input == null) {
            return false;
        }
        try {
            DataInputStream data = new DataInputStream(input);
            Glyph[][] letters = new Glyph[10][];
            Glyph[][] operators = new Glyph[10][];
            for (int i = 0; i < letters.length; i++) {
                letters[i] = readGlyphs(data, LETTERS);
                operators[i] = readGlyphs(data, OPERATORS);
            }
            letterBanks = letters;
            operatorBanks = operators;
            return true;
        } catch (IOException ex) {
            return false;
        } finally {
            try {
                input.close();
            } catch (IOException ex) {
                // 字形表已经读完或读失败，关闭失败不影响识别。
            }
        }
    }

    private static Glyph[] readGlyphs(DataInputStream data, String alphabet) throws IOException {
        Glyph[] glyphs = new Glyph[alphabet.length()];
        for (int i = 0; i < alphabet.length(); i++) {
            int count = data.readUnsignedShort();
            int[] xs = new int[count];
            int[] ys = new int[count];
            int minX = WIDTH;
            int minY = HEIGHT;
            int maxX = 0;
            int maxY = 0;
            for (int p = 0; p < count; p++) {
                int x = data.readUnsignedByte();
                int y = data.readUnsignedByte();
                xs[p] = x;
                ys[p] = y;
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
            if (count == 0) {
                minX = 0;
                minY = 0;
            }
            glyphs[i] = new Glyph(alphabet.charAt(i), xs, ys, minX, minY, maxX, maxY);
        }
        return glyphs;
    }

    private static final class Glyph {
        private final char ch;
        private final int[] xs;
        private final int[] ys;
        private final int count;
        private final int minX;
        private final int minY;
        private final int maxX;
        private final int maxY;

        private Glyph(char ch, int[] xs, int[] ys, int minX, int minY, int maxX, int maxY) {
            this.ch = ch;
            this.xs = xs;
            this.ys = ys;
            this.count = xs.length;
            this.minX = minX;
            this.minY = minY;
            this.maxX = maxX;
            this.maxY = maxY;
        }
    }

    private static final class Pick {
        private final char ch;
        private final double net;
        private final double dice;

        private Pick(char ch, double net, double dice) {
            this.ch = ch;
            this.net = net;
            this.dice = dice;
        }
    }

    private static final class Attempt {
        private final String text;
        private final double net;
        private final double dice;
        private final boolean marked;

        private Attempt(String text, double net, double dice, boolean marked) {
            this.text = text;
            this.net = net;
            this.dice = dice;
            this.marked = marked;
        }
    }
}
