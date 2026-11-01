package com.alianga.jkit.captcha;

import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

/**
 * 常规图片验证码识别入口。
 *
 * <p>模板匹配处理算术、多帧 GIF 和大小写。单帧图再用 ddddocr 的 {@code common_old.onnx}
 * 补字符，候选收在当前类型或 {@link RecognizeOptions#withAlphabet(String)} 给出的字符集里。
 * 算术已经算出结果时不再交给模型。宽 130、高 48 的白底彩色字先按 easy-captcha 的十套字体比对，
 * 四字和三目算式也用这套字形。对不上再用 {@code common.onnx}：四帧按字位投票读常用汉字，五帧读字母和数字，
 * 算式先盖住右侧再计算。浅色大图先拉开对比度，整串都是字母时按大写输出。
 * 需要替换整条链路时，实现 {@link CaptchaSolver} 并 {@link #register(CaptchaSolver)}。
 *
 * <p>本类实现 {@link AutoCloseable}：{@link TextModel} 内部持有 ONNX Runtime 的
 * OrtSession（native 内存指针），不关闭会随 GC Finalizer 延迟释放，在长寿命服务里
 * 会积累 native memory 压力。使用方应在 shutdown 时调用 {@link #close()}。</p>
 */
public final class CaptchaRecognizer implements AutoCloseable {
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
        RecognizeResult result = recognize(image, options, true);
        return applyPlateCase(image, result);
    }

    /** 按同一张图里的字高差距拨正短身字母。130×48 的白底字已经由字形表定过大小写，这里不再改。 */
    private RecognizeResult applyPlateCase(BufferedImage image, RecognizeResult result) {
        if (WhitePlate.matches(image)) {
            return result;
        }
        String adjusted = PlateCase.adjust(result.getText(), result.getGlyphs(), image.getHeight());
        // 减号补回和算式修复只服务真算术图；字母图被读成数字串时进来只会把它包装成假算式。
        if (result.getType() == CaptchaType.ARITHMETIC) {
            adjusted = MinusInsert.insert(image, adjusted);
            adjusted = OpRepair.repair(adjusted);
        }
        if (adjusted.equals(result.getText())) {
            return result;
        }
        ArithmeticEvaluator.Value value = ArithmeticEvaluator.evaluate(adjusted);
        String expression = value == null ? null : value.expression;
        Integer number = value == null ? null : Integer.valueOf(value.result);
        return new RecognizeResult(adjusted, expression, number, result.getConfidence(), result.getType(),
                result.getElapsedMillis(), result.getGlyphs());
    }

    private RecognizeResult recognize(BufferedImage image, RecognizeOptions options, boolean refine) {
        if (image == null) {
            throw new IllegalArgumentException("image");
        }
        if (image.getWidth() <= 0 || image.getHeight() <= 0) {
            // 不拦住的话，深处的 borderMedianLuminance 会以数组越界的形式报错。
            throw new IllegalArgumentException("image size");
        }
        RecognizeOptions effective = options == null ? RecognizeOptions.auto() : options;
        List<CaptchaSolver> candidates;
        synchronized (solvers) {
            candidates = new ArrayList<CaptchaSolver>(solvers);
        }
        // 第三方 solver 的识别耗时不可控，快照后在锁外遍历，register 不被它卡住。
        for (int i = 0; i < candidates.size(); i++) {
            CaptchaSolver solver = candidates.get(i);
            if (solver.supports(effective.getType())) {
                return solver.recognize(image, effective);
            }
        }
        // 浅色大图笔画和背景挤在一起，拉开对比度后再走原来的链路。拉伸后必有暗通道，不会再次进入这里。
        if (refine && PastelLift.matches(image)) {
            return liftLetters(recognize(PastelLift.stretch(image), effective, true), effective);
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
        String upper = text.toUpperCase(java.util.Locale.ROOT);
        if (upper.equals(text)) {
            return result;
        }
        return new RecognizeResult(upper, null, null, result.getConfidence(), result.getType(),
                result.getElapsedMillis(), result.getGlyphs());
    }

    /**
     * 模型解码用的字符集。调用方给了字符集就用它；空串与未设置一样按类型取默认值，
     * 与模板路径的 {@code alphabetFor} 保持同一语义，模型不会因此拿到全字符集。
     * 只有调用方明确指定算术型时才带运算符。自动判断里的深色图会先被标成算术型，
     * 蓝字车牌仍要输出字母，不能跟着这个临时类型把字母从模型里拿掉。
     */
    static String modelAlphabet(RecognizeOptions options) {
        String chosen = options.getAlphabet();
        if (chosen != null && chosen.length() == 0) {
            chosen = null;
        }
        if (chosen == null) {
            if (options.getType() == CaptchaType.ARITHMETIC) {
                return TemplateBank.ALPHANUMERIC + "+-=?*/";
            }
            return TemplateBank.ALPHANUMERIC;
        }
        return chosen;
    }

    /**
     * 深色蓝字按版式读。其余单帧图用模型补字，大小写仍沿用模板。模型只输出当前字符集里的字。
     */
    private RecognizeResult refine(BufferedImage image, RecognizeResult result, RecognizeOptions options) {
        String alphabet = modelAlphabet(options);
        // 深色算术图模型也别拿全字母数字表：算式里的斜杠会被它读成 l，减号读成 s，
        // CaseAlign 合并时这些字母就进了算式。只在模板串里确有运算符时才限到算术字符集——
        // 裸数字串可能是字母图被强套的数字标签，锁死字符集会断掉模型对字母的救援。
        if (result.getType() == CaptchaType.ARITHMETIC && options.getType() == CaptchaType.AUTO
                && options.getAlphabet() == null && containsOperator(result.getText())) {
            alphabet = TemplateBank.ARITHMETIC;
        }
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
        if (result.getType() == CaptchaType.ARITHMETIC && containsOperator(result.getText())) {
            merged = repairOperators(result.getText(), merged);
        }
        if (merged.equals(result.getText())) {
            return result;
        }
        return rewrite(result, merged);
    }

    private static boolean containsOperator(String text) {
        if (text == null) {
            return false;
        }
        for (int i = 0; i < text.length(); i++) {
            if ("+-*/".indexOf(text.charAt(i)) >= 0) {
                return true;
            }
        }
        return false;
    }

    /**
     * 算术图的合并修复。模板串常带着裂开数字的垃圾（7 裂成两块，一块丢一块误标成斜杠）
     * 导致算不出值才进到这里，CaseAlign 又会拿模型字覆盖模板里正确的运算符。
     * 把模板里的加减乘除按位还原回去，在所有还原组合里挑唯一能算出值的那个；
     * 有歧义或都算不出值时保守维持原合并。
     */
    private static String repairOperators(String template, String merged) {
        if (template == null || merged == null || template.length() != merged.length()
                || template.equals(merged)) {
            return merged;
        }
        List<Integer> spots = new ArrayList<Integer>();
        for (int i = 0; i < template.length(); i++) {
            char t = template.charAt(i);
            if (t != merged.charAt(i) && "+-*/".indexOf(t) >= 0) {
                spots.add(Integer.valueOf(i));
            }
        }
        if (spots.isEmpty() || spots.size() > 4) {
            return merged;
        }
        String found = null;
        for (int mask = 1; mask < 1 << spots.size(); mask++) {
            StringBuilder builder = new StringBuilder(merged);
            for (int i = 0; i < spots.size(); i++) {
                if ((mask & 1 << i) != 0) {
                    builder.setCharAt(spots.get(i).intValue(), template.charAt(spots.get(i).intValue()));
                }
            }
            String candidate = builder.toString();
            if (ArithmeticEvaluator.evaluate(candidate) != null) {
                if (found != null) {
                    return merged;
                }
                found = candidate;
            }
        }
        return found == null ? merged : found;
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
        return recognize(Io.readAll(input), RecognizeOptions.auto());
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
        if (image.getWidth() <= 0 || image.getHeight() <= 0) {
            throw new IllegalArgumentException("image size");
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
        String text = GlyphHit.textOf(merged);
        CaptchaType type = options.getType() == CaptchaType.AUTO ? CaptchaType.ALPHANUMERIC : options.getType();
        ArithmeticEvaluator.Value value = ArithmeticEvaluator.evaluate(text);
        String expression = value == null ? null : value.expression;
        Integer number = value == null ? null : Integer.valueOf(value.result);
        long elapsed = (System.nanoTime() - started) / 1000000L;
        return new RecognizeResult(text, expression, number, GlyphHit.confidence(merged), type, elapsed,
                RecognizeResult.freeze(merged));
    }

    private static RecognizeResult withElapsed(RecognizeResult result, long started) {
        long elapsed = (System.nanoTime() - started) / 1000000L;
        return new RecognizeResult(result.getText(), result.getExpression(), result.getValue(),
                result.getConfidence(), result.getType(), elapsed, result.getGlyphs());
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

    /**
     * 释放 {@link TextModel} 持有的 ONNX Runtime native 资源。
     *
     * <p>多次调用幂等。调用后本识别器仍可使用（再次识别时 {@link TextModel} 会重新加载
     * 模型），但 native 内存被释放；如果不关闭，长寿命服务里 native memory 会持续累积。</p>
     */
    @Override
    public void close() {
        textModel.close();
    }

    /**
     * 主模型（{@code common_old.onnx}）当前是否可用。加载失败时识别会静默退回
     * 纯模板结果，接口行为不变，这个方法用于部署环境的诊断。close 不算失败：
     * 下次识别会延迟重新加载。
     */
    public boolean isModelAvailable() {
        return textModel.primaryAvailable();
    }
}
