package com.alianga.jkit.captcha;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * 浅色背景上的字符验证码。深色文字走亮度阈值，彩色文字走饱和度。
 * 被细线粘连的宽块按垂直投影切开。
 */
final class LightSegmenter {
    /** 这次分割是否切开了粘连字符，供类型判断使用。 */
    boolean splitWideComponent;

    List<RawGlyph> segment(RgbImage image) {
        splitWideComponent = false;
        byte[] mask = binarize(image);
        if (image.height >= 48) {
            LineScrubber.scrub(mask, image.width, image.height);
        }
        BinaryImage binary = new BinaryImage(image.width, image.height);
        System.arraycopy(mask, 0, binary.data, 0, mask.length);
        List<RawGlyph> parts = binary.components(12, true);
        List<RawGlyph> glyphs = new ArrayList<RawGlyph>();
        for (int i = 0; i < parts.size(); i++) {
            RawGlyph glyph = parts.get(i);
            if (glyph.width > image.width * 0.92) {
                continue;
            }
            if (isNoise(glyph, image.height)) {
                if (isDot(glyph)) {
                    glyphs.add(glyph);
                }
                continue;
            }
            if (glyph.density() < 0.08) {
                continue;
            }
            if (image.height >= 48 && isStrike(glyph)) {
                continue;
            }
            if (glyph.width > glyph.height * 1.35 && glyph.width >= 16) {
                List<RawGlyph> pieces = split(glyph);
                if (pieces.size() > 1) {
                    splitWideComponent = true;
                    glyphs.addAll(pieces);
                    continue;
                }
            }
            glyphs.add(glyph);
        }
        glyphs = mergeDots(glyphs);
        Collections.sort(glyphs, new Comparator<RawGlyph>() {
            @Override
            public int compare(RawGlyph left, RawGlyph right) {
                return left.x - right.x;
            }
        });
        return glyphs;
    }

    static byte[] foreground(RgbImage image) {
        return binarize(image);
    }

    private static byte[] binarize(RgbImage image) {
        int background = image.borderMedianLuminance();
        int darkCount = 0;
        int colorCount = 0;
        boolean[] dark = new boolean[image.pixels.length];
        boolean[] color = new boolean[image.pixels.length];
        for (int i = 0; i < image.pixels.length; i++) {
            int red = image.red(i);
            int green = image.green(i);
            int blue = image.blue(i);
            int luminance = RgbImage.luminance(image.pixels[i]);
            int saturation = RgbImage.saturation(red, green, blue);
            int sum = red + green + blue;
            if (luminance <= 168 && luminance < background - 25) {
                dark[i] = true;
                darkCount++;
            } else if (saturation >= 45 && sum < 680 && luminance < 245) {
                color[i] = true;
                colorCount++;
            }
        }
        byte[] mask = new byte[image.pixels.length];
        for (int i = 0; i < mask.length; i++) {
            if (dark[i]) {
                mask[i] = 1;
            }
        }
        if (colorCount > darkCount) {
            for (int i = 0; i < mask.length; i++) {
                if (color[i]) {
                    mask[i] = 1;
                }
            }
            return mask;
        }
        addColorGlyphs(mask, color, image.width, image.height);
        return mask;
    }

    /**
     * 深色像素已经够多时，仍保留成块的亮色字符。零散的椭圆干扰圈又矮又小，不并进来。
     */
    private static void addColorGlyphs(byte[] mask, boolean[] color, int width, int height) {
        BinaryImage binary = new BinaryImage(width, height);
        for (int i = 0; i < color.length; i++) {
            if (color[i] && mask[i] == 0) {
                binary.data[i] = 1;
            }
        }
        int minHeight = Math.max(8, height * 9 / 20);
        List<RawGlyph> parts = binary.components(20, true);
        for (int i = 0; i < parts.size(); i++) {
            RawGlyph glyph = parts.get(i);
            if (glyph.height < minHeight || glyph.density() < 0.12) {
                continue;
            }
            for (int y = 0; y < glyph.height; y++) {
                for (int x = 0; x < glyph.width; x++) {
                    if (glyph.pixels[y * glyph.width + x] == 0) {
                        continue;
                    }
                    mask[(glyph.y + y) * width + glyph.x + x] = 1;
                }
            }
        }
    }

    private static List<RawGlyph> split(RawGlyph glyph) {
        int[] columns = new int[glyph.width];
        for (int y = 0; y < glyph.height; y++) {
            for (int x = 0; x < glyph.width; x++) {
                columns[x] += glyph.pixels[y * glyph.width + x];
            }
        }
        List<RawGlyph> strict = pieces(glyph, columns, 2, 12);
        if (strict.size() > 1) {
            return strict;
        }
        if (glyph.height >= 22 && glyph.width >= 50) {
            List<RawGlyph> loose = pieces(glyph, columns, 5, 12);
            if (loose.size() > 1) {
                return loose;
            }
        }
        if (glyph.width >= 40) {
            List<RawGlyph> soft = pieces(glyph, columns, 3, 40);
            if (soft.size() > 1) {
                return soft;
            }
        }
        List<RawGlyph> original = new ArrayList<RawGlyph>();
        original.add(glyph);
        return original;
    }

