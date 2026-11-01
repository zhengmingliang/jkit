package com.alianga.jkit.captcha.behavior;

import java.util.List;

/**
 * {@link TrackRulePack} 的 tianai-captcha 开源版实现：规则集来自
 * {@link TianaiTrackRules}（对应开源版 BasicCaptchaTrackValidator 的 7 条行为规则）。
 */
public final class TianaiTrackRulePack implements TrackRulePack {
    /** 单例。 */
    public static final TianaiTrackRulePack INSTANCE = new TianaiTrackRulePack();

    private TianaiTrackRulePack() {
    }

    @Override
    public String validate(List<TrackPoint> track, int backgroundWidth, long startMs, long stopMs) {
        TianaiTrackRules.Failure failure = TianaiTrackRules.validate(track, backgroundWidth, startMs, stopMs);
        return failure == null ? null : failure.name();
    }
}
