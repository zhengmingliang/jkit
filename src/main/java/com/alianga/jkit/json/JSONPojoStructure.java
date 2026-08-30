package com.alianga.jkit.json;

import com.alianga.jkit.json.annotations.JsonProperty;
import com.alianga.jkit.json.annotations.JsonTypeSetting;
import com.alianga.jkit.reflect.ClassStrucWrap;
import com.alianga.jkit.reflect.GenericParameterizedType;
import com.alianga.jkit.reflect.GetterInfo;
import com.alianga.jkit.reflect.SetterInfo;

import java.util.*;

/**
 * 对ClassStrucWrap的进一步包装，提供给json模块使用
 *
 */
public final class JSONPojoStructure {
    final JSONStore store;
    final ClassStrucWrap classStrucWrap;
    private final ClassStrucWrap.ClassWrapperType classWrapperType;
    private final GenericParameterizedType<?> genericType;
    final JSONValueMatcher<JSONPojoFieldDeserializer> fieldDeserializerMatcher;
    // deserializers
    final List<JSONPojoFieldDeserializer> fieldDeserializers;

    // getter methods
    private final JSONPojoFieldSerializer[] getterMethodSerializers;
    // field
    private final JSONPojoFieldSerializer[] getterFieldSerializers;
    private final boolean forceUseFields;
    volatile boolean fieldDeserializersInitialized;
    volatile boolean fieldSerializersInitialized;
    private final boolean supportedJavaBeanConvention;
    private final boolean enableJIT;
    final boolean supportedDeserOptimize;
    final Map<String, JSONPropertyDefinition> propertyDefinitions;

    JSONPojoStructure(JSONStore store, ClassStrucWrap strucWrap) {
        this(store, strucWrap, null);
    }

