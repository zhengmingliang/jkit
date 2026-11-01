package com.alianga.jkit.captcha.behavior;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * 行为验证码编排器（厂商无关）：串起「取验证码 → 缺口定位 → 轨迹合成（规则包自检）→
 * 提交 → 失败码驱动重试」的完整流水线。解算与轨迹层对任意厂商复用，
 * 厂商差异全部收敛在 {@link BehaviorCaptchaProtocol} 与 {@link TrackRulePack} 两个适配点。
 *
 * <p>重试策略（对抗层原则）：只有 {@code EXPIRED} 与 {@code POSITION_MISMATCH}
 * 值得换图重试；{@code TRACK_REJECTED}、{@code BAD_PARAM}、{@code UNKNOWN}
 * 立即失败返回——重发同样性质的请求只会对目标服务形成无意义压力。
 *
 * <p>当前编排器内置滑块解算；旋转/点选等类型需要厂商专属解算器，
 * 挑战带不出模板条时会直接抛出并明示。
 */
public final class BehaviorCaptchaSolver {
    /** 默认最大尝试次数。 */
    public static final int DEFAULT_MAX_ATTEMPTS = 3;

    private final BehaviorCaptchaProtocol protocol;
    private final SliderCaptchaSolver sliderSolver;
    private final TrackRulePack rulePack;
    private final Random random;

    public BehaviorCaptchaSolver(BehaviorCaptchaProtocol protocol) {
        this(protocol, new SliderCaptchaSolver(), TianaiTrackRulePack.INSTANCE, new Random());
    }

    public BehaviorCaptchaSolver(BehaviorCaptchaProtocol protocol, Random random) {
        this(protocol, new SliderCaptchaSolver(), TianaiTrackRulePack.INSTANCE, random);
    }

    public BehaviorCaptchaSolver(BehaviorCaptchaProtocol protocol, SliderCaptchaSolver sliderSolver,
                                 TrackRulePack rulePack, Random random) {
        this.protocol = protocol;
        this.sliderSolver = sliderSolver;
        this.rulePack = rulePack;
        this.random = random;
    }

    /** 按 {@link #DEFAULT_MAX_ATTEMPTS} 次上限解一次验证码。 */
    public Attempt solve() throws IOException {
        return solve(DEFAULT_MAX_ATTEMPTS);
    }

    /**
     * 完整解一次验证码，最多 maxAttempts 轮（每轮换新验证码）。
     *
     * @return 最终结果与每轮日志（含定位策略、置信度、厂商原始码），永不返回 null
     */
    public Attempt solve(int maxAttempts) throws IOException {
        List<String> log = new ArrayList<>();
        for (int i = 1; i <= maxAttempts; i++) {
            BehaviorChallenge challenge = protocol.fetch();
            SliderCaptchaSolver.Solution solution = solveChallenge(challenge);
            List<TrackPoint> track = sliderSolver.buildTrack(solution, challenge.getBackgroundWidth(), random, rulePack);
            long startMs = System.currentTimeMillis();
            long stopMs = startMs + (long) track.get(track.size() - 1).getT();
            BehaviorAnswer answer = new BehaviorAnswer(solution.getGapX(), solution.getStrategy(),
                    solution.getConfidence(), track, startMs, stopMs);
            SubmitResult result = protocol.submit(challenge, answer);
            log.add("#" + i + " " + challenge.getVendor() + "/" + challenge.getType()
                    + " sessionId=" + abbreviate(challenge.getSessionId())
                    + " " + solution + " -> " + result);
            if (result.getKind() == SubmitResult.FailureKind.SUCCESS) {
                return new Attempt(true, result, log);
            }
            if (result.getKind() != SubmitResult.FailureKind.EXPIRED
                    && result.getKind() != SubmitResult.FailureKind.POSITION_MISMATCH) {
                // 轨迹被拒 / 参数错 / 未分类失败：重试同样性质的请求没有意义，立即返回
                return new Attempt(false, result, log);
            }
        }
        return new Attempt(false, new SubmitResult(SubmitResult.FailureKind.UNKNOWN, -1, "attempts exhausted"), log);
    }

    /** 单轮：定位缺口（当前仅滑块类型）。供子类/测试扩展其他类型时复用协议与轨迹装配。 */
    public SliderCaptchaSolver.Solution solveChallenge(BehaviorChallenge challenge) {
        if (challenge.getTemplateImage() == null) {
            throw new UnsupportedOperationException(
                    "验证码类型 " + challenge.getType() + " 没有模板条，需要厂商专属解算器");
        }
        return sliderSolver.solve(challenge.getBackgroundImage(), challenge.getTemplateImage());
    }

    private static String abbreviate(String text) {
        if (text == null) {
            return "null";
        }
        return text.length() <= 12 ? text : text.substring(0, 12) + "...";
    }

    /** 一次完整解算的最终结果与逐轮日志。 */
    public static final class Attempt {
        private final boolean success;
        private final SubmitResult result;
        private final List<String> log;

        Attempt(boolean success, SubmitResult result, List<String> log) {
            this.success = success;
            this.result = result;
            this.log = log;
        }

        public boolean isSuccess() {
            return success;
        }

        public SubmitResult getResult() {
            return result;
        }

        public List<String> getLog() {
            return log;
        }

        @Override
        public String toString() {
            return "Attempt{success=" + success + ", result=" + result + ", trials=" + log.size() + "}";
        }
    }
}
