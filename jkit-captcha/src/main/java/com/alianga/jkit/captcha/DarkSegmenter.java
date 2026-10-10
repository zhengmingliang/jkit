package com.alianga.jkit.captcha;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * 黑底彩色验证码分割。每个字符一种颜色，干扰线是细长的另一颜色。
 * 同色像素聚类后，去掉细直线，再把被线切开的同一字符拼回去。
 */
final class DarkSegmenter {
    private static final int COLOR_DISTANCE = 40 * 40;
    private static final int MIN_CLUSTER = 25;

    List<RawGlyph> segment(RgbImage image) {
        int[] labels = cluster(image);
        int clusterCount = 0;
        for (int i = 0; i < labels.length; i++) {
            if (labels[i] + 1 > clusterCount) {
                clusterCount = labels[i] + 1;
            }
        }
        List<RawGlyph> glyphs = new ArrayList<RawGlyph>();
        for (int cluster = 0; cluster < clusterCount; cluster++) {
            if (countLabel(labels, cluster) < MIN_CLUSTER) {
                continue;
            }
            byte[] mask = maskOf(labels, image.width, image.height, cluster);
            scrub(mask, image.width, image.height);
            glyphs.addAll(glyphsFromMask(mask, image.width, image.height));
        }
        Collections.sort(glyphs, new Comparator<RawGlyph>() {
            @Override
            public int compare(RawGlyph left, RawGlyph right) {
                return left.x - right.x;
            }
        });
        return glyphs;
    }

    private static int[] cluster(RgbImage image) {
        List<double[]> centers = new ArrayList<double[]>();
        int[] labels = new int[image.pixels.length];
        for (int i = 0; i < labels.length; i++) {
            labels[i] = -1;
            int red = image.red(i);
            int green = image.green(i);
            int blue = image.blue(i);
            if (red + green + blue < 80) {
                continue;
            }
            int best = -1;
            int bestDistance = Integer.MAX_VALUE;
            for (int c = 0; c < centers.size(); c++) {
                int distance = distance(red, green, blue, centers.get(c));
                if (distance < bestDistance) {
                    bestDistance = distance;
                    best = c;
                }
            }
            if (best >= 0 && bestDistance <= COLOR_DISTANCE) {
                absorb(centers.get(best), red, green, blue);
                labels[i] = best;
            } else {
                centers.add(new double[] {red, green, blue, 1});
                labels[i] = centers.size() - 1;
            }
        }
        return labels;
    }

    private static void absorb(double[] center, int red, int green, int blue) {
        double count = center[3];
        double next = count + 1;
        center[0] = (center[0] * count + red) / next;
        center[1] = (center[1] * count + green) / next;
        center[2] = (center[2] * count + blue) / next;
        center[3] = next;
    }

    private static int distance(int red, int green, int blue, double[] center) {
        double dr = red - center[0];
        double dg = green - center[1];
        double db = blue - center[2];
        return (int) (dr * dr + dg * dg + db * db);
    }

    private static int countLabel(int[] labels, int cluster) {
        int count = 0;
        for (int i = 0; i < labels.length; i++) {
            if (labels[i] == cluster) {
                count++;
            }
        }
        return count;
    }

    private static byte[] maskOf(int[] labels, int width, int height, int cluster) {
        byte[] mask = new byte[width * height];
        for (int i = 0; i < labels.length; i++) {
            if (labels[i] == cluster) {
                mask[i] = 1;
            }
        }
        return mask;
    }

    private static List<RawGlyph> glyphsFromMask(byte[] mask, int width, int height) {
        BinaryImage binary = new BinaryImage(width, height);
        System.arraycopy(mask, 0, binary.data, 0, mask.length);
        List<RawGlyph> parts = binary.components(12, false);
        boolean[] used = new boolean[parts.size()];
        List<RawGlyph> merged = new ArrayList<RawGlyph>();
        for (int i = 0; i < parts.size(); i++) {
            if (used[i]) {
                continue;
            }
            used[i] = true;
            RawGlyph current = parts.get(i);
            boolean changed = true;
            while (changed) {
                changed = false;
                for (int j = 0; j < parts.size(); j++) {
                    if (used[j]) {
                        continue;
                    }
                    RawGlyph other = parts.get(j);
                    if (!canMerge(current, other)) {
                        continue;
                    }
                    current = union(current, other);
                    used[j] = true;
                    changed = true;
                }
            }
            List<RawGlyph> pieces = splitWide(current);
            for (int p = 0; p < pieces.size(); p++) {
                RawGlyph kept = classify(pieces.get(p), height);
                if (kept != null) {
                    merged.add(kept);
                }
            }
        }
        return merged;
    }

    /**
     * 同色的数字和加号挨在一起时会并成一块。中间有空列就切开，等号只有二十多像素宽，不会走到这里。
     */
    private static List<RawGlyph> splitWide(RawGlyph glyph) {
        List<RawGlyph> single = new ArrayList<RawGlyph>();
        single.add(glyph);
        if (glyph.width < 30) {
            return single;
        }
        int[] columns = new int[glyph.width];
        for (int y = 0; y < glyph.height; y++) {
            for (int x = 0; x < glyph.width; x++) {
                columns[x] += glyph.pixels[y * glyph.width + x];
            }
        }
        List<RawGlyph> pieces = new ArrayList<RawGlyph>();
        int start = -1;
        for (int x = 0; x < glyph.width; x++) {
            if (columns[x] > 1 && start < 0) {
                start = x;
            } else if (columns[x] <= 1 && start >= 0) {
                addSpan(pieces, glyph, start, x - 1);
                start = -1;
            }
        }
        if (start >= 0) {
            addSpan(pieces, glyph, start, glyph.width - 1);
        }
        if (pieces.isEmpty()) {
            return single;
        }
        return pieces;
    }

