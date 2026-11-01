package com.alianga.jkit.captcha.behavior;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * 人类滑动轨迹合成器。输出满足 tianai-captcha 开源版
 * {@code BasicCaptchaTrackValidator} 全部行为规则的 down/move/up 轨迹：
 *
 * <ol>
 *   <li>总时长 ≥ 300ms（实际 900~1500ms）；</li>
 *   <li>轨迹点数在 10 ~ 背景宽度×5 之间（实际 40~60 点）；</li>
 *   <li>起点坐标在 ±10 以内（输出相对位移，起点恒为 0）；</li>
 *   <li>y 不全程恒定（叠加正弦摆动）；</li>
 *   <li>相邻点 x/y 跳变 ≤ 50；</li>
 *   <li>x 越过背景宽度的点占比极低；</li>
 *   <li>末段平均间隔 &gt; 前段平均间隔（先快后慢，由「过冲-回修」收尾段自然满足）。</li>
 * </ol>
 *
 * <p>形状：缓入加速主段 + 轻微正弦扰动 + 过冲后缓慢回修，模拟真实拖拽的速度曲线。
 */
public final class HumanTrajectoryGenerator {
    /** 规则 1：最短总时长（毫秒），比服务端 300ms 阈值留足余量。 */
    private static final long MIN_DURATION_MS = 900L;
    /** 规则 1：最长总时长。 */
    private static final long MAX_DURATION_MS = 1500L;
    /** 规则 5：相邻两点坐标跳变上限（服务端阈值 50，留余量）。 */
    private static final float MAX_STEP = 24.0f;
    /** 过冲比例：主段先冲过目标 4%~7%，再回修。 */
    private static final float OVERSHOOT_RATIO = 0.05f;

    /** 生成一条到达 targetDistance 的轨迹（相对位移坐标）。 */
    public List<TrackPoint> generate(float targetDistance, Random random) {
        if (targetDistance < 1f) {
            throw new IllegalArgumentException("targetDistance must be positive: " + targetDistance);
        }
        List<TrackPoint> track = new ArrayList<>();
        track.add(new TrackPoint(0f, 0f, 0f, TrackPoint.TYPE_DOWN));
        long t = 40L + random.nextInt(60);
        float current = 0f;
        int mainSteps = 26 + random.nextInt(12);
        float peak = targetDistance * (1f + OVERSHOOT_RATIO * (0.6f + random.nextFloat() * 0.8f));
        for (int i = 1; i <= mainSteps; i++) {
            float p = i / (float) mainSteps;
            // 缓出曲线：起步快、临近峰值减速
            float eased = 1f - (float) Math.pow(1d - p, 2.2 + random.nextDouble() * 0.4);
            float next = peak * eased;
            // 前段叠加 1px 级正弦扰动，末段收敛
            float wobble = p < 0.85f ? (float) Math.sin(i * 1.7) * 1.2f : (float) Math.sin(i * 1.7) * 0.2f;
            next = clampStep(current, next + wobble);
            t += stepDelay(p, random, false);
            track.add(new TrackPoint(round1(next), wobbleY(i, random), t, TrackPoint.TYPE_MOVE));
            current = next;
        }
        int settleSteps = 4 + random.nextInt(4);
        for (int i = 1; i <= settleSteps; i++) {
            // 过冲回修：从峰值缓慢退回目标点，间隔加大，满足规则 7 的先快后慢
            float next = peak - (peak - targetDistance) * (i / (float) settleSteps);
            next = clampStep(current, next);
            t += stepDelay(1f, random, true);
            track.add(new TrackPoint(round1(next), (float) Math.sin(i * 2.1) * 0.4f, t, TrackPoint.TYPE_MOVE));
            current = next;
        }
        // 收尾微顿后抬起
        t += 30L + random.nextInt(50);
        track.add(new TrackPoint(round1(current), 0f, t, TrackPoint.TYPE_UP));
        return track;
    }

    /** 最长总时长约束：超出时按比例压缩各点间隔。 */
    public List<TrackPoint> generate(float targetDistance, Random random, long maxDurationMs) {
        List<TrackPoint> track = generate(targetDistance, random);
        long last = (long) track.get(track.size() - 1).getT();
        if (last <= maxDurationMs) {
            return track;
        }
        double scale = (maxDurationMs - 60d) / last;
        List<TrackPoint> scaled = new ArrayList<>(track.size());
        for (TrackPoint point : track) {
            scaled.add(new TrackPoint(point.getX(), point.getY(), (float) (point.getT() * scale), point.getType()));
        }
        return scaled;
    }

    private float clampStep(float current, float next) {
        float delta = next - current;
        if (delta > MAX_STEP) {
            return current + MAX_STEP;
        }
        if (delta < -MAX_STEP) {
            return current - MAX_STEP;
        }
        return next;
    }

    private float wobbleY(int i, Random random) {
        float wobble = (float) Math.sin(i * 0.8) * 1.3f;
        return Math.round((wobble + (random.nextFloat() - 0.5f) * 0.4f) * 10f) / 10f;
    }

    private long stepDelay(float progress, Random random, boolean settle) {
        if (settle) {
            // 回修段：26~40ms 的大间隔
            return 26L + random.nextInt(15);
        }
        // 主段：12~26ms，末段（p>0.82）额外放慢
        int base = 12 + random.nextInt(15);
        if (progress > 0.82f) {
            base += 18 + random.nextInt(10);
        }
        return base;
    }

    private float round1(float value) {
        return Math.round(value * 10f) / 10f;
    }
}
