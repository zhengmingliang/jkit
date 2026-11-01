package com.alianga.jkit.captcha.bench.behavior;

import cloud.tianai.captcha.application.ImageCaptchaApplication;
import cloud.tianai.captcha.application.TACBuilder;
import cloud.tianai.captcha.common.response.ApiResponse;
import cloud.tianai.captcha.validator.common.model.dto.ImageCaptchaTrack;
import com.alianga.jkit.captcha.behavior.SliderCaptchaSolver;
import com.alianga.jkit.captcha.behavior.TianaiCaptchaClient;
import com.alianga.jkit.captcha.behavior.TrackPoint;
import com.alianga.jkit.json.JSON;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
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
        HttpServer server = startServer();
        int port = server.getAddress().getPort();
        String base = "http://127.0.0.1:" + port;

        TianaiCaptchaClient client = new TianaiCaptchaClient();
        SliderCaptchaSolver solver = new SliderCaptchaSolver();
        Random random = new Random(20261101L);
        int pass = 0;
        int fail = 0;
        long begin = System.currentTimeMillis();

        for (int i = 0; i < count; i++) {
            long startMs = System.currentTimeMillis();
            try {
                TianaiCaptchaClient.TianaiChallenge challenge = client.generate(base + "/gen");
                SliderCaptchaSolver.Solution solution = solver.solve(challenge.background, challenge.template);
                List<TrackPoint> track = solver.buildTrack(solution, challenge.backgroundImageWidth, random);
                long stopMs = System.currentTimeMillis();
                // 轨迹的 t 是相对毫秒，把 startTime/stopTime 与轨迹时长对齐
                long trackSpan = (long) track.get(track.size() - 1).getT();
                int code = client.check(base + "/check", challenge.id,
                        challenge.backgroundImageWidth, challenge.backgroundImageHeight,
                        challenge.templateImageWidth, challenge.templateImageHeight,
                        track, startMs, startMs + trackSpan);
                if (code == TianaiCaptchaClient.CODE_SUCCESS) {
                    pass++;
                    System.out.println("[" + i + "] PASS " + solution);
                } else {
                    fail++;
                    System.out.println("[" + i + "] FAIL code=" + code + " " + solution);
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
        server.stop(0);
        System.out.println();
        System.out.println("== 端到端结果 ==");
        System.out.println("pass=" + pass + " fail=" + fail + " / " + count
                + " successRate=" + String.format("%.1f%%", pass * 100.0 / count)
                + " avg=" + elapsed / count + "ms");
    }

    /** 起一个开源 demo 同契约的最小服务端：/gen 与 /check。 */
    private static HttpServer startServer() throws IOException {
        ImageCaptchaApplication application = TACBuilder.builder()
                .addDefaultTemplate()
                .addResource("SLIDER", new cloud.tianai.captcha.resource.common.model.dto.Resource(
                        "classpath", "META-INF/cut-image/resource/1.jpg"))
                .setValidator(new cloud.tianai.captcha.validator.impl.BasicCaptchaTrackValidator())
                .build();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/gen", exchange -> {
            ApiResponse<?> response = application.generateCaptcha();
            writeJson(exchange, JSON.toJsonString(response));
        });
        server.createContext("/check", exchange -> {
            String body = new String(readAll(exchange.getRequestBody()), StandardCharsets.UTF_8);
            Map<?, ?> root = (Map<?, ?>) JSON.parseObject(body);
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
            writeJson(exchange, JSON.toJsonString(response));
        });
        server.start();
        return server;
    }

    private static void writeJson(HttpExchange exchange, String json) throws IOException {
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json;charset=UTF-8");
        exchange.sendResponseHeaders(200, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }

    private static byte[] readAll(java.io.InputStream input) throws IOException {
        if (input == null) {
            return new byte[0];
        }
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int read = input.read(buffer);
        while (read >= 0) {
            if (read > 0) {
                out.write(buffer, 0, read);
            }
            read = input.read(buffer);
        }
        return out.toByteArray();
    }
}
