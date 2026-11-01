package com.alianga.jkit.captcha;

import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;

/**
 * 深色算术图里，细减号常被当成干扰线擦掉，识别结果就只剩一串数字。
 * 只在数字列之间的空档里看到一条横跨空档的短横线时，才把减号补回去。
 * 穿过数字的线不在空档里，纯数字验证码不会因此多出一个减号。
 */
final class MinusInsert {
    private MinusInsert() {
    }

    static String insert(BufferedImage image, String text) {
        if (image == null || text == null || !text.matches("[0-9]{3,6}")) {
            return text == null ? "" : text;
        }
        RgbImage rgb = RgbImage.from(image);
        int background = rgb.borderMedianLuminance();
        if (background >= 80) {
            return text;
        }
        boolean[] ink = foreground(rgb, background);
        int[] columns = columnCounts(ink, rgb.width, rgb.height);
        List<int[]> glyphs = spans(columns, rgb.height / 5, 6);
        int gapAt = -1;
        for (int i = 0; i + 1 < glyphs.size(); i++) {
            int left = glyphs.get(i)[1] + 1;
            int right = glyphs.get(i + 1)[0] - 1;
            if (right - left < 8) {
                continue;
            }
            if (!barInGap(ink, rgb.width, rgb.height, left, right)) {
                continue;
            }
            if (gapAt >= 0) {
                return text;
            }
            int center = (left + right) / 2;
            gapAt = (int) Math.round(center * (double) text.length() / rgb.width);
        }
        if (gapAt <= 0 || gapAt >= text.length()) {
            return text;
        }
        String expression = text.substring(0, gapAt) + "-" + text.substring(gapAt);
        if (ArithmeticEvaluator.evaluate(expression) == null) {
            return text;
        }
        return expression;
    }

    private static boolean[] foreground(RgbImage rgb, int background) {
        boolean[] ink = new boolean[rgb.pixels.length];
        for (int i = 0; i < rgb.pixels.length; i++) {
            int red = rgb.red(i);
            int green = rgb.green(i);
            int blue = rgb.blue(i);
            int luminance = RgbImage.luminance(rgb.pixels[i]);
            int saturation = RgbImage.saturation(red, green, blue);
            ink[i] = luminance > background + 35 || saturation >= 40 && luminance > background + 15;
        }
        return ink;
    }

    private static int[] columnCounts(boolean[] ink, int width, int height) {
        int[] columns = new int[width];
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                if (ink[y * width + x]) {
                    columns[x]++;
                }
            }
        }
        return columns;
    }

    private static List<int[]> spans(int[] columns, int minInk, int minWidth) {
        List<int[]> spans = new ArrayList<int[]>();
        int start = -1;
        for (int x = 0; x <= columns.length; x++) {
            boolean on = x < columns.length && columns[x] >= minInk;
            if (on && start < 0) {
                start = x;
            } else if (!on && start >= 0) {
                if (x - start >= minWidth) {
                    spans.add(new int[] {start, x - 1});
                }
                start = -1;
            }
        }
        return spans;
    }

    /** 空档中间若干行几乎每列都有墨，才算减号。斜线穿过去盖不满整段空档。 */
    private static boolean barInGap(boolean[] ink, int width, int height, int left, int right) {
        int top = height * 3 / 10;
        int bottom = height * 7 / 10;
        int run = 0;
        int best = 0;
        for (int y = top; y <= bottom; y++) {
            if (longestRun(ink, width, y, left, right) >= 10) {
                run++;
                if (run > best) {
                    best = run;
                }
            } else {
                run = 0;
            }
        }
        return best >= 3 && best <= 8;
    }

    private static int longestRun(boolean[] ink, int width, int y, int left, int right) {
        int best = 0;
        int run = 0;
        for (int x = left; x <= right; x++) {
            if (ink[y * width + x]) {
                run++;
                if (run > best) {
                    best = run;
                }
            } else {
                run = 0;
            }
        }
        return best;
    }
}
