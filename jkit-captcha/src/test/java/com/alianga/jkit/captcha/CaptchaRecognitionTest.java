package com.alianga.jkit.captcha;

import com.alianga.jkit.image.GifCaptcha;
import com.alianga.jkit.image.SpecCaptcha;

import org.junit.Assert;
import org.junit.Test;

import javax.imageio.ImageIO;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.font.FontRenderContext;
import java.awt.font.TextLayout;
import java.awt.geom.AffineTransform;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;

/**
 * 用 jkit 生成的验证码和仓库里的真实样本测量识别率与耗时。
 */
public class CaptchaRecognitionTest {

    private static final String SAFE_ALPHABET =
            "ABCDEFGHGKLMNPQRSTUVWXYZabcdefghijkmnpqrstuvwxyz23456789";
    private static final int[][] PALETTE = {
            {255, 48, 48},
            {48, 210, 48},
            {48, 96, 255},
            {255, 200, 32},
            {220, 48, 220},
            {32, 210, 210},
            {255, 140, 32},
            {170, 255, 48}
    };

    @Test
    public void testOptionsAndCustomSolver() {
        try {
            RecognizeOptions.auto().withType(null);
            Assert.fail("空类型应拒绝");
        } catch (IllegalArgumentException expected) {
            Assert.assertEquals("type", expected.getMessage());
        }
        try {
            RecognizeOptions.auto().withExpectedLength(0);
            Assert.fail("长度应大于 0");
        } catch (IllegalArgumentException expected) {
            Assert.assertEquals("expectedLength", expected.getMessage());
        }
        CaptchaRecognizer recognizer = CaptchaRecognizer.createDefault();
        try {
            recognizer.recognize((BufferedImage) null);
            Assert.fail("空图应拒绝");
        } catch (IllegalArgumentException expected) {
            Assert.assertEquals("image", expected.getMessage());
        }
        recognizer.register(new CaptchaSolver() {
            @Override
            public boolean supports(CaptchaType type) {
                return type == CaptchaType.ALPHANUMERIC;
            }

            @Override
            public RecognizeResult recognize(BufferedImage image, RecognizeOptions options) {
                return new RecognizeResult("OK", null, null, 1, CaptchaType.ALPHANUMERIC, 0,
                        RecognizeResult.freeze(new ArrayList<GlyphHit>()));
            }
        });
        BufferedImage blank = new BufferedImage(16, 16, BufferedImage.TYPE_INT_RGB);
        Assert.assertEquals("OK", recognizer.recognize(blank, CaptchaType.ALPHANUMERIC).getText());
        Assert.assertFalse("OK".equals(recognizer.recognize(blank).getText()));
    }

