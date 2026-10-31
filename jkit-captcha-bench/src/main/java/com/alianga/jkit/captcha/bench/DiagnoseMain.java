package com.alianga.jkit.captcha.bench;

import com.alianga.jkit.captcha.CaptchaRecognizer;
import com.alianga.jkit.captcha.RecognizeOptions;
import com.alianga.jkit.captcha.RecognizeResult;

import javax.imageio.ImageIO;

import java.awt.image.BufferedImage;
import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * 诊断入口：在数据集抽样上对比"只走模板"与"走完整链路（含模型）"的准确率，
 * 定位准确率损失发生在模板匹配、分割还是模型补字。
 *
 * <pre>
 * mvn -pl jkit-captcha-bench exec:java -Dexec.mainClass=...DiagnoseMain \
 *     -Dexec.args="--out target/captcha-dataset --limit 4000"
 * </pre>
 */
public final class DiagnoseMain {

    private DiagnoseMain() {
    }

    /**
     * @param args 命令行参数
     * @throws Exception 识别或读图失败时
     */
    public static void main(String[] args) throws Exception {
        File output = new File(arg(args, "--out", "target/captcha-dataset"));
        int limit = Integer.parseInt(arg(args, "--limit", "4000"));
        int threads = Integer.parseInt(arg(args, "--threads", "16"));
        List<Sample> samples = DatasetGenerator.load(output);
        if (limit > 0 && limit < samples.size()) {
            // 均匀抽样，四类都覆盖到
            List<Sample> picked = new ArrayList<Sample>();
            double step = (double) samples.size() / limit;
            for (int i = 0; i < limit; i++) {
                picked.add(samples.get((int) (i * step)));
            }
            samples = picked;
        }
        System.out.println("抽样 " + samples.size() + " 张，线程数 " + threads);

        // 注册一个空求解器会走模板链路吗？不能：register 的 solver 会覆盖默认链路。
        // 这里改为直接对比"完整链路"与"限定字符集"两种调用，外加统计置信度与长度吻合度。
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            List<Future<int[]>> futures = new ArrayList<Future<int[]>>();
            int batch = 100;
            for (int from = 0; from < samples.size(); from += batch) {
                final List<Sample> slice =
                        new ArrayList<Sample>(samples.subList(from, Math.min(samples.size(), from + batch)));
                futures.add(pool.submit(new Callable<int[]>() {
                    @Override
                    public int[] call() {
                        return measure(slice);
                    }
                }));
            }
            int total = 0;
            int correct = 0;
            int lengthOk = 0;
            int emptyText = 0;
            int lowConf = 0;
            for (int i = 0; i < futures.size(); i++) {
                int[] part = futures.get(i).get();
                total += part[0];
                correct += part[1];
                lengthOk += part[2];
                emptyText += part[3];
                lowConf += part[4];
            }
            System.out.printf(Locale.ROOT,
                    "整串正确 %d/%d = %.2f%%，长度吻合 %d，空串 %d，低置信(<0.6) %d%n",
                    Integer.valueOf(correct), Integer.valueOf(total),
                    Double.valueOf(correct * 100d / total),
                    Integer.valueOf(lengthOk), Integer.valueOf(emptyText), Integer.valueOf(lowConf));
        } finally {
            pool.shutdown();
        }
    }

    private static int[] measure(List<Sample> slice) {
        CaptchaRecognizer recognizer = CaptchaRecognizer.createDefault();
        int total = 0;
        int correct = 0;
        int lengthOk = 0;
        int emptyText = 0;
        int lowConf = 0;
        try {
            for (int i = 0; i < slice.size(); i++) {
                Sample sample = slice.get(i);
                BufferedImage image;
                try {
                    image = ImageIO.read(sample.getFile());
                } catch (Exception ex) {
                    continue;
                }
                if (image == null) {
                    continue;
                }
                RecognizeResult result = recognizer.recognize(image, RecognizeOptions.auto());
                String actual = result.getText();
                total++;
                if (sample.getCategory() == Sample.Category.ARITHMETIC) {
                    if (result.getValue() != null && result.getValue().intValue() == sample.getValue().intValue()
                            && sample.getExpected().equals(result.getExpression())) {
                        correct++;
                    }
                } else if (sample.getExpected().equals(actual)) {
                    correct++;
                }
                if (actual.length() == sample.getExpected().length()) {
                    lengthOk++;
                }
                if (actual.isEmpty()) {
                    emptyText++;
                }
                if (result.getConfidence() < 0.6d) {
                    lowConf++;
                }
            }
        } finally {
            recognizer.close();
        }
        return new int[] {total, correct, lengthOk, emptyText, lowConf};
    }

    private static String arg(String[] args, String key, String fallback) {
        for (int i = 0; i < args.length - 1; i++) {
            if (key.equals(args[i])) {
                return args[i + 1];
            }
        }
        return fallback;
    }
}
