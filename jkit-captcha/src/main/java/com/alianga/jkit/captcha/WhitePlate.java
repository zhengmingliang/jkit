package com.alianga.jkit.captcha;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * 白底、大约 130×48 的彩色字符图。笔画和干扰线连在一起，模板分割会整块丢掉。
 * 这类图用 {@code common.onnx} 直接读。算术图右侧是 {@code =?}，先盖住再计算，避免问号被当成数字。
 * 四帧图按 easy-captcha 的透明度节奏，每帧只有一个字完全不透明，按时间步把最高分投到对应字位。
 * 五帧图用同一套投票读字母和数字。这种字体的大小写是同一个字形，结果保留模型给出的字母。
 */
final class WhitePlate {
    private static final int MIN_WIDTH = 124;
    private static final int MAX_WIDTH = 136;
    private static final int MIN_HEIGHT = 46;
    private static final int MAX_HEIGHT = 50;
    private static final int MIN_BORDER = 220;
    private static final int MIN_INK = 20;
    private static final int INK_LUMINANCE = 200;
    private static final int TAIL_PERCENT = 62;
    private static final int HAN_FRAMES = 4;
    private static final float PLUS_GAP = 4.0f;
    private static final float TIMES_GAP = 0.55f;
    private static final String OPERATORS = "0123456789+-x";

    private static boolean hanLoaded;
    private static String hanChars;

    private WhitePlate() {
    }

    static boolean matches(BufferedImage image) {
        return image != null && canvas(image) && ink(image) >= MIN_INK;
    }

    static boolean matches(List<BufferedImage> frames) {
        if (frames == null || frames.isEmpty() || !canvas(frames.get(0))) {
            return false;
        }
        int width = frames.get(0).getWidth();
        int height = frames.get(0).getHeight();
        int total = 0;
        for (int i = 0; i < frames.size(); i++) {
            BufferedImage frame = frames.get(i);
            if (frame.getWidth() == width && frame.getHeight() == height) {
                total += ink(frame);
            }
        }
        return total >= MIN_INK;
    }

    static RecognizeResult read(BufferedImage image, TextModel model, RecognizeOptions options) {
        long started = System.nanoTime();
        String user = options.getAlphabet();
        TextModel.Reading reading;
        boolean arithmetic = false;
        if (user != null) {
            reading = model.readBetaReading(image, user.length() == 0 ? null : user);
        } else if (options.getType() == CaptchaType.ARITHMETIC) {
            reading = model.readBetaReading(maskTail(image), OPERATORS);
            arithmetic = true;
        } else if (options.getType() == CaptchaType.ALPHANUMERIC
                || options.getType() == CaptchaType.DISTORTED) {
            reading = model.readBetaReading(image, TemplateBank.ALPHANUMERIC);
        } else {
            TextModel.Reading expression = tryExpression(image, model);
            if (expression != null) {
                reading = expression;
                arithmetic = true;
            } else {
                reading = lettersOrHan(image, model);
            }
        }
        if (reading == null || reading.text.length() == 0) {
            return null;
        }
        if (arithmetic) {
            return arithmeticResult(reading.text, reading.confidence, started);
        }
        return textResult(reading.text, reading.confidence, started);
    }

    static RecognizeResult readFrames(List<BufferedImage> frames, TextModel model, RecognizeOptions options) {
        String user = options.getAlphabet();
        String alphabet;
        boolean arithmetic = false;
        if (user != null) {
            alphabet = user.length() == 0 ? null : user;
        } else if (options.getType() == CaptchaType.ARITHMETIC) {
            alphabet = OPERATORS;
            arithmetic = true;
        } else if (options.getType() == CaptchaType.ALPHANUMERIC
                || options.getType() == CaptchaType.DISTORTED) {
            alphabet = TemplateBank.ALPHANUMERIC;
        } else if (frames.size() == HAN_FRAMES) {
            alphabet = hanCharset();
            if (alphabet.length() == 0) {
                return null;
            }
        } else {
            alphabet = TemplateBank.ALPHANUMERIC;
        }
        TextModel.Reading reading = vote(frames, model, alphabet);
        if (reading.text.length() == 0) {
            return null;
        }
        if (arithmetic) {
            return arithmeticResult(reading.text, reading.confidence, System.nanoTime());
        }
        return textResult(reading.text, reading.confidence, System.nanoTime());
    }

    private static TextModel.Reading tryExpression(BufferedImage image, TextModel model) {
        BufferedImage masked = maskTail(image);
        TextModel.Reading ops = model.readBetaReading(masked, OPERATORS);
        if (ops.text.length() == 0) {
            return null;
        }
        TextModel.Reading letters = model.readBetaReading(masked, TemplateBank.ALPHANUMERIC);
        if (!preferExpression(ops.text, ops.confidence, letters.confidence)) {
            return null;
        }
        return ops;
    }

    /**
     * 加减号和字母的分差可以到 4。乘号 {@code x} 和字母 x 长得一样，只在分差不超过 0.55 时采信。
     */
    private static boolean preferExpression(String text, float opsScore, float letterScore) {
        int operator = -1;
        for (int i = 0; i < text.length(); i++) {
            char value = text.charAt(i);
            if (value == '+' || value == '-' || value == 'x') {
                if (operator >= 0) {
                    return false;
                }
                operator = i;
            } else if (value < '0' || value > '9') {
                return false;
            }
        }
        if (operator <= 0 || operator >= text.length() - 1) {
            return false;
        }
        float gap = letterScore - opsScore;
        if (text.charAt(operator) == 'x') {
            return gap <= TIMES_GAP;
        }
        return gap <= PLUS_GAP;
    }