    JSONPojoStructure(JSONStore store, ClassStrucWrap strucWrap,
                      Map<String, JSONPropertyDefinition> propertyDefinitions) {
        strucWrap.getClass();
        this.store = store;
        this.classStrucWrap = strucWrap;
        this.classWrapperType = strucWrap.getClassWrapperType();
        this.forceUseFields = strucWrap.isForceUseFields();
        JsonTypeSetting jsonTypeSetting = (JsonTypeSetting) strucWrap.getDeclaredAnnotation(JsonTypeSetting.class);
        if (propertyDefinitions == null) {
            propertyDefinitions = new HashMap<String, JSONPropertyDefinition>();
        }
        this.propertyDefinitions = propertyDefinitions;
        // serializer info
        List<GetterInfo> getterInfos = strucWrap.getGetterInfos();
        List<JSONPojoFieldSerializer> fieldSerializers = new ArrayList<JSONPojoFieldSerializer>();
        Map<String, JSONPojoFieldSerializer> fieldSerializerHashMap = new HashMap<String, JSONPojoFieldSerializer>();
        for (GetterInfo getterInfo : getterInfos) {
            if (store.isNoneStringMode() && isStringParameterizedType(getterInfo.getGenericParameterizedType())) {
                continue;
            }
            String name = getterInfo.getName();
            JsonProperty jsonProperty = (JsonProperty) getterInfo.getAnnotation(JsonProperty.class);
            JSONPropertyDefinition annotationedProperty = JSONPropertyDefinition.of(jsonProperty);
            JSONPropertyDefinition definition = propertyDefinitions.get(name);
            if (definition == null) {
                definition = annotationedProperty;
            } else {
                definition.merge(annotationedProperty);
            }
            if (definition != null) {
                if (!definition.serialize()) {
                    continue;
                }
                String aliasName;
                if (!(aliasName = definition.name()).isEmpty()) {
                    name = aliasName;
                }
            }
            JSONPojoFieldSerializer fieldSerializer = new JSONPojoFieldSerializer(store, getterInfo, name, definition);
            fieldSerializers.add(fieldSerializer);
            fieldSerializerHashMap.put(name, fieldSerializer);
        }
        getterMethodSerializers = fieldSerializers.toArray(new JSONPojoFieldSerializer[fieldSerializers.size()]);

        fieldSerializers.clear();
        List<GetterInfo> getterByFieldInfos = strucWrap.getGetterInfos(true);
        for (GetterInfo getterInfo : getterByFieldInfos) {
            if (store.isNoneStringMode() && isStringParameterizedType(getterInfo.getGenericParameterizedType())) {
                continue;
            }
            String name = getterInfo.getName();
            JsonProperty jsonProperty = (JsonProperty) getterInfo.getAnnotation(JsonProperty.class);
            JSONPropertyDefinition annotationedProperty = JSONPropertyDefinition.of(jsonProperty);
            JSONPropertyDefinition definition = propertyDefinitions.get(name);
            if (definition == null) {
                definition = annotationedProperty;
            } else {
                definition.merge(annotationedProperty);
            }
            if (definition != null) {
                if (!definition.serialize()) {
                    continue;
                }
                String aliasName;
                if (!(aliasName = definition.name()).isEmpty()) {
                    name = aliasName;
                }
            }
            JSONPojoFieldSerializer fieldSerializer = new JSONPojoFieldSerializer(store, getterInfo, name, definition);
            fieldSerializers.add(fieldSerializer);
        }
        getterFieldSerializers = fieldSerializers.toArray(new JSONPojoFieldSerializer[fieldSerializers.size()]);
        supportedJavaBeanConvention = checkJavaBeanConvention(fieldSerializerHashMap);

        // 反序列化初始化
        this.genericType = GenericParameterizedType.actualType(strucWrap.getSourceClass());
        Set<String> setterNames = strucWrap.setterNames();

        Map<String, JSONPojoFieldDeserializer> fieldDeserializerHashMap =
                new HashMap<String, JSONPojoFieldDeserializer>();
        boolean deserializeOptimizable = true;
        for (String setterName : setterNames) {
            SetterInfo setterInfo = strucWrap.getSetterInfo(setterName);
            String name = setterName;
            boolean priority = name.equals(setterInfo.getName());
            JsonProperty jsonProperty = (JsonProperty) setterInfo.getAnnotation(JsonProperty.class);
            JSONPropertyDefinition annotationedProperty = JSONPropertyDefinition.of(jsonProperty);
            JSONPropertyDefinition definition = propertyDefinitions.get(name);
            if (definition == null) {
                definition = annotationedProperty;
            } else {
                definition.merge(annotationedProperty);
            }
            if (definition != null) {
                if (!definition.deserialize()) {
                    continue;
                }
                String mapperName = definition.name();
                if (!mapperName.isEmpty()) {
                    name = mapperName;
                    priority = true;
                }
            }
            JSONPojoFieldDeserializer fieldDeserializer =
                    new JSONPojoFieldDeserializer(store, name, setterInfo, definition);
            fieldDeserializer.setPriority(priority);
            fieldDeserializerHashMap.put(name, fieldDeserializer);

            if (!fieldDeserializer.ensuredTypeDeserializable()) {
                deserializeOptimizable = false;
            }
        }
        this.fieldDeserializers = new ArrayList<JSONPojoFieldDeserializer>(fieldDeserializerHashMap.values());
        this.fieldDeserializerMatcher = JSONValueMatcher.build(fieldDeserializerHashMap);

        this.enableJIT = jsonTypeSetting != null && jsonTypeSetting.enableJIT();
        this.supportedDeserOptimize = /*this.enableJIT && */deserializeOptimizable;
    }

    static boolean isStringParameterizedType(GenericParameterizedType<?> parameterizedType) {
        if (parameterizedType == null) {
            return false;
        }
        if (parameterizedType.getActualType() == String.class) {
            return true;
        }
        return parameterizedType.getValueType() == GenericParameterizedType.StringType;
    }

    /**
     * 检查pojo是否满足实体bean公约规范（每个属性都定义了相应的getter/setter方法）
     */
    private boolean checkJavaBeanConvention(Map<String, JSONPojoFieldSerializer> fieldSerializerHashMap) {
        return !classStrucWrap.isPrivate();
    }

    void ensureInitializedFieldSerializers() {
        if (fieldSerializersInitialized) {
            return;
        }
        synchronized (this) {
            if (fieldSerializersInitialized) {
                return;
            }
            for (JSONPojoFieldSerializer fieldSerializer : getterMethodSerializers) {
                fieldSerializer.initSerializer();
            }
            for (JSONPojoFieldSerializer fieldSerializer : getterFieldSerializers) {
                fieldSerializer.initSerializer();
            }
            fieldSerializersInitialized = true;
        }
    }

    void ensureInitializedFieldDeserializers() {
        if (fieldDeserializersInitialized) {
            return;
        }
        synchronized (this) {
            if (fieldDeserializersInitialized) {
                return;
            }
            for (JSONPojoFieldDeserializer fieldDeserializer : fieldDeserializers) {
                fieldDeserializer.initDeserializer();
            }
            fieldDeserializersInitialized = true;
        }
    }

