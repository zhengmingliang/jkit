package com.alianga.jkit.captcha;

import java.util.List;

import org.junit.Assert;
import org.junit.Test;

/**
 * BinaryImage 连通域：面积过滤与 4/8 邻域的选择。
 */
public class BinaryImageTest {

    @Test
    public void componentsFilterSmallAreas() {
        BinaryImage image = new BinaryImage(20, 10);
        fill(image, 1, 1, 3, 3);
        fill(image, 10, 5, 2, 2);
        image.set(18, 8, true);

        Assert.assertEquals(2, image.components(4, true).size());
        Assert.assertEquals(1, image.components(9, true).size());
    }

    @Test
    public void diagonalConnectionFollowsNeighborhood() {
        BinaryImage image = new BinaryImage(4, 2);
        image.set(0, 0, true);
        image.set(1, 1, true);
        Assert.assertEquals(1, image.components(1, true).size());
        Assert.assertEquals(2, image.components(1, false).size());
    }

    @Test
    public void emptyImageHasNoComponents() {
        Assert.assertTrue(new BinaryImage(6, 6).components(1, true).isEmpty());
    }

    private static void fill(BinaryImage image, int x0, int y0, int width, int height) {
        for (int y = y0; y < y0 + height; y++) {
            for (int x = x0; x < x0 + width; x++) {
                image.set(x, y, true);
            }
        }
    }
}
