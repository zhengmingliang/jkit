package com.alianga.jkit.captcha.behavior;

import java.io.IOException;

/**
 * {@link BehaviorCaptchaProtocol} 的 tianai-captcha 开源版实现，
 * 底层复用 {@link TianaiCaptchaClient}（/gen + /check 明文协议）。
 *
 * <p>坐标系：轨迹与答案使用挑战的自然宽（backgroundWidth），客户端按「终点位移 /
 * 背景宽」的百分比校验，坐标系自洽即可。
 */
public final class TianaiBehaviorProtocol implements BehaviorCaptchaProtocol {
    private final TianaiCaptchaClient client;
    private final String genUrl;
    private final String checkUrl;

    public TianaiBehaviorProtocol(TianaiCaptchaClient client, String genUrl, String checkUrl) {
        this.client = client;
        this.genUrl = genUrl;
        this.checkUrl = checkUrl;
    }

    @Override
    public String vendor() {
        return "tianai";
    }

    @Override
    public BehaviorChallenge fetch() throws IOException {
        TianaiCaptchaClient.TianaiChallenge challenge = client.generate(genUrl);
        return new BehaviorChallenge(vendor(), challenge.type, challenge.id,
                challenge.background, challenge.template,
                challenge.backgroundImageWidth, challenge.backgroundImageHeight, null);
    }

    @Override
    public SubmitResult submit(BehaviorChallenge challenge, BehaviorAnswer answer) throws IOException {
        int code = client.check(checkUrl, challenge.getSessionId(),
                challenge.getBackgroundWidth(), challenge.getBackgroundHeight(),
                challenge.getTemplateImage() == null ? 0 : challenge.getTemplateImage().getWidth(),
                challenge.getTemplateImage() == null ? 0 : challenge.getTemplateImage().getHeight(),
                answer.getTrack(), answer.getStartMs(), answer.getStopMs());
        return new SubmitResult(mapCode(code), code, null);
    }

    private static SubmitResult.FailureKind mapCode(int code) {
        switch (code) {
            case TianaiCaptchaClient.CODE_SUCCESS:
                return SubmitResult.FailureKind.SUCCESS;
            case TianaiCaptchaClient.CODE_BASIC_CHECK_FAIL:
                return SubmitResult.FailureKind.POSITION_MISMATCH;
            case 50001:
                return SubmitResult.FailureKind.TRACK_REJECTED;
            case TianaiCaptchaClient.CODE_EXPIRED:
                return SubmitResult.FailureKind.EXPIRED;
            case 403:
                return SubmitResult.FailureKind.BAD_PARAM;
            default:
                return SubmitResult.FailureKind.UNKNOWN;
        }
    }
}