    @Test
    public void testLearnSingleGlyph() {
        BufferedImage block = new BufferedImage(24, 36, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = block.createGraphics();
        graphics.setColor(Color.WHITE);
        graphics.fillRect(0, 0, 24, 36);
        graphics.setColor(Color.BLACK);
        graphics.fillRect(5, 6, 14, 24);
        graphics.dispose();
        CaptchaRecognizer recognizer = CaptchaRecognizer.createDefault();
        recognizer.learn(block, 'Q');
        RecognizeResult result = recognizer.recognize(block, CaptchaType.ALPHANUMERIC);
        Assert.assertEquals("Q", result.getText());
    }

    @Test
    public void testAlphaNumberStaysAlphanumeric() throws IOException {
        File dir = new File("src/test/resources/samples/alpha-number");
        File[] files = dir.listFiles();
        Assert.assertNotNull(files);
        CaptchaRecognizer recognizer = CaptchaRecognizer.createDefault();
        int seen = 0;
        for (int i = 0; i < files.length; i++) {
            if (!files[i].isFile() || !files[i].getName().endsWith(".png")) {
                continue;
            }
            seen++;
            BufferedImage image = ImageIO.read(files[i]);
            RecognizeResult result = recognizer.recognize(image, CaptchaType.ALPHANUMERIC);
            Assert.assertEquals(files[i].getName(), CaptchaType.ALPHANUMERIC, result.getType());
            String text = result.getText();
            for (int c = 0; c < text.length(); c++) {
                char ch = text.charAt(c);
                Assert.assertTrue(files[i].getName() + " -> " + text,
                        TemplateBank.ALPHANUMERIC.indexOf(ch) >= 0);
            }
        }
        Assert.assertTrue(seen >= 11);
    }

    @Test
    public void testMathSamples() throws IOException {
        String[] files = {"21+0.jpeg", "22-10.jpeg", "46+33.jpeg", "48+8.jpeg", "48-36.jpeg"};
        CaptchaRecognizer recognizer = CaptchaRecognizer.createDefault();
        for (int i = 0; i < files.length; i++) {
            String stem = files[i].substring(0, files[i].indexOf('.'));
            int expected = ArithmeticEvaluator.evaluate(stem).result;
            BufferedImage image = ImageIO.read(new File("src/test/resources/samples/math", files[i]));
            assertMath(stem, expected, recognizer.recognize(image));
            assertMath(stem, expected, recognizer.recognize(image, CaptchaType.ARITHMETIC));
        }
    }

    private static void assertMath(String stem, int expected, RecognizeResult result) {
        Assert.assertEquals(result.getText(), stem, result.getExpression());
        Assert.assertEquals(stem, Integer.valueOf(expected), result.getValue());
    }

    /**
     * 未纳入版本库的 easy-captcha 样例。字母图所用字体大小写是同一个字形，按忽略大小写计整串。
     */
    @Test
    public void testUntrackedSamples() throws IOException {
        CaptchaRecognizer recognizer = CaptchaRecognizer.createDefault();
        assertPlain(recognizer, "arithmetic", true, false);
        assertPlain(recognizer, "chinese", false, false);
        assertPlain(recognizer, "spec", false, true);
        assertPlain(recognizer, "chinese-gif", false, false);
        assertPlain(recognizer, "gif", false, true);
    }

    private static void assertPlain(CaptchaRecognizer recognizer, String group, boolean arithmetic,
                                    boolean ignoreCase) throws IOException {
        File dir = new File("src/test/resources/samples", group);
        File[] files = dir.listFiles();
        Assert.assertNotNull(dir.getPath(), files);
        Arrays.sort(files);
        int seen = 0;
        int hit = 0;
        int exact = 0;
        StringBuilder misses = new StringBuilder();
        for (int i = 0; i < files.length; i++) {
            if (!files[i].isFile()) {
                continue;
            }
            String name = files[i].getName();
            int dot = name.lastIndexOf('.');
            if (dot <= 0) {
                continue;
            }
            String stem = name.substring(0, dot);
            String label = stem;
            if (arithmetic) {
                int mark = stem.indexOf('=');
                Assert.assertTrue(name, mark > 0);
                label = stem.substring(0, mark);
            }
            RecognizeResult result = recognizer.recognize(files[i]);
            boolean correct;
            if (arithmetic) {
                ArithmeticEvaluator.Value expected = ArithmeticEvaluator.evaluate(label);
                correct = expected != null && expected.expression.equals(result.getExpression())
                        && result.getValue() != null && result.getValue().intValue() == expected.result;
            } else if (ignoreCase) {
                correct = label.equalsIgnoreCase(result.getText());
            } else {
                correct = label.equals(result.getText());
            }
            seen++;
            if (label.equals(result.getText()) || (arithmetic && label.equals(result.getExpression()))) {
                exact++;
            }
            if (correct) {
                hit++;
            } else if (misses.length() < 240) {
                misses.append(label).append("=>").append(result.getText()).append(' ');
            }
        }
        System.out.println("plain " + group + " " + hit + "/" + seen + " exact " + exact);
        Assert.assertTrue(group + " files", seen >= 100);
        Assert.assertTrue(group + " " + hit + "/" + seen + " " + misses, hit * 100 >= seen * 80);
    }

    @Test
    public void testAccuracy() throws Exception {
        CaptchaRecognizer recognizer = CaptchaRecognizer.createDefault();
        List<Trial> trials = new ArrayList<Trial>();
        long coldStart = System.nanoTime();
        recognizer.recognize(spec(4).image, CaptchaType.ALPHANUMERIC);
        double coldMillis = millis(coldStart);
        runSamples(recognizer, trials);
        runLabeled(recognizer, trials, new File("src/test/resources/samples/halo"), "halo");
        runOnline(recognizer, trials);
        runSpec(recognizer, trials, 4, 30);
        runSpec(recognizer, trials, 5, 16);
        runArithmetic(recognizer, trials, 24);
        runDistorted(recognizer, trials, 12);
        runOutline(recognizer, trials, 10);
        runGif(recognizer, trials, 6);
        writeReport(trials, coldMillis);
        printSummary(trials, coldMillis);
        assertGroup(trials, "sample-arithmetic", 1.0);
        assertGroup(trials, "sample-9050", 1.0);
        assertGroup(trials, "sample-outline", 1.0);
        assertGroup(trials, "halo", 0.55);
        assertGroup(trials, "spec-4", 0.40);
        assertGroup(trials, "spec-5", 0.35);
        assertGroup(trials, "arithmetic", 0.75);
        assertGroup(trials, "distorted", 0.25);
        assertGroup(trials, "outline", 0.75);
        double mean = meanMillis(trials, false);
        Assert.assertTrue("单张平均耗时应低于 50ms，实际 " + mean, mean < 50);
        assertOverall(trials, 0.80);
    }

    private static void runSamples(CaptchaRecognizer recognizer, List<Trial> trials) throws IOException {
        String[] files = {"14+8.jpeg", "16+4.jpeg", "27-23.jpeg", "40+5.jpeg", "41-31.jpeg"};
        for (int i = 0; i < files.length; i++) {
            File file = new File("src/test/resources/samples", files[i]);
            String stem = files[i].substring(0, files[i].indexOf('.'));
            BufferedImage image = ImageIO.read(file);
            Timed timed = time(recognizer, image, RecognizeOptions.auto());
            int expected = ArithmeticEvaluator.evaluate(stem).result;
            boolean correct = timed.result.getValue() != null && timed.result.getValue().intValue() == expected
                    && stem.equals(timed.result.getExpression());
            trials.add(new Trial("sample-arithmetic", stem, describe(timed.result), correct, timed.millis));
        }
        File digits = new File("src/test/resources/samples", "9050.jpg");
        Timed digitsTimed = time(recognizer, ImageIO.read(digits), RecognizeOptions.auto());
        trials.add(new Trial("sample-9050", "9050", digitsTimed.result.getText(),
                "9050".equals(digitsTimed.result.getText()), digitsTimed.millis));
        File outline = new File("src/test/resources/samples", "R8Y2.jpg");
        Timed outlineTimed = time(recognizer, ImageIO.read(outline), CaptchaType.DISTORTED);
        trials.add(new Trial("sample-outline", "R8Y2", outlineTimed.result.getText(),
                "R8Y2".equals(outlineTimed.result.getText()), outlineTimed.millis));
    }

    private static void runOnline(CaptchaRecognizer recognizer, List<Trial> trials) throws IOException {
        File root = new File("src/test/resources/samples/online");
        File[] dirs = root.listFiles();
        if (dirs == null) {
            return;
        }
        Arrays.sort(dirs);
        for (int d = 0; d < dirs.length; d++) {
            if (!dirs[d].isDirectory()) {
                continue;
            }
            runLabeled(recognizer, trials, dirs[d], "online-" + dirs[d].getName());
        }
    }

    /**
     * 文件名（去掉扩展名）就是期望文本。模板图不放在这里。
     */
    private static void runLabeled(CaptchaRecognizer recognizer, List<Trial> trials, File dir, String group)
            throws IOException {
        File[] files = dir.listFiles();
        if (files == null) {
            return;
        }
        Arrays.sort(files);
        for (int i = 0; i < files.length; i++) {
            if (!files[i].isFile()) {
                continue;
            }
            String name = files[i].getName();
            int dot = name.lastIndexOf('.');
            if (dot <= 0) {
                continue;
            }
            String stem = name.substring(0, dot);
            BufferedImage image = ImageIO.read(files[i]);
            Timed timed = time(recognizer, image, RecognizeOptions.auto());
            trials.add(new Trial(group, stem, timed.result.getText(), stem.equals(timed.result.getText()),
                    timed.millis));
        }
    }

    private static void runSpec(CaptchaRecognizer recognizer, List<Trial> trials, int length, int count) {
        for (int i = 0; i < count; i++) {
            Generated generated = spec(length);
            Timed timed = time(recognizer, generated.image, CaptchaType.ALPHANUMERIC);
            boolean correct = generated.text.equals(timed.result.getText());
            trials.add(new Trial("spec-" + length, generated.text, timed.result.getText(), correct, timed.millis));
        }
    }

    private static void runArithmetic(CaptchaRecognizer recognizer, List<Trial> trials, int count) {
        Random random = new Random(20261011L);
        for (int i = 0; i < count; i++) {
            int left = 1 + random.nextInt(40);
            int right = 1 + random.nextInt(30);
            char operator = random.nextBoolean() ? '+' : '-';
            String expression = left + String.valueOf(operator) + right;
            BufferedImage image = arithmeticImage(expression + "=?", random);
            Timed timed = time(recognizer, image, CaptchaType.ARITHMETIC);
            int expected = ArithmeticEvaluator.evaluate(expression).result;
            boolean correct = timed.result.getValue() != null && timed.result.getValue().intValue() == expected;
            String actual = timed.result.getText() + " => " + timed.result.getValue();
            trials.add(new Trial("arithmetic", expression, actual, correct, timed.millis));
        }
    }

    private static void runDistorted(CaptchaRecognizer recognizer, List<Trial> trials, int count) {
        Random random = new Random(20261011L);
        for (int i = 0; i < count; i++) {
            String text = randomText(random, 4);
            BufferedImage image = distortedImage(text, random);
            Timed timed = time(recognizer, image, CaptchaType.DISTORTED);
            trials.add(new Trial("distorted", text, timed.result.getText(),
                    text.equals(timed.result.getText()), timed.millis));
        }
    }

    private static void runOutline(CaptchaRecognizer recognizer, List<Trial> trials, int count) {
        Random random = new Random(7L);
        for (int i = 0; i < count; i++) {
            String text = randomText(random, 4);
            BufferedImage image = outlineImage(text);
            Timed timed = time(recognizer, image, CaptchaType.DISTORTED);
            trials.add(new Trial("outline", text, timed.result.getText(),
                    text.equals(timed.result.getText()), timed.millis));
        }
    }

    private static void runGif(CaptchaRecognizer recognizer, List<Trial> trials, int count) throws IOException {
        for (int i = 0; i < count; i++) {
            Generated generated = gif(4);
            long started = System.nanoTime();
            RecognizeResult result = recognizer.recognize(generated.data, CaptchaType.ALPHANUMERIC);
            double elapsed = millis(started);
            trials.add(new Trial("gif", generated.text, result.getText(),
                    generated.text.equals(result.getText()), elapsed));
        }
    }

    private static Generated spec(int length) {
        SpecCaptcha captcha = new SpecCaptcha(150, 40, length);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        captcha.out(out);
        return new Generated(captcha.text(), readImage(out.toByteArray()), null);
    }

    private static Generated gif(int length) {
        GifCaptcha captcha = new GifCaptcha(150, 40, length);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        captcha.out(out);
        return new Generated(captcha.text(), null, out.toByteArray());
    }

    private static BufferedImage readImage(byte[] data) {
        try {
            return ImageIO.read(new ByteArrayInputStream(data));
        } catch (IOException ex) {
            return null;
        }
    }

    private static BufferedImage arithmeticImage(String text, Random random) {
        int width = 210;
        int height = 54;
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = image.createGraphics();
        graphics.setColor(Color.BLACK);
        graphics.fillRect(0, 0, width, height);
        graphics.setFont(new Font("Arial", Font.BOLD, 32));
        graphics.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_OFF);
        int x = 6;
        for (int i = 0; i < text.length(); i++) {
            char ch = text.charAt(i);
            int[] color = PALETTE[i % PALETTE.length];
            graphics.setColor(new Color(color[0], color[1], color[2]));
            if (ch == '+') {
                graphics.fillRect(x + 1, 24, 18, 5);
                graphics.fillRect(x + 7, 16, 5, 20);
                x += 24;
            } else if (ch == '-') {
                graphics.fillRect(x, 24, 13, 5);
                x += 18;
            } else if (ch == '=') {
                graphics.fillRect(x, 18, 22, 4);
                graphics.fillRect(x, 26, 22, 4);
                x += 28;
            } else {
                String token = String.valueOf(ch);
                graphics.drawString(token, x, 42);
                graphics.drawString(token, x + 1, 42);
                graphics.drawString(token, x, 43);
                x += graphics.getFontMetrics().charWidth(ch) + 6;
            }
        }
        graphics.setStroke(new BasicStroke(1.5f));
        for (int i = 0; i < 4; i++) {
            graphics.setColor(new Color(40 + random.nextInt(80), 180 + random.nextInt(70), 200 + random.nextInt(55)));
            graphics.drawLine(random.nextInt(width), random.nextInt(height),
                    random.nextInt(width), random.nextInt(height));
        }
        graphics.dispose();
        return image;
    }

