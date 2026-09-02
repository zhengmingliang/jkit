package com.alianga.jkit.json;

/**
 * <p>
 * JSON类型模块化注册（集中注册管理）
 * </p>
 *
 */
public interface JSONTypeModule {
    /**
     * 向注册器集中注册本模块提供的类型序列化/反序列化实现。
     *
     * @param registor 类型注册器
     */
    void register(JSONTypeRegistor registor);
}
