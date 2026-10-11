package com.alianga.jkit.captcha;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 常规图片验证码识别入口。
 *
 * <p>模板匹配处理算术、多帧 GIF 和大小写。单帧图再用 ddddocr 的 {@code common_old.onnx}
 * 补字符，候选收在当前类型或 {@link RecognizeOptions#withAlphabet(String)} 给出的字符集里。
 * 算术已经算出结果时不再交给模型。宽 130、高 48 的白底彩色字先按 easy-captcha 的十套字体比对，
 * 四字和三目算式也用这套字形。对不上再用 {@code common.onnx}：四帧按字位投票读常用汉字，五帧读字母和数字，
 * 算式先盖住右侧再计算。浅色大图先拉开对比度，整串都是字母时按大写输出。
 * 需要替换整条链路时，实现 {@link CaptchaSolver} 并 {@link #register(CaptchaSolver)}。
 */
public final class CaptchaRecognizer {
    private static final int PASTEL_MIN_WIDTH = 200;
    private static final int PASTEL_MIN_HEIGHT = 80;
    private static final int PASTEL_MIN_INK = 100;
    private static final int PASTEL_CHANNEL = 80;
    private static final int PASTEL_INK = 40;

    private final TemplateBank bank;
    private final DefaultCaptchaSolver fallback;
    private final TextModel textModel = new TextModel();
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
        return recognize(image, options, true);
    }

    private RecognizeResult recognize(BufferedImage image, RecognizeOptions options, boolean refine) {
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
        // 浅色大图笔画和背景挤在一起，拉开对比度后再走原来的链路。拉伸后必有暗通道，不会再次进入这里。
        if (refine && pastel(image)) {
            return liftLetters(recognize(stretch(image), effective, true), effective);
        }
        // 白底 130×48 的彩色字走 common.onnx。模板会把粘连的笔画整块丢掉，也会把算式尾巴读成数字。
        if (refine && WhitePlate.matches(image)) {
            RecognizeResult plate = WhitePlate.read(image, textModel, effective);
            if (plate != null) {
                return plate;
            }
        }
        RecognizeResult result = fallback.recognize(image, effective);
        // 算式已经算出值时不再改写。深色蓝字里的 X 也会被当成乘号，这种图仍交给模型。
        if (!refine || (result.getValue() != null && !BluePlate.matches(image))) {
            return result;
        }
        return refine(image, result, effective);
    }

    /**
     * 深色蓝字按版式读。其余单帧图用模型补字，大小写仍沿用模板。模型只输出当前字符集里的字。
     */
    private RecognizeResult refine(BufferedImage image, RecognizeResult result, RecognizeOptions options) {
        String alphabet = modelAlphabet(options);
        String plate = BluePlate.read(image, textModel, alphabet);
        if (plate != null) {
            return rewrite(result, plate);
        }
        String model = textModel.read(image, alphabet);
        if (model.isEmpty()) {
            return result;
        }
        // 单字学习样本要以模板为准。模型把没见过的实心块读成另一个单字时不覆盖。
        if (result.getText().length() == 1 && model.length() == 1
                && !result.getText().equalsIgnoreCase(model)) {
            return result;
        }
        String merged = CaseAlign.align(result.getText(), model);
        if (merged.equals(result.getText())) {
            return result;
        }
        return rewrite(result, merged);
    }

    /**
     * 调用方给了字符集就用它。空串表示不限制。
     * 只有调用方明确指定算术型时才带运算符。自动判断里的深色图会先被标成算术型，
     * 蓝字车牌仍要输出字母，不能跟着这个临时类型把字母从模型里拿掉。
     */
    /**
     * 宽高都够大、墨迹不少于 100、并且没有一个墨迹通道低于 80，才算浅色大图。
     * 饱和的政务图和 130×48 的彩色字都有深色通道，不会被拉开。
     */
    private static boolean pastel(BufferedImage image) {
        int width = image.getWidth();
        int height = image.getHeight();
        if (width < PASTEL_MIN_WIDTH || height < PASTEL_MIN_HEIGHT) {
            return false;
        }
        int ink = 0;
        int limit = PASTEL_INK * PASTEL_INK;
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int rgb = image.getRGB(x, y);
                int red = (rgb >> 16) & 255;
                int green = (rgb >> 8) & 255;
                int blue = rgb & 255;
                int dr = 255 - red;
                int dg = 255 - green;
                int db = 255 - blue;
                if (dr * dr + dg * dg + db * db < limit) {
                    continue;
                }
                if (red < PASTEL_CHANNEL || green < PASTEL_CHANNEL || blue < PASTEL_CHANNEL) {
                    return false;
                }
                ink++;
            }
        }
        return ink >= PASTEL_MIN_INK;
    }

    /** 按亮度的最小最大把每个通道拉开。公式和测过 FGIP 的那次拉伸一致。 */
    private static BufferedImage stretch(BufferedImage src) {
        int width = src.getWidth();
        int height = src.getHeight();
        BufferedImage out = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        int min = 255;
        int max = 0;
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int rgb = src.getRGB(x, y);
                int luminance = ((rgb >> 16) & 255) * 3 + ((rgb >> 8) & 255) * 6 + (rgb & 255);
                luminance /= 10;
                if (luminance < min) {
                    min = luminance;
                }
                if (luminance > max) {
                    max = luminance;
                }
            }
        }
        int span = Math.max(1, max - min);
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int rgb = src.getRGB(x, y);
                int red = scaleChannel((rgb >> 16) & 255, min, span);
                int green = scaleChannel((rgb >> 8) & 255, min, span);
                int blue = scaleChannel(rgb & 255, min, span);
                out.setRGB(x, y, (red << 16) | (green << 8) | blue);
            }
        }
        return out;
    }

    private static int scaleChannel(int value, int min, int span) {
        int scaled = (value - min) * 255 / span;
        if (scaled < 0) {
            return 0;
        }
        if (scaled > 255) {
            return 255;
        }
        return scaled;
    }

    /** 调用方没有限定字符集、结果又全是字母时，浅色大图按大写输出。 */
    private static RecognizeResult liftLetters(RecognizeResult result, RecognizeOptions options) {
        if (result.getValue() != null) {
            return result;
        }
        String alphabet = options.getAlphabet();
        if (alphabet != null && alphabet.length() > 0) {
            return result;
        }
        String text = result.getText();
        if (text.isEmpty()) {
            return result;
        }
        for (int i = 0; i < text.length(); i++) {
            char ch = text.charAt(i);
            if (ch < 'A' || (ch > 'Z' && ch < 'a') || ch > 'z') {
                return result;
            }
        }
        String upper = text.toUpperCase(Locale.ROOT);
        if (upper.equals(text)) {
            return result;
        }
        return new RecognizeResult(upper, null, null, result.getConfidence(), result.getType(),
                result.getElapsedMillis(), result.getGlyphs());
    }

    private static String modelAlphabet(RecognizeOptions options) {
        String chosen = options.getAlphabet();
        if (chosen != null) {
            return chosen.length() == 0 ? null : chosen;
        }
        if (options.getType() == CaptchaType.ARITHMETIC) {
            return TemplateBank.ALPHANUMERIC + "+-=?*/";
        }
        return TemplateBank.ALPHANUMERIC;
    }

    private static RecognizeResult rewrite(RecognizeResult result, String text) {
        ArithmeticEvaluator.Value value = ArithmeticEvaluator.evaluate(text);
        String expression = value == null ? null : value.expression;
        Integer number = value == null ? null : Integer.valueOf(value.result);
        return new RecognizeResult(text, expression, number, result.getConfidence(), result.getType(),
                result.getElapsedMillis(), result.getGlyphs());
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
        if (WhitePlate.matches(frames)) {
            RecognizeResult plate = WhitePlate.readFrames(frames, textModel, options);
            if (plate != null) {
                return withElapsed(plate, started);
            }
        }
        RecognizeResult solid = recognize(GifFrames.composite(frames), options, false);
        if (solid.getText().length() >= 3) {
            return withElapsed(solid, started);
        }
        List<GlyphHit> hits = new ArrayList<GlyphHit>();
        for (int i = 0; i < frames.size(); i++) {
            hits.addAll(recognize(frames.get(i), options, false).getGlyphs());
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