    private static BufferedImage distortedImage(String text, Random random) {
        int width = 24 + text.length() * 40;
        int height = 52;
        BufferedImage raw = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = raw.createGraphics();
        graphics.setColor(Color.WHITE);
        graphics.fillRect(0, 0, width, height);
        graphics.setFont(new Font("Verdana", Font.BOLD | Font.ITALIC, 32));
        graphics.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_OFF);
        for (int i = 0; i < text.length(); i++) {
            graphics.setColor(new Color(25 + random.nextInt(80), 25 + random.nextInt(80), 25 + random.nextInt(80)));
            graphics.drawString(String.valueOf(text.charAt(i)), 8 + i * 40, 38);
        }
        graphics.setStroke(new BasicStroke(1f));
        for (int i = 0; i < 3; i++) {
            graphics.setColor(new Color(90 + random.nextInt(80), 90 + random.nextInt(80), 90 + random.nextInt(80)));
            graphics.drawLine(random.nextInt(width), random.nextInt(height),
                    random.nextInt(width), random.nextInt(height));
        }
        for (int i = 0; i < 25; i++) {
            graphics.fillRect(random.nextInt(width), random.nextInt(height), 1, 1);
        }
        graphics.dispose();
        BufferedImage warped = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int sx = x + (int) (3 * Math.sin(y / 6.0));
                int sy = y + (int) (2 * Math.sin(x / 10.0));
                if (sx < 0 || sy < 0 || sx >= width || sy >= height) {
                    warped.setRGB(x, y, 0xffffff);
                } else {
                    warped.setRGB(x, y, raw.getRGB(sx, sy));
                }
            }
        }
        return warped;
    }

    private static BufferedImage outlineImage(String text) {
        int width = 16 + text.length() * 36;
        int height = 48;
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = image.createGraphics();
        graphics.setColor(new Color(214, 214, 214));
        graphics.fillRect(0, 0, width, height);
        graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_OFF);
        Font font = new Font("Verdana", Font.BOLD, 32);
        FontRenderContext context = graphics.getFontRenderContext();
        int x = 8;
        for (int i = 0; i < text.length(); i++) {
            TextLayout layout = new TextLayout(String.valueOf(text.charAt(i)), font, context);
            AffineTransform transform = AffineTransform.getTranslateInstance(x, 36);
            graphics.setColor(Color.WHITE);
            graphics.fill(layout.getOutline(transform));
            graphics.setColor(new Color(25, 45, 120));
            graphics.setStroke(new BasicStroke(2.2f));
            graphics.draw(layout.getOutline(transform));
            x += 36;
        }
        graphics.dispose();
        return image;
    }

    private static String randomText(Random random, int length) {
        StringBuilder builder = new StringBuilder(length);
        for (int i = 0; i < length; i++) {
            builder.append(SAFE_ALPHABET.charAt(random.nextInt(SAFE_ALPHABET.length())));
        }
        return builder.toString();
    }

    private static Timed time(CaptchaRecognizer recognizer, BufferedImage image, CaptchaType type) {
        return time(recognizer, image, RecognizeOptions.auto().withType(type));
    }

    private static Timed time(CaptchaRecognizer recognizer, BufferedImage image, RecognizeOptions options) {
        long started = System.nanoTime();
        RecognizeResult result = recognizer.recognize(image, options);
        return new Timed(result, millis(started));
    }

    private static double millis(long started) {
        return (System.nanoTime() - started) / 1000000.0;
    }

    private static String describe(RecognizeResult result) {
        return result.getText() + " => " + result.getValue();
    }

    private static void assertOverall(List<Trial> trials, double minimum) {
        int correct = 0;
        for (int i = 0; i < trials.size(); i++) {
            if (trials.get(i).correct) {
                correct++;
            }
        }
        double accuracy = trials.isEmpty() ? 0 : correct / (double) trials.size();
        Assert.assertTrue("整体准确率 " + correct + "/" + trials.size() + " = " + accuracy
                + "，期望不低于 " + minimum, accuracy + 1e-9 >= minimum);
    }

    private static void assertGroup(List<Trial> trials, String group, double minimum) {
        int total = 0;
        int correct = 0;
        for (int i = 0; i < trials.size(); i++) {
            Trial trial = trials.get(i);
            if (!group.equals(trial.group)) {
                continue;
            }
            total++;
            if (trial.correct) {
                correct++;
            }
        }
        double accuracy = total == 0 ? 0 : correct / (double) total;
        Assert.assertTrue(group + " 准确率 " + correct + "/" + total + " = " + accuracy
                + "，期望不低于 " + minimum, accuracy + 1e-9 >= minimum);
    }

    private static double meanMillis(List<Trial> trials, boolean includeGif) {
        double sum = 0;
        int count = 0;
        for (int i = 0; i < trials.size(); i++) {
            Trial trial = trials.get(i);
            if (!includeGif && "gif".equals(trial.group)) {
                continue;
            }
            sum += trial.millis;
            count++;
        }
        return count == 0 ? 0 : sum / count;
    }

    private static void printSummary(List<Trial> trials, double coldMillis) {
        int correct = 0;
        for (int i = 0; i < trials.size(); i++) {
            if (trials.get(i).correct) {
                correct++;
            }
        }
        double overall = trials.isEmpty() ? 0 : correct / (double) trials.size();
        System.out.println("overall=" + correct + "/" + trials.size()
                + " = " + String.format("%.3f", Double.valueOf(overall)));
        System.out.println("coldStartMs=" + String.format("%.1f", Double.valueOf(coldMillis)));
        String[] groups = {"sample-arithmetic", "sample-9050", "sample-outline", "halo",
                "online-pony", "online-miit", "online-shandong", "online-jszwfw",
                "spec-4", "spec-5", "arithmetic", "distorted", "outline", "gif"};
        for (int i = 0; i < groups.length; i++) {
            System.out.println(stats(trials, groups[i]));
        }
        for (int i = 0; i < trials.size(); i++) {
            Trial trial = trials.get(i);
            if (!trial.correct) {
                System.out.println("MISS " + trial.group + " expected=" + trial.expected
                        + " actual=" + trial.actual + " ms=" + String.format("%.2f", Double.valueOf(trial.millis)));
            }
        }
    }

    private static String stats(List<Trial> trials, String group) {
        int total = 0;
        int correct = 0;
        double sum = 0;
        double max = 0;
        for (int i = 0; i < trials.size(); i++) {
            Trial trial = trials.get(i);
            if (!group.equals(trial.group)) {
                continue;
            }
            total++;
            if (trial.correct) {
                correct++;
            }
            sum += trial.millis;
            if (trial.millis > max) {
                max = trial.millis;
            }
        }
        double accuracy = total == 0 ? 0 : correct / (double) total;
        double mean = total == 0 ? 0 : sum / total;
        return group + " " + correct + "/" + total + " acc=" + String.format("%.3f", Double.valueOf(accuracy))
                + " meanMs=" + String.format("%.2f", Double.valueOf(mean))
                + " maxMs=" + String.format("%.2f", Double.valueOf(max));
    }

    private static void writeReport(List<Trial> trials, double coldMillis) throws IOException {
        StringBuilder builder = new StringBuilder();
        builder.append("# 验证码识别测试报告\n\n");
        builder.append("冷启动（含首次渲染字体模板） ").append(String.format("%.1f", Double.valueOf(coldMillis)));
        builder.append(" ms。下表耗时不含这次冷启动。\n\n");
        builder.append("| 组别 | 整串正确 | 整串准确率 | 字符准确率 | 平均耗时 ms | 最大耗时 ms |\n");
        builder.append("| --- | --- | --- | --- | --- | --- |\n");
        String[] groups = {"sample-arithmetic", "sample-9050", "sample-outline", "halo",
                "online-pony", "online-miit", "online-shandong", "online-jszwfw",
                "spec-4", "spec-5", "arithmetic", "distorted", "outline", "gif"};
        String[] titles = {
                "真实算术样本", "真实数字样本 9050", "真实锯齿样本 R8Y2",
                "问卷网白边字", "在线彩色数字 ponytest", "在线彩色数字 miit",
                "在线手写 山东", "在线手写 江苏政务",
                "SpecCaptcha 4 位", "SpecCaptcha 5 位", "生成的算术验证码",
                "扭曲加干扰线", "空心描边", "GifCaptcha 多帧"
        };
        for (int i = 0; i < groups.length; i++) {
            builder.append("| ").append(titles[i]).append(" | ");
            builder.append(countText(trials, groups[i])).append(" |\n");
        }
        builder.append("\n## 失败明细\n\n");
        boolean any = false;
        for (int i = 0; i < trials.size(); i++) {
            Trial trial = trials.get(i);
            if (trial.correct) {
                continue;
            }
            any = true;
            builder.append("- ").append(trial.group).append(" 期望 `").append(trial.expected);
            builder.append("` 实际 `").append(trial.actual).append("`\n");
        }
        if (!any) {
            builder.append("无。\n");
        }
        builder.append("\n## 说明\n\n");
        builder.append("- 真实算术样本来自 `src/test/resources/samples`，答案是文件名，判定值与表达式都正确。\n");
        builder.append("- SpecCaptcha、GifCaptcha 由 jkit 的生成类现场绘制。字符表不含 0、1、O、I、l。\n");
        builder.append("- 算术图为黑底彩色字，字符画完后再画 4 条干扰线。\n");
        builder.append("- 扭曲图在白底上做正弦位移，并叠加线段和噪点。\n");
        builder.append("- 空心图用 Verdana 粗体描边。模板按同样的描边和闭运算生成，断裂的左右碎片会拼回去。\n");
        builder.append("- 问卷网白边字和 R8Y2 是同一种灰底白边锯齿字。模板来自另一批标注图，本表这些图没有拿去当模板。\n");
        builder.append("- ponytest、miit、山东、江苏政务的答案是对照画面读出来的，接口本身不返回文本。\n");
        builder.append("- GifCaptcha 先把至少两帧都偏暗的像素合成一张图再识别；合成结果短于 3 个字符时，改按各帧投票。\n");
        File report = new File("target/captcha-accuracy-report.md");
        File parent = report.getParentFile();
        if (parent != null && !parent.isDirectory()) {
            Assert.assertTrue(parent.mkdirs());
        }
        Files.write(report.toPath(), builder.toString().getBytes(StandardCharsets.UTF_8));
    }

    private static String countText(List<Trial> trials, String group) {
        int total = 0;
        int correct = 0;
        double sum = 0;
        double max = 0;
        for (int i = 0; i < trials.size(); i++) {
            Trial trial = trials.get(i);
            if (!group.equals(trial.group)) {
                continue;
            }
            total++;
            if (trial.correct) {
                correct++;
            }
            sum += trial.millis;
            if (trial.millis > max) {
                max = trial.millis;
            }
        }
        double accuracy = total == 0 ? 0 : correct / (double) total;
        double mean = total == 0 ? 0 : sum / total;
        return correct + "/" + total + " | " + String.format("%.1f%%", Double.valueOf(accuracy * 100))
                + " | " + String.format("%.1f%%", Double.valueOf(charAccuracy(trials, group) * 100))
                + " | " + String.format("%.2f", Double.valueOf(mean))
                + " | " + String.format("%.2f", Double.valueOf(max));
    }

    private static double charAccuracy(List<Trial> trials, String group) {
        int expectedChars = 0;
        int distance = 0;
        for (int i = 0; i < trials.size(); i++) {
            Trial trial = trials.get(i);
            if (!group.equals(trial.group)) {
                continue;
            }
            String actual = trial.actual;
            int cut = actual.indexOf(" => ");
            if (cut >= 0) {
                actual = actual.substring(0, cut);
            }
            if (actual.endsWith("=?")) {
                actual = actual.substring(0, actual.length() - 2);
            }
            expectedChars += trial.expected.length();
            distance += editDistance(trial.expected, actual);
        }
        if (expectedChars == 0) {
            return 0;
        }
        double accuracy = 1.0 - distance / (double) expectedChars;
        return accuracy < 0 ? 0 : accuracy;
    }

    private static int editDistance(String left, String right) {
        int[] previous = new int[right.length() + 1];
        int[] current = new int[right.length() + 1];
        for (int j = 0; j <= right.length(); j++) {
            previous[j] = j;
        }
        for (int i = 1; i <= left.length(); i++) {
            current[0] = i;
            for (int j = 1; j <= right.length(); j++) {
                int cost = left.charAt(i - 1) == right.charAt(j - 1) ? 0 : 1;
                int insert = current[j - 1] + 1;
                int delete = previous[j] + 1;
                int replace = previous[j - 1] + cost;
                current[j] = Math.min(insert, Math.min(delete, replace));
            }
            int[] swap = previous;
            previous = current;
            current = swap;
        }
        return previous[right.length()];
    }

    private static final class Trial {
        final String group;
        final String expected;
        final String actual;
        final boolean correct;
        final double millis;

        Trial(String group, String expected, String actual, boolean correct, double millis) {
            this.group = group;
            this.expected = expected;
            this.actual = actual;
            this.correct = correct;
            this.millis = millis;
        }
    }

    private static final class Generated {
        final String text;
        final BufferedImage image;
        final byte[] data;

        Generated(String text, BufferedImage image, byte[] data) {
            this.text = text;
            this.image = image;
            this.data = data;
        }
    }

    private static final class Timed {
        final RecognizeResult result;
        final double millis;

        Timed(RecognizeResult result, double millis) {
            this.result = result;
            this.millis = millis;
        }
    }
}
