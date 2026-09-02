package com.alianga.jkit.json;

import com.alianga.jkit.reflect.GenericParameterizedType;

/**
 * POJO（实体对象）反序列化器基类，负责创建实体实例并将解析出的值转换为最终的 POJO 对象
 */
public abstract class JSONPojoDeserializer extends JSONTypeDeserializer {
    abstract Object deserializePojo(CharSource charSource, byte[] buf, int fromIndex,
                                    GenericParameterizedType<?> parameterizedType, Object entity,
                                    JSONParseContext parseContext) throws Exception;

    abstract Object deserializePojo(CharSource charSource, char[] buf, int fromIndex,
                                    GenericParameterizedType<?> parameterizedType, Object entity,
                                    JSONParseContext parseContext) throws Exception;

    /**
     * 创建反序列化过程中用于承载属性值的实体实例
     *
     * @return 新创建的实体对象（或用于收集属性的中间载体）
     * @throws Exception 创建实例失败时抛出
     */
    protected abstract Object createPojo() throws Exception;

    /**
     * 将 {@link #createPojo()} 产生并填充完属性的中间对象转换为最终的 POJO 对象
     *
     * @param value 已填充属性值的中间对象
     * @return 最终的 POJO 对象
     */
    protected abstract Object pojo(Object value);
}
