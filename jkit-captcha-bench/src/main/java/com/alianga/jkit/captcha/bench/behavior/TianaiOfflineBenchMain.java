package com.alianga.jkit.captcha.bench.behavior;

import cloud.tianai.captcha.application.ImageCaptchaApplication;
import cloud.tianai.captcha.application.TACBuilder;
import cloud.tianai.captcha.generator.ImageCaptchaGenerator;
import cloud.tianai.captcha.generator.common.model.dto.ImageCaptchaInfo;
import cloud.tianai.captcha.resource.common.model.dto.Resource;
import com.alianga.jkit.captcha.behavior.SliderCaptchaSolver;
import com.alianga.jkit.captcha.behavior.TianaiTrackRules;
import com.alianga.jkit.captcha.behavior.TrackPoint;

import javax.imageio.ImageIO;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Base64;
import java.util.List;
import java.util.Random;

/**
 * 行为验证码离线校准基准：用开源 tianai-captcha 在本地生成带真值（randomX）的滑块验证码，
 * 评估 jkit 缺口定位三策略的像素误差分布，并验证轨迹合成能通过行为规则自检。
 *
 * <p>运行：mvn -pl jkit-captcha-bench compile exec:java@tianai-offline
 * （或直接运行 main）。输出误差统计与样本图片，用于校准定位策略融合参数。
 */
public final class TianaiOfflineBenchMain {
    private static final int DEFAULT_COUNT = 30;

    private TianaiOfflineBenchMain() {
    }

    public static void main(String[] args) throws IOException {
        int count = args.length > 0 ? Integer.parseInt(args[0]) : DEFAULT_COUNT;
        Path output = Paths.get(System.getProperty("bench.output", "target/captcha-dataset"), "tianai-behavior");
        Files.createDirectories(output);

        ImageCaptchaApplication application = TACBuilder.builder()
                .addDefaultTemplate()
                .addResource("SLIDER", new Resource("classpath",
                        "META-INF/cut-image/resource/1.jpg"))
                .build();
        ImageCaptchaGenerator generator = application.getImageCaptchaGenerator();

        SliderCaptchaSolver solver = new SliderCaptchaSolver();
        Random random = new Random(20261101L);
        Stats interior = new Stats();
        Stats ring = new Stats();
        Stats flatCore = new Stats();
        Stats chosen = new Stats();
        int solveFail = 0;
        int trackFail = 0;

        for (int i = 0; i < count; i++) {
            ImageCaptchaInfo info = generator.generateCaptchaImage("SLIDER");
            BufferedImage bg = decode(info.getBackgroundImage());
            BufferedImage template = decode(info.getTemplateImage());
            int truth = info.getRandomX();
            SliderCaptchaSolver.Solution solution;
            try {
                solution = solver.solve(bg, template);
            } catch (IllegalStateException e) {
                solveFail++;
                System.out.println("[" + i + "] solve fail: " + e.getMessage());
                continue;
            }
            recordError(interior, solution.getInteriorCandidate(), truth);
            recordError(ring, solution.getRingCandidate(), truth);
            recordError(flatCore, solution.getFlatCoreCandidate(), truth);
            chosen.add(solution.getGapX() - truth, solution.getStrategy(), solution.getConfidence());
            // 轨迹自检：以自然宽 600 提交坐标系
            List<TrackPoint> track = solver.buildTrack(solution, info.getBackgroundImageWidth(), random);
            long start = System.currentTimeMillis();
            long stop = start + (long) track.get(track.size() - 1).getT();
            TianaiTrackRules.Failure failure = TianaiTrackRules.validate(
                    track, info.getBackgroundImageWidth(), start, stop);
            if (failure != null) {
                trackFail++;
                System.out.println("[" + i + "] track rule fail: " + failure);
            }
            if (i < 3) {
                ImageIO.write(bg, "png", output.resolve("bg-" + i + ".png").toFile());
                ImageIO.write(template, "png", output.resolve("tpl-" + i + ".png").toFile());
                Files.write(output.resolve("truth-" + i + ".txt"),
                        ("randomX=" + truth + " solution=" + solution).getBytes(StandardCharsets.UTF_8));
            }
            System.out.println("[" + i + "] truth=" + truth + " " + solution
                    + " err=" + (solution.getGapX() - truth));
        }

        System.out.println();
        System.out.println("== 缺口定位误差（像素，负=偏左） ==");
        System.out.println("interior : " + interior.report());
        System.out.println("ring     : " + ring.report());
        System.out.println("flatCore : " + flatCore.report());
        System.out.println("chosen   : " + chosen.report());
        System.out.println("solveFail=" + solveFail + " trackFail=" + trackFail + " / " + count);
    }

    private static void recordError(Stats stats, SliderCaptchaSolver.Candidate candidate, int truth) {
        if (candidate == null) {
            stats.miss();
        } else {
            stats.add(candidate.x - truth, candidate.strategy, candidate.confidence);
        }
    }

    private static BufferedImage decode(String value) throws IOException {
        int comma = value.indexOf(";base64,");
        String base64 = comma >= 0 ? value.substring(comma + 8) : value;
        return ImageIO.read(new ByteArrayInputStream(Base64.getMimeDecoder().decode(base64)));
    }

    /** 简单误差统计：均值/中位数/最大绝对值/命中率。 */
    private static final class Stats {
        private final List<Integer> errors = new java.util.ArrayList<>();
        private final List<String> strategies = new java.util.ArrayList<>();
        private final List<Double> confidences = new java.util.ArrayList<>();
        private int miss;

        void add(int error, String strategy, double confidence) {
            errors.add(error);
            strategies.add(strategy);
            confidences.add(confidence);
        }

        void miss() {
            miss++;
        }

        String report() {
            if (errors.isEmpty()) {
                return "n=0 miss=" + miss;
            }
            List<Integer> sorted = new java.util.ArrayList<>(errors);
            java.util.Collections.sort(sorted);
            double mean = 0;
            double maxAbs = 0;
            java.util.Map<String, Integer> byStrategy = new java.util.LinkedHashMap<>();
            for (int i = 0; i < errors.size(); i++) {
                mean += errors.get(i);
                maxAbs = Math.max(maxAbs, Math.abs(errors.get(i)));
                byStrategy.merge(strategies.get(i), 1, Integer::sum);
            }
            mean /= errors.size();
            int median = sorted.get(sorted.size() / 2);
            int within3 = 0;
            for (int e : errors) {
                if (Math.abs(e) <= 3) {
                    within3++;
                }
            }
            return "n=" + errors.size() + " miss=" + miss
                    + " mean=" + String.format("%.1f", mean)
                    + " median=" + median
                    + " maxAbs=" + (int) maxAbs
                    + " |err|<=3: " + within3 + "/" + errors.size()
                    + " byStrategy=" + byStrategy;
        }
    }
}
