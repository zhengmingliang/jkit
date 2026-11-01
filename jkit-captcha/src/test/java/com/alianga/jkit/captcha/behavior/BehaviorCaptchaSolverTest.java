package com.alianga.jkit.captcha.behavior;

import org.junit.Test;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.util.Collections;
import java.util.Random;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * 编排器与厂商 SPI 的闭环测试。{@link FakeVendorProtocol} 同时是新厂商接入的样例：
 * 实现 {@link BehaviorCaptchaProtocol#fetch()} 装挑战、{@code submit()} 翻译答案并映射
 * 失败分类，解算与轨迹层零改动复用。
 */
public class BehaviorCaptchaSolverTest {

    private static final int BG_W = 600;
    private static final int BG_H = 360;
    private static final int TEMPLATE_W = 110;
    private static final int TEMPLATE_H = 110;
    private static final int CONTENT_OFFSET = 11;

    @Test
    public void orchestratorShouldSolveThroughVendorProtocol() throws IOException {
        FakeVendorProtocol protocol = new FakeVendorProtocol(false);
        BehaviorCaptchaSolver solver = new BehaviorCaptchaSolver(protocol, new Random(11L));
        BehaviorCaptchaSolver.Attempt attempt = solver.solve(3);
        assertTrue("答案在容差内应一次通过: " + attempt.getLog(), attempt.isSuccess());
        assertEquals(1, attempt.getLog().size());
    }

    @Test
    public void orchestratorShouldRetryOnExpiredThenSucceed() throws IOException {
        FakeVendorProtocol protocol = new FakeVendorProtocol(true);
        BehaviorCaptchaSolver solver = new BehaviorCaptchaSolver(protocol, new Random(12L));
        BehaviorCaptchaSolver.Attempt attempt = solver.solve(3);
        assertTrue("过期应换图重试后通过: " + attempt.getLog(), attempt.isSuccess());
        assertEquals("应恰好两轮：过期一轮 + 通过一轮", 2, attempt.getLog().size());
    }

    @Test
    public void orchestratorShouldFailFastOnTrackRejected() throws IOException {
        FakeVendorProtocol protocol = new FakeVendorProtocol(false) {
            @Override
            public SubmitResult submit(BehaviorChallenge challenge, BehaviorAnswer answer) {
                return new SubmitResult(SubmitResult.FailureKind.TRACK_REJECTED, 50001, "behavior");
            }
        };
        BehaviorCaptchaSolver solver = new BehaviorCaptchaSolver(protocol, new Random(13L));
        BehaviorCaptchaSolver.Attempt attempt = solver.solve(3);
        assertFalse("轨迹被拒应立即停止，不继续对目标施压", attempt.isSuccess());
        assertEquals("应只尝试一轮", 1, attempt.getLog().size());
    }

    @Test
    public void orchestratorShouldRetryOnPositionMismatchWithinLimit() throws IOException {
        // 故意把协议判定容差设成不可能命中的负值 → 每轮都 POSITION_MISMATCH，直到耗尽次数
        FakeVendorProtocol protocol = new FakeVendorProtocol(false) {
            @Override
            public SubmitResult submit(BehaviorChallenge challenge, BehaviorAnswer answer) {
                return new SubmitResult(SubmitResult.FailureKind.POSITION_MISMATCH, 4001, "basic");
            }
        };
        BehaviorCaptchaSolver solver = new BehaviorCaptchaSolver(protocol, new Random(14L));
        BehaviorCaptchaSolver.Attempt attempt = solver.solve(3);
        assertFalse(attempt.isSuccess());
        assertEquals("位置不对应换图重试直到耗尽", 3, attempt.getLog().size());
    }

    /** 假厂商：内存生成挑战，按与真值距离判定答案，可选地把第一次判定为过期。 */
    private static class FakeVendorProtocol implements BehaviorCaptchaProtocol {
        private final boolean expireFirst;
        private int fetchCount;

        FakeVendorProtocol(boolean expireFirst) {
            this.expireFirst = expireFirst;
        }

        @Override
        public String vendor() {
            return "fake";
        }

        @Override
        public BehaviorChallenge fetch() {
            fetchCount++;
            int truth = 120 + (fetchCount * 37) % 360;
            BufferedImage[] pair = buildSliderPair(truth, 60 + (fetchCount * 23) % 120);
            return new BehaviorChallenge(vendor(), "SLIDER", "FAKE-" + fetchCount,
                    pair[0], pair[1], BG_W, BG_H, Collections.singletonMap("truth", truth));
        }

        @Override
        public SubmitResult submit(BehaviorChallenge challenge, BehaviorAnswer answer) {
            if (expireFirst && fetchCount == 1) {
                return new SubmitResult(SubmitResult.FailureKind.EXPIRED, 4000, "expired");
            }
            int truth = truthOf(challenge);
            boolean hit = Math.abs(answer.getValue() - truth) <= 2;
            return new SubmitResult(hit ? SubmitResult.FailureKind.SUCCESS
                    : SubmitResult.FailureKind.POSITION_MISMATCH, hit ? 200 : 4001, null);
        }

        private int truthOf(BehaviorChallenge challenge) {
            Integer truth = (Integer) challenge.getExtra().get("truth");
            return truth == null ? 0 : truth;
        }
    }

    /** 与 SliderCaptchaSolverTest 相同几何的合成滑块：槽在 (gapX + 内容偏移)，模板内容取槽区域并同洗白。 */
    private static BufferedImage[] buildSliderPair(int gapX, int gapY) {
        BufferedImage bg = new BufferedImage(BG_W, BG_H, BufferedImage.TYPE_INT_RGB);
        Random texture = new Random(gapX * 31L + gapY);
        for (int y = 0; y < BG_H; y++) {
            for (int x = 0; x < BG_W; x++) {
                int base = 120 + ((x / 7 + y / 5) % 3) * 30 + texture.nextInt(24);
                bg.setRGB(x, y, (clamp(base) << 16) | (clamp(base / 2) << 8) | clamp(base));
            }
        }
        boolean[][] mask = roundedSquareMask();
        BufferedImage background = new BufferedImage(BG_W, BG_H, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < BG_H; y++) {
            for (int x = 0; x < BG_W; x++) {
                int packed = bg.getRGB(x, y);
                int mx = x - gapX - CONTENT_OFFSET;
                int my = y - gapY;
                if (mx >= 0 && mx < TEMPLATE_W && my >= 0 && my < TEMPLATE_H && mask[my][mx]) {
                    packed = wash(packed);
                }
                background.setRGB(x, y, packed);
            }
        }
        BufferedImage template = new BufferedImage(TEMPLATE_W, BG_H, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < TEMPLATE_H; y++) {
            for (int x = 0; x < TEMPLATE_W; x++) {
                if (mask[y][x]) {
                    template.setRGB(CONTENT_OFFSET + x, gapY + y, 0xff000000 | wash(bg.getRGB(gapX + CONTENT_OFFSET + x, gapY + y)));
                }
            }
        }
        return new BufferedImage[]{background, template};
    }

    private static boolean[][] roundedSquareMask() {
        boolean[][] mask = new boolean[TEMPLATE_H][TEMPLATE_W];
        int size = 66;
        int corner = 14;
        for (int y = 0; y < size; y++) {
            for (int x = 0; x < size; x++) {
                int dx = Math.min(x, size - 1 - x);
                int dy = Math.min(y, size - 1 - y);
                boolean inside = !(dx < corner && dy < corner
                        && (corner - dx) * (corner - dx) + (corner - dy) * (corner - dy) > corner * corner);
                mask[y][x] = inside;
            }
        }
        return mask;
    }

    private static int wash(int packed) {
        int red = (packed >> 16) & 0xff;
        int green = (packed >> 8) & 0xff;
        int blue = packed & 0xff;
        red = (int) (red * 0.6 + 255 * 0.4);
        green = (int) (green * 0.6 + 255 * 0.4);
        blue = (int) (blue * 0.6 + 255 * 0.4);
        return (red << 16) | (green << 8) | blue;
    }

    private static int clamp(int value) {
        return Math.max(0, Math.min(255, value));
    }
}
