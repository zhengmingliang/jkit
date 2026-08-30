package com.alianga.jkit.http.lb;

import java.util.List;

/**
 * 负载均衡调度策略。
 * <p>
 * 实现方只需在给定的<b>候选列表</b>（已过滤掉熔断中、已摘流、本次已试过的端点）里挑一个，
 * 健康统计、在途计数与熔断都由 {@link EndpointPool} 负责。
 * <p>
 * 内置实现见 {@link LoadBalanceStrategies}。自定义策略实现本接口即可。
 *
 * @author 郑明亮
 */
public interface LoadBalanceStrategy {
    /**
     * @return 策略名，用于日志与监控
     */
    String name();

    /**
     * 从候选端点中选择一个。
     *
     * @param candidates 候选端点，非空且至少一个元素
     * @param hashKey 会话亲和键（一致性哈希用），可为 {@code null}
     * @return 选中的端点，不能为 {@code null}
     */
    Endpoint select(List<Endpoint> candidates, String hashKey);
}
