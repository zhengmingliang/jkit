package com.alianga.jkit.captcha.bench;

import javax.imageio.ImageIO;

import java.awt.Font;
import java.awt.GraphicsEnvironment;
import java.io.BufferedWriter;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

/**
 * 批量生成验证码数据集。
 *
 * <p>四类各占一份：纯英文、纯数字、英文数字混合、算术运算。字体、旋转、噪点、
 * 穿越字符的干扰线、背景纹理、字距、缩放都取多个值，画布高度也不固定。
 *
 * <p>产出目录下一份 {@code manifest.tsv} 和 {@code images}。清单是后续基准测试的
 * 唯一真值来源。同一序号用同一个随机种子，可以重画出同一张图。
 */
public final class DatasetGenerator {
    /** 默认每类生成 25000 张，四类合计 10 万张。 */
    public static final int DEFAULT_PER_CATEGORY = 25000;

    /** 本机已安装的才会用上，一套都没有时退回 SansSerif。 */
    private static final String[] FONT_FAMILIES = {"Verdana", "Arial"};
    private static final int[] FONT_STYLES = {Font.BOLD};
    private static final float[] FONT_SIZES = {32f, 36f};
    private static final float[] NOISE_LEVELS = {0f, 0.1f};
    private static final float[] ROTATIONS = {0f, 8f};
    private static final float[] SPACINGS = {1.05f, 1.35f};
    private static final float[] SCALES = {0.95f, 1.05f};
    private static final int[] HEIGHTS = {50, 58};
    private static final CaptchaStyle.Lines[] LINE_KINDS = {CaptchaStyle.Lines.NONE, CaptchaStyle.Lines.FEW};
    private static final CaptchaStyle.Texture[] TEXTURES = {
            CaptchaStyle.Texture.NONE, CaptchaStyle.Texture.NOISE
    };
    private static final CaptchaStyle.Surface[] SURFACES = {
            CaptchaStyle.Surface.PLAIN_WHITE, CaptchaStyle.Surface.LIGHT_GRAY, CaptchaStyle.Surface.DARK
    };

    private final List<String> usableFonts = new ArrayList<String>();

    private DatasetGenerator() {
        Set<String> installed = new HashSet<String>(Arrays.asList(
                GraphicsEnvironment.getLocalGraphicsEnvironment().getAvailableFontFamilyNames()));
        for (int i = 0; i < FONT_FAMILIES.length; i++) {
            if (installed.contains(FONT_FAMILIES[i])) {
                usableFonts.add(FONT_FAMILIES[i]);
            }
        }
        if (usableFonts.isEmpty()) {
            usableFonts.add("SansSerif");
        }
    }

    /**
     * 生成数据集。
     *
     * @param output  输出目录
     * @param perCategory 每类张数
     * @param seed    总种子，同一种子产出同一套数据
     * @param progress 进度回调，可为 {@code null}
     * @return 全部样本
     * @throws IOException 写盘失败时
     */
    public static List<Sample> generate(File output, int perCategory, long seed, Progress progress)
            throws IOException {
        return new DatasetGenerator().build(output, perCategory, seed, progress);
    }

