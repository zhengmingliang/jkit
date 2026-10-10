package com.alianga.jkit.captcha;

/**
 * 用笔画结构判断加号。算术验证码里的加号经常被干扰线切偏，纯模板分数会和数字 4 缠在一起。
 */
final class PlusShape {
    private PlusShape() {
    }

    static boolean looksLikePlus(byte[] pixels, int width, int height) {
        if (width < 8 || height < 10) {
            return false;
        }
        int[] rows = new int[height];
        int[] cols = new int[width];
        int ink = 0;
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                if (pixels[y * width + x] == 0) {
                    continue;
                }
                rows[y]++;
                cols[x]++;
                ink++;
            }
        }
        if (ink == 0 || ink > width * height * 0.62) {
            return false;
        }
        int row = indexOfMax(rows);
        int col = indexOfMax(cols);
        if (rows[row] < width * 0.60 || cols[col] < height * 0.42) {
            return false;
        }
        if (row <= height * 0.22 || row >= height * 0.78) {
            return false;
        }
        if (!barExtendsBothWays(pixels, width, row, col)) {
            return false;
        }
        if (!stemExtendsBothWays(pixels, width, height, row, col)) {
            return false;
        }
        if (tallColumns(cols, height) > 7) {
            return false;
        }
        return onCross(pixels, width, height, row, col, ink);
    }

    /**
     * 加号的墨水几乎都在横竖两笔上。数字 4 被粗干扰线穿过时也有十字，但斜笔落在十字外面。
     */
    private static boolean onCross(byte[] pixels, int width, int height, int row, int col, int ink) {
        int on = 0;
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                if (pixels[y * width + x] == 0) {
                    continue;
                }
                if (Math.abs(y - row) <= 2 || Math.abs(x - col) <= 2) {
                    on++;
                }
            }
        }
        return on >= ink * 0.72;
    }

    private static boolean barExtendsBothWays(byte[] pixels, int width, int row, int col) {
        int left = 0;
        for (int x = 0; x < col; x++) {
            left += pixels[row * width + x];
        }
        int right = 0;
        for (int x = col + 1; x < width; x++) {
            right += pixels[row * width + x];
        }
        return left >= width * 0.18 && right >= width * 0.10;
    }

    private static boolean stemExtendsBothWays(byte[] pixels, int width, int height, int row, int col) {
        int above = 0;
        for (int y = 0; y < row; y++) {
            above += pixels[y * width + col];
        }
        int below = 0;
        for (int y = row + 1; y < height; y++) {
            below += pixels[y * width + col];
        }
        return above >= height * 0.12 && below >= height * 0.12;
    }

    private static int tallColumns(int[] cols, int height) {
        int tall = 0;
        double limit = height * 0.55;
        for (int i = 0; i < cols.length; i++) {
            if (cols[i] >= limit) {
                tall++;
            }
        }
        return tall;
    }

    private static int indexOfMax(int[] values) {
        int index = 0;
        for (int i = 1; i < values.length; i++) {
            if (values[i] > values[index]) {
                index = i;
            }
        }
        return index;
    }
}
