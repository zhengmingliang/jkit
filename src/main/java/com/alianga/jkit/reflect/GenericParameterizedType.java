package com.alianga.jkit.reflect;

import java.lang.reflect.*;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 解决多层泛型类型解析
 *
 * @time 2021/12/30 10:34
 */
public final class GenericParameterizedType<T> {
    private GenericParameterizedType() {
    }

    // cache
    private static final Map<Class, GenericParameterizedType> GENERIC_PARAMETERIZED_TYPE_MAP =
            new ConcurrentHashMap<Class, GenericParameterizedType>();

    /**
     * AnyType类型
     */
    public static final GenericParameterizedType AnyType = GenericParameterizedType.actualType(Object.class);

    /**
     * 字符串类型
     */
    public static final GenericParameterizedType StringType = GenericParameterizedType.actualType(String.class);
    /**
     * 默认Map类型
     */
    public static final GenericParameterizedType DefaultMap = GenericParameterizedType.actualType(LinkedHashMap.class);
//    /**
//     * 默认Collection类型
//     */
//    public static final GenericParameterizedType DefaultCollection = GenericParameterizedType.collectionType
//    (ArrayList.class, Object.class);

    /***
     * int类型
     */
    public static final GenericParameterizedType<Integer> IntType = GenericParameterizedType.actualType(int.class);
    /***
     * Integer包装类型
     */
    public static final GenericParameterizedType<Integer> IntWrapType =
            GenericParameterizedType.actualType(Integer.class);

    /***
     * long类型
     */
    public static final GenericParameterizedType<Long> LongType = GenericParameterizedType.actualType(long.class);
    /***
     * Long包装类型
     */
    public static final GenericParameterizedType<Long> LongWrapType = GenericParameterizedType.actualType(Long.class);

    /***
     * double类型
     */
    public static final GenericParameterizedType<Double> DoubleType = GenericParameterizedType.actualType(double.class);

    /***
     * BigDecimalType类型
     */
    public static final GenericParameterizedType<BigDecimal> BigDecimalType =
            GenericParameterizedType.actualType(BigDecimal.class);

    /**
     * 是否数组类型，数组无对应的class
     */
    boolean array;

    /**
     * 实际类型： Map类型(key和value两个泛型)/Collection类型（value一个泛型）/其他实体类（0-n个）
     */
    Class<?> actualType;

    // ClassCategory
    ReflectConsts.ClassCategory actualClassCategory;

    /**
     * map类型的泛型key类型
     */
    Class<?> mapKeyClass;

    /**
     * map类的泛型value类型或者Collection类型的元素泛型
     */
    GenericParameterizedType<?> valueType;

    /**
     * 是否泛型
     */
    boolean generic;

    /**
     * 其他实体类泛型列表(实体类暂时只支持单泛型且不能嵌套)
     */
    Class<?> genericClass;

    /**
     * 泛型映射
     */
    Map<String, Class<?>> genericClassMap;

    /**
     * 是否伪泛型
     */
    private boolean camouflage;

    /**
     * 伪泛型名称，可以通过上级泛型结构的genericClassMap中解析出实际类型
     */
    String genericName;

    /**
     * 根据实际类型构建泛型结构对象
     *
     * @param actualType 实际类型，为 {@code null} 时返回 {@link #AnyType}
     * @param <T> 实际类型对应的泛型
     * @return 与该类型对应的泛型结构对象，结果按类型缓存复用；数组类型会同时解析其元素类型
     */
    public static <T> GenericParameterizedType<T> actualType(Class<T> actualType) {
        if (actualType == null) {
            return AnyType;
        }
        GenericParameterizedType<?> genericParameterizedType = GENERIC_PARAMETERIZED_TYPE_MAP.get(actualType);
        if (genericParameterizedType == null) {
            genericParameterizedType = new GenericParameterizedType<Object>();
            genericParameterizedType.setActualType(actualType);
            genericParameterizedType.actualClassCategory = ReflectConsts.getClassCategory(actualType);
            if (actualType.isArray()) {
                genericParameterizedType.valueType = actualType(actualType.getComponentType());
            }
            GENERIC_PARAMETERIZED_TYPE_MAP.put(actualType, genericParameterizedType);
        }
        return (GenericParameterizedType<T>) genericParameterizedType;
    }

