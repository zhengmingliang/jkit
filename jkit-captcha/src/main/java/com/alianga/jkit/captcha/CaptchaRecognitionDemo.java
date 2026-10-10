package com.alianga.jkit.captcha;

import com.alianga.jkit.image.SpecCaptcha;

import javax.imageio.ImageIO;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;

/**
 * 命令行示例。无参数时生成一张 {@link SpecCaptcha} 并识别；有参数时识别每个图片文件。
 *
 * <p>运行前加上 {@code -Djava.awt.headless=true}。示例：
 * {@code java -Djava.awt.headless=true -cp jkit-captcha.jar:jkit.jar com.alianga.jkit.captcha.CaptchaRecognitionDemo}
 */
public final class CaptchaRecognitionDemo {
    private CaptchaRecognitionDemo() {
    }

    /**
     * 入口。
     *
     * @param args 图片路径；为空时现场生成一张字符验证码
     * @throws IOException 图片读写失败时
     */
    public static void main(String[] args) throws IOException {
        CaptchaRecognizer recognizer = CaptchaRecognizer.createDefault();
        if (args == null || args.length == 0) {
            SpecCaptcha captcha = new SpecCaptcha(150, 40, 4);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            captcha.out(out);
            BufferedImage image = ImageIO.read(new ByteArrayInputStream(out.toByteArray()));
            RecognizeResult result = recognizer.recognize(image, CaptchaType.ALPHANUMERIC);
            System.out.println("expected=" + captcha.text());
            System.out.println(result.summary());
            return;
        }
        for (int i = 0; i < args.length; i++) {
            RecognizeResult result = recognizer.recognize(new File(args[i]));
            System.out.println(args[i] + " " + result.summary());
        }
    }
}
