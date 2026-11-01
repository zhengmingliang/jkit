package com.alianga.jkit.captcha.behavior;

/**
 * 提交结果：厂商原始返回码 + 通用失败分类。分类是编排器重试策略的依据——
 * 只有「位置不对」「已过期」值得换图重试，「轨迹被拒」「参数错」应立即停止，
 * 避免对目标服务形成无意义压力。
 */
public final class SubmitResult {
    /** 通用失败分类。 */
    public enum FailureKind {
        /** 校验通过。 */
        SUCCESS,
        /** 位置/答案比对未通过（值得换图重试）。 */
        POSITION_MISMATCH,
        /** 行为轨迹/环境层拒绝（重试同样轨迹参数无意义）。 */
        TRACK_REJECTED,
        /** 验证码已过期（重取即可）。 */
        EXPIRED,
        /** 请求参数不合法（协议实现有误，停止）。 */
        BAD_PARAM,
        /** 未分类失败，携带原始码供诊断。 */
        UNKNOWN
    }

    private final FailureKind kind;
    private final int rawCode;
    private final String rawMessage;

    public SubmitResult(FailureKind kind, int rawCode, String rawMessage) {
        this.kind = kind;
        this.rawCode = rawCode;
        this.rawMessage = rawMessage;
    }

    public FailureKind getKind() {
        return kind;
    }

    public int getRawCode() {
        return rawCode;
    }

    public String getRawMessage() {
        return rawMessage;
    }

    @Override
    public String toString() {
        return kind + "(code=" + rawCode + (rawMessage == null ? "" : ", msg=" + rawMessage) + ")";
    }
}
