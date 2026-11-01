package com.alianga.jkit.captcha.behavior;

import java.util.List;

/**
 * 厂商无关的解算答案：{@link BehaviorCaptchaProtocol#submit(BehaviorChallenge, BehaviorAnswer)}
 * 的输入，由编排器从解算结果与轨迹合成。
 *
 * <p>坐标系约定：轨迹为相对位移（x/y 相对按下点），bgWidth 用挑战的自然宽；
 * value 的含义按验证码类型——滑块为缺口位移（自然像素），旋转为目标角度。
 */
public final class BehaviorAnswer {
    /** 解算答案值：滑块=缺口位移，旋转=角度。 */
    private final double value;
    /** 命中的定位策略名。 */
    private final String strategy;
    /** 定位置信度 0..1。 */
    private final double confidence;
    /** 人类轨迹（相对位移坐标，t 为相对毫秒）。 */
    private final List<TrackPoint> track;
    /** 按下时刻墙钟毫秒。 */
    private final long startMs;
    /** 抬起时刻墙钟毫秒。 */
    private final long stopMs;

    public BehaviorAnswer(double value, String strategy, double confidence,
                          List<TrackPoint> track, long startMs, long stopMs) {
        this.value = value;
        this.strategy = strategy;
        this.confidence = confidence;
        this.track = track;
        this.startMs = startMs;
        this.stopMs = stopMs;
    }

    public double getValue() {
        return value;
    }

    public String getStrategy() {
        return strategy;
    }

    public double getConfidence() {
        return confidence;
    }

    public List<TrackPoint> getTrack() {
        return track;
    }

    public long getStartMs() {
        return startMs;
    }

    public long getStopMs() {
        return stopMs;
    }
}