    /**
     * 通过new构建,场景需要隔离时使用
     */
    static GenericParameterizedType<?> createInternal(Class<?> actualType) {
        if (actualType == Object.class) {
            return AnyType;
        }
        if (actualType == String.class) {
            return StringType;
        }
        if (actualType == Integer.class || actualType == int.class) {
            return actualType == Integer.class ? IntWrapType : IntType;
        }
        if (actualType == Long.class || actualType == long.class) {
            return actualType == Long.class ? LongWrapType : LongType;
        }
        GenericParameterizedType<?> genericParameterizedType = new GenericParameterizedType<Object>();
        genericParameterizedType.setActualType(actualType);
        genericParameterizedType.actualClassCategory = ReflectConsts.getClassCategory(actualType);
        return genericParameterizedType;
    }

    /**
     * 根据 Map 子类声明在父类上的泛型参数构建泛型结构对象。
     *
     * @param mapClass Map 实现类
     * @return 解析出 key/value 泛型的 Map 泛型结构；无法从父类泛型中解析出两个类型参数时，
     *     退化为 {@link #actualType(Class)} 的结果
     */
    public static GenericParameterizedType<?> mapOf(Class<? extends Map> mapClass) {
        Type type = mapClass.getGenericSuperclass();
        if (type instanceof ParameterizedType) {
            ParameterizedType parameterizedType = (ParameterizedType) type;
            Type[] actualTypeArguments = parameterizedType.getActualTypeArguments();
            if (actualTypeArguments != null && actualTypeArguments.length == 2) {
                Type arg0 = actualTypeArguments[0];
                Type arg1 = actualTypeArguments[1];
                if (arg0 instanceof Class) {
                    return GenericParameterizedType.mapType(mapClass, (Class<?>) arg0,
                            GenericParameterizedType.of(arg1));
                }
            }
        }
        return actualType(mapClass);
    }

    /**
     * 根据 Collection 子类声明在父类上的泛型参数构建泛型结构对象。
     *
     * @param collectionClass Collection 实现类
     * @return 解析出元素泛型的集合泛型结构；无法从父类泛型中解析出元素类型时，
     *     退化为 {@link #actualType(Class)} 的结果
     */
    public static GenericParameterizedType<?> collectionOf(Class<? extends Collection> collectionClass) {
        Type type = collectionClass.getGenericSuperclass();
        if (type instanceof ParameterizedType) {
            ParameterizedType parameterizedType = (ParameterizedType) type;
            Type[] actualTypeArguments = parameterizedType.getActualTypeArguments();
            if (actualTypeArguments != null && actualTypeArguments.length == 1) {
                return collectionType(collectionClass, GenericParameterizedType.of(actualTypeArguments[0]));
            }
        }
        return actualType(collectionClass);
    }

//    static Type getEntityGenericParameterizedType(Class entityClass) {
//        Type type = entityClass.getGenericSuperclass();
//        if (type instanceof ParameterizedType) {
//            ParameterizedType parameterizedType = (ParameterizedType) type;
//            Type[] actualTypeArguments = parameterizedType.getActualTypeArguments();
//            if (actualTypeArguments != null && actualTypeArguments.length == 1) {
//                return actualTypeArguments[0];
//            }
//        }
//        return null;
//    }

    /**
     * 判断当前实例是否为任意类型（{@link #AnyType}）。
     *
     * @return 当前实例就是 {@link #AnyType} 单例时返回 {@code true}，否则返回 {@code false}
     */
    public boolean isAnyType() {
        return this == AnyType;
    }

    private void setActualType(Class<?> actualType) {
        this.actualType = actualType;
    }

