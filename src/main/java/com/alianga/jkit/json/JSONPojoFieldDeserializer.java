package com.alianga.jkit.json;

import com.alianga.jkit.expression.Expression;
import com.alianga.jkit.reflect.GenericParameterizedType;
import com.alianga.jkit.reflect.ReflectConsts;
import com.alianga.jkit.reflect.SetterInfo;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 属性反序列化模型
 *
 */
public final class JSONPojoFieldDeserializer extends JSONTypeDeserializer
        implements Comparable<JSONPojoFieldDeserializer> {
    final JSONStore store;
    final String name;
    final int fieldIndex;
    final SetterInfo setterInfo;
    final JSONPropertyDefinition propertyDefinition;
    /**
     * 类型信息
     */
    GenericParameterizedType<?> genericParameterizedType;

    /**
     * 类型分类
     */
    final ReflectConsts.ClassCategory classCategory;

    Class<?> implClass;

    /**
     * 反序列化器
     */
    JSONTypeDeserializer deserializer;

    /**
     * 是否自定义反序列器
     */
    boolean customDeserialize;

    final String pattern;
    final String timezone;
    boolean flag;

    // 自定义反序列化器
    private static final Map<Class<? extends JSONTypeFieldMapper>, JSONTypeDeserializer> customDeserializers =
            new ConcurrentHashMap<Class<? extends JSONTypeFieldMapper>, JSONTypeDeserializer>();
    private boolean priority;

    JSONPojoFieldDeserializer(JSONStore store, String name, SetterInfo setterInfo,
                              JSONPropertyDefinition propertyDefinition) {
        this.store = store;
        this.name = name;
        this.setterInfo = setterInfo;
        this.fieldIndex = setterInfo.getIndex();
        this.propertyDefinition = propertyDefinition;
        this.genericParameterizedType = setterInfo.getGenericParameterizedType();

        Class<?> actualClass = genericParameterizedType.getActualType();
        this.classCategory = ReflectConsts.getClassCategory(actualClass);

        String pattern = null;
        String timezone = null;
        if (propertyDefinition != null) {
            pattern = propertyDefinition.pattern();
            timezone = propertyDefinition.timezone();
            if (pattern.isEmpty()) {
                pattern = null;
            }
            if (timezone.isEmpty()) {
                timezone = null;
            }
        }
        this.pattern = pattern;
        this.timezone = timezone;
    }

    void initDeserializer() {
        if (!flag) {
            flag = true;
            Class<?> impl;
            boolean unfixedType = false;
            Class<?>[] possibleTypes = null;
            String possibleExpression = null;
            if (propertyDefinition != null) {
                possibleTypes = propertyDefinition.possibleTypes();
                possibleExpression = propertyDefinition.possibleExpression();
                if ((impl = propertyDefinition.impl()) != Object.class) {
                    if (isAvailableImpl(impl)) {
                        implClass = impl;
                    }
                }
                unfixedType = propertyDefinition.unfixedType();
            }
            if (possibleTypes != null && possibleTypes.length > 0) {
                this.deserializer =
                        store.getPossibleTypesTypeDeserializer(genericParameterizedType.getActualType(), possibleTypes,
                                possibleExpression.isEmpty() ? null : Expression.parse(possibleExpression));
            } else if (implClass != null) {
                this.deserializer = store.getTypeDeserializer(implClass);
                this.genericParameterizedType = GenericParameterizedType.actualType(implClass);
            } else {
                if (setterInfo.isNonInstanceType()) {
                    this.deserializer = store.getCachedTypeDeserializer(genericParameterizedType.getActualType());
                } else {
                    if (genericParameterizedType.getActualClassCategory() ==
                            ReflectConsts.ClassCategory.ObjectCategory && unfixedType) {
                        this.deserializer = null;
                    } else {
                        this.deserializer = getDeserializer(genericParameterizedType);
                    }
                }
            }
        }
    }

    boolean ensuredTypeDeserializable() {
        if (setterInfo.isNonInstanceType()) {
            JSONTypeDeserializer deserializer =
                    store.getCachedTypeDeserializer(genericParameterizedType.getActualType());
            if (deserializer == null) {
                return false;
            }
            if (propertyDefinition == null || propertyDefinition.possibleTypes().length == 0) {
                this.deserializer = deserializer;
                flag = true;
            }
        }
        boolean unfixedType = propertyDefinition != null && propertyDefinition.unfixedType();
        if (unfixedType &&
                genericParameterizedType.getActualClassCategory() == ReflectConsts.ClassCategory.ObjectCategory) {
            return false;
        }
        return true;
    }

    private JSONTypeDeserializer getDeserializer(GenericParameterizedType<?> genericParameterizedType) {
        // check custom Deserializer
        if (propertyDefinition != null) {
            try {
                Class<? extends JSONTypeFieldMapper> fieldMapperClass = propertyDefinition.mapper();
                if (fieldMapperClass != JSONTypeFieldMapper.class) {
                    JSONTypeDeserializer customDeserializer = customDeserializers.get(fieldMapperClass);
                    if (customDeserializer != null) {
                        this.customDeserialize = true;
                        return customDeserializer;
                    }
                    JSONTypeFieldMapper<?> fieldMapper = fieldMapperClass.newInstance();
                    if (fieldMapper.deserialize()) {
                        customDeserializer = store.buildDeserializer(fieldMapper);
                        if (fieldMapper.singleton()) {
                            customDeserializers.put(fieldMapperClass, customDeserializer);
                        }
                        this.customDeserialize = true;
                        return customDeserializer;
                    }
                }
            } catch (Throwable throwable) {
                throwable.printStackTrace();
            }
        }
        return store.getFieldDeserializer(genericParameterizedType, propertyDefinition);
    }

    /**
     * 获取属性名称。
     *
     * @return 该属性在 JSON 中对应的字段名
     */
    public String getName() {
        return name;
    }

    /**
     * 判断该属性是否使用了自定义反序列化器。
     *
     * @return 使用了自定义反序列化器时返回 {@code true}，否则返回 {@code false}
     */
    public boolean isCustomDeserialize() {
        return customDeserialize;
    }

//    public SetterInfo getSetterInfo() {
//        return setterInfo;
//    }

//    public GenericParameterizedType getGenericParameterizedType() {
//        return genericParameterizedType;
//    }

    protected Object deserialize(CharSource charSource, char[] buf, int fromIndex,
                                 GenericParameterizedType<?> parameterizedType, Object defaultValue, int endToken,
                                 JSONParseContext jsonParseContext) throws Exception {
        throw new UnsupportedOperationException();
    }

    protected Object deserialize(CharSource charSource, byte[] buf, int fromIndex,
                                 GenericParameterizedType<?> parameterizedType, Object defaultValue, int endToken,
                                 JSONParseContext jsonParseContext) throws Exception {
        throw new UnsupportedOperationException();
    }

    Object getDefaultFieldValue(Object instance) {
        return JSON_SECURE_TRUSTED_ACCESS.getSetterDefault(setterInfo, instance);
    }

    /**
     * 获取属性在实体字段列表中的序号。
     *
     * @return setter 信息中记录的字段索引
     */
    public int getIndex() {
        return setterInfo.getIndex();
    }

//    public void invoke(Object entity, Object value) {
//        JSON_SECURE_TRUSTED_ACCESS.set(setterInfo, entity, value); // setterInfo.invoke(entity, value);
//    }

    /**
     * 获取属性的实现类。
     *
     * @return 反序列化时实际使用的实现类，未指定时可能为 {@code null}
     */
    public Class<?> getImplClass() {
        return implClass;
    }

    /**
     * 判断给定类是否可以作为该属性的实现类使用。
     *
     * @param cls 待校验的类
     * @return 该类是属性声明类型的子类型且属性属于对象、集合、Map 或无法实例化的类别时返回 {@code true}，
     *         否则返回 {@code false}
     */
    public boolean isAvailableImpl(Class<?> cls) {
        boolean assignableFrom = genericParameterizedType.getActualType().isAssignableFrom(cls);
        if (!assignableFrom) {
            return false;
        }
        // only supported ObjectCategory, CollectionCategory, MapCategory
        switch (classCategory) {
            case MapCategory:
            case CollectionCategory:
            case ObjectCategory:
            case NonInstance:
                return true;
            default:
                break;
        }
        return false;
    }

    /**
     * 判断给定值是否可以赋值给该属性。
     *
     * @param value 待判断的值
     * @return 值为 {@code null} 或属于属性声明类型的实例时返回 {@code true}，否则返回 {@code false}
     */
    public boolean isInstance(Object value) {
        return value == null || genericParameterizedType.getActualType().isInstance(value);
    }

    /**
     * 设置该属性是否优先匹配，影响同名属性之间的排序结果。
     *
     * @param priority {@code true} 表示优先
     */
    public void setPriority(boolean priority) {
        this.priority = priority;
    }

    @Override
    public int compareTo(JSONPojoFieldDeserializer o) {
        if (setterInfo != o.setterInfo) {
            return -1;
        }
        return priority ? 1 : 0;
    }

//    static JSONPojoFieldDeserializer createFieldDeserializer(String name, final SetterInfo setterInfo, JsonProperty
//    jsonProperty) {
//        final long fieldOffset = setterInfo.getFieldOffset();
//        if (fieldOffset != -1) {
//            ReflectConsts.PrimitiveType primitiveType = setterInfo.getPrimitiveType();
//            if (primitiveType != null) {
//                switch (primitiveType) {
//                    case PrimitiveByte: {
//                        return new JSONPojoFieldDeserializer(name, setterInfo, jsonProperty) {
//                            @Override
//                            public void invoke(Object entity, Object value) {
//                                JSONUnsafe.UNSAFE.putByte(entity, fieldOffset, (Byte) value);
//                            }
//                        };
//                    }
//                    case PrimitiveShort: {
//                        return new JSONPojoFieldDeserializer(name, setterInfo, jsonProperty) {
//                            @Override
//                            public void invoke(Object entity, Object value) {
//                                JSONUnsafe.UNSAFE.putShort(entity, fieldOffset, (Short) value);
//                            }
//                        };
//                    }
//                    case PrimitiveInt: {
//                        return new JSONPojoFieldDeserializer(name, setterInfo, jsonProperty) {
//                            @Override
//                            public void invoke(Object entity, Object value) {
//                                JSONUnsafe.UNSAFE.putInt(entity, fieldOffset, (Integer) value);
//                            }
//                        };
//                    }
//                    case PrimitiveFloat: {
//                        return new JSONPojoFieldDeserializer(name, setterInfo, jsonProperty) {
//                            @Override
//                            public void invoke(Object entity, Object value) {
//                                JSONUnsafe.UNSAFE.putFloat(entity, fieldOffset, (Float) value);
//                            }
//                        };
//                    }
//                    case PrimitiveLong: {
//                        return new JSONPojoFieldDeserializer(name, setterInfo, jsonProperty) {
//                            @Override
//                            public void invoke(Object entity, Object value) {
//                                JSONUnsafe.UNSAFE.putLong(entity, fieldOffset, (Long) value);
//                            }
//                        };
//                    }
//                    case PrimitiveDouble: {
//                        return new JSONPojoFieldDeserializer(name, setterInfo, jsonProperty) {
//                            @Override
//                            public void invoke(Object entity, Object value) {
//                                JSONUnsafe.UNSAFE.putDouble(entity, fieldOffset, (Double) value);
//                            }
//                        };
//                    }
//                    case PrimitiveBoolean: {
//                        return new JSONPojoFieldDeserializer(name, setterInfo, jsonProperty) {
//                            @Override
//                            public void invoke(Object entity, Object value) {
//                                JSONUnsafe.UNSAFE.putBoolean(entity, fieldOffset, (Boolean) value);
//                            }
//                        };
//                    }
//                    default: {
//                        return new JSONPojoFieldDeserializer(name, setterInfo, jsonProperty) {
//                            @Override
//                            public void invoke(Object entity, Object value) {
//                                JSONUnsafe.UNSAFE.putChar(entity, fieldOffset, (Character) value);
//                            }
//                        };
//                    }
//                }
//            } else {
//                return new JSONPojoFieldDeserializer(name, setterInfo, jsonProperty) {
//                    @Override
//                    public void invoke(Object entity, Object value) {
//                        JSONUnsafe.UNSAFE.putObject(entity, fieldOffset, value);
//                    }
//                };
//            }
//        } else {
//            return new JSONPojoFieldDeserializer(name, setterInfo, jsonProperty) {
//                @Override
//                public void invoke(Object entity, Object value) {
//                    JSON_SECURE_TRUSTED_ACCESS.set(setterInfo, entity, value);
//                }
//            };
//        }
//    }
}
