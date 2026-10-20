package com.alianga.jkit.mock.server;

/**
 * 多响应方案挑选策略（审计 P0-5：序贯 / 概率场景模拟）。
 *
 * <ul>
 *   <li>{@link #FIRST}：默认。带规则的方案先评估，第一个规则全中的生效；全不中回落默认方案。</li>
 *   <li>{@link #SEQUENCE}：按命中次数轮播方案（第 1 次给第 1 条，之后循环），激活规则忽略。</li>
 *   <li>{@link #RANDOM}：按 {@link MockResponse#getWeight() 方案权重} 随机挑选，激活规则忽略。</li>
 * </ul>
 *
 * @author 郑明亮
 * @see MockRoute#getResponseStrategy()
 * @since 2.0.4
 */
public enum MockPlanStrategy {
    /** 按激活规则评估（历史行为）。 */
    FIRST,
    /** 按命中次数轮播（“第 1 次 200、之后 503”场景）。 */
    SEQUENCE,
    /** 按权重随机（“30% 失败率”场景）。 */
    RANDOM
}
