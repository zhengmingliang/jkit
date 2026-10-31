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
 * <p>四类各占一份：纯英文、纯数字、英文数字混合、算术运算。每一类在字体、字号、旋转、
 * 噪点、干扰线、背景纹理、字距、缩放、斜切、扭曲、描边、半透明、逐字变色这些维度上
 * 做笛卡尔式取样，覆盖真实场景的变体。
 *
 * <p>产出目录下一份 {@code manifest.tsv} 与若干 {@code part-NN} 子目录。清单是后续基准测试的
 * 唯一真值来源，图片本身可以按需重画：同一清单行由文件名里的序号决定，序号即随机种子。
 */
public final class DatasetGenerator {
    /** 默认每类生成 25000 张，四类合计 10 万张。 */
    public static final int DEFAULT_PER_CATEGORY = 25000;

    /** 生成时使用的字体族。全部是本机已安装字体，缺失时自动跳过。 */
    private static final String[] FONT_FAMILIES = {
            "Verdana", "Arial", "Arial Black", "DejaVu Sans", "Liberation Sans", "Times New Roman",
            "Tahoma", "Georgia", "Courier New", "Comic Sans MS", "Impact", "Trebuchet MS",
            "Noto Sans", "Noto Serif", "DejaVu Serif", "Liberation Serif", "Liberation Mono",
            "DejaVu Sans Mono", "URW Bookman", "URW Gothic", "Ubuntu",
    };

    private static final int[] FONT_STYLES = {Font.PLAIN, Font.BOLD, Font.ITALIC, Font.BOLD | Font.ITALIC};
    private static final float[] FONT_SIZES = {26f, 30f, 34f, 38f};
    private static final CaptchaStyle.Surface[] SURFACES = CaptchaStyle.Surface.values();
    private static final CaptchaStyle.Lines[] LINE_KINDS = CaptchaStyle.Lines.values();
    private static final CaptchaStyle.Texture[] TEXTURES = CaptchaStyle.Texture.values();
    private static final float[] NOISE_LEVELS = {0f, 0.15f, 0.35f, 0.6f};
    private static final float[] ROTATIONS = {0f, 6f, 12f, 18f};
    private static final float[] SPACINGS = {0.6f, 1f, 1.5f};
    private static final float[] SCALES = {0.85f, 1f, 1.2f};
    private static final float[] SHEARS = {0f, -0.15f, 0.15f};
    private static final float[] WAVES = {0f, 0f, 2f, 3f};
    private static final int[] LENGTHS = {4, 5};

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
                Sample sample = renderOne(images, category, serial);
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

    private Sample renderOne(File images, Sample.Category category, long serial) throws IOException {
        Random random = new Random(serial);
        CaptchaStyle style = styleFor(category, random);
        String text;
        Integer value = null;
        if (category == Sample.Category.ARITHMETIC) {
            TextGen.Expression expression =
                    TextGen.arithmetic(random, random.nextInt(4) == 0);
            text = expression.getText() + "=?";
            value = Integer.valueOf(expression.getValue());
        } else {
            int length = LENGTHS[random.nextInt(LENGTHS.length)];
            if (category == Sample.Category.LETTERS) {
                text = TextGen.letters(random, length);
            } else if (category == Sample.Category.DIGITS) {
                text = TextGen.digits(random, length);
            } else {
                text = TextGen.mixed(random, length);
            }
        }
        int width = 110 + Math.round(text.length() * 22f);
        int height = 44;
        String name = category.name().toLowerCase() + '-' + serial + ".png";
        File file = new File(images, name);
        ImageIO.write(CaptchaRenderer.render(text, style, width, height, random), "png", file);
        String expected = category == Sample.Category.ARITHMETIC
                ? text.substring(0, text.length() - 2) : text;
        return new Sample(file, expected, category, style.tag(), value);
    }

    /**
     * 按类别取样一种风格。
     *
     * <p>干扰项不再各自独立随机叠加：那样会同时叠出旋转+扭曲+密集噪点+网格纹理，
     * 真实验证码不会长成那样，图上连人都读不出来。这里改成先定"这一张有几个干扰项"
     * （0 到 2 个），再从中挑几项上，且旋转与扭曲不同时出现，字距也不压到字符重叠。
     */
    private CaptchaStyle styleFor(Sample.Category category, Random random) {
        CaptchaStyle.Builder builder = new CaptchaStyle.Builder()
                .fontFamily(usableFonts.get(random.nextInt(usableFonts.size())))
                .fontStyle(FONT_STYLES[random.nextInt(FONT_STYLES.length)])
                .fontSize(FONT_SIZES[random.nextInt(FONT_SIZES.length)])
                .surface(SURFACES[random.nextInt(SURFACES.length)])
                .lines(CaptchaStyle.Lines.NONE)
                .texture(CaptchaStyle.Texture.NONE)
                .noise(0f)
                .rotation(0f)
                .spacing(SPACINGS[random.nextInt(SPACINGS.length)])
                .scaleX(SCALES[random.nextInt(SCALES.length)])
                .shear(SHEARS[random.nextInt(SHEARS.length)])
                .wave(0f)
                .outline(category != Sample.Category.ARITHMETIC && random.nextInt(12) == 0)
                .alphaBlend(random.nextInt(3) == 0)
                .colorPerChar(random.nextInt(2) == 0);
        if (category == Sample.Category.ARITHMETIC) {
            // 真实算术验证码几乎都是深色底彩色字，运算符不能被描边吃掉。
            builder.surface(CaptchaStyle.Surface.DARK).outline(false).colorPerChar(true);
        }
        int budget = random.nextInt(3);
        if (budget > 0) {
            // 候选干扰项：噪点、干扰线、纹理、旋转、扭曲。挑 budget 个上。
            java.util.List<Integer> pool = new java.util.ArrayList<Integer>(
                    java.util.Arrays.asList(Integer.valueOf(0), Integer.valueOf(1),
                            Integer.valueOf(2), Integer.valueOf(3), Integer.valueOf(4)));
            java.util.Collections.shuffle(pool, random);
            boolean rotated = false;
            for (int i = 0; i < budget; i++) {
                switch (pool.get(i).intValue()) {
                    case 0:
                        builder.noise(NOISE_LEVELS[1 + random.nextInt(NOISE_LEVELS.length - 1)]);
                        break;
                    case 1:
                        builder.lines(LINE_KINDS[1 + random.nextInt(LINE_KINDS.length - 1)]);
                        break;
                    case 2:
                        builder.texture(TEXTURES[1 + random.nextInt(TEXTURES.length - 1)]);
                        break;
                    case 3:
                        builder.rotation(ROTATIONS[1 + random.nextInt(ROTATIONS.length - 1)]);
                        rotated = true;
                        break;
                    case 4:
                        // 旋转和扭曲不同时上：真实验证码基本不会两者叠满。
                        if (!rotated) {
                            builder.wave(WAVES[2 + random.nextInt(WAVES.length - 2)]);
                        }
                        break;
                    default:
                        break;
                }
            }
        }
        // 字距不压到字符重叠，否则连笔后任何算法都切不开。
        if (builder.build().getSpacing() < 1f) {
            builder.spacing(1f);
        }
        return builder.build();
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
