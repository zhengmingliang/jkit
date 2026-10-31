package com.alianga.jkit.captcha;

import java.awt.image.BufferedImage;
import java.io.File;
import java.io.FilenameFilter;

import javax.imageio.ImageIO;

import org.junit.Assert;
import org.junit.Test;

/**
 * {@code withAlphabet("")} 与未设置必须同义：模板路径和模型路径都按类型取默认字符集，
 * 不允许出现"模板收在字母数字、模型却拿到全字符集"的分叉。回归覆盖 modelAlphabet 的
 * 映射，以及空字符集下静图与 SpecCaptcha 样本的端到端输出字符范围。
 */
public class RecognizeAlphabetTest {

    @Test
    public void modelAlphabetMapsEmptyToDefault() {
        Assert.assertEquals(TemplateBank.ALPHANUMERIC,
                CaptchaRecognizer.modelAlphabet(RecognizeOptions.auto()));
        Assert.assertEquals(TemplateBank.ALPHANUMERIC,
                CaptchaRecognizer.modelAlphabet(RecognizeOptions.auto().withAlphabet("")));
        Assert.assertEquals(TemplateBank.ALPHANUMERIC + "+-=?*/",
                CaptchaRecognizer.modelAlphabet(RecognizeOptions.auto().withType(CaptchaType.ARITHMETIC)));
        Assert.assertEquals(TemplateBank.ALPHANUMERIC + "+-=?*/",
                CaptchaRecognizer.modelAlphabet(RecognizeOptions.auto().withAlphabet("")
                        .withType(CaptchaType.ARITHMETIC)));
        Assert.assertEquals("abc123",
                CaptchaRecognizer.modelAlphabet(RecognizeOptions.auto().withAlphabet("abc123")));
    }

    @Test
    public void recognizeWithEmptyAlphabetStaysInDefaultCharset() throws Exception {
        try (CaptchaRecognizer recognizer = CaptchaRecognizer.createDefault()) {
            BufferedImage plate = ImageIO.read(new File("src/test/resources/samples/9050.jpg"));
            RecognizeResult plateResult = recognizer.recognize(plate, RecognizeOptions.auto().withAlphabet(""));
            Assert.assertNotNull(plateResult);
            assertAlphanumeric(plateResult.getText());

            File specDir = new File("src/test/resources/samples/spec");
            File[] samples = specDir.listFiles(new FilenameFilter() {
                @Override
                public boolean accept(File dir, String name) {
                    return name.endsWith(".png");
                }
            });
            Assert.assertTrue("spec 样本目录应非空", samples != null && samples.length > 0);
            RecognizeResult specResult = recognizer.recognize(ImageIO.read(samples[0]),
                    RecognizeOptions.auto().withAlphabet(""));
            Assert.assertNotNull(specResult);
            assertAlphanumeric(specResult.getText());
        }
    }

    private static void assertAlphanumeric(String text) {
        for (int i = 0; i < text.length(); i++) {
            Assert.assertTrue("输出字符越出默认字符集: " + text,
                    TemplateBank.ALPHANUMERIC.indexOf(text.charAt(i)) >= 0);
        }
    }
}
