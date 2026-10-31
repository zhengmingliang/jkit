package com.alianga.jkit.captcha;

import org.junit.Assert;
import org.junit.Test;

/**
 * HoleCount 封闭孔统计：孔面积至少 3、最多记 2 个。
 */
public class HoleCountTest {

    @Test
    public void solidBlockHasNoHole() {
        Assert.assertEquals(0, HoleCount.count(rect(8, 8, 0, 0, 8, 8), 8, 8));
        Assert.assertEquals(0, HoleCount.count(new byte[8 * 8], 8, 8));
    }

    @Test
    public void ringHasOneHole() {
        byte[] ring = rect(8, 8, 0, 0, 8, 8);
        carve(ring, 8, 2, 2, 4, 4);
        Assert.assertEquals(1, HoleCount.count(ring, 8, 8));
    }

    @Test
    public void twoChambersCountTwo() {
        byte[] twin = rect(13, 6, 0, 0, 13, 6);
        carve(twin, 13, 2, 2, 2, 2);
        carve(twin, 13, 8, 2, 2, 2);
        Assert.assertEquals(2, HoleCount.count(twin, 13, 6));
    }

    @Test
    public void tinyGapBelowMinAreaIsIgnored() {
        byte[] block = rect(6, 6, 0, 0, 6, 6);
        carve(block, 6, 3, 3, 1, 1);
        Assert.assertEquals(0, HoleCount.count(block, 6, 6));
    }

    private static byte[] rect(int width, int height, int x0, int y0, int w, int h) {
        byte[] pixels = new byte[width * height];
        for (int y = y0; y < y0 + h; y++) {
            for (int x = x0; x < x0 + w; x++) {
                pixels[y * width + x] = 1;
            }
        }
        return pixels;
    }

    private static void carve(byte[] pixels, int width, int x0, int y0, int w, int h) {
        for (int y = y0; y < y0 + h; y++) {
            for (int x = x0; x < x0 + w; x++) {
                pixels[y * width + x] = 0;
            }
        }
    }
}
