package com.alianga.jkit.captcha.bench;

import com.alianga.jkit.captcha.CaptchaRecognizer;
import com.alianga.jkit.captcha.RecognizeOptions;
import com.alianga.jkit.captcha.RecognizeResult;

import java.io.File;
import java.io.FilenameFilter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * 扫描目录里的真实验证码并逐张识别，人工核对识别情况用。
 *
 * <p>文件名是纯字母数字时按真值与识别结果对比，一致与否只作参考：
 * 真实目录里的命名未必可靠，最终以人眼核对的画面为准。
 * 调用方已知验证码场景时可用 {@code --alphabet 0123456789} 限定字符集。
 *
 * <pre>
 * mvn -pl jkit-captcha-bench exec:java -Dexec.args="--dir /home/zml/图片/验证码/1"
 * mvn -pl jkit-captcha-bench exec:java -Dexec.args="--dir ... --alphabet 0123456789"
 * </pre>
 */
public final class DirScanMain {
    private static final String[] SUFFIXES = {".png", ".jpg", ".jpeg", ".gif", ".bmp"};

    private DirScanMain() {
    }

    /**
     * @param args 命令行参数，{@code --dir} 图片目录
     * @throws Exception 读图或识别失败时
     */
    public static void main(String[] args) throws Exception {
        File dir = new File(arg(args, "--dir", "."));
        String alphabet = arg(args, "--alphabet", "");
        RecognizeOptions options = alphabet.isEmpty() ? RecognizeOptions.auto()
                : RecognizeOptions.auto().withAlphabet(alphabet);
        File[] files = dir.listFiles(new FilenameFilter() {
            @Override
            public boolean accept(File parent, String name) {
                String lower = name.toLowerCase(Locale.ROOT);
                for (int i = 0; i < SUFFIXES.length; i++) {
                    if (lower.endsWith(SUFFIXES[i])) {
                        return true;
                    }
                }
                return false;
            }
        });
        if (files == null || files.length == 0) {
            System.out.println("目录没有图片 -> " + dir.getAbsolutePath());
            return;
        }
        Arrays.sort(files);
        CaptchaRecognizer recognizer = CaptchaRecognizer.createDefault();
        int matched = 0;
        int comparable = 0;
        int broken = 0;
        List<String> mismatched = new ArrayList<String>();
        try {
            System.out.println("文件名          识别结果      类型            置信度  耗时  与文件名");
            for (int i = 0; i < files.length; i++) {
                File file = files[i];
                long started = System.nanoTime();
                RecognizeResult result;
                try {
                    result = recognizer.recognize(java.nio.file.Files.readAllBytes(file.toPath()), options);
                } catch (RuntimeException ex) {
                    broken++;
                    System.out.printf(Locale.ROOT, "%-15s 识别异常: %s%n",
                            file.getName(), ex.getMessage());
                    continue;
                }
                long millis = (System.nanoTime() - started) / 1000000L;
                String expected = stem(file.getName());
                String verdict = "  —";
                if (!expected.isEmpty() && expected.matches("[0-9A-Za-z]+")) {
                    comparable++;
                    if (expected.equals(result.getText())) {
                        verdict = "  ✓";
                        matched++;
                    } else {
                        verdict = "  ✗ (文件名 " + expected + ")";
                        mismatched.add(file.getName() + " 画面与文件名不同，识别=" + result.getText());
                    }
                }
                String extra = result.getValue() == null ? ""
                        : " 值=" + result.getValue();
                System.out.printf(Locale.ROOT, "%-15s %-12s %-14s %5.2f  %4d ms%s%s%n",
                        file.getName(), result.getText(), result.getType(),
                        Double.valueOf(result.getConfidence()), Long.valueOf(millis), extra, verdict);
            }
        } finally {
            recognizer.close();
        }
        System.out.printf(Locale.ROOT, "共 %d 张，可与文件名对比 %d 张，一致 %d 张，识别异常 %d 张%n",
                Integer.valueOf(files.length), Integer.valueOf(comparable), Integer.valueOf(matched),
                Integer.valueOf(broken));
        if (!mismatched.isEmpty()) {
            System.out.println("与文件名不一致（可能命名本身不可靠，需人眼核对画面）：");
            for (int i = 0; i < mismatched.size(); i++) {
                System.out.println("  " + mismatched.get(i));
            }
        }
    }

    private static int countRecognized(File[] files) {
        return files.length;
    }

    private static String stem(String name) {
        int dot = name.lastIndexOf('.');
        return dot < 0 ? name : name.substring(0, dot);
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
