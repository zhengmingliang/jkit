package com.alianga.jkit.captcha;

import org.junit.Assert;
import org.junit.Test;

/**
 * LineScrubber 只去又细又长的直线：长度达到 minRun 且两侧邻域无墨；
 * 短线和成块笔画不受影响。
 */
public class LineScrubberTest {

    @Test
    public void longThinLineRemoved() {
        int width = 60;
        int height = 20;
        byte[] data = new byte[width * height];
        hline(data, width, 5, 5, 54);
        LineScrubber.scrub(data, width, height);
        for (int x = 5; x <= 54; x++) {
            Assert.assertEquals("横线像素应被清除 x=" + x, 0, data[5 * width + x]);
        }
    }

    @Test
    public void shortLineKept() {
        int width = 60;
        int height = 20;
        byte[] data = new byte[width * height];
        hline(data, width, 5, 20, 40);
        LineScrubber.scrub(data, width, height);
        for (int x = 20; x <= 40; x++) {
            Assert.assertEquals("短横线应保留 x=" + x, 1, data[5 * width + x]);
        }
    }

    @Test
    public void solidBlobKept() {
        int width = 60;
        int height = 20;
        byte[] data = new byte[width * height];
        hline(data, width, 2, 2, 57);
        for (int y = 10; y < 16; y++) {
            for (int x = 20; x < 26; x++) {
                data[y * width + x] = 1;
            }
        }
        LineScrubber.scrub(data, width, height);
        for (int y = 10; y < 16; y++) {
            for (int x = 20; x < 26; x++) {
                Assert.assertEquals("笔画块应保留", 1, data[y * width + x]);
            }
        }
    }

    /** 在 [x0, x1] 画一条 1 像素高的横线。 */
    private static void hline(byte[] data, int width, int y, int x0, int x1) {
        for (int x = x0; x <= x1; x++) {
            data[y * width + x] = 1;
        }
    }
}