    private static List<RawGlyph> pieces(RawGlyph glyph, int[] columns, int gap, int minArea) {
        List<int[]> spans = new ArrayList<int[]>();
        int start = -1;
        for (int x = 0; x < glyph.width; x++) {
            if (columns[x] >= gap && start < 0) {
                start = x;
            } else if (columns[x] < gap && start >= 0) {
                spans.add(new int[] {start, x - 1});
                start = -1;
            }
        }
        if (start >= 0) {
            spans.add(new int[] {start, glyph.width - 1});
        }
        List<RawGlyph> pieces = new ArrayList<RawGlyph>();
        for (int i = 0; i < spans.size(); i++) {
            int from = spans.get(i)[0];
            int to = spans.get(i)[1];
            if (to - from + 1 < 4) {
                continue;
            }
            RawGlyph piece = cropColumns(glyph, from, to);
            if (piece == null || piece.area < minArea || piece.height < 8) {
                continue;
            }
            if (minArea >= 40 && piece.width < 8) {
                continue;
            }
            pieces.add(piece);
        }
        return pieces;
    }

    private static RawGlyph cropColumns(RawGlyph glyph, int from, int to) {
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

    private static List<RawGlyph> mergeDots(List<RawGlyph> glyphs) {
        boolean[] used = new boolean[glyphs.size()];
        List<RawGlyph> merged = new ArrayList<RawGlyph>();
        for (int i = 0; i < glyphs.size(); i++) {
            if (used[i]) {
                continue;
            }
            RawGlyph glyph = glyphs.get(i);
            if (!isDot(glyph)) {
                continue;
            }
            int host = findHost(glyphs, used, glyph);
            if (host < 0) {
                continue;
            }
            used[i] = true;
            used[host] = true;
            merged.add(union(glyph, glyphs.get(host)));
        }
        for (int i = 0; i < glyphs.size(); i++) {
            if (!used[i]) {
                merged.add(glyphs.get(i));
            }
        }
        return merged;
    }

    /**
     * 浅色底上的椭圆干扰圈又矮又窄。字符，尤其是小写，会更高。
     * 点状部件留给 {@link #mergeDots} 拼回 i、j。
     */
    private static boolean isNoise(RawGlyph glyph, int imageHeight) {
        if (glyph.height < 8) {
            return true;
        }
        if (imageHeight < 36 || glyph.height > 14 || glyph.width > 16) {
            return false;
        }
        return glyph.area < 110 && glyph.density() < 0.50;
    }

    /**
     * 横穿字符的干扰线又扁又稀，切开后会变成假字符。
     */
    private static boolean isStrike(RawGlyph glyph) {
        return glyph.height <= 14 && glyph.width >= glyph.height * 2 && glyph.density() < 0.30;
    }

    private static boolean isDot(RawGlyph glyph) {
        if (glyph.width > 10 || glyph.height > 8 || glyph.area < 6 || glyph.area >= 45) {
            return false;
        }
        return glyph.density() >= 0.3;
    }

    private static int findHost(List<RawGlyph> glyphs, boolean[] used, RawGlyph dot) {
        int best = -1;
        int bestGap = Integer.MAX_VALUE;
        int dotBottom = dot.y + dot.height - 1;
        for (int i = 0; i < glyphs.size(); i++) {
            if (used[i] || glyphs.get(i) == dot || isDot(glyphs.get(i))) {
                continue;
            }
            RawGlyph host = glyphs.get(i);
            int overlap = Math.min(dot.x + dot.width, host.x + host.width) - Math.max(dot.x, host.x);
            if (overlap < -3) {
                continue;
            }
            int gap = host.y - dotBottom - 1;
            if (gap < -2 || gap > 5) {
                continue;
            }
            if (gap < bestGap) {
                bestGap = gap;
                best = i;
            }
        }
        return best;
    }

    private static RawGlyph union(RawGlyph left, RawGlyph right) {
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

    private static void blit(byte[] target, int targetWidth, int minX, int minY, RawGlyph glyph) {
        for (int y = 0; y < glyph.height; y++) {
            for (int x = 0; x < glyph.width; x++) {
                if (glyph.pixels[y * glyph.width + x] == 0) {
                    continue;
                }
                target[(glyph.y - minY + y) * targetWidth + glyph.x - minX + x] = 1;
            }
        }
    }
}
