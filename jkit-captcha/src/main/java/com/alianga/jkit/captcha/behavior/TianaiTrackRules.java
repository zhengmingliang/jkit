package com.alianga.jkit.captcha.behavior;

import java.util.List;

/**
 * tianai-captcha 开源版行为轨迹规则的本地移植（对应
 * {@code cloud.tianai.captcha.validator.impl.BasicCaptchaTrackValidator#afterValid}）。
 *
 * <p>用途有二：轨迹合成后先本地自检，避免明显会被服务端拒绝的轨迹发出去；
 * 同时作为通用方案里「对抗层规则库」的参考实现。规则与开源版一一对应：
 *
 * <ol>
 *   <li>滑动时长 ≥ 300ms；</li>
 *   <li>轨迹点数 ∈ [10, 背景宽度×5]；</li>
 *   <li>首点坐标 ∈ [-10, 10]；</li>
 *   <li>y 全程与首点相同判定为机器；</li>
 *   <li>相邻点 x 或 y 跳变 &gt; 50 判定异常；</li>
 *   <li>x 超过背景宽度的次数 &gt; 200 判定异常；</li>
 *   <li>末 30% 轨迹的平均时间间隔必须大于前 70%（先快后慢）。</li>
 * </ol>
 */
public final class TianaiTrackRules {
    /** 行为校验失败原因。 */
    public enum Failure {
        /** 时长不足 300ms。 */
        TOO_FAST,
        /** 轨迹点数不在允许区间。 */
        BAD_TRACK_SIZE,
        /** 首点坐标越界。 */
        BAD_START_POINT,
        /** y 全程不变。 */
        CONSTANT_Y,
        /** 相邻点跳变过大。 */
        JUMP_TOO_FAR,
        /** x 越界次数过多。 */
        TOO_MANY_OVERFLOW,
        /** 速度曲线未先快后慢。 */
        NOT_DECELERATING
    }

    private TianaiTrackRules() {
    }

    /**
     * 校验轨迹。startMs/stopMs 为按下/抬起的墙钟毫秒（与轨迹 t 字段可分别自洽，
     * 服务端用二者差值做规则 1）。
     *
     * @return null 表示通过，否则返回首个失败原因
     */
    public static Failure validate(List<TrackPoint> track, float bgImageWidth, long startMs, long stopMs) {
        if (track == null || track.isEmpty()) {
            return Failure.BAD_TRACK_SIZE;
        }
        // 规则 1
        if (startMs + 300L > stopMs) {
            return Failure.TOO_FAST;
        }
        // 规则 2
        if (track.size() < 10 || track.size() > bgImageWidth * 5) {
            return Failure.BAD_TRACK_SIZE;
        }
        // 规则 3
        TrackPoint first = track.get(0);
        if (first.getX() > 10f || first.getX() < -10f || first.getY() > 10f || first.getY() < -10f) {
            return Failure.BAD_START_POINT;
        }
        int constantY = 0;
        int overflow = 0;
        for (int i = 1; i < track.size(); i++) {
            TrackPoint current = track.get(i);
            TrackPoint previous = track.get(i - 1);
            // 规则 4
            if (first.getY() == current.getY()) {
                constantY++;
            }
            // 规则 6
            if (current.getX() >= bgImageWidth) {
                overflow++;
            }
            // 规则 5
            if (current.getX() - previous.getX() > 50f || current.getY() - previous.getY() > 50f) {
                return Failure.JUMP_TOO_FAR;
            }
        }
        if (constantY == track.size() || overflow > 200) {
            return constantY == track.size() ? Failure.CONSTANT_Y : Failure.TOO_MANY_OVERFLOW;
        }
        // 规则 7
        int splitPos = (int) (track.size() * 0.7);
        if (splitPos < 1 || splitPos >= track.size()) {
            return Failure.NOT_DECELERATING;
        }
        float splitT = track.get(splitPos - 1).getT();
        float startAvg = splitT / splitPos;
        float lastT = track.get(track.size() - 1).getT();
        double endAvg = lastT / (double) (track.size() - splitPos);
        return endAvg > startAvg ? null : Failure.NOT_DECELERATING;
    }
}
