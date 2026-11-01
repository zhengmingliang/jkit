package com.alianga.jkit.captcha.behavior;

import java.awt.image.BufferedImage;
import java.util.List;
import java.util.Random;

/**
 * 滑块验证码解算器：缺口定位 + 人类轨迹合成 + 规则自检。
 *
 * <p>输入为验证码下发的一对图（背景图与模板条），输出缺口在背景图自然坐标系下的
 * x 坐标以及一条可直接随校验请求提交的轨迹。定位策略融合规则：
 * 内容区匹配置信度足够时优先（开源协议栈上最准），否则用平铺灰芯反推，
 * 形状环匹配作为兜底。
 */
public final class SliderCaptchaSolver {
    /** 内容区匹配置信度达到该值时直接采信。 */
    private static final double INTERIOR_CONFIDENT = 0.75;
    /** 形状环匹配置信度达到该值时直接采信（开源模板上该策略最准）。 */
    private static final double RING_CONFIDENT = 0.12;
    /** 平铺灰芯置信度达到该值时优先于剩余策略。 */
    private static final double FLAT_CORE_CONFIDENT = 0.4;

    private final HumanTrajectoryGenerator trajectoryGenerator = new HumanTrajectoryGenerator();

    /** 单策略候选的公开视图，供诊断与基准使用。 */
    public static final class Candidate {
        /** 策略给出的缺口 x（背景图自然坐标）。 */
        public final int x;
        /** 策略置信度 0..1。 */
        public final double confidence;
        /** 策略名：interior / ring / flatCore。 */
        public final String strategy;

        Candidate(int x, double confidence, String strategy) {
            this.x = x;
            this.confidence = confidence;
            this.strategy = strategy;
        }

        @Override
        public String toString() {
            return strategy + "#x=" + x + "#conf=" + String.format("%.3f", confidence);
        }
    }

    /** 解算结果。 */
    public static final class Solution {
        /** 缺口左缘 x（背景图自然坐标）。 */
        private final int gapX;
        /** 命中的定位策略。 */
        private final String strategy;
        /** 策略置信度 0..1。 */
        private final double confidence;
        /** 三策略原始定位结果，便于诊断与基准。 */
        private final SliderGapLocator.Location location;

        Solution(int gapX, String strategy, double confidence, SliderGapLocator.Location location) {
            this.gapX = gapX;
            this.strategy = strategy;
            this.confidence = confidence;
            this.location = location;
        }

        public int getGapX() {
            return gapX;
        }

        public String getStrategy() {
            return strategy;
        }

        public double getConfidence() {
            return confidence;
        }

        /** 内容区匹配候选（可能为 null）。 */
        public Candidate getInteriorCandidate() {
            return view(location.interior);
        }

        /** 形状环匹配候选（可能为 null）。 */
        public Candidate getRingCandidate() {
            return view(location.ring);
        }

        /** 平铺灰芯候选（可能为 null）。 */
        public Candidate getFlatCoreCandidate() {
            return view(location.flatCore);
        }

        private Candidate view(SliderGapLocator.GapCandidate candidate) {
            return candidate == null ? null
                    : new Candidate(candidate.x, candidate.confidence, candidate.strategy);
        }

        @Override
        public String toString() {
            return "Solution{gapX=" + gapX + ", strategy=" + strategy
                    + ", confidence=" + String.format("%.3f", confidence) + "}";
        }
    }

    /** 定位缺口。背景图与模板条都必须是已解码的位图。 */
    public Solution solve(BufferedImage background, BufferedImage templateImage) {
        SliderGapLocator.Location location = SliderGapLocator.locate(
                ArgbImage.from(background), ArgbImage.from(templateImage));
        if (location == null) {
            throw new IllegalStateException("模板条或背景图尺寸异常，无法定位缺口");
        }
        SliderGapLocator.GapCandidate best;
        if (location.ring != null && location.ring.confidence >= RING_CONFIDENT) {
            best = location.ring;
        } else if (location.interior != null && location.interior.confidence >= INTERIOR_CONFIDENT) {
            best = location.interior;
        } else if (location.flatCore != null && location.flatCore.confidence >= FLAT_CORE_CONFIDENT) {
            best = location.flatCore;
        } else {
            best = pickBest(location.interior, location.ring, location.flatCore);
        }
        if (best == null) {
            throw new IllegalStateException("三种定位策略均未命中缺口");
        }
        return new Solution(best.x, best.strategy, best.confidence, location);
    }

    /**
     * 为解算结果合成可通过行为校验的拖拽轨迹。坐标为相对位移；
     * bgImageWidth 传提交时使用的背景宽度（自然宽或显示宽均可，需与轨迹坐标系一致）。
     */
    public List<TrackPoint> buildTrack(Solution solution, float bgImageWidth, Random random) {
        for (int attempt = 0; attempt < 8; attempt++) {
            List<TrackPoint> track = trajectoryGenerator.generate(solution.gapX, random);
            long start = System.currentTimeMillis();
            long stop = start + (long) track.get(track.size() - 1).getT();
            TianaiTrackRules.Failure failure = TianaiTrackRules.validate(track, bgImageWidth, start, stop);
            if (failure == null) {
                return track;
            }
        }
        throw new IllegalStateException("轨迹合成未能通过行为规则自检");
    }

    private SliderGapLocator.GapCandidate pickBest(SliderGapLocator.GapCandidate... candidates) {
        SliderGapLocator.GapCandidate best = null;
        for (SliderGapLocator.GapCandidate candidate : candidates) {
            if (candidate == null) {
                continue;
            }
            if (best == null || candidate.confidence > best.confidence) {
                best = candidate;
            }
        }
        return best;
    }
}
