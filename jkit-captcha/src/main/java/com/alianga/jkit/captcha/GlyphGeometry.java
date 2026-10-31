package com.alianga.jkit.captcha;

/**
 * 字形像素几何操作。Dark/Light/Outline 三个分割器共用，实现与原先
 * 各处私有副本逐行一致。
 */
final class GlyphGeometry {
    private GlyphGeometry() {
    }

    static RawGlyph union(RawGlyph left, RawGlyph right) {
        int minX = Math.min(left.x, right.x);
        int minY = Math.min(left.y, right.y);
        int width = Math.max(left.x + left.width, right.x + right.width) - minX;
        int height = Math.max(left.y + left.height, right.y + right.height) - minY;
        byte[] pixels = new byte[width * height];
        blit(pixels, width, minX, minY, left);
        blit(pixels, width, minX, minY, right);
        int area = 0;
        for (int i = 0; i < pixels.length; i++) {
            area += pixels[i];
        }
        return new RawGlyph(minX, minY, width, height, pixels, area);
    }

    static void blit(byte[] target, int targetWidth, int minX, int minY, RawGlyph glyph) {
        for (int y = 0; y < glyph.height; y++) {
            for (int x = 0; x < glyph.width; x++) {
                if (glyph.pixels[y * glyph.width + x] == 0) {
                    continue;
                }
                int tx = glyph.x - minX + x;
                int ty = glyph.y - minY + y;
                target[ty * targetWidth + tx] = 1;
            }
        }
    }

    static RawGlyph cropColumns(RawGlyph glyph, int from, int to) {
        int top = glyph.height;
        int bottom = -1;
        int area = 0;
        for (int y = 0; y < glyph.height; y++) {
            for (int x = from; x <= to; x++) {
                if (glyph.pixels[y * glyph.width + x] == 0) {
                    continue;
                }
                area++;
                if (y < top) {
                    top = y;
                }
                if (y > bottom) {
                    bottom = y;
                }
            }
        }
        if (bottom < top) {
            return null;
        }
        int width = to - from + 1;
        int height = bottom - top + 1;
        byte[] pixels = new byte[width * height];
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                pixels[y * width + x] = glyph.pixels[(top + y) * glyph.width + from + x];
            }
        }
        return new RawGlyph(glyph.x + from, glyph.y + top, width, height, pixels, area);
    }
}
