package com.alianga.jkit.captcha;

import org.junit.Assert;
import org.junit.Test;

/**
 * GlyphGeometry 的几何操作。输入输出都是直接构造的 RawGlyph，不依赖图像。
 */
public class GlyphGeometryTest {

    @Test
    public void unionCoversBothBoxesAndKeepsInk() {
        RawGlyph left = glyph(0, 0, 2, 1, new int[] {1, 0});
        RawGlyph right = glyph(3, 1, 1, 2, new int[] {0, 1});
        RawGlyph merged = GlyphGeometry.union(left, right);
        Assert.assertEquals(0, merged.x);
        Assert.assertEquals(0, merged.y);
        Assert.assertEquals(4, merged.width);
        Assert.assertEquals(3, merged.height);
        Assert.assertEquals(2, merged.area);
        Assert.assertEquals(1, merged.pixels[0]);
        Assert.assertEquals(1, merged.pixels[2 * 4 + 3]);
        Assert.assertEquals(0, merged.pixels[2 * 4 + 1]);
    }

    @Test
    public void cropColumnsKeepsInkAndOffsets() {
        // 5×4，第 1..3 列有墨，第 4 列全空。
        RawGlyph glyph = glyph(10, 20, 5, 4, new int[] {
                0, 1, 1, 1, 0,
                0, 1, 0, 1, 0,
                0, 1, 1, 1, 0,
                0, 1, 0, 1, 0,
        });
        RawGlyph cropped = GlyphGeometry.cropColumns(glyph, 1, 3);
        Assert.assertNotNull(cropped);
        Assert.assertEquals(11, cropped.x);
        Assert.assertEquals(20, cropped.y);
        Assert.assertEquals(3, cropped.width);
        Assert.assertEquals(4, cropped.height);
        Assert.assertEquals(10, cropped.area);

        // 区间里全是背景：返回 null，由调用方决定丢弃。
        Assert.assertNull(GlyphGeometry.cropColumns(glyph, 4, 4));
    }

    /** grid 按行优先给出每个像素的 0/1。 */
    private static RawGlyph glyph(int x, int y, int width, int height, int[] grid) {
        byte[] pixels = new byte[width * height];
        int area = 0;
        for (int i = 0; i < grid.length; i++) {
            if (grid[i] != 0) {
                pixels[i] = 1;
                area++;
            }
        }
        return new RawGlyph(x, y, width, height, pixels, area);
    }
}
