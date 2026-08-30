package com.alianga.jkit.json;

import com.alianga.jkit.json.internal.utils.EnvUtils;
import com.alianga.jkit.reflect.GenericParameterizedType;
import com.alianga.jkit.reflect.GetterInfo;
import com.alianga.jkit.reflect.ReflectConsts;

import java.io.IOException;
import java.lang.reflect.Modifier;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 *
 */
public class JSONPojoFieldSerializer {
    final JSONStore store;
    final GetterInfo getterInfo;
    final JSONPropertyDefinition propertyDefinition;
    final String name;
    final ReflectConsts.ClassCategory classCategory;
    JSONTypeSerializer serializer;
    private char[] fieldNameTokenChars;
    private String fieldNameToken;
    private int fieldNameTokenOffset;
    private long[] fieldNameCharLongs;
    private long[] fieldNameByteLongs;
    private boolean flag;
    private boolean customSerialize;

    // 自定义序列化器
    private static final Map<Class<? extends JSONTypeFieldMapper>, JSONTypeSerializer> customSerializers =
            new ConcurrentHashMap<Class<? extends JSONTypeFieldMapper>, JSONTypeSerializer>();

    JSONPojoFieldSerializer(JSONStore store, GetterInfo getterInfo, String name,
                            JSONPropertyDefinition propertyDefinition) {
        this.store = store;
        this.getterInfo = getterInfo;
        this.classCategory = getterInfo.getClassCategory();
        this.name = name;
        this.propertyDefinition = propertyDefinition;
        setFieldNameToken();
    }

    void initSerializer() {
        if (flag) {
            return;
        }
        if (this.serializer == null) {
            flag = true;
            this.serializer = createSerializer();
        }
    }

    private JSONTypeSerializer createSerializer() {
        // check custom Serializer
        if (propertyDefinition != null) {
            try {
                Class<? extends JSONTypeFieldMapper> fieldMapperClass = propertyDefinition.mapper();
                if (fieldMapperClass != JSONTypeFieldMapper.class) {
                    JSONTypeSerializer serializer = customSerializers.get(fieldMapperClass);
                    if (serializer != null) {
                        customSerialize = true;
                        return serializer;
                    }
                    JSONTypeFieldMapper<?> fieldMapper = fieldMapperClass.newInstance();
                    if (fieldMapper.serialize()) {
                        serializer = store.buildSerializer(fieldMapper);
                        if (fieldMapper.singleton()) {
                            customSerializers.put(fieldMapperClass, serializer);
                        }
                        customSerialize = true;
                        return serializer;
                    }
                }
            } catch (Throwable e) {
                e.printStackTrace();
            }
        }
        Class<?> returnType = getterInfo.getReturnType();
        if (returnType == String.class) {
            return store.STRING_SER;
        } else if (classCategory == ReflectConsts.ClassCategory.NumberCategory) {
            // From cache by different number types (int/float/double/long...)
            return store.getTypeSerializer(returnType);
        } else {
            GenericParameterizedType<?> genericParameterizedType = getterInfo.getGenericParameterizedType();
            if (classCategory == ReflectConsts.ClassCategory.CollectionCategory && genericParameterizedType != null) {
                GenericParameterizedType<?> valueType = genericParameterizedType.getValueType();
                if (valueType != null) {
                    Class<?> valueClass = valueType.getActualType();
                    if (Modifier.isFinal(valueClass.getModifiers())) {
                        return store.createCollectionSerializer(valueClass);
                    }
                }
            }
            return store.getFieldTypeSerializer(classCategory, returnType, propertyDefinition);
        }
    }

    /**
     * 判断该字段是否为元素类型明确为 String 的集合
     *
     * @return 字段是集合类型且泛型元素类型为 String 时返回 {@code true}，否则返回 {@code false}
     */
    public boolean isStringCollection() {
        if (classCategory == ReflectConsts.ClassCategory.CollectionCategory) {
            GenericParameterizedType<?> parameterizedType = getterInfo.getGenericParameterizedType();
            return parameterizedType != null && parameterizedType.getValueType() == GenericParameterizedType.StringType;
        }
        return false;
    }

    private void setFieldNameToken() {
        int len = name.length();
        // "${name}":null
        fieldNameTokenChars = new char[len + 7];
        int i = 0;
        fieldNameTokenChars[i++] = '"';
        name.getChars(0, len, fieldNameTokenChars, i);
        fieldNameTokenChars[len + 1] = '"';
        fieldNameTokenChars[len + 2] = ':';
        fieldNameTokenOffset = len + 3;
        fieldNameTokenChars[len + 3] = 'n';
        fieldNameTokenChars[len + 4] = 'u';
        fieldNameTokenChars[len + 5] = 'l';
        fieldNameTokenChars[len + 6] = 'l';
        if (EnvUtils.JDK_9_PLUS) {
            fieldNameToken = new String(fieldNameTokenChars);
        }
        // optimize use unsafe(适用JDK8，JDK9+提升不明显)
        if (name.getBytes().length == len) {
            String stringForUnsafe = new String(fieldNameTokenChars, 0, fieldNameTokenOffset);
            fieldNameCharLongs = JSONMemoryHandle.getCharLongs(stringForUnsafe);
            fieldNameByteLongs = JSONMemoryHandle.getByteLongs(stringForUnsafe);
        }
    }

//    Object invoke(Object pojo) {
//        // return getterInfo.invoke(pojo);
//        return JSON_SECURE_TRUSTED_ACCESS.get(getterInfo, pojo);
//    }
//
//    <T> T invoke(Object pojo, Class<T> tClass) {
//        return (T) JSON_SECURE_TRUSTED_ACCESS.get(getterInfo, pojo);
//    }

    void writeFieldNameAndColonTo(JSONWriter writer) throws IOException {
        if (fieldNameCharLongs != null) {
            writer.writeMemory(fieldNameCharLongs, fieldNameByteLongs, fieldNameTokenOffset);
        } else {
            if (fieldNameToken != null) {
                writer.write(fieldNameToken, 0, fieldNameTokenOffset);
            } else {
                writer.writeShortChars(fieldNameTokenChars, 0, fieldNameTokenOffset);
            }
        }
    }

    void writeJSONFieldNameWithNull(JSONWriter writer) throws IOException {
        if (fieldNameToken != null) {
            writer.write(fieldNameToken, 0, fieldNameTokenChars.length);
        } else {
            writer.writeShortChars(fieldNameTokenChars, 0, fieldNameTokenChars.length);
        }
    }

    /**
     * 获取该字段使用的序列化器
     *
     * @return 当前字段的序列化器，在初始化之前可能为 {@code null}
     */
    public JSONTypeSerializer getSerializer() {
        return serializer;
    }

    /**
     * 判断该字段是否使用了通过 {@code JsonProperty#mapper()} 指定的自定义序列化器
     *
     * @return 使用自定义序列化器时返回 {@code true}，否则返回 {@code false}
     */
    public boolean isCustomSerialize() {
        return customSerialize;
    }

    /**
     * 获取该字段序列化输出时使用的名称
     *
     * @return 当前字段的输出名称
     */
    public String getName() {
        return name;
    }

    /**
     * 获取该字段的属性定义信息（{@code JsonProperty} 注解的编码实现）
     *
     * @return 当前字段的属性定义，字段未配置注解时为 {@code null}
     */
    public JSONPropertyDefinition getPropertyDefinition() {
        return propertyDefinition;
    }
}
