package com.alianga.jkit.captcha;

import java.util.ArrayList;
import java.util.List;

/**
 * 二值图。0 是背景，1 是前景。
 */
final class BinaryImage {
    final int width;
    final int height;
    final byte[] data;

    BinaryImage(int width, int height) {
        this.width = width;
        this.height = height;
        this.data = new byte[width * height];
    }

    boolean get(int x, int y) {
        return data[y * width + x] != 0;
    }

    void set(int x, int y, boolean foreground) {
        data[y * width + x] = foreground ? (byte) 1 : 0;
    }

    /**
     * 连通域。{@code eight} 为 true 时使用 8 邻域。
     *
     * @param minArea 小于该面积的连通域丢弃
     * @param eight 是否 8 连通
     * @return 按扫描顺序发现的字形，调用方再按 x 排序
     */
    List<RawGlyph> components(int minArea, boolean eight) {
        int length = width * height;
        byte[] seen = new byte[length];
        List<RawGlyph> glyphs = new ArrayList<RawGlyph>();
        int[] stackX = new int[length];
        int[] stackY = new int[length];
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int start = y * width + x;
                if (data[start] == 0 || seen[start] != 0) {
                    continue;
                }
                RawGlyph glyph = flood(x, y, seen, stackX, stackY, eight);
                if (glyph.area >= minArea) {
                    glyphs.add(glyph);
                }
            }
        }
        return glyphs;
    }

    private RawGlyph flood(int startX, int startY, byte[] seen, int[] stackX, int[] stackY, boolean eight) {
        int top = 0;
        stackX[0] = startX;
        stackY[0] = startY;
        seen[startY * width + startX] = 1;
        int minX = startX;
        int maxX = startX;
        int minY = startY;
        int maxY = startY;
        int area = 0;
        int[] pointX = new int[width * height];
        int[] pointY = new int[width * height];
        while (top >= 0) {
            int x = stackX[top];
            int y = stackY[top];
            top--;
            pointX[area] = x;
            pointY[area] = y;
            area++;
            if (x < minX) {
                minX = x;
            }
            if (x > maxX) {
                maxX = x;
            }
            if (y < minY) {
                minY = y;
            }
            if (y > maxY) {
                maxY = y;
            }
            top = pushNeighbors(x, y, seen, stackX, stackY, top, eight);
        }
        int glyphWidth = maxX - minX + 1;
        int glyphHeight = maxY - minY + 1;
        byte[] pixels = new byte[glyphWidth * glyphHeight];
        for (int i = 0; i < area; i++) {
            int localX = pointX[i] - minX;
            int localY = pointY[i] - minY;
            pixels[localY * glyphWidth + localX] = 1;
        }
        return new RawGlyph(minX, minY, glyphWidth, glyphHeight, pixels, area);
    }

    private int pushNeighbors(int x, int y, byte[] seen, int[] stackX, int[] stackY, int top, boolean eight) {
        int next = top;
        for (int dy = -1; dy <= 1; dy++) {
            for (int dx = -1; dx <= 1; dx++) {
                if (dx == 0 && dy == 0) {
                    continue;
                }
                if (!eight && dx != 0 && dy != 0) {
                    continue;
                }
                int nx = x + dx;
                int ny = y + dy;
                if (nx < 0 || ny < 0 || nx >= width || ny >= height) {
                    continue;
                }
                int index = ny * width + nx;
                if (data[index] == 0 || seen[index] != 0) {
                    continue;
                }
                seen[index] = 1;
                next++;
                stackX[next] = nx;
                stackY[next] = ny;
            }
        }
        return next;
    }
}