    /**
     * 构建map类型的泛型结构对象, value为简单类型（无泛型）
     *
     * @param mapClass Map 实现类
     * @param mapKeyClass Map 的 key 类型
     * @param valueActualType Map 的 value 实际类型
     * @return 新建的 Map 泛型结构对象，已标记为泛型
     */
    public static GenericParameterizedType<?> mapType(Class<? extends Map> mapClass, Class<?> mapKeyClass,
                                                      Class<?> valueActualType) {
        GenericParameterizedType<?> parameterizedType = new GenericParameterizedType<Object>();
        parameterizedType.generic = true;
        parameterizedType.setActualType(mapClass);

        parameterizedType.mapKeyClass = mapKeyClass;

        GenericParameterizedType<?> valueType = new GenericParameterizedType<Object>();
        valueType.setActualType(valueActualType);

        parameterizedType.valueType = valueType;
        return parameterizedType;
    }

    /**
     * 构建map类型的泛型结构, value也可能存在泛型
     *
     * @param mapClass Map 实现类
     * @param mapKeyClass Map 的 key 类型
     * @param valueType Map 的 value 泛型结构，可继续嵌套
     * @return 新建的 Map 泛型结构对象，已标记为泛型
     */
    public static GenericParameterizedType<?> mapType(Class<? extends Map> mapClass, Class<?> mapKeyClass,
                                                      GenericParameterizedType<?> valueType) {
        GenericParameterizedType<?> parameterizedType = new GenericParameterizedType<Object>();
        parameterizedType.generic = true;
        parameterizedType.setActualType(mapClass);
        parameterizedType.mapKeyClass = mapKeyClass;

        parameterizedType.valueType = valueType;
        return parameterizedType;
    }

    /**
     * 构建数组类型的泛型结构，元素为简单类型
     *
     * @param componentType 数组元素泛型
     * @return 新建的数组泛型结构对象，actualType 为对应的数组类型，并已标记为数组与泛型
     */
    public static GenericParameterizedType<?> arrayType(Class<?> componentType) {
        GenericParameterizedType<?> parameterizedType = new GenericParameterizedType<Object>();
        parameterizedType.actualType = Array.newInstance(componentType, 0).getClass();
        parameterizedType.generic = true;
        parameterizedType.array = true;

        parameterizedType.valueType = actualType(componentType);
        return parameterizedType;
    }

//    /**
//     * 构建数组类型的泛型结构，元素为可嵌套类型
//     *
//     * @param valueType 数组元素泛型结构
//     * @return
//     */
//    public static GenericParameterizedType arrayType(GenericParameterizedType valueType) {
//        GenericParameterizedType parameterizedType = new GenericParameterizedType();
//        parameterizedType.generic = true;
//        parameterizedType.array = true;
//        parameterizedType.valueType = valueType;
//        return parameterizedType;
//    }

    /**
     * 构建Collection类型的泛型结构，元素为简单类型
     *
     * @param collectionClass 集合实现类，为 {@code null} 时使用 {@link ArrayList}
     * @param valueActualType 集合元素的实际类型
     * @param <E> 集合类型
     * @return 新建的集合泛型结构对象，已标记为泛型
     */
    public static <E> GenericParameterizedType<E> collectionType(Class<E> collectionClass, Class<?> valueActualType) {
        GenericParameterizedType<E> parameterizedType = new GenericParameterizedType<E>();
        parameterizedType.generic = true;
        parameterizedType.setActualType(collectionClass == null ? ArrayList.class : collectionClass);

        parameterizedType.valueType = actualType(valueActualType);
        return parameterizedType;
    }

    /**
     * 构建Collection类型的泛型结构，元素也可能存在泛型
     *
     * @param collectionClass 集合实现类
     * @param valueType 集合元素的泛型结构，可继续嵌套
     * @param <E> 集合类型
     * @return 新建的集合泛型结构对象，已标记为泛型
     */
    public static <E> GenericParameterizedType<E> collectionType(Class<E> collectionClass,
                                                                 GenericParameterizedType<?> valueType) {
        GenericParameterizedType<E> parameterizedType = new GenericParameterizedType<E>();
        parameterizedType.generic = true;
        parameterizedType.setActualType(collectionClass);
        parameterizedType.valueType = valueType;
        return parameterizedType;
    }

