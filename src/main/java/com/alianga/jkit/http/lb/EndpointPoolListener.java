package com.alianga.jkit.http.lb;

/**
 * 端点池事件监听：熔断打开/恢复、故障转移、端点上下线、发现刷新失败。
 * <p>
 * 这些事件是线上排障与告警的唯一入口——没有它们，负载均衡对运维就是个黑盒。
 * 回调在调用线程（或发现刷新线程）上同步执行，实现里不要做耗时操作。
 *
 * @author 郑明亮
 */
public interface EndpointPoolListener {
    /**
     * 端点被熔断。
     *
     * @param pool 端点池
     * @param endpoint 端点
     * @param cooldownMs 本次冷却时长
     */
    default void onBreakerOpen(EndpointPool pool, Endpoint endpoint, long cooldownMs) {
    }

    /**
     * 端点熔断恢复（半开探测成功）。
     *
     * @param pool 端点池
     * @param endpoint 端点
     */
    default void onBreakerClose(EndpointPool pool, Endpoint endpoint) {
    }

    /**
     * 请求从一个端点转移到另一个端点。
     *
     * @param pool 端点池
     * @param failed 失败的端点
     * @param next 接替的端点
     * @param cause 失败原因描述
     */
    default void onFailover(EndpointPool pool, Endpoint failed, Endpoint next, String cause) {
    }

    /**
     * 服务发现刷新导致端点列表变化。
     *
     * @param pool 端点池
     * @param added 新增端点数
     * @param removed 移除端点数
     * @param total 刷新后总端点数
     */
    default void onEndpointsChanged(EndpointPool pool, int added, int removed, int total) {
    }

    /**
     * 服务发现刷新失败（此时保留上一次的端点快照，不清空）。
     *
     * @param pool 端点池
     * @param error 异常
     */
    default void onDiscoveryFailure(EndpointPool pool, Exception error) {
    }

    /**
     * 全部端点都不可用，被迫放行一个半开探测。
     *
     * @param pool 端点池
     * @param probe 被选作探测的端点
     */
    default void onAllEndpointsDown(EndpointPool pool, Endpoint probe) {
    }
}
