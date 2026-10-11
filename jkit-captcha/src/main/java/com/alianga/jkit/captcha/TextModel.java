package com.alianga.jkit.captcha;

import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtException;
import ai.onnxruntime.OrtLoggingLevel;
import ai.onnxruntime.OrtSession;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.FloatBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Collections;

/**
 * ddddocr 的两个 ONNX 模型（Apache-2.0）。高度缩到 64，灰度后做 CTC 解码。
 * {@code common_old.onnx} 偏小写，大小写交给 {@link CaseAlign}。
 * {@code common.onnx} 用在窄幅蓝字上，大小写留在模型结果里。
 */
final class TextModel {
    private static final int TARGET_HEIGHT = 64;
    private static final int LANCZOS_A = 3;

    private final Object lock = new Object();
    private OrtEnvironment environment;
    private OrtSession session;
    private String inputName;
    private String[] charset;
    private boolean failed;
    private OrtSession betaSession;
    private String betaInputName;
    private String[] betaCharset;
    private boolean betaFailed;

    String read(BufferedImage image) {
        return read(image, false);
    }

    /**
     * 用 {@code common.onnx} 读图。加载失败时返回空串。
     */
    String readBeta(BufferedImage image) {
        return read(image, true);
    }

    private String read(BufferedImage image, boolean beta) {
        if (image == null || image.getWidth() <= 0 || image.getHeight() <= 0) {
            return "";
        }
        try {
            float[][] rows = logits(image, beta);
            if (rows == null) {
                return "";
            }
            return decode(rows, beta ? betaCharset : charset);
        } catch (OrtException ex) {
            // 模型不可用时退回模板结果，不把异常抛到识别入口。
            return "";
        }
    }

    private float[][] logits(BufferedImage image, boolean beta) throws OrtException {
        synchronized (lock) {
            OrtSession used = open(beta);
            if (used == null) {
                return null;
            }
            String name = beta ? betaInputName : inputName;
            float[] input = pixels(image);
            int width = input.length / TARGET_HEIGHT;
            long[] shape = new long[] {1L, 1L, TARGET_HEIGHT, width};
            OnnxTensor tensor = OnnxTensor.createTensor(environment, FloatBuffer.wrap(input), shape);
            try {
                OrtSession.Result result = used.run(Collections.singletonMap(name, tensor));
                try {
                    return rows(result.get(0).getValue());
                } finally {
                    result.close();
                }
            } finally {
                tensor.close();
            }
        }
    }

    private OrtSession open(boolean beta) {
        if (beta) {
            if (betaFailed) {
                return null;
            }
            if (betaSession == null && !loadBeta()) {
                return null;
            }
            return betaSession;
        }
        if (failed) {
            return null;
        }
        if (session == null && !load()) {
            return null;
        }
        return session;
    }

    private boolean load() {
        try {
            byte[] model = readResource("ocr/common_old.onnx");
            charset = readCharset(readResource("ocr/charset.txt"));
            environment = OrtEnvironment.getEnvironment(OrtLoggingLevel.ORT_LOGGING_LEVEL_ERROR);
            OrtSession.SessionOptions options = new OrtSession.SessionOptions();
            options.setIntraOpNumThreads(1);
            session = environment.createSession(model, options);
            inputName = session.getInputNames().iterator().next();
            options.close();
            return true;
        } catch (OrtException ex) {
            failed = true;
            return false;
        } catch (IOException ex) {
            failed = true;
            return false;
        }
    }

    private boolean loadBeta() {
        try {
            byte[] model = readResource("ocr/common.onnx");
            betaCharset = readCharset(readResource("ocr/charset_beta.txt"));
            if (environment == null) {
                environment = OrtEnvironment.getEnvironment(OrtLoggingLevel.ORT_LOGGING_LEVEL_ERROR);
            }
            OrtSession.SessionOptions options = new OrtSession.SessionOptions();
            options.setIntraOpNumThreads(1);
            betaSession = environment.createSession(model, options);
            betaInputName = betaSession.getInputNames().iterator().next();
            options.close();
            return true;
        } catch (OrtException ex) {
            betaFailed = true;
            return false;
        } catch (IOException ex) {
            betaFailed = true;
            return false;
        }
    }

    private static String decode(float[][] rows, String[] table) {
        StringBuilder builder = new StringBuilder();
        int previous = 0;
        for (int t = 0; t < rows.length; t++) {
            int best = argmax(rows[t]);
            if (best != 0 && best != previous && best < table.length) {
                builder.append(table[best]);
            }
            previous = best;
        }
        return builder.toString();
    }

    private static int argmax(float[] row) {
        int best = 0;
        float score = row[0];
        for (int i = 1; i < row.length; i++) {
            if (row[i] > score) {
                score = row[i];
                best = i;
            }
        }
        return best;
    }

