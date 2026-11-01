package com.alianga.jkit.captcha.behavior;

import java.io.IOException;

/**
 * 行为验证码厂商协议适配点：把「取验证码 / 提交答案」从厂商私有契约
 * （端点、报文、加密、失败码语义）翻译成本包的厂商无关模型。
 *
 * <p>新增厂商只需实现本接口与 {@link TrackRulePack}（可选），解算与轨迹层原样复用：
 * 实现 {@code fetch()} 时把厂商响应里的图与会话标识装入 {@link BehaviorChallenge}
 * （自然尺寸）；实现 {@code submit()} 时把 {@link BehaviorAnswer} 翻译成厂商报文
 * （轨迹坐标系、字段名、加密与指纹按厂商文档/SDK 处理），并把厂商返回码映射为
 * {@link SubmitResult.FailureKind} 供编排器决策。
 *
 * <p>参考实现：{@link TianaiBehaviorProtocol}；测试样例：
 * {@code SliderCaptchaSolverTest} 侧的假厂商见 {@code BehaviorCaptchaSolverTest}。
 */
public interface BehaviorCaptchaProtocol {
    /** 厂商标识，如 tianai / geetest / yidun。 */
    String vendor();

    /** 拉取一次验证码挑战。 */
    BehaviorChallenge fetch() throws IOException;

    /** 提交解算答案与轨迹，返回厂商无关的校验结果。 */
    SubmitResult submit(BehaviorChallenge challenge, BehaviorAnswer answer) throws IOException;
}