    /**
     * 构建普通实体类型的泛型结构
     * (ps:单泛型模式)
     *
     * @param entityClass 实体类
     * @param genericClass 实体类上单个泛型对应的实际类型
     * @param <E> 实体类型
     * @return 新建的实体泛型结构对象，已标记为泛型
     */
    public static <E> GenericParameterizedType<E> entityType(Class<E> entityClass, Class<?> genericClass) {
        GenericParameterizedType<E> parameterizedType = new GenericParameterizedType<E>();
        parameterizedType.setActualType(entityClass);
        parameterizedType.generic = true;
        parameterizedType.genericClass = genericClass;
        // If it is an array type, it needs to be handled separately ?
        return parameterizedType;
    }

    /**
     * 构建普通实体类型的泛型结构
     * 支持n个泛型，通过名称进行映射
     *
     * @param entityClass 实体类
     * @param genericClassMap 泛型名称到实际类型的映射
     * @param <E> 实体类型
     * @return 新建的实体泛型结构对象；注意该方法不会把 generic 标记为 true
     */
    public static <E> GenericParameterizedType<E> entityType(Class<E> entityClass,
                                                             Map<String, Class<?>> genericClassMap) {
        GenericParameterizedType<E> parameterizedType = new GenericParameterizedType<E>();
        parameterizedType.setActualType(entityClass);
        parameterizedType.genericClassMap = genericClassMap;
        return parameterizedType;
    }

    /**
     * 递归解析Collection的泛型结构
     */
    static GenericParameterizedType<?> genericCollectionType(Class<?> parameterType, Type genericType) {
        GenericParameterizedType<?> genericParameterizedType = new GenericParameterizedType<Object>();
        genericParameterizedType.setActualType(parameterType);
        genericParameterizedType.generic = true;
        parseValueType(genericParameterizedType, genericType);
        return genericParameterizedType;
    }

    /**
     * 递归解析数组的泛型结构
     */
    static GenericParameterizedType<?> genericArrayType(Type genericComponentType) {
        GenericParameterizedType<?> genericParameterizedType = new GenericParameterizedType<Object>();
        genericParameterizedType.array = true;
        genericParameterizedType.generic = true;
        parseValueType(genericParameterizedType, genericComponentType);
        return genericParameterizedType;
    }

    static GenericParameterizedType<?> genericMapType(Class<?> parameterType, Type key, Type value) {
        GenericParameterizedType<?> genericParameterizedType = new GenericParameterizedType<Object>();
        genericParameterizedType.setActualType(parameterType);
        genericParameterizedType.generic = true;
        genericParameterizedType.mapKeyClass = !(key instanceof Class<?>) ? Object.class : (Class<?>) key;
        parseValueType(genericParameterizedType, value);
        return genericParameterizedType;
    }

    /**
     * 通过类上的伪泛型构建泛型结构
     *
     * @param parameterType 类
     * @param genericName   伪泛型名称
     */
    static GenericParameterizedType<?> genericEntityType(Class<?> parameterType, String genericName) {
        GenericParameterizedType<?> genericParameterizedType = new GenericParameterizedType<Object>();
        genericParameterizedType.setActualType(parameterType);
        genericParameterizedType.genericName = genericName;
        genericParameterizedType.camouflage = true;
        return genericParameterizedType;
    }

