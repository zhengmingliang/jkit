package com.alianga.jkit.captcha.behavior;

import org.junit.Before;
import org.junit.Test;

import java.awt.image.BufferedImage;
import java.util.List;
import java.util.Random;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * 滑块缺口定位与轨迹合成的合成场景回归：不依赖 tianai-captcha，
 * 用确定性合成图验证三种定位策略与行为规则自检的正确性。
 */
public class SliderCaptchaSolverTest {

    private static final int BG_W = 600;
    private static final int BG_H = 360;
    private static final int TEMPLATE_W = 110;
    private static final int TEMPLATE_H = 110;
    /** 模板条内容的透明边距（模拟 active 模板 11px 边距）。 */
    private static final int CONTENT_OFFSET = 11;

    private SliderCaptchaSolver solver;

    @Before
    public void setUp() {
        solver = new SliderCaptchaSolver();
    }

    @Test
    public void ringStrategyShouldHitSyntheticGapPrecisely() {
        boolean[][] mask = roundedSquareMask();
        for (int trial = 0; trial < 5; trial++) {
            int gapX = 130 + trial * 80;
            int gapY = 60 + trial * 30;
            BufferedImage[] pair = buildSliderPair(texturedBackground(1000L + trial), gapX, gapY, mask);
            SliderCaptchaSolver.Solution solution = solver.solve(pair[0], pair[1]);
            assertEquals("trial " + trial + " 缺口 x 误差应 ≤ 2px", gapX, solution.getGapX(), 2d);
        }
    }

    @Test
    public void flatCoreStrategyShouldHitFilledGap() {
        // 模拟增强版渲染：槽 = 白环 + 平坦灰芯，模板内容与背景无关（只靠形状与灰芯定位）
        boolean[][] mask = roundedSquareMask();
        BufferedImage bg = texturedBackground(7L);
        int gapX = 260;
        int gapY = 180;
        int ring = 12;
        for (int y = 0; y < TEMPLATE_H; y++) {
            for (int x = 0; x < TEMPLATE_W; x++) {
                if (!mask[y][x]) {
                    continue;
                }
                // 内容画在内容偏移处（与模板条同几何）
                boolean core = erodedBy(mask, y, x, ring);
                int gray = core ? 130 : 255;
                bg.setRGB(gapX + CONTENT_OFFSET + x, gapY + y, (gray << 16) | (gray << 8) | gray);
            }
        }
        BufferedImage template = new BufferedImage(TEMPLATE_W, BG_H, BufferedImage.TYPE_INT_ARGB);
        Random content = new Random(9L);
        for (int y = 0; y < TEMPLATE_H; y++) {
            for (int x = 0; x < TEMPLATE_W; x++) {
                if (mask[y][x]) {
                    int gray = 100 + content.nextInt(80);
                    template.setRGB(CONTENT_OFFSET + x, gapY + y, (0xff << 24) | (gray << 16) | (gray << 8) | gray);
                }
            }
        }
        SliderGapLocator.Location location = SliderGapLocator.locate(
                ArgbImage.from(bg), ArgbImage.from(template));
        assertNotNull("flatCore 候选应存在", location.flatCore);
        assertEquals("灰芯反推应命中缺口", gapX, location.flatCore.x, 6d);
    }

    @Test
    public void generatedTrackShouldPassAllBehaviorRules() {
        for (int distance : new int[]{60, 150, 240, 320, 242}) {
            for (long seed : new long[]{1L, 7L, 42L}) {
                SliderCaptchaSolver.Solution solution =
                        new SliderCaptchaSolver.Solution(distance, "ring", 0.9, null);
                List<TrackPoint> track = solver.buildTrack(solution, BG_W, new Random(seed));
                assertTrue("轨迹点数应 ≥ 10: " + track.size(), track.size() >= 10);
                assertEquals("终点应到达目标", distance, track.get(track.size() - 1).getX(), 1.5d);
                assertEquals("首事件应为 down", TrackPoint.TYPE_DOWN, track.get(0).getType());
                assertEquals("末事件应为 up", TrackPoint.TYPE_UP, track.get(track.size() - 1).getType());
                long span = (long) track.get(track.size() - 1).getT();
                assertNull("distance=" + distance + " seed=" + seed,
                        TianaiTrackRules.validate(track, BG_W, 0L, span));
                assertTrue("总时长应 ≥ 300ms", span >= 300L);
            }
        }
    }

    @Test
    public void trackDurationShouldBeCompressible() {
        HumanTrajectoryGenerator generator = new HumanTrajectoryGenerator();
        List<TrackPoint> track = generator.generate(240f, new Random(3L), 600L);
        long span = (long) track.get(track.size() - 1).getT();
        assertTrue("压缩后总时长应 ≤ 600ms: " + span, span <= 600L);
        assertNull("压缩后仍应满足行为规则",
                TianaiTrackRules.validate(track, BG_W, 0L, span));
    }

    /** mask 按半径腐蚀：上下左右 radius 邻域内全为 true 才算核心区。 */
    private boolean erodedBy(boolean[][] mask, int y, int x, int radius) {
        for (int dy = -radius; dy <= radius; dy++) {
            for (int dx = -radius; dx <= radius; dx++) {
                int ny = y + dy;
                int nx = x + dx;
                if (ny < 0 || nx < 0 || ny >= TEMPLATE_H || nx >= TEMPLATE_W || !mask[ny][nx]) {
                    return false;
                }
            }
        }
        return true;
    }

    /** 生成一张带纹理的确定性背景图：横向条纹 + 伪随机噪点。 */
    private BufferedImage texturedBackground(long seed) {
        BufferedImage image = new BufferedImage(BG_W, BG_H, BufferedImage.TYPE_INT_RGB);
        Random texture = new Random(seed);
        for (int y = 0; y < BG_H; y++) {
            for (int x = 0; x < BG_W; x++) {
                int base = 120 + ((x / 7 + y / 5) % 3) * 30;
                int noise = texture.nextInt(24);
                int red = clamp(base + noise);
                int green = clamp(base + noise / 2);
                int blue = clamp(base);
                image.setRGB(x, y, (red << 16) | (green << 8) | blue);
            }
        }
        return image;
    }

    /** 与开源版一致的 40% 白色半透明叠加。 */
    private static int wash(int packed) {
        int red = (packed >> 16) & 0xff;
        int green = (packed >> 8) & 0xff;
        int blue = packed & 0xff;
        red = (int) (red * 0.6 + 255 * 0.4);
        green = (int) (green * 0.6 + 255 * 0.4);
        blue = (int) (blue * 0.6 + 255 * 0.4);
        return (red << 16) | (green << 8) | blue;
    }

    /**
     * 模拟开源版滑块：槽按 (gapX + 内容偏移) 处 40% 白叠加；模板条内容取自槽同区域并做
     * 相同洗白（与真实生成器两侧等洗白一致），画在 CONTENT_OFFSET 偏移处。
     */
    private BufferedImage[] buildSliderPair(BufferedImage bg, int gapX, int gapY, boolean[][] mask) {
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
                    int packed = bg.getRGB(gapX + CONTENT_OFFSET + x, gapY + y);
                    template.setRGB(CONTENT_OFFSET + x, gapY + y, 0xff000000 | wash(packed));
                }
            }
        }
        return new BufferedImage[]{background, template};
    }

    /** 圆角方形拼图 mask（模拟典型滑块形状，含边缘弧度）。 */
    private boolean[][] roundedSquareMask() {
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

    private static int clamp(int value) {
        return Math.max(0, Math.min(255, value));
    }
}
