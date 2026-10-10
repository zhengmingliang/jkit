package com.alianga.jkit.captcha;

/**
 * 把任意大小的二值字形缩放到固定网格，供模板比对。保持宽高比并居中。
 */
final class GlyphNormalizer {
    static final int WIDTH = 24;
    static final int HEIGHT = 32;

    private GlyphNormalizer() {
    }

    static byte[] toGrid(byte[] pixels, int width, int height) {
        int minX = width;
        int minY = height;
        int maxX = -1;
        int maxY = -1;
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                if (pixels[y * width + x] == 0) {
                    continue;
                }
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
        byte[] grid = new byte[WIDTH * HEIGHT];
        if (maxX < minX) {
            return grid;
        }
        int boxWidth = maxX - minX + 1;
        int boxHeight = maxY - minY + 1;
        double scale = Math.min((WIDTH - 2) / (double) boxWidth, (HEIGHT - 2) / (double) boxHeight);
        int targetWidth = Math.max(1, (int) Math.round(boxWidth * scale));
        int targetHeight = Math.max(1, (int) Math.round(boxHeight * scale));
        int offsetX = (WIDTH - targetWidth) / 2;
        int offsetY = (HEIGHT - targetHeight) / 2;
        for (int y = 0; y < targetHeight; y++) {
            int sourceY = minY + Math.min(boxHeight - 1, (int) (y / scale));
            for (int x = 0; x < targetWidth; x++) {
                int sourceX = minX + Math.min(boxWidth - 1, (int) (x / scale));
                if (pixels[sourceY * width + sourceX] != 0) {
                    grid[(offsetY + y) * WIDTH + offsetX + x] = 1;
                }
            }
        }
        return grid;
    }

    static int ink(byte[] grid) {
        int count = 0;
        for (int i = 0; i < grid.length; i++) {
            count += grid[i];
        }
        return count;
    }

    static double dice(byte[] left, byte[] right) {
        int intersection = 0;
        int leftInk = 0;
        int rightInk = 0;
        for (int i = 0; i < left.length; i++) {
            int leftBit = left[i];
            int rightBit = right[i];
            leftInk += leftBit;
            rightInk += rightBit;
            if (leftBit != 0 && rightBit != 0) {
                intersection++;
            }
        }
        int total = leftInk + rightInk;
        if (total == 0) {
            return 0;
        }
        return (2.0 * intersection) / total;
    }
}
