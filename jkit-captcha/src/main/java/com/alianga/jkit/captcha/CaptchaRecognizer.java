package com.alianga.jkit.captcha;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

/**
 * 常规图片验证码识别入口。
 *
 * <p>默认实现只使用 JDK 的 {@code java.awt} 与 {@code ImageIO}：灰度化、二值化、去干扰线、
 * 字符分割、字体模板匹配。字符型、扭曲干扰型和算术型走同一条链路，预处理按背景分开。
 * 需要接 OpenCV 或 Tesseract 时，实现 {@link CaptchaSolver} 并 {@link #register(CaptchaSolver)}。
 */
public final class CaptchaRecognizer {
    private final TemplateBank bank;
    private final DefaultCaptchaSolver fallback;
    private final List<CaptchaSolver> solvers = new ArrayList<CaptchaSolver>();

    private CaptchaRecognizer(TemplateBank bank) {
        this.bank = bank;
        this.fallback = new DefaultCaptchaSolver(bank);
    }

    /**
     * 创建带内置字体模板的识别器。模板在第一次识别时渲染，之后复用。
     *
     * @return 识别器
     */
    public static CaptchaRecognizer createDefault() {
        return new CaptchaRecognizer(new TemplateBank());
    }

    /**
     * 注册自定义求解器。后注册的排在前面，对它声明支持的类型优先生效。
     *
     * @param solver 求解器，不能为空
     */
    public void register(CaptchaSolver solver) {
        if (solver == null) {
            throw new IllegalArgumentException("solver");
        }
        synchronized (solvers) {
            solvers.add(0, solver);
        }
    }

    /**
     * 自动判断类型并识别。
     *
     * @param image 验证码图像，不能为空
     * @return 识别结果
     */
    public RecognizeResult recognize(BufferedImage image) {
        return recognize(image, RecognizeOptions.auto());
    }

    /**
     * 按指定类型识别。
     *
     * @param image 验证码图像，不能为空
     * @param type 验证码类型，不能为空
     * @return 识别结果
     */
    public RecognizeResult recognize(BufferedImage image, CaptchaType type) {
        return recognize(image, RecognizeOptions.auto().withType(type));
    }

    /**
     * 按参数识别。
     *
     * @param image 验证码图像，不能为空
     * @param options 识别参数，为空时按自动类型处理
     * @return 识别结果
     */
    public RecognizeResult recognize(BufferedImage image, RecognizeOptions options) {
        if (image == null) {
            throw new IllegalArgumentException("image");
        }
        RecognizeOptions effective = options == null ? RecognizeOptions.auto() : options;
        synchronized (solvers) {
            for (int i = 0; i < solvers.size(); i++) {
                CaptchaSolver solver = solvers.get(i);
                if (solver.supports(effective.getType())) {
                    return solver.recognize(image, effective);
                }
            }
        }
        return fallback.recognize(image, effective);
    }

    /**
     * 从文件识别。
     *
     * @param file 图片文件，不能为空
     * @return 识别结果
     * @throws IOException 文件无法按图片读取时
     */
    public RecognizeResult recognize(File file) throws IOException {
        if (file == null) {
            throw new IllegalArgumentException("file");
        }
        return recognize(Files.readAllBytes(file.toPath()), RecognizeOptions.auto());
    }

    /**
     * 从图片字节识别。GIF 会读完全部帧，再按位置拼出每个字符最清晰的那一帧。
     *
     * @param data 图片字节，不能为空
     * @return 识别结果
     * @throws IOException 字节无法按图片读取时
     */
    public RecognizeResult recognize(byte[] data) throws IOException {
        return recognize(data, RecognizeOptions.auto());
    }

    /**
     * 从图片字节识别，并指定类型。
     *
     * @param data 图片字节，不能为空
     * @param type 验证码类型，不能为空
     * @return 识别结果
     * @throws IOException 字节无法按图片读取时
     */
    public RecognizeResult recognize(byte[] data, CaptchaType type) throws IOException {
        return recognize(data, RecognizeOptions.auto().withType(type));
    }

    /**
     * 从图片字节识别。
     *
     * @param data 图片字节，不能为空
     * @param options 识别参数，为空时按自动类型处理
     * @return 识别结果
     * @throws IOException 字节无法按图片读取时
     */
    public RecognizeResult recognize(byte[] data, RecognizeOptions options) throws IOException {
        if (data == null) {
            throw new IllegalArgumentException("data");
        }
        RecognizeOptions effective = options == null ? RecognizeOptions.auto() : options;
        List<BufferedImage> frames = GifFrames.read(data);
        if (frames.size() == 1) {
            return recognize(frames.get(0), effective);
        }
        return recognizeFrames(frames, effective);
    }