    private static TextModel.Reading lettersOrHan(BufferedImage image, TextModel model) {
        TextModel.Reading letters = model.readBetaReading(image, TemplateBank.ALPHANUMERIC);
        String han = hanCharset();
        if (han.length() == 0) {
            return letters;
        }
        TextModel.Reading chinese = model.readBetaReading(image, han);
        if (chinese.text.length() > 0 && chinese.confidence >= letters.confidence) {
            return chinese;
        }
        return letters;
    }

    private static TextModel.Reading vote(List<BufferedImage> frames, TextModel model, String alphabet) {
        int slots = frames.size();
        String[] chars = new String[slots];
        float[] scores = new float[slots];
        for (int i = 0; i < slots; i++) {
            scores[i] = Float.NEGATIVE_INFINITY;
        }
        for (int i = 0; i < frames.size(); i++) {
            TextModel.Reading reading = model.readBetaReading(frames.get(i), alphabet);
            if (reading.steps <= 0) {
                continue;
            }
            for (int t = 0; t < reading.tokens.size(); t++) {
                TextModel.Token token = reading.tokens.get(t);
                int slot = slotOf(token.time, reading.steps, slots);
                if (token.score > scores[slot]) {
                    scores[slot] = token.score;
                    chars[slot] = token.text;
                }
            }
        }
        StringBuilder builder = new StringBuilder(slots);
        float sum = 0f;
        int counted = 0;
        for (int i = 0; i < slots; i++) {
            if (chars[i] != null) {
                builder.append(chars[i]);
                sum += scores[i];
                counted++;
            }
        }
        float confidence = counted == 0 ? Float.NEGATIVE_INFINITY : sum / counted;
        return new TextModel.Reading(builder.toString(), confidence, slots, new ArrayList<TextModel.Token>());
    }

    private static int slotOf(int time, int steps, int slots) {
        int slot = (int) ((time + 0.5d) * slots / steps);
        if (slot < 0) {
            return 0;
        }
        if (slot >= slots) {
            return slots - 1;
        }
        return slot;
    }

    private static BufferedImage maskTail(BufferedImage image) {
        int width = image.getWidth();
        int height = image.getHeight();
        BufferedImage copy = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = copy.createGraphics();
        graphics.drawImage(image, 0, 0, null);
        graphics.setColor(Color.WHITE);
        int cut = width * TAIL_PERCENT / 100;
        graphics.fillRect(cut, 0, width - cut, height);
        graphics.dispose();
        return copy;
    }

    private static RecognizeResult arithmeticResult(String text, float confidence, long started) {
        ArithmeticEvaluator.Value value = ArithmeticEvaluator.evaluate(text);
        long elapsed = (System.nanoTime() - started) / 1000000L;
        List<GlyphHit> glyphs = RecognizeResult.freeze(new ArrayList<GlyphHit>());
        if (value == null) {
            return new RecognizeResult(text, null, null, unit(confidence), CaptchaType.ALPHANUMERIC,
                    elapsed, glyphs);
        }
        return new RecognizeResult(value.expression, value.expression, Integer.valueOf(value.result),
                unit(confidence), CaptchaType.ARITHMETIC, elapsed, glyphs);
    }

    private static RecognizeResult textResult(String text, float confidence, long started) {
        long elapsed = (System.nanoTime() - started) / 1000000L;
        return new RecognizeResult(text, null, null, unit(confidence), CaptchaType.ALPHANUMERIC, elapsed,
                RecognizeResult.freeze(new ArrayList<GlyphHit>()));
    }

    private static double unit(float logit) {
        if (logit <= 0f || Float.isInfinite(logit)) {
            return 0;
        }
        if (logit >= 30f) {
            return 1;
        }
        return logit / 30.0;
    }

    private static boolean canvas(BufferedImage image) {
        int width = image.getWidth();
        int height = image.getHeight();
        if (width < MIN_WIDTH || width > MAX_WIDTH || height < MIN_HEIGHT || height > MAX_HEIGHT) {
            return false;
        }
        return RgbImage.from(image).borderMedianLuminance() >= MIN_BORDER;
    }

    private static int ink(BufferedImage image) {
        int width = image.getWidth();
        int height = image.getHeight();
        int[] pixels = image.getRGB(0, 0, width, height, null, 0, width);
        int count = 0;
        for (int i = 0; i < pixels.length; i++) {
            if (RgbImage.luminance(pixels[i]) < INK_LUMINANCE) {
                count++;
            }
        }
        return count;
    }

    private static String hanCharset() {
        if (hanLoaded) {
            return hanChars;
        }
        hanChars = loadHan();
        hanLoaded = true;
        return hanChars;
    }

    private static String loadHan() {
        InputStream input = WhitePlate.class.getResourceAsStream("ocr/easy-han.txt");
        if (input == null) {
            return "";
        }
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[1024];
            int read = input.read(buffer);
            while (read >= 0) {
                if (read > 0) {
                    out.write(buffer, 0, read);
                }
                read = input.read(buffer);
            }
            return new String(out.toByteArray(), StandardCharsets.UTF_8).trim();
        } catch (IOException ex) {
            return "";
        } finally {
            try {
                input.close();
            } catch (IOException ex) {
                // 字符集已经读完，关闭失败不影响识别。
            }
        }
    }
}