    /**
     * 获取被包装的源类型。
     *
     * @return 该结构对应的原始 class
     */
    public Class<?> getSourceClass() {
        return classStrucWrap.getSourceClass();
    }

    /**
     * 判断源类型是否为 record 类型。
     *
     * @return 是 record 类型时返回 {@code true}，否则返回 {@code false}
     */
    public boolean isRecord() {
        return classStrucWrap.isRecord();
    }

    /**
     * 判断源类型是否为时间日期（Temporal）类型。
     *
     * @return 是时间日期类型时返回 {@code true}，否则返回 {@code false}
     */
    public boolean isTemporal() {
        return classStrucWrap.isTemporal();
    }

    /**
     * 获取源类型的字段数量。
     *
     * @return 源类型参与序列化/反序列化的字段个数
     */
    public int getFieldCount() {
        return classStrucWrap.getFieldCount();
    }

    /**
     * 获取源类型的包装类别。
     *
     * @return 源类型对应的 ClassWrapperType 枚举值
     */
    public ClassStrucWrap.ClassWrapperType getClassWrapperType() {
        return classWrapperType;
    }

    /**
     * 创建调用构造器所需的默认参数数组。
     *
     * @return 按构造器参数类型填充默认值的参数数组
     */
    public Object[] createConstructorArgs() {
        return classStrucWrap.createConstructorArgs();
    }

    /**
     * 使用默认方式创建源类型的实例。
     *
     * @return 新创建的源类型实例
     * @throws Exception 实例化过程中发生的反射异常
     */
    public Object newInstance() throws Exception {
        return classStrucWrap.newInstance();
    }

    /**
     * 使用指定构造参数创建源类型的实例。
     *
     * @param constructorArgs 构造器参数数组
     * @return 新创建的源类型实例
     * @throws Exception 实例化过程中发生的反射异常
     */
    public Object newInstance(Object[] constructorArgs) throws Exception {
        return classStrucWrap.newInstance(constructorArgs);
    }

    /**
     * 获取源类型对应的泛型类型描述。
     *
     * @return 源类型的实际泛型类型描述
     */
    public GenericParameterizedType<?> getGenericType() {
        return genericType;
    }

    /**
     * 判断源类型是否可以由 Map 赋值（即 Map 是否为其子类型）。
     *
     * @return 可以用 Map 赋值时返回 {@code true}，否则返回 {@code false}
     */
    public boolean isAssignableFromMap() {
        return classStrucWrap.isAssignableFromMap();
    }

    /**
     * 获取字段序列化器数组。
     *
     * @param useFields 是否使用字段（而非 getter 方法）序列化
     * @return 基于字段的序列化器数组；useFields 为 false 且未强制使用字段时返回基于 getter 方法的序列化器数组
     */
    public JSONPojoFieldSerializer[] getFieldSerializers(boolean useFields) {
        return useFields || forceUseFields ? getterFieldSerializers : getterMethodSerializers;
    }

    /**
     * 判断源类型是否为 private 访问级别。
     *
     * @return 源类型为 private 时返回 {@code true}，否则返回 {@code false}
     */
    public boolean isPrivate() {
        return classStrucWrap.isPrivate();
    }

    /**
     * 判断源类型是否为 public 访问级别。
     *
     * @return 源类型为 public 时返回 {@code true}，否则返回 {@code false}
     */
    public boolean isPublic() {
        return classStrucWrap.isPublic();
    }

    /**
     * 判断是否强制使用字段而非 getter 方法访问属性。
     *
     * @return 强制使用字段时返回 {@code true}，否则返回 {@code false}
     */
    public boolean isForceUseFields() {
        return forceUseFields;
    }

    /**
     * supported java bean convention
     *
     * @return {@code true} if the source class follows the java bean convention, {@code false} otherwise
     */
    public boolean isSupportedJavaBeanConvention() {
        return this.supportedJavaBeanConvention;
    }

    /**
     * if supported JIT optimization
     *
     * @return {@code true} if the source class follows the java bean convention, is public and JIT is
     * enabled, {@code false} otherwise
     */
    public boolean isSupportedJIT() {
        return isSupportedJavaBeanConvention() && isPublic() && enableJIT;
    }

    boolean isSupportedOptimize() {
        return supportedDeserOptimize && fieldDeserializerMatcher.isSupportedOptimize();
    }
}
