package com.alianga.jkit.captcha;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * 灰底空心描边字符。抽出暗色笔画并做一次闭运算，断裂的左右碎片再拼回去。
 */
final class OutlineSegmenter {
    static boolean matches(RgbImage image) {
        int background = image.borderMedianLuminance();
        if (background < 160 || background > 235) {
            return false;
        }
        int dark = 0;
        int white = 0;
        for (int i = 0; i < image.pixels.length; i++) {
            int luminance = RgbImage.luminance(image.pixels[i]);
            if (luminance < 140) {
                dark++;
            }
            if (luminance > 245) {
                white++;
            }
        }
        double total = image.pixels.length;
        return dark / total > 0.04 && dark / total < 0.45 && white / total > 0.08;
    }

    List<RawGlyph> segment(RgbImage image) {
        BinaryImage binary = new BinaryImage(image.width, image.height);
        for (int i = 0; i < image.pixels.length; i++) {
            if (RgbImage.luminance(image.pixels[i]) < 145) {
                binary.data[i] = 1;
            }
        }
        List<RawGlyph> parts = binary.components(30, true);
        List<RawGlyph> glyphs = new ArrayList<RawGlyph>();
        for (int i = 0; i < parts.size(); i++) {
            RawGlyph glyph = parts.get(i);
            if (glyph.height < Math.max(12, image.height / 3)) {
                continue;
            }
            glyphs.add(withClosedStroke(glyph));
        }
        Collections.sort(glyphs, new Comparator<RawGlyph>() {
            @Override
            public int compare(RawGlyph left, RawGlyph right) {
                return left.x - right.x;
            }
        });
        return mergeFragments(glyphs);
    }

    /**
     * 描边断裂时一个字会变成左右两块。间距很小且合起来仍像一个字宽时拼回去。
     */
    private static List<RawGlyph> mergeFragments(List<RawGlyph> glyphs) {
        List<RawGlyph> merged = new ArrayList<RawGlyph>(glyphs);
        boolean changed = true;
        while (changed) {
            changed = false;
            for (int i = 0; i + 1 < merged.size(); i++) {
                RawGlyph left = merged.get(i);
                RawGlyph right = merged.get(i + 1);
                int gap = right.x - (left.x + left.width);
                int span = right.x + right.width - left.x;
                if (gap > 4 || span > 34) {
                    continue;
                }
                merged.set(i, union(left, right));
                merged.remove(i + 1);
                changed = true;
                break;
            }
        }
        return merged;
    }

    private static RawGlyph union(RawGlyph left, RawGlyph right) {
        int x = Math.min(left.x, right.x);
        int y = Math.min(left.y, right.y);
        int width = Math.max(left.x + left.width, right.x + right.width) - x;
        int height = Math.max(left.y + left.height, right.y + right.height) - y;
        byte[] pixels = new byte[width * height];
        stamp(pixels, width, x, y, left);
        stamp(pixels, width, x, y, right);
        int area = 0;
        for (int i = 0; i < pixels.length; i++) {
            area += pixels[i];
        }
        return new RawGlyph(x, y, width, height, pixels, area);
    }

    private static void stamp(byte[] pixels, int width, int originX, int originY, RawGlyph glyph) {
        for (int y = 0; y < glyph.height; y++) {
            for (int x = 0; x < glyph.width; x++) {
                if (glyph.pixels[y * glyph.width + x] == 0) {
                    continue;
                }
                pixels[(glyph.y - originY + y) * width + (glyph.x - originX + x)] = 1;
            }
        }
    }

    private static RawGlyph withClosedStroke(RawGlyph glyph) {
        byte[] closed = closeStroke(glyph.pixels, glyph.width, glyph.height);
        int area = 0;
        for (int i = 0; i < closed.length; i++) {
            area += closed[i];
        }
        return new RawGlyph(glyph.x, glyph.y, glyph.width, glyph.height, closed, area);
    }