    private static void parseValueType(GenericParameterizedType<?> genericParameterizedType, Type genericType) {
        if (genericType instanceof Class) {
            // 实体类型
            Class<?> entityClass = (Class<?>) genericType;
            genericParameterizedType.valueType = actualType(entityClass);
        } else if (genericType instanceof GenericArrayType) {
            // 数组类型
            GenericArrayType genericArrayType = (GenericArrayType) genericType;
            Type genericComponentType = genericArrayType.getGenericComponentType();

            // jdk6 char[]等基础数据的genericType依然是Type(数组加componentType)
            // jdk7+ char[]等基础数据的enericType已经是一个数组类型(char[].class)
            ReflectConsts.PrimitiveType primitiveType;
            // jdk1.6 兼容处理
            if (genericComponentType instanceof Class &&
                    (primitiveType = ReflectConsts.PrimitiveType.typeOf((Class) genericComponentType)) != null) {
                genericParameterizedType.valueType = actualType(primitiveType.getGenericArrayType());
            } else {
                genericParameterizedType.valueType = genericArrayType(genericComponentType);
            }
        } else if (genericType instanceof ParameterizedType) {
            // 两级泛型时如： List<Map<String,Object>>
            ParameterizedType ptType = (ParameterizedType) genericType;
            Class<?> genericClazz = (Class<?>) ptType.getRawType();
            // 设置二级泛型类型
            Type[] types = ptType.getActualTypeArguments();
            if (Map.class.isAssignableFrom(genericClazz)) {
                if (types.length == 2) {
                    genericParameterizedType.valueType = genericMapType(genericClazz, types[0], types[1]);
                } else {
                    genericParameterizedType.valueType = genericMapType(genericClazz, Object.class, Object.class);
                }
            } else if (Collection.class.isAssignableFrom(genericClazz)) {
                if (types.length == 1) {
                    genericParameterizedType.valueType = genericCollectionType(genericClazz, types[0]);
                }
            } else if (genericClazz.isArray()) {
                Class<?> componentType = genericClazz.getComponentType();
                genericParameterizedType.valueType = entityType(genericClazz, componentType);
            } else {
                // 实体类上的泛型，仅仅支持单泛型,且不能嵌套，否则跳过
                if (types.length == 1) {
                    try {
                        Class<?> cls = (Class<?>) types[0];
                        genericParameterizedType.valueType = entityType(genericClazz, cls);
                    } catch (Throwable ignored) {
                    }
                }
            }
        } else if (genericType instanceof WildcardType) {
            // ?
            genericParameterizedType.valueType = actualType(Object.class);
        }
    }

    /**
     * 接口或者抽象类使用属性默认值时，可以替换实例类型，并保留其泛型结构
     *
     * @param actualType 替换后的实际类型
     * @return 新的泛型结构对象，actualType 被替换，valueType、mapKeyClass、genericClass 沿用当前对象
     */
    public GenericParameterizedType<?> copyAndReplaceActualType(Class<?> actualType) {
        GenericParameterizedType<?> genericParameterizedType = new GenericParameterizedType<Object>();
        genericParameterizedType.setActualType(actualType);
        genericParameterizedType.valueType = this.valueType;
        genericParameterizedType.mapKeyClass = this.mapKeyClass;
        genericParameterizedType.genericClass = this.genericClass;
        return genericParameterizedType;
    }

    /**
     * 获取实际类型。
     *
     * @return 当前的实际类型，通过 {@link #genericArrayType(Type)} 构建的数组结构可能为 {@code null}
     */
    public Class<?> getActualType() {
        return actualType;
    }

    /**
     * 获取 Map 的 key 类型。
     *
     * @return Map 的 key 类型，非 Map 结构时为 {@code null}
     */
    public Class<?> getMapKeyClass() {
        return mapKeyClass;
    }

    /**
     * 获取 Map 的 value 或集合、数组元素的泛型结构。
     *
     * @return 元素泛型结构，未解析出元素类型时为 {@code null}
     */
    public GenericParameterizedType<?> getValueType() {
        return valueType;
    }

    /**
     * 获取实体类上的单泛型实际类型。
     *
     * @return 单泛型实际类型，未设置时为 {@code null}
     */
    public Class<?> getGenericClass() {
        return genericClass;
    }

    /**
     * 按泛型名称获取实际类型。
     *
     * @param genericName 泛型名称
     * @return 单泛型模式下直接返回 genericClass；多泛型模式下返回名称映射的类型；两者都不存在时返回 {@code null}
     */
    public Class<?> getGenericClass(String genericName) {
        if (genericClass != null) {
            return genericClass;
        }
        if (genericClassMap != null) {
            return genericClassMap.get(genericName);
        }
        return null;
    }

