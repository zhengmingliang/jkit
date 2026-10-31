package com.alianga.jkit.captcha;

import java.awt.image.BufferedImage;
import java.io.File;

import javax.imageio.ImageIO;

import org.junit.Assert;
import org.junit.Test;

/**
 * 模型与资源加载失败时识别静默降级，这里用探针把降级状态观测出来。
 * 同时兼作资源打包守卫：ocr/ 下的模型、字形表、汉字表被打包过滤掉时这组断言会红。
 */
public class DegradationProbeTest {

    @Test
    public void probesReportHealthyWhenResourcesBundled() throws Exception {
        BufferedImage image = ImageIO.read(new File("src/test/resources/samples/14+8.jpeg"));
        TextModel model = new TextModel();
        try {
            String text = model.read(image, TemplateBank.ALPHANUMERIC);
            Assert.assertFalse("主模型应能读出文本", text.isEmpty());
            Assert.assertTrue(model.primaryAvailable());
            Assert.assertTrue(model.betaAvailable());
            Assert.assertNull(model.lastLoadError());
        } finally {
            model.close();
        }

        Assert.assertTrue("easy 字形表应能加载", EasyPlate.banksAvailable());
        Assert.assertTrue("汉字字符集应能加载", WhitePlate.hanCharsetAvailable());
    }

    @Test
    public void recognizerIsModelAvailableTrueAfterUse() throws Exception {
        try (CaptchaRecognizer recognizer = CaptchaRecognizer.createDefault()) {
            BufferedImage image = ImageIO.read(new File("src/test/resources/samples/9050.jpg"));
            recognizer.recognize(image);
            Assert.assertTrue(recognizer.isModelAvailable());
        }
    }
}
