package com.alianga.jkit.captcha;

import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;

import org.junit.Assert;
import org.junit.Test;

/**
 * 公共入口对退化输入的行为。
 * BufferedImage 自身不允许 0 尺寸（构造器与 getSubimage 都会抛异常），
 * 但调用方可能传入谎报尺寸的子类，畸形 SPI 解码产物也可能产生这类图，
 * 入口统一给 IllegalArgumentException，而不是让深处报数组越界。
 * GIF 多帧合成对尺寸不符的帧跳过，与 WhitePlate.matches 同口径。
 */
public class InputGuardTest {

    @Test
    public void recognizeRejectsZeroReportedSize() {
        try (CaptchaRecognizer recognizer = CaptchaRecognizer.createDefault()) {
            BufferedImage lying = new BufferedImage(10, 10, BufferedImage.TYPE_INT_RGB) {
                @Override
                public int getWidth() {
                    return 0;
                }
            };
            try {
                recognizer.recognize(lying);
                Assert.fail("0 宽度应抛 IllegalArgumentException");
            } catch (IllegalArgumentException expected) {
                Assert.assertEquals("image size", expected.getMessage());
            }
        }
    }

    @Test
    public void learnRejectsZeroReportedHeight() {
        try (CaptchaRecognizer recognizer = CaptchaRecognizer.createDefault()) {
            BufferedImage lying = new BufferedImage(10, 10, BufferedImage.TYPE_INT_RGB) {
                @Override
                public int getHeight() {
                    return 0;
                }
            };
            try {
                recognizer.learn(lying, 'Y');
                Assert.fail("0 高度应抛 IllegalArgumentException");
            } catch (IllegalArgumentException expected) {
                Assert.assertEquals("image size", expected.getMessage());
            }
        }
    }

    @Test
    public void oneByOneImageDoesNotThrow() {
        try (CaptchaRecognizer recognizer = CaptchaRecognizer.createDefault()) {
            BufferedImage one = new BufferedImage(1, 1, BufferedImage.TYPE_INT_RGB);
            one.setRGB(0, 0, 0xFFFFFF);
            RecognizeResult result = recognizer.recognize(one);
            Assert.assertNotNull(result);
        }
    }

    @Test
    public void compositeSkipsMismatchedFrames() {
        List<BufferedImage> frames = new ArrayList<BufferedImage>();
        frames.add(uniform(20, 20, 0xFFFFFF));
        frames.add(uniform(20, 20, 0xFFFFFF));
        frames.add(uniform(10, 10, 0x000000));
        BufferedImage composed = GifFrames.composite(frames);
        Assert.assertEquals(20, composed.getWidth());
        Assert.assertEquals(20, composed.getHeight());
        // 两帧全白：合成结果应全白。10×10 的帧被跳过而不是抛越界。
        Assert.assertEquals(0xFFFFFFFF, composed.getRGB(0, 0));

        List<BufferedImage> darker = new ArrayList<BufferedImage>();
        BufferedImage first = uniform(20, 20, 0xFFFFFF);
        first.setRGB(0, 0, 0x000000);
        darker.add(first);
        BufferedImage second = uniform(20, 20, 0xFFFFFF);
        second.setRGB(0, 0, 0x101010);
        darker.add(second);
        BufferedImage composedDark = GifFrames.composite(darker);
        // 两帧在 (0,0) 都偏暗：合成后为暗底色；其余像素仍为白。
        Assert.assertEquals(0xFF202020, composedDark.getRGB(0, 0));
        Assert.assertEquals(0xFFFFFFFF, composedDark.getRGB(19, 19));
    }

    private static BufferedImage uniform(int width, int height, int rgb) {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                image.setRGB(x, y, rgb);
            }
        }
        return image;
    }
}
