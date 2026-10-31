package com.alianga.jkit.captcha;

import java.awt.image.BufferedImage;
import java.util.Random;

import org.junit.Test;

/**
 * DarkSegmenter 的聚类中心数有上限（MAX_CENTERS）。
 * 用远超上限的随机彩色噪点图验证分割仍能正常结束、不抛异常，
 * 防止极端图片把识别线程拖死。
 */
public class DarkSegmenterCapTest {

    @Test
    public void segmentCompletesWhenCentersExceedCap() {
        BufferedImage image = new BufferedImage(64, 64, BufferedImage.TYPE_INT_RGB);
        Random random = new Random(20261031L);
        for (int y = 0; y < image.getHeight(); y++) {
            for (int x = 0; x < image.getWidth(); x++) {
                // 深底上铺随机彩色噪点，几乎每个像素都是独立颜色。
                image.setRGB(x, y, random.nextInt(0x1000000));
            }
        }
        DarkSegmenter segmenter = new DarkSegmenter();
        // 正常返回、不抛异常即为通过；分割出的块数不作断言。
        segmenter.segment(RgbImage.from(image));
    }
}