    private static float[][] rows(Object value) {
        if (value instanceof float[][][]) {
            float[][][] cube = (float[][][]) value;
            if (cube.length == 1 && cube[0].length > 1) {
                return cube[0];
            }
            float[][] flat = new float[cube.length][];
            for (int i = 0; i < cube.length; i++) {
                flat[i] = cube[i][0];
            }
            return flat;
        }
        if (value instanceof float[][]) {
            return (float[][]) value;
        }
        return null;
    }

    private static float[] pixels(BufferedImage image) {
        int width = Math.max(8, image.getWidth() * TARGET_HEIGHT / image.getHeight());
        int[] rgb = resize(image, width, TARGET_HEIGHT);
        float[] gray = new float[width * TARGET_HEIGHT];
        for (int i = 0; i < rgb.length; i++) {
            int red = (rgb[i] >> 16) & 0xff;
            int green = (rgb[i] >> 8) & 0xff;
            int blue = rgb[i] & 0xff;
            gray[i] = ((red * 299 + green * 587 + blue * 114) / 1000) / 255.0f;
        }
        return gray;
    }

    private static int[] resize(BufferedImage image, int width, int height) {
        int srcWidth = image.getWidth();
        int srcHeight = image.getHeight();
        int[] src = new int[srcWidth * srcHeight];
        for (int y = 0; y < srcHeight; y++) {
            for (int x = 0; x < srcWidth; x++) {
                src[y * srcWidth + x] = image.getRGB(x, y);
            }
        }
        int[] horizontal = new int[width * srcHeight];
        for (int y = 0; y < srcHeight; y++) {
            for (int x = 0; x < width; x++) {
                horizontal[y * width + x] = sample(src, srcWidth, y * srcWidth, x, width, srcWidth, true);
            }
        }
        int[] vertical = new int[width * height];
        for (int x = 0; x < width; x++) {
            for (int y = 0; y < height; y++) {
                vertical[y * width + x] = sample(horizontal, srcHeight, x, y, height, width, false);
            }
        }
        return vertical;
    }

    private static int sample(int[] src, int srcSize, int origin, int dest, int destSize, int stride, boolean row) {
        double scale = srcSize / (double) destSize;
        double filter = scale > 1.0 ? scale : 1.0;
        double support = LANCZOS_A * filter;
        double center = (dest + 0.5) * scale - 0.5;
        int left = (int) Math.floor(center - support);
        int right = (int) Math.ceil(center + support);
        double red = 0;
        double green = 0;
        double blue = 0;
        double weightSum = 0;
        for (int i = left; i <= right; i++) {
            double weight = lanczos((i - center) / filter);
            if (weight == 0) {
                continue;
            }
            int index = i;
            if (index < 0) {
                index = 0;
            } else if (index >= srcSize) {
                index = srcSize - 1;
            }
            int packed = row ? src[origin + index] : src[index * stride + origin];
            red += ((packed >> 16) & 0xff) * weight;
            green += ((packed >> 8) & 0xff) * weight;
            blue += (packed & 0xff) * weight;
            weightSum += weight;
        }
        if (weightSum == 0) {
            return 0;
        }
        return (clamp(red / weightSum) << 16) | (clamp(green / weightSum) << 8) | clamp(blue / weightSum);
    }

    private static double lanczos(double x) {
        double ax = Math.abs(x);
        if (ax >= LANCZOS_A) {
            return 0;
        }
        if (ax < 1.0e-6) {
            return 1;
        }
        double pix = Math.PI * x;
        double pixa = pix / LANCZOS_A;
        return Math.sin(pix) * Math.sin(pixa) / (pix * pixa);
    }

    private static int clamp(double value) {
        if (value < 0) {
            return 0;
        }
        if (value > 255) {
            return 255;
        }
        return (int) Math.round(value);
    }

    private static byte[] readResource(String name) throws IOException {
        InputStream input = TextModel.class.getResourceAsStream(name);
        if (input == null) {
            throw new IOException(name);
        }
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            int read = input.read(buffer);
            while (read >= 0) {
                if (read > 0) {
                    out.write(buffer, 0, read);
                }
                read = input.read(buffer);
            }
            return out.toByteArray();
        } finally {
            input.close();
        }
    }

    private static String[] readCharset(byte[] data) {
        String text = new String(data, StandardCharsets.UTF_8);
        String[] lines = text.split("\n", -1);
        int count = lines.length;
        if (count > 0 && lines[count - 1].isEmpty()) {
            count--;
        }
        String[] chars = new String[count];
        for (int i = 0; i < count; i++) {
            chars[i] = lines[i];
        }
        return chars;
    }
}
