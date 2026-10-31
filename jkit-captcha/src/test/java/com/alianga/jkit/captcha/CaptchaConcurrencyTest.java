package com.alianga.jkit.captcha;

import java.awt.image.BufferedImage;
import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import javax.imageio.ImageIO;

import org.junit.Assert;
import org.junit.Test;

/**
 * 长寿命服务里多线程共用一个识别器是预期用法。
 * 冒烟覆盖各链路静态缓存的并发首调：模板库、ONNX 会话、easy 字形表、白板汉字字符集。
 * 并发下不抛异常、结果非空即为通过，不断言具体识别内容。
 */
public class CaptchaConcurrencyTest {

    @Test
    public void concurrentRecognizeDoesNotThrow() throws Exception {
        List<Object> inputs = new ArrayList<Object>();
        String[] files = {"9050.jpg", "14+8.jpeg", "R8Y2.jpg"};
        for (int i = 0; i < files.length; i++) {
            inputs.add(ImageIO.read(new File("src/test/resources/samples", files[i])));
        }
        File gif = new File("src/test/resources/samples/gif/2HJmb.gif");
        Assert.assertTrue("GIF 样本应存在", gif.isFile());
        inputs.add(gif);

        try (CaptchaRecognizer recognizer = CaptchaRecognizer.createDefault()) {
            recognizer.recognize((BufferedImage) inputs.get(0));
            ExecutorService pool = Executors.newFixedThreadPool(8);
            try {
                List<Future<RecognizeResult>> futures = new ArrayList<Future<RecognizeResult>>();
                for (int t = 0; t < 8; t++) {
                    for (int round = 0; round < 4; round++) {
                        final Object input = inputs.get((t + round) % inputs.size());
                        futures.add(pool.submit(new Callable<RecognizeResult>() {
                            @Override
                            public RecognizeResult call() throws Exception {
                                if (input instanceof BufferedImage) {
                                    return recognizer.recognize((BufferedImage) input);
                                }
                                return recognizer.recognize((File) input);
                            }
                        }));
                    }
                }
                for (Future<RecognizeResult> future : futures) {
                    RecognizeResult result = future.get(60, TimeUnit.SECONDS);
                    Assert.assertNotNull(result);
                }
            } finally {
                pool.shutdownNow();
            }
        }
    }
}