    private static RawGlyph fillHoles(RawGlyph glyph) {
        byte[] closed = closeStroke(glyph.pixels, glyph.width, glyph.height);
        int width = glyph.width + 2;
        int height = glyph.height + 2;
        byte[] mask = new byte[width * height];
        for (int y = 0; y < glyph.height; y++) {
            for (int x = 0; x < glyph.width; x++) {
                mask[(y + 1) * width + (x + 1)] = closed[y * glyph.width + x];
            }
        }
        byte[] reached = new byte[mask.length];
        floodBackground(mask, reached, width, height);
        byte[] filled = new byte[glyph.width * glyph.height];
        int area = 0;
        for (int y = 0; y < glyph.height; y++) {
            for (int x = 0; x < glyph.width; x++) {
                int index = (y + 1) * width + (x + 1);
                if (mask[index] != 0 || reached[index] == 0) {
                    filled[y * glyph.width + x] = 1;
                    area++;
                }
            }
        }
        return new RawGlyph(glyph.x, glyph.y, glyph.width, glyph.height, filled, area);
    }

    private static byte[] closeStroke(byte[] pixels, int width, int height) {
        return erode(dilate(pixels, width, height), width, height);
    }

    private static byte[] dilate(byte[] pixels, int width, int height) {
        byte[] grown = new byte[pixels.length];
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                if (covers(pixels, width, height, x, y)) {
                    grown[y * width + x] = 1;
                }
            }
        }
        return grown;
    }

    private static byte[] erode(byte[] pixels, int width, int height) {
        byte[] shrunk = new byte[pixels.length];
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                if (surrounded(pixels, width, height, x, y)) {
                    shrunk[y * width + x] = 1;
                }
            }
        }
        return shrunk;
    }

    private static boolean surrounded(byte[] pixels, int width, int height, int x, int y) {
        for (int dy = -1; dy <= 1; dy++) {
            for (int dx = -1; dx <= 1; dx++) {
                if (dx != 0 && dy != 0) {
                    continue;
                }
                int nx = x + dx;
                int ny = y + dy;
                if (nx < 0 || ny < 0 || nx >= width || ny >= height || pixels[ny * width + nx] == 0) {
                    return false;
                }
            }
        }
        return true;
    }

    private static boolean covers(byte[] pixels, int width, int height, int x, int y) {
        for (int dy = -1; dy <= 1; dy++) {
            for (int dx = -1; dx <= 1; dx++) {
                if (dx != 0 && dy != 0) {
                    continue;
                }
                int nx = x + dx;
                int ny = y + dy;
                if (nx < 0 || ny < 0 || nx >= width || ny >= height) {
                    continue;
                }
                if (pixels[ny * width + nx] != 0) {
                    return true;
                }
            }
        }
        return false;
    }

    private static void floodBackground(byte[] mask, byte[] reached, int width, int height) {
        int[] stackX = new int[mask.length];
        int[] stackY = new int[mask.length];
        int top = -1;
        top = seedRow(mask, reached, stackX, stackY, top, width, 0);
        top = seedRow(mask, reached, stackX, stackY, top, width, height - 1);
        top = seedColumn(mask, reached, stackX, stackY, top, height, width, 0);
        top = seedColumn(mask, reached, stackX, stackY, top, height, width, width - 1);
        while (top >= 0) {
            int x = stackX[top];
            int y = stackY[top];
            top--;
            top = open(mask, reached, stackX, stackY, top, width, height, x + 1, y);
            top = open(mask, reached, stackX, stackY, top, width, height, x - 1, y);
            top = open(mask, reached, stackX, stackY, top, width, height, x, y + 1);
            top = open(mask, reached, stackX, stackY, top, width, height, x, y - 1);
        }
    }

    private static int seedRow(byte[] mask, byte[] reached, int[] stackX, int[] stackY,
                               int top, int width, int y) {
        int next = top;
        for (int x = 0; x < width; x++) {
            next = open(mask, reached, stackX, stackY, next, width, reached.length / width, x, y);
        }
        return next;
    }

    private static int seedColumn(byte[] mask, byte[] reached, int[] stackX, int[] stackY,
                                  int top, int height, int width, int x) {
        int next = top;
        for (int y = 0; y < height; y++) {
            next = open(mask, reached, stackX, stackY, next, width, height, x, y);
        }
        return next;
    }

    private static int open(byte[] mask, byte[] reached, int[] stackX, int[] stackY,
                            int top, int width, int height, int x, int y) {
        if (x < 0 || y < 0 || x >= width || y >= height) {
            return top;
        }
        int index = y * width + x;
        if (reached[index] != 0 || mask[index] != 0) {
            return top;
        }
        reached[index] = 1;
        top++;
        stackX[top] = x;
        stackY[top] = y;
        return top;
    }
}