    private static void addSpan(List<RawGlyph> pieces, RawGlyph glyph, int from, int to) {
        if (to - from + 1 < 8) {
            return;
        }
        RawGlyph piece = cropColumns(glyph, from, to);
        if (piece != null && piece.area >= 40 && piece.height >= 8) {
            pieces.add(piece);
        }
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

    private static boolean canMerge(RawGlyph left, RawGlyph right) {
        if (gap(left.x, left.width, right.x, right.width) > 5) {
            return false;
        }
        if (gap(left.y, left.height, right.y, right.height) > 6) {
            return false;
        }
        int spanX = Math.max(left.x + left.width - 1, right.x + right.width - 1) - Math.min(left.x, right.x);
        int spanY = Math.max(left.y + left.height - 1, right.y + right.height - 1) - Math.min(left.y, right.y);
        return spanX <= 38 && spanY <= 40;
    }

    private static int gap(int origin, int size, int otherOrigin, int otherSize) {
        int end = origin + size - 1;
        int otherEnd = otherOrigin + otherSize - 1;
        if (otherEnd >= origin && end >= otherOrigin) {
            return 0;
        }
        if (otherEnd < origin) {
            return origin - otherEnd - 1;
        }
        return otherOrigin - end - 1;
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
                int tx = glyph.x - minX + x;
                int ty = glyph.y - minY + y;
                target[ty * targetWidth + tx] = 1;
            }
        }
    }

    private static RawGlyph classify(RawGlyph glyph, int imageHeight) {
        double density = glyph.density();
        int center = glyph.centerY();
        boolean middle = center > imageHeight * 0.25 && center < imageHeight * 0.80;
        if (isMinus(glyph, density, center, imageHeight)) {
            glyph.forced = '-';
            return glyph;
        }
        if (middle && isEquals(glyph, density)) {
            glyph.forced = '=';
            return glyph;
        }
        if (glyph.height >= 16 && glyph.width >= 8 && glyph.width <= 40 && density >= 0.2 && glyph.area >= 50) {
            return glyph;
        }
        return null;
    }

    private static boolean isMinus(RawGlyph glyph, double density, int center, int imageHeight) {
        if (glyph.height < 4 || glyph.height > 8 || glyph.width < 8 || glyph.width > 16) {
            return false;
        }
        if (density < 0.7) {
            return false;
        }
        return center > imageHeight * 0.30 && center < imageHeight * 0.75;
    }

    private static boolean isEquals(RawGlyph glyph, double density) {
        if (glyph.height < 10 || glyph.height > 17 || glyph.width < 18 || glyph.width > 34) {
            return false;
        }
        if (density < 0.45) {
            return false;
        }
        return horizontalBands(glyph) >= 2;
    }

    private static int horizontalBands(RawGlyph glyph) {
        int bands = 0;
        boolean inside = false;
        for (int y = 0; y < glyph.height; y++) {
            boolean row = false;
            int offset = y * glyph.width;
            for (int x = 0; x < glyph.width; x++) {
                if (glyph.pixels[offset + x] != 0) {
                    row = true;
                    break;
                }
            }
            if (row && !inside) {
                bands++;
                inside = true;
            } else if (!row) {
                inside = false;
            }
        }
        return bands;
    }

    private static void scrub(byte[] mask, int width, int height) {
        byte[] remove = new byte[mask.length];
        int[][] directions = new int[][] {{1, 0}, {0, 1}, {1, 1}, {1, -1}};
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                if (mask[y * width + x] == 0) {
                    continue;
                }
                markThin(mask, width, height, remove, directions, x, y);
            }
        }
        for (int i = 0; i < mask.length; i++) {
            if (remove[i] != 0) {
                mask[i] = 0;
            }
        }
    }

    private static void markThin(byte[] mask, int width, int height, byte[] remove,
                                 int[][] directions, int x, int y) {
        for (int d = 0; d < directions.length; d++) {
            int dx = directions[d][0];
            int dy = directions[d][1];
            int run = runLength(mask, width, height, x, y, dx, dy);
            if (run < 18) {
                continue;
            }
            if (thickness(mask, width, height, x, y, -dy, dx) <= 2) {
                remove[y * width + x] = 1;
                return;
            }
        }
    }

    private static int runLength(byte[] mask, int width, int height, int x, int y, int dx, int dy) {
        int count = 1 + walk(mask, width, height, x, y, dx, dy) + walk(mask, width, height, x, y, -dx, -dy);
        return count;
    }

    private static int walk(byte[] mask, int width, int height, int x, int y, int dx, int dy) {
        int count = 0;
        int cx = x + dx;
        int cy = y + dy;
        while (count < 40 && inside(width, height, cx, cy) && mask[cy * width + cx] != 0) {
            count++;
            cx += dx;
            cy += dy;
        }
        return count;
    }

    private static int thickness(byte[] mask, int width, int height, int x, int y, int px, int py) {
        int positive = walkLimited(mask, width, height, x, y, px, py);
        int negative = walkLimited(mask, width, height, x, y, -px, -py);
        return positive + negative + 1;
    }

    private static int walkLimited(byte[] mask, int width, int height, int x, int y, int dx, int dy) {
        int count = 0;
        while (count <= 3 && inside(width, height, x + dx * (count + 1), y + dy * (count + 1))
                && mask[(y + dy * (count + 1)) * width + x + dx * (count + 1)] != 0) {
            count++;
        }
        return count;
    }

    private static boolean inside(int width, int height, int x, int y) {
        return x >= 0 && y >= 0 && x < width && y < height;
    }
}
