package com.alianga.jkit.json;

/**
 * <p> 主要用途于@JsonProperty里面的mapper()</p>
 * <p> 自定义字段类型映射器，继承此抽象类，并实现readOf和writeAs方法，即可完成字段类型映射；
 * <P> 定义在属性上的注解，只对当前注解属性字段生效不会影响全局的类型使用；</p>
 * <p> 也可以用于JSON(或JSONInstance)针对指定类型进行全局注册（singleton()强制true）</p>
 *
 * @time 2026/1/23 16:05
 **/
public abstract class JSONTypeFieldMapper<E> implements JSONTypeMapper<E> {
    /**
     * 是否序列化
     *
     * @return 需要参与序列化时返回 {@code true}，否则返回 {@code false}，默认 {@code true}
     */
    protected boolean serialize() {
        return true;
    }

    /**
     * 是否反序列化
     *
     * @return 需要参与反序列化时返回 {@code true}，否则返回 {@code false}，默认 {@code true}
     */
    protected boolean deserialize() {
        return true;
    }

    /**
     * 是否单例(缓存共享一份)
     *
     * @return 该映射器可作为单例缓存共享时返回 {@code true}，否则返回 {@code false}，默认 {@code true}
     */
    protected boolean singleton() {
        return true;
    }
}
