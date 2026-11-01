package com.alianga.jkit.captcha.behavior;

import java.util.List;

/**
 * 行为轨迹规则包（厂商适配点之一）：在轨迹出门前按厂商已知的服务端规则做本地自检，
 * 避免明显会被拒绝的轨迹发出去。每个厂商一份实现；规则未知时可用
 * {@link com.alianga.jkit.captcha.behavior.TianaiTrackRulePack} 之外的不做任何检查的空实现兜底。
 *
 * <p>契约：返回 {@code null} 表示通过；返回非空字符串为首个不满足的规则名，
 * 编排器会据此重新合成轨迹。
 */
public interface TrackRulePack {
    /**
     * 校验轨迹。
     *
     * @param track           轨迹（相对位移坐标，t 为相对毫秒）
     * @param backgroundWidth 提交时使用的背景宽（与轨迹坐标系一致）
     * @param startMs         按下时刻墙钟毫秒
     * @param stopMs          抬起时刻墙钟毫秒
     * @return null 通过；非空为首个不满足的规则名
     */
    String validate(List<TrackPoint> track, int backgroundWidth, long startMs, long stopMs);
}
