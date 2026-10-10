package com.alianga.jkit.captcha;

/**
 * 去掉二值图上又细又长的直线。粗笔画两侧有墨，不会被当成干扰线。
 */
final class LineScrubber {
    private LineScrubber() {
    }

    static void scrub(byte[] data, int width, int height) {
        byte[] kill = new byte[data.length];
        mark(data, kill, width, height, 1, 0, 0, 1, 28);
        mark(data, kill, width, height, 0, 1, 1, 0, Math.max(28, height * 3 / 4));
        mark(data, kill, width, height, 1, 1, 1, -1, 24);
        mark(data, kill, width, height, 1, -1, 1, 1, 24);
        for (int i = 0; i < data.length; i++) {
            if (kill[i] != 0) {
                data[i] = 0;
            }
        }
    }

    private static void mark(byte[] data, byte[] kill, int width, int height,
                             int dx, int dy, int px, int py, int minRun) {
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                if (data[y * width + x] == 0 || !head(data, width, height, x, y, dx, dy)) {
                    continue;
                }
                int length = run(data, width, height, x, y, dx, dy, px, py);
                if (length < minRun) {
                    continue;
                }
                int cx = x;
                int cy = y;
                for (int i = 0; i < length; i++) {
                    kill[cy * width + cx] = 1;
                    cx += dx;
                    cy += dy;
                }
            }
        }
    }

    private static boolean head(byte[] data, int width, int height, int x, int y, int dx, int dy) {
        int px = x - dx;
        int py = y - dy;
        return !inside(px, py, width, height) || data[py * width + px] == 0;
    }

    private static int run(byte[] data, int width, int height, int x, int y,
                           int dx, int dy, int px, int py) {
        int length = 0;
        int cx = x;
        int cy = y;
        while (length < 80 && inside(cx, cy, width, height) && data[cy * width + cx] != 0) {
            if (!thin(data, width, height, cx, cy, px, py)) {
                break;
            }
            length++;
            cx += dx;
            cy += dy;
        }
        return length;
    }

    private static boolean thin(byte[] data, int width, int height, int x, int y, int px, int py) {
        return empty(data, width, height, x + px, y + py) && empty(data, width, height, x - px, y - py);
    }

    private static boolean empty(byte[] data, int width, int height, int x, int y) {
        if (!inside(x, y, width, height)) {
            return true;
        }
        return data[y * width + x] == 0;
    }

    private static boolean inside(int x, int y, int width, int height) {
        return x >= 0 && y >= 0 && x < width && y < height;
    }
}