    /**
     * 判断当前结构是否携带泛型信息。
     *
     * @return 构建时标记为泛型结构则返回 {@code true}，否则返回 {@code false}
     */
    public boolean isGeneric() {
        return generic;
    }

    /**
     * 判断当前结构是否为伪泛型（仅有泛型名称，实际类型需由上级结构解析）。
     *
     * @return 是伪泛型时返回 {@code true}，否则返回 {@code false}
     */
    public boolean isCamouflage() {
        return camouflage;
    }

    /**
     * 获取伪泛型名称。
     *
     * @return 伪泛型名称，非伪泛型结构时为 {@code null}
     */
    public String getGenericName() {
        return genericName;
    }

    /**
     * 获取实际类型对应的类型分类，首次调用时计算并缓存。
     *
     * @return 实际类型对应的 {@link ReflectConsts.ClassCategory}
     */
    public ReflectConsts.ClassCategory getActualClassCategory() {
        if (actualClassCategory != null) {
            return actualClassCategory;
        }
        return actualClassCategory = ReflectConsts.getClassCategory(actualType);
    }

    /**
     * 判断指定类型是否可以赋值给当前实际类型。
     *
     * @param childClass 待判断的类型
     * @return 与实际类型相同或是其子类型时返回 {@code true}，否则返回 {@code false}
     */
    public boolean isAssignableFrom(Class<?> childClass) {
        return actualType == childClass || actualType.isAssignableFrom(childClass);
    }

    /**
     * 通过Type构建GenericParameterizedType实例
     *
     * @param type 反射得到的类型，支持 Class、ParameterizedType、GenericArrayType、TypeVariable、WildcardType
     * @return 对应的泛型结构对象；通配符无上界时返回 {@link #AnyType}；
     *     类型不受支持或解析过程抛出异常时返回 {@code null}
     */
    public static GenericParameterizedType<?> of(Type type) {
        if (type instanceof Class<?>) {
            Class typeClass = (Class<?>) type;
            if (Map.class.isAssignableFrom(typeClass)) {
                return mapOf(typeClass);
            } else if (Collection.class.isAssignableFrom(typeClass)) {
                return collectionOf(typeClass);
            }
            return actualType(typeClass);
        }
        try {
            if (type instanceof ParameterizedType) {
                ParameterizedType pt = (ParameterizedType) type;
                Type[] actualTypeArguments = pt.getActualTypeArguments();
                Type rawType = pt.getRawType();
                if (rawType instanceof Class<?>) {
                    Class rawClass = (Class<?>) rawType;
                    if (Collection.class.isAssignableFrom(rawClass)) {
                        return collectionType(rawClass, of(actualTypeArguments[0]));
                    } else if (Map.class.isAssignableFrom(rawClass)) {
                        Type key = actualTypeArguments[0];
                        return mapType(rawClass, key instanceof Class<?> ? (Class<?>) key : Object.class,
                                of(actualTypeArguments[1]));
                    } else {
                        // maybe an entity<T>
                        return genericEntityType(rawClass, actualTypeArguments[0].getTypeName());
                    }
                }
            }
            if (type instanceof GenericArrayType) {
                GenericArrayType genericArrayType = (GenericArrayType) type;
                Type genericComponentType = genericArrayType.getGenericComponentType();
                return GenericParameterizedType.genericArrayType(genericComponentType);
            }
            if (type instanceof TypeVariable) {
                TypeVariable<?> typeVariable = (TypeVariable<?>) type;
                Type[] bounds = typeVariable.getBounds();
                if (bounds.length > 0) {
                    return of(bounds[bounds.length - 1]);
                }
            }
            if (type instanceof WildcardType) {
                WildcardType wildcardType = (WildcardType) type;
                Type[] upperBounds = wildcardType.getUpperBounds();
                if (upperBounds.length > 0) {
                    return of(upperBounds[upperBounds.length - 1]);
                }
                return AnyType;
            }
            return null;
        } catch (Throwable throwable) {
            return null;
        }
    }
}
