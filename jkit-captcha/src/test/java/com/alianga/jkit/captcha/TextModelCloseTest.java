package com.alianga.jkit.captcha;

import org.junit.Test;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * 覆盖 P0-6：TextModel / CaptchaRecognizer 实现 AutoCloseable 后，
 * close 行为正确释放 native 资源且幂等。
 */
public class TextModelCloseTest {

    /**
     * 从 classpath 读取一张内置样本。
     */
    private static BufferedImage loadSample(String name) throws IOException {
        InputStream in = TextModelCloseTest.class.getResourceAsStream("/samples/" + name);
        if (in == null) {
            in = TextModelCloseTest.class.getResourceAsStream("/" + name);
        }
        assertNotNull("missing sample: " + name, in);
        try {
            return ImageIO.read(in);
        } finally {
            in.close();
        }
    }

    /**
     * CaptchaRecognizer 实现 AutoCloseable：close() 调用后再次识别仍能工作
     * （TextModel 内部会重新加载模型）。
     */
    @Test
    public void captchaRecognizerCloseIsIdempotentAndSafe() throws Exception {
        CaptchaRecognizer recognizer = CaptchaRecognizer.createDefault();
        BufferedImage image = loadSample("14+8.jpeg");

        // 先识别一次
        RecognizeResult first = recognizer.recognize(image);
        assertNotNull(first);

        // close 两次（幂等）
        recognizer.close();
        recognizer.close();

        // 再次识别应仍能工作（重新加载模型）
        RecognizeResult second = recognizer.recognize(image);
        assertNotNull(second);

        // 两次识别结果一致
        assertEquals(first.getText(), second.getText());
    }

    /**
     * close 后 TextModel 内部状态：failed/betaFailed 标志位置位，session 置 null。
     */
    @Test
    public void textModelCloseResetsState() {
        TextModel model = new TextModel();
        // close 即使未加载过模型也应正常工作（幂等）
        model.close();
        // 此时 read 应返回空串（failed=true）
        BufferedImage img = new BufferedImage(100, 40, BufferedImage.TYPE_INT_RGB);
        String result = model.read(img);
        assertNotNull(result);
        // failed=true 时 read 返回空串
        assertEquals("", result);
    }

    /**
     * try-with-resources 语法兼容：CaptchaRecognizer 实现 AutoCloseable 后
     * 可以在 try-with-resources 中使用。
     */
    @Test
    public void captchaRecognizerWorksInTryWithResources() throws Exception {
        try (CaptchaRecognizer r = CaptchaRecognizer.createDefault()) {
            BufferedImage image = loadSample("14+8.jpeg");
            RecognizeResult result = r.recognize(image);
            assertNotNull(result);
        }
        // try-with-resources 会自动调用 close()，这里不再验证内部状态
    }

    /**
     * 在未识别过任何图的情况下 close 是安全的。
     */
    @Test
    public void closeWithoutUseIsSafe() {
        TextModel model = new TextModel();
        model.close();
        // 不应抛异常
        model.close();
    }

    /**
     * close 后再 read，返回空串（而不是 NPE），这是设计预期。
     */
    @Test
    public void readAfterCloseReturnsEmptyNotNpe() {
        TextModel model = new TextModel();
        model.close();
        BufferedImage img = new BufferedImage(50, 30, BufferedImage.TYPE_INT_RGB);
        String s = model.read(img);
        assertNotNull(s);
        assertEquals("", s);
    }
}
