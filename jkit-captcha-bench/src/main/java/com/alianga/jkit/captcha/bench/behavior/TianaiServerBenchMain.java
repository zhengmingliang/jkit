package com.alianga.jkit.captcha.bench.behavior;

import cloud.tianai.captcha.application.ImageCaptchaApplication;
import cloud.tianai.captcha.application.TACBuilder;
import cloud.tianai.captcha.common.response.ApiResponse;
import cloud.tianai.captcha.resource.common.model.dto.Resource;
import cloud.tianai.captcha.validator.common.model.dto.ImageCaptchaTrack;
import cloud.tianai.captcha.validator.impl.BasicCaptchaTrackValidator;
import com.alianga.jkit.captcha.behavior.BehaviorCaptchaSolver;
import com.alianga.jkit.captcha.behavior.TianaiBehaviorProtocol;
import com.alianga.jkit.captcha.behavior.TianaiCaptchaClient;
import com.alianga.jkit.json.JSON;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * 行为验证码端到端基准：在 JVM 内起一个与开源 tianai-captcha demo 同契约的 HTTP 服务
 * （/gen + /check，行为轨迹校验器开启），再用 {@link TianaiCaptchaClient} 走完整流程：
 * 取验证码 → 定位缺口 → 合成轨迹 → 提交校验，统计成功率。
 *
 * <p>这是通用方案的闭环验证：不依赖外网、带真值、服务端规则与线上同源。
 */
public final class TianaiServerBenchMain {
    private static final int DEFAULT_COUNT = 20;

    private TianaiServerBenchMain() {
    }

    public static void main(String[] args) throws IOException {
        int count = args.length > 0 ? Integer.parseInt(args[0]) : DEFAULT_COUNT;
        RawMiniServer server = startServer();
        String base = "http://127.0.0.1:" + server.port();

        // 走厂商无关编排器：协议适配 tianai 开源版，解算/轨迹层通用
        BehaviorCaptchaSolver solver = new BehaviorCaptchaSolver(
                new TianaiBehaviorProtocol(new TianaiCaptchaClient(), base + "/gen", base + "/check"),
                new Random(20261101L));
        int pass = 0;
        int fail = 0;
        long begin = System.currentTimeMillis();

        for (int i = 0; i < count; i++) {
            try {
                BehaviorCaptchaSolver.Attempt attempt = solver.solve(2);
                if (attempt.isSuccess()) {
                    pass++;
                    System.out.println("[" + i + "] PASS " + attempt.getLog().get(attempt.getLog().size() - 1));
                } else {
                    fail++;
                    System.out.println("[" + i + "] FAIL " + attempt.getLog());
                }
            } catch (IOException | IllegalStateException e) {
                fail++;
                System.out.println("[" + i + "] ERROR " + e.getMessage());
            }
            try {
                Thread.sleep(60);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        long elapsed = System.currentTimeMillis() - begin;
        server.stop();
        System.out.println();
        System.out.println("== 端到端结果 ==");
        System.out.println("pass=" + pass + " fail=" + fail + " / " + count
                + " successRate=" + String.format("%.1f%%", pass * 100.0 / count)
                + " avg=" + elapsed / count + "ms");
    }

    /** 起一个开源 demo 同契约的最小服务端：/gen 与 /check。 */
    private static RawMiniServer startServer() throws IOException {
        ImageCaptchaApplication application = TACBuilder.builder()
                .addDefaultTemplate()
                .addResource("SLIDER", new Resource("classpath", "META-INF/cut-image/resource/1.jpg"))
                .setValidator(new BasicCaptchaTrackValidator())
                .build();
        return new RawMiniServer((path, body) -> {
            if (path.startsWith("/check")) {
                return handleCheck(application, new String(body, StandardCharsets.UTF_8));
            }
            ApiResponse<?> response = application.generateCaptcha();
            return JSON.toJsonString(response).getBytes(StandardCharsets.UTF_8);
        });
    }

    /** 处理 /check：解析开源 SDK 的校验请求体并走应用层 matching。 */
    private static byte[] handleCheck(ImageCaptchaApplication application, String requestBody) {
        Map<?, ?> root = (Map<?, ?>) JSON.parseObject(requestBody);
        String id = String.valueOf(root.get("id"));
        Map<?, ?> data = (Map<?, ?>) root.get("data");
        ImageCaptchaTrack track = new ImageCaptchaTrack();
        track.setBgImageWidth(((Number) data.get("bgImageWidth")).intValue());
        track.setBgImageHeight(((Number) data.get("bgImageHeight")).intValue());
        track.setTemplateImageWidth(((Number) data.get("templateImageWidth")).intValue());
        track.setTemplateImageHeight(((Number) data.get("templateImageHeight")).intValue());
        track.setStartTime(((Number) data.get("startTime")).longValue());
        track.setStopTime(((Number) data.get("stopTime")).longValue());
        List<?> rawTracks = (List<?>) data.get("trackList");
        List<ImageCaptchaTrack.Track> tracks = new ArrayList<>(rawTracks.size());
        for (Object item : rawTracks) {
            Map<?, ?> point = (Map<?, ?>) item;
            tracks.add(new ImageCaptchaTrack.Track(
                    ((Number) point.get("x")).floatValue(),
                    ((Number) point.get("y")).floatValue(),
                    ((Number) point.get("t")).floatValue(),
                    String.valueOf(point.get("type"))));
        }
        track.setTrackList(tracks);
        ApiResponse<?> response = application.matching(id, track);
        return JSON.toJsonString(response).getBytes(StandardCharsets.UTF_8);
    }
}