    /**
     * 按清单重建样本列表，不重画图片。
     *
     * @param output 数据集目录
     * @return 样本列表
     * @throws IOException 清单读取失败时
     */
    public static List<Sample> load(File output) throws IOException {
        File manifest = new File(output, "manifest.tsv");
        List<String> lines = Files.readAllLines(manifest.toPath(), StandardCharsets.UTF_8);
        List<Sample> samples = new ArrayList<Sample>(lines.size());
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);
            if (line.isEmpty() || line.charAt(0) == '#') {
                continue;
            }
            Sample sample = Sample.fromManifest(output, line);
            if (sample != null) {
                samples.add(sample);
            }
        }
        return samples;
    }

    private List<Sample> build(File output, int perCategory, long seed, Progress progress) throws IOException {
        File images = new File(output, "images");
        if (!images.isDirectory() && !images.mkdirs()) {
            throw new IOException("无法创建目录 " + images);
        }
        List<Sample> samples = new ArrayList<Sample>(perCategory * 4);
        long index = seed * 1000000L;
        for (int c = 0; c < Sample.Category.values().length; c++) {
            Sample.Category category = Sample.Category.values()[c];
            for (int i = 0; i < perCategory; i++) {
                long serial = index + i;
                Sample sample = renderOne(images, category, serial, i);
                samples.add(sample);
                if (progress != null && i % 2000 == 0) {
                    progress.onProgress(category.name(), i, perCategory);
                }
            }
            index += perCategory;
        }
        writeManifest(output, samples);
        return samples;
    }

    private Sample renderOne(File images, Sample.Category category, long serial, int ordinal)
            throws IOException {
        Random random = new Random(serial);
        CaptchaStyle style = styleFor(category, random);
        String drawn;
        String expected;
        Integer value = null;
        if (category == Sample.Category.ARITHMETIC) {
            TextGen.Expression expression = TextGen.arithmetic(random, ordinal);
            drawn = expression.getText();
            expected = expression.getText();
            value = Integer.valueOf(expression.getValue());
        } else if (category == Sample.Category.LETTERS) {
            drawn = TextGen.letters(random, ordinal);
            expected = drawn;
        } else if (category == Sample.Category.DIGITS) {
            drawn = TextGen.digits(random, ordinal);
            expected = drawn;
        } else {
            drawn = TextGen.mixed(random, ordinal);
            expected = drawn;
        }
        int height = HEIGHTS[random.nextInt(HEIGHTS.length)];
        int width = 96 + drawn.length() * 28;
        String name = category.name().toLowerCase() + '-' + serial + ".png";
        File file = new File(images, name);
        ImageIO.write(CaptchaRenderer.render(drawn, style, width, height, random), "png", file);
        return new Sample(file, expected, category, style.tag(), value);
    }

    /**
     * 字体、旋转、噪点、穿越画面的干扰线、背景纹理、字距、缩放都在多个取值里抽。
     * 旋转含 0° 和 8°，干扰线穿过字符，噪点压在笔画上。算术题用深色底，运算符仍是字体字形。
     */
    private CaptchaStyle styleFor(Sample.Category category, Random random) {
        CaptchaStyle.Surface surface = category == Sample.Category.ARITHMETIC
                ? CaptchaStyle.Surface.DARK
                : SURFACES[random.nextInt(SURFACES.length)];
        return new CaptchaStyle.Builder()
                .fontFamily(usableFonts.get(random.nextInt(usableFonts.size())))
                .fontStyle(FONT_STYLES[random.nextInt(FONT_STYLES.length)])
                .fontSize(FONT_SIZES[random.nextInt(FONT_SIZES.length)])
                .surface(surface)
                .lines(LINE_KINDS[random.nextInt(LINE_KINDS.length)])
                .texture(TEXTURES[random.nextInt(TEXTURES.length)])
                .noise(NOISE_LEVELS[random.nextInt(NOISE_LEVELS.length)])
                .rotation(ROTATIONS[random.nextInt(ROTATIONS.length)])
                .spacing(SPACINGS[random.nextInt(SPACINGS.length)])
                .scaleX(SCALES[random.nextInt(SCALES.length)])
                .colorPerChar(random.nextBoolean())
                .build();
    }

    private static void writeManifest(File output, List<Sample> samples) throws IOException {
        File manifest = new File(output, "manifest.tsv");
        BufferedWriter writer = Files.newBufferedWriter(manifest.toPath(), StandardCharsets.UTF_8);
        try {
            writer.write("# name\texpected\tcategory\tstyle\tvalue");
            writer.newLine();
            for (int i = 0; i < samples.size(); i++) {
                writer.write(samples.get(i).manifestLine());
                writer.newLine();
            }
        } finally {
            writer.close();
        }
    }

    /** 生成过程中的进度回调。 */
    public interface Progress {
        /**
         * @param category 当前类别
         * @param done     已完成张数
         * @param total    该类总张数
         */
        void onProgress(String category, int done, int total);
    }
}