    /**
     * 从输入流识别。流由调用方关闭。GIF 会合并各帧。
     *
     * @param input 图片输入流，不能为空
     * @return 识别结果
     * @throws IOException 流无法按图片读取时
     */
    public RecognizeResult recognize(InputStream input) throws IOException {
        if (input == null) {
            throw new IllegalArgumentException("input");
        }
        return recognize(readBytes(input), RecognizeOptions.auto());
    }

    /**
     * 把一张单字符图片加入模板库。适合默认字体对不上的空心字或手写风格。
     *
     * @param image 只含一个字符的图片，不能为空
     * @param label 该字符的正确答案
     */
    public void learn(BufferedImage image, char label) {
        if (image == null) {
            throw new IllegalArgumentException("image");
        }
        RgbImage rgb = RgbImage.from(image);
        RawGlyph glyph = largest(segmentForLearn(rgb));
        if (glyph == null) {
            glyph = fallbackGlyph(rgb);
        }
        if (glyph == null) {
            throw new IllegalArgumentException("glyph");
        }
        bank.learn(GlyphNormalizer.toGrid(glyph.pixels, glyph.width, glyph.height), label);
    }

    private RecognizeResult recognizeFrames(List<BufferedImage> frames, RecognizeOptions options) {
        long started = System.nanoTime();
        RecognizeResult solid = recognize(GifFrames.composite(frames), options);
        if (solid.getText().length() >= 3) {
            return withElapsed(solid, started);
        }
        List<GlyphHit> hits = new ArrayList<GlyphHit>();
        for (int i = 0; i < frames.size(); i++) {
            hits.addAll(recognize(frames.get(i), options).getGlyphs());
        }
        List<GlyphHit> merged = GifFrames.merge(hits, frames.get(0).getHeight());
        String text = textOf(merged);
        CaptchaType type = options.getType() == CaptchaType.AUTO ? CaptchaType.ALPHANUMERIC : options.getType();
        ArithmeticEvaluator.Value value = ArithmeticEvaluator.evaluate(text);
        String expression = value == null ? null : value.expression;
        Integer number = value == null ? null : Integer.valueOf(value.result);
        long elapsed = (System.nanoTime() - started) / 1000000L;
        return new RecognizeResult(text, expression, number, confidence(merged), type, elapsed,
                RecognizeResult.freeze(merged));
    }

    private static RecognizeResult withElapsed(RecognizeResult result, long started) {
        long elapsed = (System.nanoTime() - started) / 1000000L;
        return new RecognizeResult(result.getText(), result.getExpression(), result.getValue(),
                result.getConfidence(), result.getType(), elapsed, result.getGlyphs());
    }

    private static String textOf(List<GlyphHit> hits) {
        StringBuilder builder = new StringBuilder(hits.size());
        for (int i = 0; i < hits.size(); i++) {
            builder.append(hits.get(i).getValue());
        }
        return builder.toString();
    }

    private static double confidence(List<GlyphHit> hits) {
        if (hits.isEmpty()) {
            return 0;
        }
        double sum = 0;
        for (int i = 0; i < hits.size(); i++) {
            sum += hits.get(i).getConfidence();
        }
        return sum / hits.size();
    }

    private static byte[] readBytes(InputStream input) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[4096];
        int read = input.read(buffer);
        while (read >= 0) {
            if (read > 0) {
                out.write(buffer, 0, read);
            }
            read = input.read(buffer);
        }
        return out.toByteArray();
    }

    private static List<RawGlyph> segmentForLearn(RgbImage rgb) {
        int background = rgb.borderMedianLuminance();
        if (background < 80) {
            return new DarkSegmenter().segment(rgb);
        }
        if (OutlineSegmenter.matches(rgb)) {
            return new OutlineSegmenter().segment(rgb);
        }
        return new LightSegmenter().segment(rgb);
    }

    private static RawGlyph fallbackGlyph(RgbImage image) {
        int background = image.borderMedianLuminance();
        BinaryImage binary = new BinaryImage(image.width, image.height);
        for (int i = 0; i < image.pixels.length; i++) {
            int luminance = RgbImage.luminance(image.pixels[i]);
            boolean foreground = background < 80 ? luminance > 80 : luminance < background - 25;
            binary.data[i] = foreground ? (byte) 1 : 0;
        }
        return largest(binary.components(4, true));
    }

    private static RawGlyph largest(List<RawGlyph> glyphs) {
        RawGlyph best = null;
        for (int i = 0; i < glyphs.size(); i++) {
            RawGlyph glyph = glyphs.get(i);
            if (best == null || glyph.area > best.area) {
                best = glyph;
            }
        }
        return best;
    }
}
