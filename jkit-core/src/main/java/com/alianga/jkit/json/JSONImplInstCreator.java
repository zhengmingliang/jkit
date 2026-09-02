package com.alianga.jkit.json;

import com.alianga.jkit.reflect.GenericParameterizedType;

/**
 * 对于未知抽象类和接口提供构造实例的接口
 *
 * @param <T> 待创建的实例类型
 * @time 2024/4/11 13:06
 */
public interface JSONImplInstCreator<T> {
    /**
     * 根据反序列化时解析出的泛型类型信息创建一个可用实例。
     *
     * @param parameterizedType 目标类型的泛型描述信息
     * @return 新创建的目标类型实例
     */
    public T create(GenericParameterizedType<T> parameterizedType);

}
